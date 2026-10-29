package com.loopjet.app.platform

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.loopjet.app.core.Bytes
import com.loopjet.app.core.Identity
import com.loopjet.app.core.Json
import com.loopjet.app.core.SoftwareIdentity
import com.loopjet.app.core.str
import com.loopjet.app.core.objList
import com.loopjet.app.core.long
import com.loopjet.app.core.bool
import com.loopjet.app.core.int
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID

class FlowLinkApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var identity: Identity
        private set
    lateinit var history: HistoryStore
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        identity = IdentityFactory.load(this)
        history = HistoryStore(prefs)
        createChannels()
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        val transfer = NotificationChannel(
            CHANNEL_TRANSFER, "Transfers", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Live transfer progress with pause and cancel"
            setShowBadge(false)
        }
        val server = NotificationChannel(
            CHANNEL_SERVER, "Sharing servers", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Invite and Web Share sessions"
            setShowBadge(false)
        }
        manager.createNotificationChannel(transfer)
        manager.createNotificationChannel(server)
    }

    companion object {
        const val CHANNEL_TRANSFER = "loopjet.transfer"
        const val CHANNEL_SERVER = "loopjet.server"
        lateinit var instance: FlowLinkApp
            private set
        val context: Context get() = instance.applicationContext
    }
}

/**
 * Long-term device identity: an EC P-256 key in the Android Keystore, used only for signing the
 * session transcript. If a device refuses Keystore EC keys (a few OEM builds do), we fall back to a
 * software key stored in private preferences and say so in the About screen.
 */
object IdentityFactory {
    private const val TAG = "FlowLinkIdentity"
    private const val KEYSTORE = "AndroidKeyStore"
    const val ALIAS = "loopjet.identity.v1"
    private const val PREF_SOFT_KEY = "identity.software.pkcs8"
    private const val PREF_SOFT_PUB = "identity.software.spki"

    fun load(context: Context): Identity {
        return try {
            KeystoreIdentity()
        } catch (t: Throwable) {
            Log.w(TAG, "Keystore identity unavailable, using software key", t)
            softwareIdentity(context)
        }
    }

    /**
     * True only when the identity actually in use is the Keystore one. Checking the alias alone used
     * to report "hardware" even after [load] had fallen back to a software key.
     */
    fun isHardwareBacked(): Boolean = runCatching {
        FlowLinkApp.instance.identity is KeystoreIdentity
    }.getOrDefault(false)

    private fun softwareIdentity(context: Context): Identity {
        val prefs = context.getSharedPreferences("loopjet.identity", Context.MODE_PRIVATE)
        val existing = prefs.getString(PREF_SOFT_KEY, null)
        val existingPub = prefs.getString(PREF_SOFT_PUB, null)
        if (existing != null && existingPub != null) {
            return SoftwareIdentity.fromPkcs8(Bytes.fromB64(existing), Bytes.fromB64(existingPub))
        }
        val identity = SoftwareIdentity.generate()
        prefs.edit()
            .putString(PREF_SOFT_KEY, Bytes.b64(identity.privateKeyPkcs8()))
            .putString(PREF_SOFT_PUB, Bytes.b64(identity.publicKeySpki))
            .apply()
        return identity
    }

}

class KeystoreIdentity : Identity {
    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val publicKey: PublicKey

    init {
        if (!keyStore.containsAlias(IdentityFactory.ALIAS)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            generator.initialize(
                KeyGenParameterSpec.Builder(
                    IdentityFactory.ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build()
            )
            generator.generateKeyPair()
        }
        publicKey = keyStore.getCertificate(IdentityFactory.ALIAS).publicKey
    }

    override val publicKeySpki: ByteArray = publicKey.encoded
    override val algorithm: String = "EC"

    override fun sign(data: ByteArray): ByteArray {
        val entry = keyStore.getEntry(IdentityFactory.ALIAS, null) as KeyStore.PrivateKeyEntry
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(entry.privateKey)
        signature.update(data)
        return signature.sign()
    }

    private companion object {
        // Alias duplicated here so the object above can stay private; kept in sync by tests.
    }
}

/** UI preferences, persisted in one place. */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("loopjet.settings", Context.MODE_PRIVATE)

    var deviceName: String
        get() = prefs.getString("device.name", null) ?: defaultDeviceName()
        set(value) = prefs.edit().putString("device.name", value.take(40)).apply()

    var avatar: String
        get() = prefs.getString("device.avatar", "\uD83D\uDE80") ?: "\uD83D\uDE80"
        set(value) = prefs.edit().putString("device.avatar", value).apply()

    var theme: String
        get() = prefs.getString("theme", "system") ?: "system"
        set(value) = prefs.edit().putString("theme", value).apply()

    var speedMode: String
        get() = prefs.getString("speed", "fast") ?: "fast"
        set(value) = prefs.edit().putString("speed", value).apply()

    var autoAcceptTrusted: Boolean
        get() = prefs.getBoolean("autoAccept", false)
        set(value) = prefs.edit().putBoolean("autoAccept", value).apply()

    var saveFolderName: String
        get() = prefs.getString("save.folder", "FlowLink") ?: "FlowLink"
        set(value) = prefs.edit().putString("save.folder", sanitizeFolder(value)).apply()

    var saveTreeUri: String?
        get() = prefs.getString("save.tree", null)
        set(value) = prefs.edit().putString("save.tree", value).apply()

    var verifyAfterTransfer: Boolean
        get() = prefs.getBoolean("verify.after", true)
        set(value) = prefs.edit().putBoolean("verify.after", value).apply()

    var onboardingDone: Boolean
        get() = prefs.getBoolean("onboarding.done", false)
        set(value) = prefs.edit().putBoolean("onboarding.done", value).apply()

    var webPin: String
        get() = prefs.getString("web.pin", "") ?: ""
        set(value) = prefs.edit().putString("web.pin", value).apply()

    var webRequirePin: Boolean
        get() = prefs.getBoolean("web.requirePin", false)
        set(value) = prefs.edit().putBoolean("web.requirePin", value).apply()

    var lastBenchmark: String?
        get() = prefs.getString("bench.last", null)
        set(value) = prefs.edit().putString("bench.last", value).apply()

    var trustedDevices: Set<String>
        get() = prefs.getStringSet("trusted", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("trusted", value).apply()

    /** Permissions this install has already asked for, so a refusal can be told apart from a no. */
    var askedPermissions: Set<String>
        get() = prefs.getStringSet("asked.perms", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("asked.perms", value).apply()

    var bypassBatteryOpt: Boolean
        get() = prefs.getBoolean("battery.bypass", false)
        set(value) = prefs.edit().putBoolean("battery.bypass", value).apply()

    fun trustedNames(): Map<String, String> {
        val raw = prefs.getString("trusted.names", null) ?: return emptyMap()
        val map = HashMap<String, String>()
        for (entry in Json.parseObject(raw)) {
            val name = entry.value as? String ?: continue
            map[entry.key] = name
        }
        return map
    }

    fun trust(fingerprint: String, name: String) {
        trustedDevices = trustedDevices + fingerprint
        val map = trustedNames().toMutableMap()
        map[fingerprint] = name
        prefs.edit().putString("trusted.names", Json.stringify(map)).apply()
    }

    fun untrust(fingerprint: String) {
        trustedDevices = trustedDevices - fingerprint
        val map = trustedNames().toMutableMap()
        map.remove(fingerprint)
        prefs.edit().putString("trusted.names", Json.stringify(map)).apply()
    }

    private fun defaultDeviceName(): String {
        val manufacturer = Build.MANUFACTURER?.replaceFirstChar { it.uppercase() } ?: "Android"
        val model = Build.MODEL ?: "device"
        val name = "$manufacturer $model".trim()
        return if (name.length > 28) name.take(28) else name
    }

    private fun sanitizeFolder(value: String): String {
        val cleaned = value.replace('/', '_').replace('\\', '_').trim()
        return if (cleaned.isEmpty()) "FlowLink" else cleaned.take(48)
    }

    fun deviceId(): String {
        val existing = prefs.getString("device.id", null)
        if (existing != null) return existing
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        prefs.edit().putString("device.id", id).apply()
        return id
    }
}

data class HistoryEntry(
    val id: String,
    val direction: String,
    val peerName: String,
    val peerFingerprint: String,
    val files: Int,
    val bytes: Long,
    val startedAt: Long,
    val durationMs: Long,
    val ok: Boolean,
    val path: String,
    val fileNames: List<String>
) {
    fun toJson(): Map<String, Any?> = Json.obj(
        "id" to id, "dir" to direction, "peer" to peerName, "fp" to peerFingerprint,
        "files" to files, "bytes" to bytes, "at" to startedAt, "ms" to durationMs,
        "ok" to ok, "path" to path, "names" to fileNames
    )

    companion object {
        fun fromJson(m: Map<String, Any?>) = HistoryEntry(
            id = m.str("id") ?: "",
            direction = m.str("dir") ?: "send",
            peerName = m.str("peer") ?: "",
            peerFingerprint = m.str("fp") ?: "",
            files = m.int("files") ?: 0,
            bytes = m.long("bytes") ?: 0,
            startedAt = m.long("at") ?: 0,
            durationMs = m.long("ms") ?: 0,
            ok = m.bool("ok", true),
            path = m.str("path") ?: "",
            fileNames = (m["names"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
        )
    }
}

class HistoryStore(private val prefs: Prefs) {
    private val context = FlowLinkApp.context
    private val prefsFile = context.getSharedPreferences("loopjet.history", Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<HistoryEntry> {
        val raw = prefsFile.getString("entries", null) ?: return emptyList()
        return runCatching {
            Json.parseObjectArray(raw).map { HistoryEntry.fromJson(it) }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun add(entry: HistoryEntry) {
        val list = (all() + entry).takeLast(200)
        save(list)
    }

    @Synchronized
    fun clear() {
        save(emptyList())
    }

    private fun save(list: List<HistoryEntry>) {
        prefsFile.edit().putString("entries", Json.stringify(list.map { it.toJson() })).apply()
    }
}

private fun Json.parseObjectArray(raw: String): List<Map<String, Any?>> {
    @Suppress("UNCHECKED_CAST")
    val parsed = Json.parse(raw) as? List<Any?> ?: return emptyList()
    return parsed.mapNotNull { it as? Map<String, Any?> }
}
