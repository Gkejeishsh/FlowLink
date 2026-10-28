package com.loopjet.app.core

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// --- bytes ---------------------------------------------------------------------------------------

object Bytes {
    fun u32(v: Int): ByteArray = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()
    )

    fun u64(v: Long): ByteArray = byteArrayOf(
        (v ushr 56).toByte(), (v ushr 48).toByte(), (v ushr 40).toByte(), (v ushr 32).toByte(),
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()
    )

    fun readU32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    fun readU64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    fun concat(vararg parts: ByteArray): ByteArray {
        var total = 0
        for (p in parts) total += p.size
        val out = ByteArray(total)
        var o = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, o, p.size)
            o += p.size
        }
        return out
    }

    fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX_CHARS[v ushr 4]).append(HEX_CHARS[v and 0x0F])
        }
        return sb.toString()
    }

    fun fromHex(s: String): ByteArray {
        require(s.length % 2 == 0) { "odd hex length" }
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = ((HEX_CHARS.indexOf(s[i * 2]) shl 4) or HEX_CHARS.indexOf(s[i * 2 + 1])).toByte()
        }
        return out
    }

    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun fromB64(s: String): ByteArray = Base64.getDecoder().decode(s)

    private const val HEX_CHARS = "0123456789abcdef"
}

// --- hashing -------------------------------------------------------------------------------------

object Sha256 {
    private val random = SecureRandom()

    fun digest(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun digest(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) md.update(p)
        return md.digest()
    }

    fun digest(data: ByteArray, off: Int, len: Int): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(data, off, len)
        return md.digest()
    }

    fun digestTo(data: ByteArray, off: Int, len: Int, out: ByteArray, outOff: Int) {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(data, off, len)
        val d = md.digest()
        System.arraycopy(d, 0, out, outOff, 32)
    }

    fun ofFile(file: File): ByteArray = ofFile(file, 0, file.length())

    /** Streaming hash of a byte range. Used for full-file verification after a transfer. */
    fun ofFile(file: File, position: Long, length: Long): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(position)
            val buf = ByteArray(512 * 1024)
            var remaining = length
            while (remaining > 0) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n <= 0) break
                md.update(buf, 0, n)
                remaining -= n
            }
        }
        return md.digest()
    }

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }
}

// --- HKDF ----------------------------------------------------------------------------------------

object Hkdf {
    /** RFC 5869 HKDF-SHA256. */
    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(salt.ifEmpty { ByteArray(32) }, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            t = hmac(prk, Bytes.concat(t, info, byteArrayOf(counter.toByte())))
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}

// --- AEAD ----------------------------------------------------------------------------------------

/**
 * AES-256-GCM record cipher. One instance per thread: `Cipher` objects are not thread-safe, and
 * `init()` per record is the only correct way to use GCM with a fresh nonce.
 *
 * `seal`/`open` write straight into a caller-provided buffer so the data path performs no
 * per-record allocation.
 */
class AeadKey(key: ByteArray) {
    private val keySpec = SecretKeySpec(key, "AES")
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")

    fun seal(
        nonce: ByteArray,
        aad: ByteArray,
        aadOff: Int,
        aadLen: Int,
        src: ByteArray,
        srcOff: Int,
        len: Int,
        dst: ByteArray,
        dstOff: Int
    ): Int {
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad, aadOff, aadLen)
        val n = cipher.update(src, srcOff, len, dst, dstOff)
        return n + cipher.doFinal(dst, dstOff + n)
    }

    /** Returns the plaintext length, or -1 when authentication fails. */
    fun open(
        nonce: ByteArray,
        aad: ByteArray,
        aadOff: Int,
        aadLen: Int,
        src: ByteArray,
        srcOff: Int,
        len: Int,
        dst: ByteArray,
        dstOff: Int
    ): Int {
        return try {
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad, aadOff, aadLen)
            val n = cipher.update(src, srcOff, len, dst, dstOff)
            n + cipher.doFinal(dst, dstOff + n)
        } catch (e: Exception) {
            -1
        }
    }

    companion object {
        const val TAG_BYTES = 16
        private const val TAG_BITS = 128
    }
}

// --- curve helpers -------------------------------------------------------------------------------

object Ecdh {
    private const val CURVE = "secp256r1"

    fun generate(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec(CURVE), SecureRandom())
        return kpg.generateKeyPair()
    }

    fun encodePublic(key: PublicKey): ByteArray = key.encoded

    fun decodePublic(spki: ByteArray): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))

    fun decodePrivate(pkcs8: ByteArray): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))

    fun agreement(privateKey: PrivateKey, peerSpki: ByteArray): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(privateKey)
        ka.doPhase(decodePublic(peerSpki), true)
        return ka.generateSecret()
    }
}

// --- identity ------------------------------------------------------------------------------------

/**
 * Long-term device identity. The Android implementation keeps the private key in the system
 * Keystore; the JVM implementation (tests, benchmarks) uses a software key.
 */
interface Identity {
    val publicKeySpki: ByteArray
    val algorithm: String
    fun sign(data: ByteArray): ByteArray

    val fingerprint: ByteArray get() = Sha256.digest(publicKeySpki)
    val fingerprintHex: String get() = Bytes.hex(fingerprint)

    fun verify(data: ByteArray, signature: ByteArray): Boolean = IdentityVerifier.verify(
        algorithm, publicKeySpki, data, signature
    )
}

object IdentityVerifier {
    fun verify(algorithm: String, spki: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
        return try {
            val sig = Signature.getInstance(signatureAlgorithm(algorithm))
            sig.initVerify(Ecdh.decodePublic(spki))
            sig.update(data)
            sig.verify(signature)
        } catch (e: Exception) {
            false
        }
    }

    private fun signatureAlgorithm(keyAlgorithm: String): String = when (keyAlgorithm.uppercase()) {
        "RSA" -> "SHA256withRSA"
        else -> "SHA256withECDSA"
    }
}

/** Software (JVM / fallback) identity: EC P-256 with SHA256withECDSA. */
class SoftwareIdentity(private val privateKey: PrivateKey, override val publicKeySpki: ByteArray) : Identity {
    override val algorithm: String = "EC"

    /** PKCS#8 export used when persisting the fallback identity in private preferences. */
    fun privateKeyPkcs8(): ByteArray = privateKey.encoded

    override fun sign(data: ByteArray): ByteArray {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(privateKey)
        sig.update(data)
        return sig.sign()
    }

    companion object {
        fun generate(): SoftwareIdentity {
            val kp = Ecdh.generate()
            return SoftwareIdentity(kp.private, Ecdh.encodePublic(kp.public))
        }

        fun fromPkcs8(privateKey: ByteArray, publicSpki: ByteArray): SoftwareIdentity =
            SoftwareIdentity(Ecdh.decodePrivate(privateKey), publicSpki)
    }
}

// --- ephemeral handshake helper -------------------------------------------------------------------

class EphemeralKeyPair(val privateKey: PrivateKey, val spki: ByteArray) {
    companion object {
        fun generate(): EphemeralKeyPair {
            val kp = Ecdh.generate()
            return EphemeralKeyPair(kp.private, Ecdh.encodePublic(kp.public))
        }
    }
}

/**
 * Session keys produced by the handshake.
 *
 * * `clientToServer` seals frames the initiator sends.
 * * `serverToClient` seals frames the responder sends.
 * * [sas] is the six-digit confirmation code both screens display.
 */
class SessionKeys(
    val clientToServer: ByteArray,
    val serverToClient: ByteArray,
    val transcriptHash: ByteArray,
    val sas: String,
    val peerFingerprintHex: String
) {
    fun clientOut() = AeadKey(clientToServer)

    fun clientIn() = AeadKey(serverToClient)

    fun serverOut() = AeadKey(serverToClient)

    fun serverIn() = AeadKey(clientToServer)
}

object HandshakeCrypto {
    private const val INFO = "LJP1-session-keys"

    fun computeKeys(
        sharedSecret: ByteArray,
        transcriptHash: ByteArray,
        localFingerprint: ByteArray,
        peerFingerprint: ByteArray
    ): SessionKeys {
        val okm = Hkdf.derive(sharedSecret, transcriptHash, INFO.toByteArray(), 64)
        val c2s = okm.copyOfRange(0, 32)
        val s2c = okm.copyOfRange(32, 64)
        val sasBytes = Sha256.digest(transcriptHash, localFingerprint, peerFingerprint)
        val digits = ((sasBytes[0].toInt() and 0xFF).toLong() shl 16 or
            ((sasBytes[1].toInt() and 0xFF).toLong() shl 8) or
            (sasBytes[2].toInt() and 0xFF).toLong()) % 1_000_000L
        val sas = digits.toString().padStart(6, '0')
        return SessionKeys(c2s, s2c, transcriptHash, sas, Bytes.hex(peerFingerprint))
    }
}

fun Closeable.closeQuietly() {
    try {
        close()
    } catch (_: Exception) {
    }
}
