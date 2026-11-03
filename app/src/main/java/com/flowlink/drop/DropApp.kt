package com.flowlink.drop

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.flowlink.drop.net.WifiQr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One received file as the UI sees it. */
data class Incoming(
    val name: String,
    val bytes: Long,
    val total: Long,
    val state: String = "receiving"
)

data class ReceivedItem(
    val name: String,
    val bytes: Long,
    val time: Long,
    val where: String
)

data class ClientInfo(
    val address: String,
    val agent: String,
    val since: Long
)

data class DropState(
    val phase: String = "idle",
    val ssid: String = "",
    val password: String = "",
    val address: String = "",
    val port: Int = 0,
    val band: String = "",
    val route: String = "",
    val reachable: Boolean? = null,
    val clients: List<ClientInfo> = emptyList(),
    val incoming: List<Incoming> = emptyList(),
    val history: List<ReceivedItem> = emptyList(),
    val message: String = "",
    val error: String = "",
    val requests: Int = 0,
    val version: String = "",
    val phoneName: String = "this phone"
) {
    val url: String
        get() = when {
            address.isBlank() || port == 0 -> ""
            port == 80 -> "http://$address"
            else -> "http://$address:$port"
        }

    val joinQr: String
        get() = if (ssid.isBlank()) "" else WifiQr.joinPayload(ssid, password)

    /** The exact text someone can type if they cannot scan. */
    val instructions: String
        get() = buildString {
            append("Join Wi-Fi: ").append(ssid)
            if (password.isNotBlank()) append("\nPassword: ").append(password)
            if (url.isNotBlank()) append("\nThen open: ").append(url)
        }
}

/** Single source of truth between the service (which owns the sockets) and the UI. */
object DropHub {
    private val _state = MutableStateFlow(DropState())
    val state: StateFlow<DropState> = _state

    fun update(block: (DropState) -> DropState) {
        _state.value = block(_state.value)
    }

    fun reset() {
        _state.value = DropState(history = _state.value.history)
    }
}

class Prefs(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("drop.settings", Context.MODE_PRIVATE)

    var keepAwake: Boolean
        get() = prefs.getBoolean("keep.awake", true)
        set(value) = prefs.edit().putBoolean("keep.awake", value).apply()

    var useFixedGroup: Boolean
        get() = prefs.getBoolean("hotspot.fixed", false)
        set(value) = prefs.edit().putBoolean("hotspot.fixed", value).apply()

    var fixedName: String
        get() = prefs.getString("hotspot.name", "").orEmpty()
        set(value) = prefs.edit().putString("hotspot.name", value).apply()

    var fixedPass: String
        get() = prefs.getString("hotspot.pass", "").orEmpty()
        set(value) = prefs.edit().putString("hotspot.pass", value).apply()

    var phoneName: String
        get() = prefs.getString("phone.name", "").orEmpty().ifBlank { defaultName() }
        set(value) = prefs.edit().putString("phone.name", value).apply()

    /** Kept small on purpose: the files themselves live in the gallery or Downloads. */
    fun history(): List<ReceivedItem> {
        val raw = prefs.getString("history", "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val parts = line.split('\u001f')
            if (parts.size < 4) return@mapNotNull null
            ReceivedItem(
                name = parts[0],
                bytes = parts[1].toLongOrNull() ?: 0L,
                time = parts[2].toLongOrNull() ?: 0L,
                where = parts[3]
            )
        }.takeLast(200)
    }

    fun remember(item: ReceivedItem) {
        val all = (history() + item).takeLast(200)
        val text = all.joinToString("\n") {
            listOf(it.name, it.bytes.toString(), it.time.toString(), it.where)
                .joinToString("\u001f")
        }
        prefs.edit().putString("history", text).apply()
    }

    fun clearHistory() = prefs.edit().remove("history").apply()

    private fun defaultName(): String {
        val model = runCatching { Build.MODEL }.getOrNull().orEmpty()
        return if (model.isBlank()) "this phone" else model
    }
}

class DropApp : Application() {
    lateinit var prefs: Prefs
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        DropHub.update {
            it.copy(
                history = prefs.history(),
                version = BuildConfig.VERSION_NAME,
                phoneName = prefs.phoneName
            )
        }
    }

    companion object {
        lateinit var instance: DropApp
            private set
    }
}
