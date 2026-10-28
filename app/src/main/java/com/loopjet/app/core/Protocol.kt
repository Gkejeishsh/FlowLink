package com.loopjet.app.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketException
import java.security.KeyPair

/** Frame identifiers of the LoopJet wire protocol (LJP/1). */
object Frame {
    const val HELLO = 0x10
    const val HELLO_REPLY = 0x11
    const val AUTH = 0x12
    const val MANIFEST = 0x13
    const val PREPARE = 0x14
    const val START = 0x15
    const val ACK = 0x16
    const val PAUSE = 0x17
    const val RESUME = 0x18
    const val COMPLETE = 0x19
    const val ERROR = 0x1A
    const val BYE = 0x1B
    const val REFUSE = 0x1C

    const val DHELLO = 0x26
    const val DHELLO_REPLY = 0x27
    const val DATA = 0x22
    const val BUNDLE = 0x23
    const val WRITEOK = 0x24
    const val DONE = 0x25

    fun name(type: Int): String = when (type) {
        HELLO -> "HELLO"
        HELLO_REPLY -> "HELLO_REPLY"
        AUTH -> "AUTH"
        MANIFEST -> "MANIFEST"
        PREPARE -> "PREPARE"
        START -> "START"
        ACK -> "ACK"
        PAUSE -> "PAUSE"
        RESUME -> "RESUME"
        COMPLETE -> "COMPLETE"
        ERROR -> "ERROR"
        BYE -> "BYE"
        REFUSE -> "REFUSE"
        DHELLO -> "DHELLO"
        DHELLO_REPLY -> "DHELLO_REPLY"
        DATA -> "DATA"
        BUNDLE -> "BUNDLE"
        WRITEOK -> "WRITEOK"
        DONE -> "DONE"
        else -> "0x${Integer.toHexString(type)}"
    }
}

/** Protocol constants shared by both ends. */
object ProtocolLimits {
    /** Payload record size on the wire (plaintext). 256 KiB keeps resume granularity fine-grained. */
    const val CHUNK_SIZE: Int = 256 * 1024

    /** Maximum plaintext payload of a single sealed record (bundles included). */
    const val MAX_RECORD: Int = CHUNK_SIZE

    /** Small files below this size are packed into bundles. */
    const val BUNDLE_FILE_MAX: Long = 64L * 1024

    /** Cap for a bundle record. */
    const val BUNDLE_MAX: Int = CHUNK_SIZE

    const val MAX_CONTROL_RECORD: Int = 8 * 1024 * 1024

    const val PROTOCOL_VERSION: Int = 1

    /** Default control port; both sides probe it. */
    const val DEFAULT_PORT: Int = 43119

    /** TLS-free, but every record is AES-256-GCM sealed. */
    const val SAS_DIGITS: Int = 6
}

/**
 * Buffered streams over a connected socket, shared by the plaintext handshake phase and the sealed
 * phase that follows it. Buffering here (and only here) is what prevents the handshake's last bytes
 * from being stranded in a discarded buffer.
 */
interface StreamIo : Closeable {
    val input: InputStream
    val output: OutputStream
}

class SocketIo(val socket: Socket) : StreamIo {
    override val input: InputStream = BufferedInputStream(socket.getInputStream(), 128 * 1024)
    override val output: OutputStream = BufferedOutputStream(socket.getOutputStream(), 128 * 1024)

    init {
        socket.tcpNoDelay = true
        try {
            socket.sendBufferSize = 4 * 1024 * 1024
            socket.receiveBufferSize = 4 * 1024 * 1024
        } catch (_: SocketException) {
            // Some devices cap the buffer; the kernel default is then used.
        }
    }

    override fun close() {
        try {
            output.flush()
        } catch (_: Exception) {
        }
        socket.close()
    }
}

/**
 * Length-prefixed record channel.
 *
 * Wire format: `u8 type | u32 cipherLen | ciphertext` where `ciphertext` is the plaintext payload
 * followed by a 16-byte GCM tag. The 5-byte header is passed as AAD, so a flipped type, length or
 * stream position fails authentication instead of desynchronising the stream.
 *
 * When [outKey]/[inKey] are null the channel is transparent (used only for the two HELLO frames
 * before session keys exist — never for file data).
 */
class RecordChannel(
    private val io: StreamIo,
    private val outKey: AeadKey?,
    private val inKey: AeadKey?,
    private val outNoncePrefix: ByteArray = ByteArray(4),
    private val inNoncePrefix: ByteArray = ByteArray(4),
    private val maxPlain: Int = ProtocolLimits.MAX_RECORD
) : Closeable {
    private val outBuf = ByteArray(5 + maxPlain + AeadKey.TAG_BYTES)
    private val inCipher = ByteArray(maxPlain + AeadKey.TAG_BYTES)
    private val inPlain = ByteArray(maxPlain)
    private val header = ByteArray(5)
    private val nonce = ByteArray(12)
    private var outCounter = 0L
    private var inCounter = 0L

    var lastType: Int = -1
        private set
    var lastLength: Int = 0
        private set

    val plaintextBuffer: ByteArray get() = inPlain

    fun writeRecord(type: Int, src: ByteArray, off: Int = 0, len: Int = src.size) {
        require(len <= maxPlain) { "record too large: $len > $maxPlain" }
        if (outKey == null) {
            header[0] = type.toByte()
            val l = len
            header[1] = (l ushr 24).toByte()
            header[2] = (l ushr 16).toByte()
            header[3] = (l ushr 8).toByte()
            header[4] = l.toByte()
            io.output.write(header, 0, 5)
            io.output.write(src, off, len)
            return
        }
        val cipherLen = len + AeadKey.TAG_BYTES
        outBuf[0] = type.toByte()
        outBuf[1] = (cipherLen ushr 24).toByte()
        outBuf[2] = (cipherLen ushr 16).toByte()
        outBuf[3] = (cipherLen ushr 8).toByte()
        outBuf[4] = cipherLen.toByte()
        System.arraycopy(outNoncePrefix, 0, nonce, 0, 4)
        putLong(nonce, 4, outCounter)
        val n = outKey.seal(nonce, outBuf, 0, 5, src, off, len, outBuf, 5)
        check(n == cipherLen) { "unexpected sealed length $n != $cipherLen" }
        io.output.write(outBuf, 0, 5 + n)
        outCounter++
    }

    fun writeJson(type: Int, value: Map<String, Any?>) {
        writeBytes(type, Json.stringify(value).toByteArray(Charsets.UTF_8))
    }

    fun writeBytes(type: Int, payload: ByteArray) = writeRecord(type, payload, 0, payload.size)

    fun flush() {
        io.output.flush()
    }

    /** Reads a record header. Returns false on a clean end of stream. */
    fun readHeader(): Boolean {
        val first = io.input.read()
        if (first < 0) return false
        header[0] = first.toByte()
        readFully(header, 1, 4)
        lastType = header[0].toInt() and 0xFF
        val len = Bytes.readU32(header, 1)
        val limit = if (outKey == null && inKey == null) maxPlain else maxPlain + AeadKey.TAG_BYTES
        if (len <= 0 || len > limit) throw ProtocolException("invalid record length $len for ${Frame.name(lastType)}")
        lastLength = len
        readFully(inCipher, 0, len)
        if (inKey == null) {
            System.arraycopy(inCipher, 0, inPlain, 0, len)
            lastPlainLength = len
            return true
        }
        System.arraycopy(inNoncePrefix, 0, nonce, 0, 4)
        putLong(nonce, 4, inCounter)
        val n = inKey.open(nonce, header, 0, 5, inCipher, 0, len, inPlain, 0)
        if (n < 0) throw ProtocolException("authentication failed on ${Frame.name(lastType)} record")
        inCounter++
        lastPlainLength = n
        return true
    }

    var lastPlainLength: Int = 0
        private set

    /** Plaintext of the record just read, valid until the next [readHeader]. */
    fun payload(): ByteArray = inPlain

    fun payloadString(): String = String(inPlain, 0, lastPlainLength, Charsets.UTF_8)

    fun payloadJson(): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return Json.parse(payloadString()) as? Map<String, Any?>
            ?: throw ProtocolException("expected a JSON object in ${Frame.name(lastType)}")
    }

    private fun readFully(dst: ByteArray, off: Int, len: Int) {
        var done = 0
        while (done < len) {
            val n = io.input.read(dst, off + done, len - done)
            if (n < 0) throw EOFException("connection closed while reading ${Frame.name(lastType)}")
            done += n
        }
    }

    override fun close() = io.close()

    private fun putLong(b: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) b[off + i] = (v ushr (56 - 8 * i)).toByte()
    }
}

class ProtocolException(message: String) : Exception(message)

// --- handshake -----------------------------------------------------------------------------------

class Hello(
    val name: String,
    val platform: String,
    val sdk: Int,
    val deviceId: String,
    val capabilities: List<String>,
    val identitySpki: ByteArray,
    val ephemeralSpki: ByteArray,
    val nonce: ByteArray
) {
    fun toJson(): Map<String, Any?> = Json.obj(
        "v" to ProtocolLimits.PROTOCOL_VERSION,
        "name" to name,
        "platform" to platform,
        "sdk" to sdk,
        "id" to deviceId,
        "caps" to capabilities,
        "ik" to Bytes.b64(identitySpki),
        "ek" to Bytes.b64(ephemeralSpki),
        "n" to Bytes.b64(nonce)
    )

    companion object {
        fun fromJson(map: Map<String, Any?>): Hello = Hello(
            name = map.str("name") ?: "Unknown device",
            platform = map.str("platform") ?: "android",
            sdk = map.int("sdk") ?: 0,
            deviceId = map.str("id") ?: "",
            capabilities = map.arr("caps").orEmpty().mapNotNull { it as? String },
            identitySpki = Bytes.fromB64(map.requiredStr("ik")),
            ephemeralSpki = Bytes.fromB64(map.requiredStr("ek")),
            nonce = Bytes.fromB64(map.requiredStr("n"))
        )
    }
}

class HandshakeResult(
    val channel: RecordChannel,
    val keys: SessionKeys,
    val peer: Hello,
    val localSas: String,
    val peerFingerprintHex: String
)

object Handshake {
    private const val MAX_HELLO = 64 * 1024

    /**
     * Both roles run the same exchange; [isClient] only decides who speaks first.
     *
     * transcript = H("LJP1" | clientHelloBytes | serverHelloBytes)
     * keys       = HKDF(ECDH(localEph, peerEph), salt = transcript, info = "LJP1-session-keys")
     */
    fun run(
        io: SocketIo,
        identity: Identity,
        hello: Hello,
        isClient: Boolean,
        onPeerHello: (Hello) -> Unit = {}
    ): HandshakeResult {
        val ephemeral: KeyPair = Ecdh.generate()
        val ephSpki = Ecdh.encodePublic(ephemeral.public)
        val nonce = Sha256.randomBytes(16)
        val localHello = Hello(
            name = hello.name,
            platform = hello.platform,
            sdk = hello.sdk,
            deviceId = hello.deviceId,
            capabilities = hello.capabilities,
            identitySpki = identity.publicKeySpki,
            ephemeralSpki = ephSpki,
            nonce = nonce
        )
        val plain = RecordChannel(io, null, null, maxPlain = MAX_HELLO)
        val localBytes = Json.stringify(localHello.toJson()).toByteArray(Charsets.UTF_8)

        val peerBytes: ByteArray
        val peerHello: Hello
        if (isClient) {
            plain.writeRecord(Frame.HELLO, localBytes)
            plain.flush()
            if (!plain.readHeader() || plain.lastType != Frame.HELLO_REPLY) {
                throw ProtocolException("expected HELLO_REPLY, got ${Frame.name(plain.lastType)}")
            }
            peerBytes = plain.payload().copyOf(plain.lastPlainLength)
            peerHello = Hello.fromJson(Json.parseObject(String(peerBytes, Charsets.UTF_8)))
        } else {
            if (!plain.readHeader() || plain.lastType != Frame.HELLO) {
                throw ProtocolException("expected HELLO, got ${Frame.name(plain.lastType)}")
            }
            peerBytes = plain.payload().copyOf(plain.lastPlainLength)
            peerHello = Hello.fromJson(Json.parseObject(String(peerBytes, Charsets.UTF_8)))
            plain.writeRecord(Frame.HELLO_REPLY, localBytes)
            plain.flush()
        }
        onPeerHello(peerHello)

        val transcript = if (isClient) {
            Sha256.digest("LJP1".toByteArray(), localBytes, peerBytes)
        } else {
            Sha256.digest("LJP1".toByteArray(), peerBytes, localBytes)
        }
        val shared = Ecdh.agreement(ephemeral.private, peerHello.ephemeralSpki)
        val localFingerprint = identity.fingerprint
        val peerFingerprint = Sha256.digest(peerHello.identitySpki)
        val keys = HandshakeCrypto.computeKeys(shared, transcript, localFingerprint, peerFingerprint)

        val outPrefix = if (isClient) byteArrayOf(0, 0, 0, 1) else byteArrayOf(0, 0, 0, 2)
        val inPrefix = if (isClient) byteArrayOf(0, 0, 0, 2) else byteArrayOf(0, 0, 0, 1)
        val sealed = RecordChannel(
            io,
            outKey = if (isClient) keys.clientOut() else keys.serverOut(),
            inKey = if (isClient) keys.clientIn() else keys.serverIn(),
            outNoncePrefix = outPrefix,
            inNoncePrefix = inPrefix
        )

        // Mutual authentication: each side signs the transcript with its long-term identity key.
        val signature = identity.sign(transcript)
        sealed.writeRecord(Frame.AUTH, signature)
        sealed.flush()
        if (!sealed.readHeader() || sealed.lastType != Frame.AUTH) {
            throw ProtocolException("expected AUTH, got ${Frame.name(sealed.lastType)}")
        }
        val peerSignature = sealed.payload().copyOf(sealed.lastPlainLength)
        if (!IdentityVerifier.verify("EC", peerHello.identitySpki, transcript, peerSignature)) {
            throw ProtocolException("peer identity signature did not verify")
        }
        return HandshakeResult(sealed, keys, peerHello, keys.sas, keys.peerFingerprintHex)
    }
}

// --- message payloads ----------------------------------------------------------------------------

object FileKind {
    const val FILE = 0
    const val APP_APK = 1
    const val TEXT = 2
}

data class TransferFile(
    val id: Int,
    val relPath: String,
    val size: Long,
    val mtime: Long,
    val kind: Int = FileKind.FILE,
    val sha256: String? = null,
    val mime: String = "application/octet-stream"
) {
    fun toJson(): Map<String, Any?> = Json.obj(
        "id" to id,
        "p" to relPath,
        "s" to size,
        "m" to mtime,
        "k" to kind,
        "h" to sha256,
        "t" to mime
    )

    companion object {
        fun fromJson(m: Map<String, Any?>) = TransferFile(
            id = m.int("id") ?: throw ProtocolException("file without id"),
            relPath = m.str("p") ?: throw ProtocolException("file without path"),
            size = m.long("s") ?: 0L,
            mtime = m.long("m") ?: 0L,
            kind = m.int("k") ?: FileKind.FILE,
            sha256 = m.str("h"),
            mime = m.str("t") ?: "application/octet-stream"
        )
    }
}

/** An installed app travelling as one item: a base APK plus every split. */
data class TransferApp(
    val packageName: String,
    val label: String,
    val versionCode: Long,
    val versionName: String,
    val baseFileId: Int,
    val splitFileIds: List<Int>,
    val minSdk: Int,
    val targetSdk: Int,
    val abis: List<String>
) {
    fun toJson(): Map<String, Any?> = Json.obj(
        "pkg" to packageName,
        "label" to label,
        "vc" to versionCode,
        "vn" to versionName,
        "base" to baseFileId,
        "splits" to splitFileIds,
        "min" to minSdk,
        "target" to targetSdk,
        "abis" to abis
    )

    companion object {
        fun fromJson(m: Map<String, Any?>) = TransferApp(
            packageName = m.requiredStr("pkg"),
            label = m.str("label") ?: m.requiredStr("pkg"),
            versionCode = m.long("vc") ?: 0L,
            versionName = m.str("vn") ?: "",
            baseFileId = m.int("base") ?: -1,
            splitFileIds = m.arr("splits").orEmpty().longList().map { it.toInt() },
            minSdk = m.int("min") ?: 0,
            targetSdk = m.int("target") ?: 0,
            abis = m.arr("abis").orEmpty().mapNotNull { it as? String }
        )
    }
}

class TransferManifest(
    val sessionId: String,
    val files: List<TransferFile>,
    val dirs: List<String>,
    val apps: List<TransferApp>,
    val totalBytes: Long,
    val chunkSize: Int,
    val senderName: String,
    val startedAt: Long
) {
    fun toJson(): Map<String, Any?> = Json.obj(
        "sid" to sessionId,
        "files" to files.map { it.toJson() },
        "dirs" to dirs,
        "apps" to apps.map { it.toJson() },
        "total" to totalBytes,
        "chunk" to chunkSize,
        "sender" to senderName,
        "at" to startedAt,
        "v" to ProtocolLimits.PROTOCOL_VERSION
    )

    companion object {
        fun fromJson(m: Map<String, Any?>) = TransferManifest(
            sessionId = m.requiredStr("sid"),
            files = m.objList("files").map { TransferFile.fromJson(it) },
            dirs = m.arr("dirs").orEmpty().mapNotNull { it as? String },
            apps = m.objList("apps").map { TransferApp.fromJson(it) },
            totalBytes = m.long("total") ?: 0L,
            chunkSize = m.int("chunk") ?: ProtocolLimits.CHUNK_SIZE,
            senderName = m.str("sender") ?: "Phone",
            startedAt = m.long("at") ?: 0L
        )
    }
}

class PrepareResponse(
    val accepted: List<Int>,
    val skipped: Map<Int, String>,
    val freeBytes: Long,
    val chunkSize: Int,
    val chunks: Map<Int, ChunkMap>,
    val saveDirectory: String
) {
    fun toJson(): Map<String, Any?> = Json.obj(
        "accept" to accepted,
        "skip" to skipped.entries.map { Json.obj("id" to it.key, "why" to it.value) },
        "free" to freeBytes,
        "chunk" to chunkSize,
        "have" to chunks.entries.associate { it.key.toString() to it.value.toBase64() },
        "dir" to saveDirectory
    )

    companion object {
        fun fromJson(m: Map<String, Any?>) = PrepareResponse(
            accepted = m.arr("accept").orEmpty().longList().map { it.toInt() },
            skipped = m.objList("skip").associate { (it.int("id") ?: -1) to (it.str("why") ?: "skip") },
            freeBytes = m.long("free") ?: 0L,
            chunkSize = m.int("chunk") ?: ProtocolLimits.CHUNK_SIZE,
            chunks = (m.obj("have") ?: emptyMap()).entries.associate { (k, v) ->
                (k.toIntOrNull() ?: -1) to ChunkMap.fromBase64(v as? String ?: "")
            },
            saveDirectory = m.str("dir") ?: ""
        )
    }
}

/**
 * Bitmap of received 256 KiB chunks for one file. This is what makes a transfer resumable after a
 * dropped link, a paused session, an app swipe-away or the Android 15 foreground-service timeout.
 */
class ChunkMap(val chunkCount: Int, private var bits: LongArray = LongArray((chunkCount + 63) ushr 6)) {
    init {
        require(bits.size == ((chunkCount + 63) ushr 6)) { "bitmap size mismatch" }
    }

    fun isSet(index: Int): Boolean {
        if (index < 0 || index >= chunkCount) return false
        val word = index ushr 6
        return (bits[word] ushr (index and 63)) and 1L == 1L
    }

    fun set(index: Int) {
        if (index < 0 || index >= chunkCount) return
        bits[index ushr 6] = bits[index ushr 6] or (1L shl (index and 63))
    }

    fun setRange(from: Int, to: Int) {
        for (i in from until to) set(i)
    }

    fun clear() {
        bits.fill(0)
    }

    fun count(): Int {
        var total = 0
        for (word in bits) total += java.lang.Long.bitCount(word)
        return total
    }

    fun isComplete(): Boolean = count() == chunkCount

    fun missingChunks(): List<Int> {
        if (isComplete()) return emptyList()
        val out = ArrayList<Int>()
        for (i in 0 until chunkCount) if (!isSet(i)) out.add(i)
        return out
    }

    fun nextMissing(from: Int): Int {
        var i = from
        while (i < chunkCount) {
            if (!isSet(i)) return i
            i++
        }
        return -1
    }

    fun copy(): ChunkMap = ChunkMap(chunkCount, bits.copyOf())

    fun toBase64(): String = Bytes.b64(toBytes())

    fun toBytes(): ByteArray {
        val out = ByteArray(4 + bits.size * 8)
        System.arraycopy(Bytes.u32(chunkCount), 0, out, 0, 4)
        var o = 4
        for (word in bits) {
            System.arraycopy(Bytes.u64(word), 0, out, o, 8)
            o += 8
        }
        return out
    }

    companion object {
        fun fromBase64(text: String): ChunkMap {
            if (text.isEmpty()) return ChunkMap(0)
            return fromBytes(Bytes.fromB64(text))
        }

        fun fromBytes(bytes: ByteArray): ChunkMap {
            if (bytes.size < 4) return ChunkMap(0)
            val count = Bytes.readU32(bytes, 0)
            val words = LongArray((count + 63) ushr 6)
            var o = 4
            for (i in words.indices) {
                if (o + 8 > bytes.size) break
                words[i] = Bytes.readU64(bytes, o)
                o += 8
            }
            return ChunkMap(count, words)
        }

        fun forSize(size: Long, chunkSize: Int): ChunkMap {
            val count = if (size <= 0) 0 else ((size + chunkSize - 1) / chunkSize).toInt()
            return ChunkMap(count)
        }
    }
}
