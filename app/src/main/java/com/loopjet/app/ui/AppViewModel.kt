package com.loopjet.app.ui

import android.app.Application
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loopjet.app.core.Fmt
import com.loopjet.app.core.Json
import com.loopjet.app.platform.Benchmark
import com.loopjet.app.platform.Perms
import com.loopjet.app.platform.ReceivedAppUi
import com.loopjet.app.platform.HistoryEntry
import com.loopjet.app.platform.LoopJetApp
import com.loopjet.app.platform.SendItem
import com.loopjet.app.platform.SendQueue
import com.loopjet.app.platform.TransferHub
import com.loopjet.app.platform.TransferService
import com.loopjet.app.platform.WebHub
import com.loopjet.app.platform.WebService
import com.loopjet.app.platform.WebShareQueue
import com.loopjet.app.platform.apps.AppInstaller
import com.loopjet.app.platform.apps.AppRepository
import com.loopjet.app.platform.apps.InstalledApp
import com.loopjet.app.platform.net.DiscoveredPeer
import com.loopjet.app.platform.net.DiscoveryEngine
import com.loopjet.app.platform.net.DiscoveryListener
import com.loopjet.app.platform.net.NetworkTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Screen { Home, Picker, Nearby, Scanner, Receive, Transfer, Invite, WebShare, History, Settings, Doctor, About, Onboarding }

enum class PickCategory(val label: String) {
    Apps("Apps"),
    Photos("Photos"),
    Videos("Videos"),
    Music("Music"),
    Documents("Documents"),
    Archives("Archives"),
    Downloads("Downloads"),
    Apks("APKs"),
    Folders("Folders")
}

enum class SortOrder(val label: String) { Newest("Newest"), Largest("Largest"), Name("Name") }

data class PickItem(
    val key: String,
    val name: String,
    val relativePath: String,
    val size: Long,
    val mtime: Long,
    val mime: String,
    val uri: String? = null,
    val filePath: String? = null,
    val isFolder: Boolean = false,
    val folderUri: String? = null
)

data class PickerState(
    val category: PickCategory = PickCategory.Photos,
    val query: String = "",
    val sort: SortOrder = SortOrder.Newest,
    val loading: Boolean = false,
    val items: List<PickItem> = emptyList(),
    val selection: Map<String, PickItem> = emptyMap(),
    val apps: List<InstalledApp> = emptyList(),
    val folderStack: List<Pair<String, String>> = emptyList(),
    val error: String? = null,
    val webShareMode: Boolean = false,
    /** The category had more rows than the picker loads in one pass. */
    val truncated: Boolean = false
) {
    val selectedBytes: Long get() = selection.values.sumOf { it.size }
}

data class DiscoveryState(
    val active: Boolean = false,
    val peers: List<DiscoveredPeer> = emptyList(),
    val log: String = "",
    val error: String? = null
)

/** Install progress for one received app. */
data class InstallUi(
    val running: Boolean = false,
    val progress: Float = 0f,
    val done: Boolean = false,
    val message: String = "",
    val needsPermission: Boolean = false
)

data class AppState(
    val screen: Screen = Screen.Home,
    val dark: Boolean = false,
    val themeMode: String = "system",
    val picker: PickerState = PickerState(),
    val discovery: DiscoveryState = DiscoveryState(),
    val benchmark: String? = null,
    val benchRunning: Boolean = false,
    val deviceName: String = "",
    val avatar: String = "🚀",
    val trustedCount: Int = 0,
    val freeSpace: String = "",
    val notice: String? = null,
    val selectedPeerName: String = ""
)

private const val PICKER_ROW_LIMIT = 20_000

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as LoopJetApp
    private val prefs = app.prefs
    private val tools = NetworkTools(application)
    private val appRepo = AppRepository(application)

    private val _state = MutableStateFlow(
        AppState(
            screen = if (prefs.onboardingDone) Screen.Home else Screen.Onboarding,
            themeMode = prefs.theme,
            deviceName = prefs.deviceName,
            avatar = prefs.avatar,
            trustedCount = prefs.trustedDevices.size,
            freeSpace = Fmt.bytes(application.filesDir.usableSpace)
        )
    )
    val state: StateFlow<AppState> = _state

    private val _installs = MutableStateFlow<Map<String, InstallUi>>(emptyMap())
    val installs: StateFlow<Map<String, InstallUi>> = _installs

    private var discovery: DiscoveryEngine? = null
    private var pendingScan = false

    val session get() = TransferHub.session
    val web get() = WebHub.state
    val sendItems get() = SendQueue.items

    // --- navigation ------------------------------------------------------------------------------

    fun go(screen: Screen) {
        _state.value = _state.value.copy(screen = screen)
        if (screen == Screen.Nearby || screen == Screen.Scanner) startDiscovery(advertise = false)
        // The scanner sits inside Send: stopping discovery there would make a freshly scanned link
        // fail with "Open Send first so discovery is running".
        if (screen != Screen.Nearby && screen != Screen.Scanner) stopDiscovery()
        if (screen == Screen.Receive) {
            TransferService.startReceive(getApplication())
            // The receiving phone is the one that has to be findable: it announces itself over mDNS,
            // multicast and BLE while the sender scans. Without this only a manual code or QR could
            // ever reach it.
            startDiscovery(advertise = true)
        }
    }

    fun back(): Screen {
        val current = _state.value.screen
        val next = when (current) {
            Screen.Scanner -> Screen.Nearby
            Screen.Doctor -> Screen.Settings
            Screen.Picker, Screen.Settings, Screen.History, Screen.Invite, Screen.WebShare, Screen.Nearby,
            Screen.Receive, Screen.About -> Screen.Home
            else -> Screen.Home
        }
        // Re-open discovery when the scanner hands control back to Nearby (see go()).
        if (current == Screen.Scanner) {
            _state.value = _state.value.copy(screen = next)
            startDiscovery(advertise = false)
            return next
        }
        if (current == Screen.Receive || current == Screen.Transfer) {
            // Leaving the receive screen stops an idle server; an active transfer keeps running in the
            // foreground service and stays reachable from the notification.
            if (TransferHub.session.value.mode == com.loopjet.app.platform.SessionMode.RECEIVE &&
                TransferHub.session.value.state != com.loopjet.app.core.TransferState.TRANSFERRING
            ) {
                TransferService.stop(getApplication())
            }
        }
        stopDiscovery()
        _state.value = _state.value.copy(screen = next)
        return next
    }

    fun toggleTheme() {
        val next = when (prefs.theme) {
            "system" -> if (_state.value.dark) "light" else "dark"
            "light" -> "dark"
            else -> "system"
        }
        setTheme(next)
    }

    fun setTheme(mode: String) {
        prefs.theme = mode
        _state.value = _state.value.copy(themeMode = mode)
    }

    fun setDark(dark: Boolean) {
        _state.value = _state.value.copy(dark = dark)
    }

    fun setDeviceName(name: String) {
        prefs.deviceName = name
        _state.value = _state.value.copy(deviceName = prefs.deviceName)
    }

    fun setAvatar(avatar: String) {
        prefs.avatar = avatar.take(2)
        _state.value = _state.value.copy(avatar = prefs.avatar)
    }

    fun notice(message: String?) {
        _state.value = _state.value.copy(notice = message)
    }

    // --- discovery -------------------------------------------------------------------------------

    fun startDiscovery(advertise: Boolean) {
        if (discovery != null) return
        val engine = DiscoveryEngine(getApplication(), prefs, tools, object : DiscoveryListener {
            override fun onPeersChanged(peers: List<DiscoveredPeer>) {
                _state.value = _state.value.copy(
                    discovery = _state.value.discovery.copy(active = true, peers = peers)
                )
            }

            override fun onDiscoveryLog(message: String) {
                _state.value = _state.value.copy(
                    discovery = _state.value.discovery.copy(log = message)
                )
            }
        })
        discovery = engine
        engine.start(advertise = advertise, controlPort = com.loopjet.app.core.ProtocolLimits.DEFAULT_PORT, beaconPort = com.loopjet.app.core.ProtocolLimits.BEACON_PORT)
        _state.value = _state.value.copy(discovery = DiscoveryState(active = true))
    }

    /**
     * Restarts discovery with a definite role. Needed after a permission dialog: discovery may have
     * been started a moment earlier, before the grant, so its mDNS/BLE registrations could have failed.
     */
    fun restartDiscovery(advertise: Boolean) {
        stopDiscovery()
        startDiscovery(advertise)
    }

    fun stopDiscovery() {
        discovery?.stop()
        discovery = null
        _state.value = _state.value.copy(discovery = DiscoveryState(active = false))
    }

    /**
     * Manual pairing by the six digits on the other phone. Resolves through a code probe, then sends
     * the current selection to the phone that answered.
     */
    fun pairWithCode(code: String) {
        if (sendItems.value.isEmpty()) {
            notice("Pick at least one file first")
            return
        }
        notice("Looking for the phone showing $code…")
        discovery?.pairWithCode(code) { peer, message ->
            if (peer == null) {
                notice(message)
            } else {
                notice(message)
                sendTo(peer)
            }
        } ?: notice("Open Send first so discovery is running")
    }

    /** Manual pairing by the link inside the receiver's QR code. */
    fun pairWithLink(link: String) {
        if (sendItems.value.isEmpty()) {
            notice("Pick at least one file first")
            return
        }
        notice("Connecting to the link…")
        discovery?.pairWithLink(link) { peer, message ->
            if (peer == null) {
                notice(message)
            } else {
                notice(message)
                sendTo(peer)
            }
        } ?: notice("Open Send first so discovery is running")
    }

    fun sendTo(peer: DiscoveredPeer) {
        val items = SendQueue.snapshot
        if (items.isEmpty()) {
            notice("Pick at least one file first")
            return
        }
        _state.value = _state.value.copy(selectedPeerName = peer.name)
        // A peer found by discovery is dialed on the normal interface; a leftover join from an earlier
        // hotspot session would send the sockets out of the wrong network.
        com.loopjet.app.platform.PendingPeer.network = null
        TransferService.startSend(getApplication(), peer)
        _state.value = _state.value.copy(screen = Screen.Transfer)
    }

    /**
     * Opens the transfer screen on whichever phone a session starts on.
     *
     * Only the sender used to navigate (see [sendTo]); the receiver stayed on the Receive screen and
     * therefore showed no percentage and no speed, which made a running transfer look like nothing was
     * happening. Returns true when it navigated.
     */
    fun openSessionIfRunning(): Boolean {
        val session = TransferHub.session.value
        if (session.mode == com.loopjet.app.platform.SessionMode.IDLE) return false
        val live = when (session.state) {
            com.loopjet.app.core.TransferState.CONNECTING,
            com.loopjet.app.core.TransferState.WAITING_ACCEPT,
            com.loopjet.app.core.TransferState.TRANSFERRING,
            com.loopjet.app.core.TransferState.PAUSED -> true
            else -> false
        }
        if (!live) return false
        val current = _state.value.screen
        // Never yank the user out of onboarding or out from behind the camera.
        if (current == Screen.Transfer || current == Screen.Onboarding || current == Screen.Scanner) return false
        _state.value = _state.value.copy(screen = Screen.Transfer)
        return true
    }

    /**
     * "Show my code": switch this phone into Receive so the other phone can scan it. Discovery stays
     * up on both sides, which is what keeps a scanned link connectable.
     */
    /**
     * Handles anything the scanner can produce.
     *
     * A v2 code that carries a network is joined first (Android shows its own consent sheet — one
     * tap, and no app can suppress it), then the peer is dialed on **that** network. A v1/v2 code with
     * only a LAN address connects directly.
     */
    fun acceptScanned(text: String) {
        when (val parsed = com.loopjet.app.platform.net.ConnectCodes.parse(text)) {
            is com.loopjet.app.platform.net.ScannedCode.JoinThenConnect -> {
                if (sendItems.value.isEmpty()) {
                    // Joining a network costs a system prompt: ask for the files first.
                    notice("Pick the files to send first, then scan again")
                    return
                }
                val net = parsed.network
                val app = getApplication<android.app.Application>()
                notice("Joining \"${net.ssid}\" — confirm the Android prompt")
                com.loopjet.app.platform.net.HotspotController.of(app)
                    .joinAp(net.ssid, net.psk.ifBlank { null }) { joined, message ->
                        if (joined == null) {
                            notice(message)
                            return@joinAp
                        }
                        // The service binds its sockets to this network; without it the session would
                        // go out of whichever Wi-Fi the phone still thinks is active.
                        com.loopjet.app.platform.PendingPeer.network = joined
                        pairWithLink(
                            com.loopjet.app.platform.net.ConnectCode(
                                deviceId = parsed.code.deviceId,
                                fingerprint = parsed.code.fingerprint,
                                name = parsed.code.name,
                                lanHost = net.host,
                                lanPort = net.port
                            ).let { c ->
                                // Hand the LAN parser a v1-shaped link pointing at the AP address.
                                "loopjet://${c.lanHost}:${c.lanPort}#${c.fingerprint}#${c.deviceId}"
                            }
                        )
                    }
            }
            is com.loopjet.app.platform.net.ScannedCode.Direct -> {
                val c = parsed.code
                pairWithLink("loopjet://${c.lanHost}:${c.lanPort}#${c.fingerprint}#${c.deviceId}")
            }
            is com.loopjet.app.platform.net.ScannedCode.PairingCode -> pairWithCode(parsed.digits)
            is com.loopjet.app.platform.net.ScannedCode.WifiOnly -> {
                val app = getApplication<android.app.Application>()
                notice("Joining \"${parsed.ssid}\" — then scan the LoopJet code again")
                com.loopjet.app.platform.net.HotspotController.of(app)
                    .joinAp(parsed.ssid, parsed.passphrase.ifBlank { null }) { joined, message ->
                        notice(if (joined != null) "Joined ${parsed.ssid} — scan again" else message)
                    }
            }
            is com.loopjet.app.platform.net.ScannedCode.WebLink -> {
                val clipboard = getApplication<android.app.Application>()
                    .getSystemService(android.content.ClipboardManager::class.java)
                clipboard?.setPrimaryClip(
                    android.content.ClipData.newPlainText("LoopJet link", parsed.url)
                )
                notice("Web Share link copied — open it in a browser")
            }
            com.loopjet.app.platform.net.ScannedCode.NeedsUpdate ->
                notice("That code is from a newer LoopJet — update this phone first")
            com.loopjet.app.platform.net.ScannedCode.Unknown ->
                notice("That QR is not a LoopJet code")
        }
    }

    fun receiveHere() {
        go(Screen.Receive)
        TransferService.startReceive(getApplication())
        startDiscovery(advertise = true)
    }

    fun cancelTransfer() {
        TransferService.stop(getApplication())
    }

    fun answerBrowser(accepted: Boolean) {
        WebHub.answerBrowser(accepted)
    }

    /**
     * Installs a received app: one PackageInstaller session holding the base APK and every split.
     * Progress and the outcome are published per package so the screen can show them.
     */
    fun installReceivedApp(received: ReceivedAppUi) {
        val context = getApplication<Application>()
        if (!Perms.canInstallPackages(context)) {
            updateInstall(received.packageName) {
                InstallUi(
                    message = "Allow “Install unknown apps” for LoopJet, then tap Install again",
                    needsPermission = true
                )
            }
            return
        }
        val files = received.files.map { java.io.File(it) }.filter { it.isFile }
        if (files.isEmpty()) {
            updateInstall(received.packageName) { InstallUi(message = "The APK files are gone") }
            return
        }
        updateInstall(received.packageName) {
            InstallUi(running = true, message = "Installing ${files.size} APK(s)…")
        }
        AppInstaller(context).install(
            packageName = received.packageName,
            apkFiles = files,
            onProgress = { fraction ->
                updateInstall(received.packageName) {
                    it.copy(running = true, progress = fraction, message = "Copying ${(fraction * 100).toInt()}%")
                }
            },
            onResult = { ok, message ->
                updateInstall(received.packageName) {
                    InstallUi(running = false, progress = 1f, done = ok, message = message)
                }
            }
        )
    }

    /** Opens the "install unknown apps" screen for this app, then the user taps Install again. */
    fun openInstallPermission() {
        val context = getApplication<Application>()
        runCatching {
            context.startActivity(
                Perms.installIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun updateInstall(packageName: String, block: (InstallUi) -> InstallUi) {
        _installs.value = _installs.value.toMutableMap().apply {
            put(packageName, block(get(packageName) ?: InstallUi()))
        }
    }

    fun installState(packageName: String): InstallUi = _installs.value[packageName] ?: InstallUi()

    fun pauseTransfer() = TransferService.pause(getApplication())

    fun resumeTransfer() = TransferService.resume(getApplication())

    fun answerOffer(accepted: Boolean, trust: Boolean) {
        TransferHub.answerOffer(accepted, trust)
        if (accepted && trust) {
            // The service persists the trust right after the decision; re-read a moment later.
            viewModelScope.launch {
                kotlinx.coroutines.delay(600)
                _state.value = _state.value.copy(trustedCount = prefs.trustedDevices.size)
            }
        }
    }

    // --- picker ----------------------------------------------------------------------------------

    fun setCategory(category: PickCategory) {
        _state.value = _state.value.copy(picker = _state.value.picker.copy(category = category, items = emptyList(), folderStack = emptyList()))
        loadCategory()
    }

    private var queryJob: kotlinx.coroutines.Job? = null

    fun setQuery(query: String) {
        _state.value = _state.value.copy(picker = _state.value.picker.copy(query = query))
        // Typing used to start a full MediaStore query per keystroke.
        queryJob?.cancel()
        queryJob = viewModelScope.launch {
            kotlinx.coroutines.delay(220)
            loadCategory()
        }
    }

    fun setSort(sort: SortOrder) {
        _state.value = _state.value.copy(picker = _state.value.picker.copy(sort = sort))
        loadCategory()
    }

    fun openPicker(webShare: Boolean = false) {
        SendQueue.clear()
        // Start finding the other phone while the user is still choosing files: the link is warm by
        // the time they tap a peer, which is most of the perceived "connects instantly" effect.
        startDiscovery(advertise = false)
        _state.value = _state.value.copy(
            screen = Screen.Picker,
            picker = PickerState(category = PickCategory.Photos, webShareMode = webShare)
        )
        loadCategory()
        loadApps()
    }

    fun loadApps() {
        // Listing ~200 packages with icons and reading each APK's length is disk work: never on the
        // main thread, or the picker freezes the first time the Apps category is opened.
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) { appRepo.load(includeSystem = false) }
            _state.value = _state.value.copy(picker = _state.value.picker.copy(apps = apps))
        }
    }

    fun loadCategory() {
        viewModelScope.launch {
            val picker = _state.value.picker
            _state.value = _state.value.copy(picker = picker.copy(loading = true, error = null))
            val items = withContext(Dispatchers.IO) { queryCategory(picker) }
            _state.value = _state.value.copy(
                picker = _state.value.picker.copy(
                    loading = false,
                    items = items,
                    error = null,
                    truncated = resultTruncated
                )
            )
        }
    }

    private fun queryCategory(picker: PickerState): List<PickItem> {
        val resolver = getApplication<Application>().contentResolver
        val query = picker.query.trim().lowercase()
        val sortColumn = when (picker.sort) {
            SortOrder.Newest -> MediaStore.MediaColumns.DATE_MODIFIED
            SortOrder.Largest -> MediaStore.MediaColumns.SIZE
            SortOrder.Name -> MediaStore.MediaColumns.DISPLAY_NAME
        }
        val order = if (picker.sort == SortOrder.Name) "ASC" else "DESC"
        val result = ArrayList<PickItem>()
        val projections = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        val collection = when (picker.category) {
            PickCategory.Photos -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            PickCategory.Videos -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            PickCategory.Music -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Files.getContentUri("external")
        }
        val selection: String?
        val args: Array<String>?
        when (picker.category) {
            PickCategory.Documents -> {
                selection = "(LOWER(${MediaStore.MediaColumns.DISPLAY_NAME}) LIKE '%.pdf' OR " +
                    "LOWER(${MediaStore.MediaColumns.DISPLAY_NAME}) LIKE '%.doc%' OR " +
                    "LOWER(${MediaStore.MediaColumns.DISPLAY_NAME}) LIKE '%.xls%' OR " +
                    "LOWER(${MediaStore.MediaColumns.DISPLAY_NAME}) LIKE '%.ppt%' OR " +
                    "LOWER(${MediaStore.MediaColumns.DISPLAY_NAME}) LIKE '%.txt' OR " +
                    "LOWER(${MediaStore.MediaColumns.DISPLAY_NAME}) LIKE '%.csv' OR " +
                    "LOWER(${MediaStore.MediaColumns.DISPLAY_NAME}) LIKE '%.md')"
                args = null
            }
            PickCategory.Archives -> {
                selection = "(LOWER(${MediaStore.MediaColumns.DISPLAY_NAME}) LIKE '%.zip' OR " +
                    "LOWER(${MediaStore.MediaColumns.DISPLAY_NAME}) LIKE '%.rar' OR " +
                    "LOWER(${MediaStore.MediaColumns.DISPLAY_NAME}) LIKE '%.7z' OR " +
                    "LOWER(${MediaStore.MediaColumns.DISPLAY_NAME}) LIKE '%.tar' OR " +
                    "LOWER(${MediaStore.MediaColumns.DISPLAY_NAME}) LIKE '%.gz')"
                args = null
            }
            PickCategory.Apks -> {
                selection = "LOWER(${MediaStore.MediaColumns.DISPLAY_NAME}) LIKE '%.apk' OR " +
                    "LOWER(${MediaStore.MediaColumns.DISPLAY_NAME}) LIKE '%.apks' OR " +
                    "LOWER(${MediaStore.MediaColumns.DISPLAY_NAME}) LIKE '%.xapk'"
                args = null
            }
            PickCategory.Downloads -> {
                selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
                args = arrayOf("%Download%")
            }
            else -> {
                selection = null
                args = null
            }
        }
        var truncated = false
        runCatching {
            resolver.query(collection, projections, selection, args, "$sortColumn $order")?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val pathIndex = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                var count = 0
                while (cursor.moveToNext()) {
                    if (count >= PICKER_ROW_LIMIT) {
                        truncated = true
                        break
                    }
                    val name = cursor.getString(nameIndex) ?: continue
                    if (query.isNotEmpty() && !name.lowercase().contains(query)) continue
                    val size = cursor.getLong(sizeIndex)
                    if (size <= 0) continue
                    val id = cursor.getLong(idIndex)
                    val uri = ContentUris.withAppendedId(collection, id)
                    val relative = if (pathIndex >= 0) cursor.getString(pathIndex) ?: "" else ""
                    // Keep the real bucket so two files with the same name stay two files.
                    val folder = relative.ifBlank { picker.category.label + "/" }
                    val mime = cursor.getString(mimeIndex) ?: "application/octet-stream"
                    result.add(
                        PickItem(
                            key = uri.toString(),
                            name = name,
                            relativePath = folder.trimEnd('/') + "/" + name,
                            size = size,
                            mtime = cursor.getLong(dateIndex) * 1000,
                            mime = mime,
                            uri = uri.toString()
                        )
                    )
                    count++
                }
            }
        }
        resultTruncated = truncated
        return result
    }

    @Volatile private var resultTruncated = false

    fun toggleSelection(item: PickItem) {
        val current = _state.value.picker.selection.toMutableMap()
        if (current.containsKey(item.key)) current.remove(item.key) else current[item.key] = item
        _state.value = _state.value.copy(picker = _state.value.picker.copy(selection = current))
    }

    fun selectAllVisible() {
        val current = _state.value.picker.selection.toMutableMap()
        // Everything the picker loaded, not an arbitrary 500: "select all" has to mean all.
        _state.value.picker.items.forEach { current[it.key] = it }
        _state.value = _state.value.copy(picker = _state.value.picker.copy(selection = current))
    }

    fun clearSelection() {
        _state.value = _state.value.copy(picker = _state.value.picker.copy(selection = emptyMap()))
    }

    fun addApp(app: InstalledApp) {
        val items = appRepo.toSendItems(app)
        SendQueue.add(items)
        _state.value = _state.value.copy(notice = "${app.label}: ${items.size} APK part(s) queued")
    }

    fun commitSelection() {
        val picker = _state.value.picker
        val items = picker.selection.values.map { pick ->
            SendItem(
                key = pick.key,
                displayName = pick.name,
                relativePath = pick.relativePath,
                size = pick.size,
                mtime = pick.mtime,
                mime = pick.mime,
                uri = pick.uri,
                filePath = pick.filePath
            )
        }
        SendQueue.add(items)
        _state.value = _state.value.copy(
            picker = picker.copy(selection = emptyMap()),
            screen = if (picker.webShareMode) Screen.WebShare else Screen.Nearby,
            notice = "${items.size} item(s) queued"
        )
        if (picker.webShareMode) {
            WebShareQueue.set(SendQueue.snapshot)
            WebService.startShare(getApplication())
            _state.value = _state.value.copy(screen = Screen.WebShare)
        } else {
            startDiscovery(advertise = false)
        }
    }

    fun clearQueue() {
        SendQueue.clear()
    }

    fun removeQueued(key: String) {
        SendQueue.remove(key)
    }

    fun addTextSnippet(text: String) {
        val file = File(getApplication<Application>().cacheDir, "LoopJet note ${System.currentTimeMillis()}.txt")
        runCatching { file.writeText(text) }
        SendQueue.add(
            listOf(
                SendItem(
                    key = "text:${file.absolutePath}",
                    displayName = file.name,
                    relativePath = "Text/${file.name}",
                    size = file.length(),
                    mtime = file.lastModified(),
                    mime = "text/plain",
                    filePath = file.absolutePath
                )
            )
        )
        _state.value = _state.value.copy(notice = "Text added to the queue")
    }

    /** Adds files handed to us by the system share sheet. */
    fun addSharedUris(uris: List<Uri>) {
        viewModelScope.launch {
            val unreadable = java.util.concurrent.atomic.AtomicInteger()
            val items = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    // Some apps hand over a transient read grant; make it stick when the provider
                    // allows it, so the send still works after the task is recreated.
                    runCatching {
                        getApplication<Application>().contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                    runCatching {
                        val resolver = getApplication<Application>().contentResolver
                        var name = "shared_file"
                        var size = 0L
                        var mime = "application/octet-stream"
                        resolver.query(uri, null, null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let {
                                    name = cursor.getString(it) ?: name
                                }
                                cursor.getColumnIndex(MediaStore.MediaColumns.SIZE).takeIf { it >= 0 }?.let {
                                    size = cursor.getLong(it)
                                }
                                cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE).takeIf { it >= 0 }?.let {
                                    mime = cursor.getString(it) ?: mime
                                }
                            }
                        }
                        if (size <= 0L) {
                            resolver.openAssetFileDescriptor(uri, "r")?.use { size = it.length }
                        }
                        // A file whose size cannot be read would arrive as a 0-byte file: refuse it
                        // here, where it can be explained, instead of failing mid-transfer.
                        if (size <= 0L) {
                            unreadable.incrementAndGet()
                            return@runCatching null
                        }
                        PickItem(
                            key = uri.toString(),
                            name = name,
                            relativePath = "Shared/$name",
                            size = size,
                            mtime = System.currentTimeMillis(),
                            mime = mime,
                            uri = uri.toString()
                        )
                    }.getOrNull()
                }
            }
            val readable = items.filterNotNull()
            val selection = readable.associateBy { it.key }
            val skipped = unreadable.get()
            _state.value = _state.value.copy(
                screen = Screen.Picker,
                picker = _state.value.picker.copy(selection = selection, category = PickCategory.Downloads),
                notice = buildString {
                    append("Shared ${readable.size} file(s) — pick who receives them")
                    if (skipped > 0) append(" · $skipped could not be read")
                }
            )
            loadCategory()
        }
    }

    // --- folders (SAF) ---------------------------------------------------------------------------

    fun openFolderTree(uri: Uri) {
        val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(uri, takeFlags)
        }
        browseFolder(uri.toString(), uri.toString().substringAfterLast('/').ifBlank { "Storage" })
    }

    fun browseFolder(uri: String, label: String) {
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) { listDocuments(Uri.parse(uri)) }
            _state.value = _state.value.copy(
                picker = _state.value.picker.copy(
                    category = PickCategory.Folders,
                    items = items,
                    folderStack = _state.value.picker.folderStack + (uri to label),
                    loading = false
                )
            )
        }
    }

    fun goUpFolder() {
        val stack = _state.value.picker.folderStack
        if (stack.size <= 1) {
            _state.value = _state.value.copy(picker = _state.value.picker.copy(folderStack = emptyList(), items = emptyList()))
            return
        }
        val parent = stack.dropLast(1)
        val (uri, label) = parent.last()
        browseFolder(uri, label)
    }

    private fun listDocuments(treeUri: Uri): List<PickItem> {
        val resolver = getApplication<Application>().contentResolver
        val document = androidx.documentfile.provider.DocumentFile.fromTreeUri(getApplication(), treeUri)
            ?: return emptyList()
        val out = ArrayList<PickItem>()
        document.listFiles().forEach { file ->
            val name = file.name ?: return@forEach
            out.add(
                PickItem(
                    key = file.uri.toString(),
                    name = name,
                    relativePath = name,
                    size = file.length(),
                    mtime = file.lastModified(),
                    mime = file.type ?: if (file.isDirectory) "inode/directory" else "application/octet-stream",
                    uri = if (file.isDirectory) null else file.uri.toString(),
                    isFolder = file.isDirectory,
                    folderUri = if (file.isDirectory) file.uri.toString() else null
                )
            )
        }
        return out.sortedWith(compareByDescending<PickItem> { it.isFolder }.thenBy { it.name.lowercase() })
    }

    // --- web / invite ----------------------------------------------------------------------------

    fun startInvite(useHotspot: Boolean) {
        WebService.startInvite(getApplication(), useHotspot)
        _state.value = _state.value.copy(screen = Screen.Invite)
    }

    fun startWebShare() {
        if (SendQueue.snapshot.isEmpty()) {
            openPicker(webShare = true)
        } else {
            WebShareQueue.set(SendQueue.snapshot)
            WebService.startShare(getApplication())
            _state.value = _state.value.copy(screen = Screen.WebShare)
        }
    }

    fun stopWeb() {
        WebService.stop(getApplication())
        _state.value = _state.value.copy(screen = Screen.Home)
    }

    // --- history / settings ----------------------------------------------------------------------

    fun history(): List<HistoryEntry> = app.history.all().sortedByDescending { it.startedAt }

    fun clearHistory() = app.history.clear()

    fun resend(entry: HistoryEntry) {
        notice("Re-send opens the picker: ${entry.fileNames.firstOrNull() ?: entry.peerName}")
        openPicker()
    }

    fun trustedDevices(): Map<String, String> = prefs.trustedNames()

    fun untrust(fingerprint: String) {
        prefs.untrust(fingerprint)
        _state.value = _state.value.copy(trustedCount = prefs.trustedDevices.size)
    }

    fun setAutoAccept(value: Boolean) {
        prefs.autoAcceptTrusted = value
        _state.value = _state.value.copy()
    }

    fun autoAccept(): Boolean = prefs.autoAcceptTrusted

    fun setSpeedMode(mode: String) {
        prefs.speedMode = mode
        _state.value = _state.value.copy()
    }

    fun speedMode(): String = prefs.speedMode

    fun setSaveFolder(name: String) {
        prefs.saveFolderName = name
        _state.value = _state.value.copy()
    }

    fun saveFolder(): String = prefs.saveFolderName

    fun setVerifyAfter(value: Boolean) {
        prefs.verifyAfterTransfer = value
        _state.value = _state.value.copy()
    }

    fun verifyAfter(): Boolean = prefs.verifyAfterTransfer

    fun setWebPin(pin: String, require: Boolean) {
        prefs.webPin = pin
        prefs.webRequirePin = require
        _state.value = _state.value.copy()
    }

    fun webPin(): String = prefs.webPin

    fun webRequirePin(): Boolean = prefs.webRequirePin

    fun runBenchmark() {
        if (_state.value.benchRunning) return
        _state.value = _state.value.copy(benchRunning = true, benchmark = "Running…")
        viewModelScope.launch {
            val result = runCatching { Benchmark(getApplication()).run { stage ->
                _state.value = _state.value.copy(benchmark = stage)
            } }.getOrNull()
            val text = if (result == null) {
                "Benchmark failed"
            } else {
                Benchmark(getApplication()).format(result) + "\n" +
                    result.notes.joinToString("\n") { "• $it" }
            }
            prefs.lastBenchmark = text
            _state.value = _state.value.copy(benchRunning = false, benchmark = text)
        }
    }

    fun deviceSummary(): String {
        val band = tools.bandLabel()
        val ssid = tools.ssid() ?: "not on Wi-Fi"
        val p2p = if (tools.supportsWifiDirect()) "Wi-Fi Direct" else "no Wi-Fi Direct"
        return "$ssid · $band · $p2p"
    }

    fun historyJson(): String = Json.stringify(history().take(20).map { it.toJson() })

    override fun onCleared() {
        stopDiscovery()
        super.onCleared()
    }
}

@androidx.compose.runtime.Composable
fun appViewModel(): AppViewModel = viewModel()
