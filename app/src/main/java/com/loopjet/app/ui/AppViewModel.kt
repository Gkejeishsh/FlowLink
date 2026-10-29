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
import com.loopjet.app.platform.ReceiverPort
import com.loopjet.app.platform.ReceivedAppUi
import com.loopjet.app.platform.HistoryEntry
import com.loopjet.app.platform.FlowLinkApp
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

/**
 * Screens carry a depth so navigation knows which way it is going. Deeper screens grow in from
 * slightly larger; shallower ones recede. Without this every transition looks the same and Back
 * feels like a new navigation rather than a step back.
 */
enum class Screen(val depth: Int) {
    Onboarding(0),
    Home(0),
    Picker(1),
    Nearby(1),
    Receive(1),
    History(1),
    Settings(1),
    Doctor(1),
    About(1),
    Invite(1),
    WebShare(1),
    Scanner(2),
    Transfer(2)
}

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

/**
 * Search is its own place now. Tapping the search field leaves the category tabs behind and asks a
 * direct question: are you looking for a file, or for an app? Files are searched across the whole
 * phone, not inside whichever folder happened to be open.
 */
enum class SearchScope(val label: String) { Files("Files"), Apps("Apps") }

/** Search budgets: MediaStore does the bulk, the tree walk is capped so it can never stall the UI. */
private const val SEARCH_LIMIT = 300
private const val SEARCH_SCAN_LIMIT = 20_000
private const val SEARCH_SCAN_BUDGET_MS = 1_200L

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
    /**
     * Apps picked with the same tick circles as files. They used to be queued the instant they were
     * tapped, in a separate place from the file selection, so "two photos + two apps, then send" was
     * impossible -- the two halves of the tray never met.
     */
    val selectedApps: Set<String> = emptySet(),
    /** Deepest-first chain of folders for the breadcrumb. */
    val folderStack: List<Pair<String, String>> = emptyList(),
    /** Absolute path being browsed in the Folders category; null when a media category is shown. */
    val browsePath: String? = null,
    /** The Folders category needs "All files access" before it can show the real file tree. */
    val needsAllFiles: Boolean = false,
    val error: String? = null,
    /** Search mode replaces the category strip with a Files/Apps choice and a global query. */
    val searchOpen: Boolean = false,
    val searchScope: SearchScope = SearchScope.Files,
    /** A category is empty only because Android has not granted media access yet. */
    val needsMediaAccess: Boolean = false,
    /** Android 14 partial photo access: media queries work but show only the ticked few. */
    val mediaPartial: Boolean = false,
    val webShareMode: Boolean = false,
    /** The category had more rows than the picker loads in one pass. */
    val truncated: Boolean = false
) {
    val selectedBytes: Long get() = selection.values.sumOf { it.size }

    val selectedAppBytes: Long get() = apps.filter { it.packageName in selectedApps }.sumOf { it.size }

    /** Everything that would be sent: picked files plus picked apps. */
    val totalSelected: Int get() = selection.size + selectedApps.size

    val totalBytes: Long get() = selectedBytes + selectedAppBytes
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
    /**
     * Observed copy of the speed preference. Reading prefs directly from a composable is invisible to
     * Compose, so picking Balanced/Fast/Max used to change nothing on screen until some unrelated
     * state update happened to repaint.
     */
    val speedMode: String = "fast",
    val picker: PickerState = PickerState(),
    val discovery: DiscoveryState = DiscoveryState(),
    val benchmark: String? = null,
    val benchRunning: Boolean = false,
    val deviceName: String = "",
    val avatar: String = "🚀",
    val trustedCount: Int = 0,
    val freeSpace: String = "",
    /**
     * Cached so Home can show it without asking WifiManager three times per recomposition. Three
     * IPC calls inside a composable body is exactly the kind of cost that turns a scroll into a
     * stutter on a mid-range phone.
     */
    val networkSummary: String = "",
    /** Session count, cached: the count used to be a JSON file read inside composition. */
    val historyCount: Int = 0,
    val notice: String? = null,
    val selectedPeerName: String = ""
)

private const val PICKER_ROW_LIMIT = 20_000

class AppViewModel(application: Application) : AndroidViewModel(application) {
    /**
     * Set by MainActivity. Joining a hotspot needs NEARBY_WIFI_DEVICES (Android 13+) or location
     * (8-12) at runtime, and those are otherwise only asked when Start/Receive is tapped — so the
     * first scan of a hotspot code would fail with a SecurityException. The scan is remembered and
     * replayed once the dialog is answered.
     */
    var requestWifiPermission: (() -> Unit)? = null
    private var pendingWifiScan: String? = null

    private val app = application as FlowLinkApp
    private val prefs = app.prefs
    private val tools = NetworkTools(application)
    private val appRepo = AppRepository(application)

    private val _state = MutableStateFlow(
        AppState(
            screen = if (prefs.onboardingDone) Screen.Home else Screen.Onboarding,
            themeMode = prefs.theme,
            speedMode = prefs.speedMode,
            deviceName = prefs.deviceName,
            avatar = prefs.avatar,
            trustedCount = prefs.trustedDevices.size,
            freeSpace = Fmt.bytes(application.filesDir.usableSpace),
            historyCount = runCatching { app.history.all().size }.getOrDefault(0),
            networkSummary = runCatching { deviceSummary() }.getOrDefault("")
        )
    )
    val state: StateFlow<AppState> = _state

    private val _installs = MutableStateFlow<Map<String, InstallUi>>(emptyMap())
    val installs: StateFlow<Map<String, InstallUi>> = _installs

    private var discovery: DiscoveryEngine? = null
    private var pendingScan = false

    val session get() = TransferHub.session
    /** Which role the running discovery engine has, so a role change restarts it. */
    private var discoveryAdvertise: Boolean? = null
    val web get() = WebHub.state
    val sendItems get() = SendQueue.items

    // --- navigation ------------------------------------------------------------------------------

    fun go(screen: Screen) {
        _state.value = _state.value.copy(screen = screen)
        if (screen == Screen.Nearby || screen == Screen.Scanner) startDiscovery(advertise = false)
        // The scanner sits inside Send: stopping discovery there would make a freshly scanned link
        // fail with "Open Send first so discovery is running".
        if (screen == Screen.Transfer) {
            // The phone that is receiving stays findable for the whole session, so a sender whose
            // first attempt failed can be retried without the user re-opening Receive on this phone.
            // The phone that is sending stops probing the subnet instead: those probes share the
            // radio with the transfer.
            if (TransferHub.session.value.mode != com.loopjet.app.platform.SessionMode.RECEIVE) {
                stopDiscovery()
            }
        } else if (screen != Screen.Nearby && screen != Screen.Scanner) {
            stopDiscovery()
        }
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
            //
            // An offer that is on screen must survive it: pressing back while the accept dialog was
            // up used to stop the service, which answered "no" on the user's behalf and left the
            // sender with a bare "Declined" (or, minutes later, a timeout) for a transfer nobody had
            // answered. CONNECTING/WAITING_ACCEPT are kept alive for the same reason.
            val session = TransferHub.session.value
            val busy = session.offer != null || session.state in setOf(
                com.loopjet.app.core.TransferState.TRANSFERRING,
                com.loopjet.app.core.TransferState.CONNECTING,
                com.loopjet.app.core.TransferState.WAITING_ACCEPT,
                com.loopjet.app.core.TransferState.PAUSED
            )
            if (session.mode == com.loopjet.app.platform.SessionMode.RECEIVE && !busy) {
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
        // The role matters. This used to return early whenever an engine already existed, so a phone
        // that had started discovery as a *sender* (scanning only) kept that role when the user then
        // opened Receive -- it never announced itself, and the other phone could not find it. That is
        // the "I pressed Receive and no connection was made" report. A different role now restarts.
        val existing = discovery
        if (existing != null && discoveryAdvertise == advertise) return
        if (existing != null) stopDiscovery()
        discoveryAdvertise = advertise
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
        // Announce the port the receiving engine actually bound, not the default one: when the
        // default pair was still held by the previous session the server moves to the next free
        // pair, and a beacon advertising the old port sends the other phone to a closed socket.
        val port = if (advertise && ReceiverPort.current > 0) {
            ReceiverPort.current
        } else {
            com.loopjet.app.core.ProtocolLimits.DEFAULT_PORT
        }
        engine.start(advertise = advertise, controlPort = port, beaconPort = com.loopjet.app.core.ProtocolLimits.BEACON_PORT)
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
        discoveryAdvertise = null
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
    /**
     * @param joinedNetwork the network the caller joined for this peer (v2 hotspot code). It is handed
     *        to [sendTo] so the sockets are bound to it; without that the send goes out of the phone's
     *        own Wi-Fi and times out, which is exactly how the hotspot path failed.
     */
    fun pairWithLink(link: String, joinedNetwork: android.net.Network? = null) {
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
                sendTo(peer, joinedNetwork)
            }
        } ?: notice("Open Send first so discovery is running")
    }

    /**
     * @param joinedNetwork the network the caller just joined (a v2 hotspot code) and wants the
     *        sockets bound to. Passing null means "plain discovery peer": any leftover join is
     *        dropped, otherwise the sockets would leave through the wrong interface.
     *
     *        This parameter exists because sendTo() used to clear the join unconditionally -- it was
     *        called from the join callback, so it wiped the network that had just been joined and the
     *        hotspot path could never connect.
     */
    fun sendTo(peer: DiscoveredPeer, joinedNetwork: android.net.Network? = null) {
        val items = SendQueue.snapshot
        if (items.isEmpty()) {
            notice("Pick at least one file first")
            return
        }
        _state.value = _state.value.copy(selectedPeerName = peer.name)
        com.loopjet.app.platform.PendingPeer.network = joinedNetwork
        TransferService.startSend(getApplication(), peer)
        // This phone is now the sender: stop probing the subnet. Those probe packets would share the
        // radio with the transfer itself. (This path sets the screen directly, so it has to say so.)
        stopDiscovery()
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
        // The receiving phone must stay findable for as long as the session lives: a sender that
        // dialled a stale address, or whose first attempt timed out, has to be able to find this
        // phone again. Re-asserting it here is idempotent (a role change is the only thing that
        // restarts the engine).
        if (session.mode == com.loopjet.app.platform.SessionMode.RECEIVE) {
            startDiscovery(advertise = true)
        }
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
    /** True when this phone may legally join/create a Wi-Fi network right now. */
    private fun hasWifiPermission(): Boolean {
        val ask = com.loopjet.app.platform.Perms.runtimeRequirements(app, forReceive = true)
            .filter {
                it.permission == android.Manifest.permission.NEARBY_WIFI_DEVICES ||
                    it.permission == com.loopjet.app.platform.Perms.LOCAL_NETWORK
            }
        return com.loopjet.app.platform.Perms.missing(app, ask).isEmpty()
    }

    /**
     * Called by MainActivity when its permission dialog closes: replay a scan that had to wait for
     * the nearby-Wi-Fi grant instead of making the user walk back to the scanner.
     */
    fun retryWifiScan() {
        val text = pendingWifiScan ?: return
        pendingWifiScan = null
        acceptScanned(text)
    }

    /** Every IPv4 address this phone holds, for the same-subnet decision. */
    private fun localIpv4Addresses(): List<String> =
        com.loopjet.app.platform.net.NetworkTools(app).allLocalAddresses()
            .mapNotNull { it.second.hostAddress }

    /**
     * Joins the hotspot advertised in [net] and sends to the receiver there. Shared by the v2
     * "join then connect" code and by the LAN code that also carries a hotspot as a fallback.
     */
    private fun joinThenSend(
        net: com.loopjet.app.platform.net.ConnectNetwork,
        code: com.loopjet.app.platform.net.ConnectCode
    ) {
        if (sendItems.value.isEmpty()) {
            notice("Pick the files to send first, then scan again")
            return
        }
        if (!hasWifiPermission()) {
            pendingWifiScan = lastScanned
            notice("Allow \"Nearby Wi-Fi devices\" so FlowLink can join the other phone")
            requestWifiPermission?.invoke()
            return
        }
        val app = getApplication<android.app.Application>()
        val link = "loopjet://${net.host}:${net.port}#${code.fingerprint}#${code.deviceId}"
        // Wi-Fi has to be on for any join, and no app can switch it on since Android 10. Saying so
        // beats a request that fails two seconds later with "could not join".
        if (!tools.wifiEnabled() && tools.ssid() != net.ssid) {
            notice("Turn Wi-Fi on to join \"${net.ssid}\" — opening Wi-Fi settings")
            openWifiSettings()
            return
        }
        // Already on that hotspot (joined it by hand, or this is a second scan): do not ask Android
        // for a network we are sitting on, just connect over it.
        if (tools.ssid() == net.ssid) {
            notice("Already on \"${net.ssid}\" — connecting")
            pairWithLink(link, joinedNetwork = com.loopjet.app.platform.net.HotspotController.of(app).joinedNetwork)
            return
        }
        notice("Joining \"${net.ssid}\" — confirm the Android prompt")
        com.loopjet.app.platform.net.HotspotController.of(app)
            .joinAp(net.ssid, net.psk.ifBlank { null }) { joined, message ->
                if (joined != null) {
                    pairWithLink(link, joinedNetwork = joined)
                    return@joinAp
                }
                // The join can be refused (OEM policy, Wi-Fi off) while the code also carries a
                // plain LAN address. Try that before reporting failure: the two phones may after all
                // share a network, and a working transfer beats a correct explanation.
                if (code.lanHost.isNotBlank() && code.lanPort > 0) {
                    notice("$message · trying the direct link instead")
                    pairWithLink("loopjet://${code.lanHost}:${code.lanPort}#${code.fingerprint}#${code.deviceId}")
                } else {
                    notice("$message · join the hotspot from Wi-Fi settings, then scan again")
                }
            }
    }

    private var lastScanned: String = ""

    fun acceptScanned(text: String) {
        lastScanned = text
        when (val parsed = com.loopjet.app.platform.net.ConnectCodes.parse(text)) {
            is com.loopjet.app.platform.net.ScannedCode.JoinThenConnect ->
                joinThenSend(parsed.network, parsed.code)
            is com.loopjet.app.platform.net.ScannedCode.Direct -> {
                val c = parsed.code
                // Decide from data instead of trying and failing: same subnet -> LAN (fastest),
                // different network -> the hotspot the same code advertises. This is the case both
                // phones have a perfectly good address on two different networks.
                val plan = com.loopjet.app.platform.net.ConnectPlanner.plan(c, localIpv4Addresses())
                val link = "loopjet://${c.lanHost}:${c.lanPort}#${c.fingerprint}#${c.deviceId}"
                // Hotspot first, always. A code that carries one is a promise that a network exists
                // which the other phone controls, and joining it costs one Android prompt. Guessing
                // from subnets ("we look like we are on the same Wi-Fi") is exactly the guess that
                // produced silent timeouts, so it is only used when the code carries no hotspot.
                when {
                    c.network != null -> joinThenSend(c.network!!, c)
                    c.lanHost.isNotBlank() && c.lanPort > 0 -> pairWithLink(link)
                    else -> notice(plan.reason)
                }
            }
            is com.loopjet.app.platform.net.ScannedCode.PairingCode -> pairWithCode(parsed.digits)
            is com.loopjet.app.platform.net.ScannedCode.WifiOnly -> {
                if (!hasWifiPermission()) {
                    pendingWifiScan = text
                    notice("Allow \"Nearby Wi-Fi devices\" so FlowLink can join this network")
                    requestWifiPermission?.invoke()
                    return
                }
                val app = getApplication<android.app.Application>()
                notice("Joining \"${parsed.ssid}\" — then scan the FlowLink code again")
                com.loopjet.app.platform.net.HotspotController.of(app)
                    .joinAp(parsed.ssid, parsed.passphrase.ifBlank { null }) { joined, message ->
                        notice(if (joined != null) "Joined ${parsed.ssid} — scan again" else message)
                    }
            }
            is com.loopjet.app.platform.net.ScannedCode.WebLink -> {
                val clipboard = getApplication<android.app.Application>()
                    .getSystemService(android.content.ClipboardManager::class.java)
                clipboard?.setPrimaryClip(
                    android.content.ClipData.newPlainText("FlowLink link", parsed.url)
                )
                notice("Web Share link copied — open it in a browser")
            }
            com.loopjet.app.platform.net.ScannedCode.NeedsUpdate ->
                notice("That code is from a newer FlowLink — update this phone first")
            com.loopjet.app.platform.net.ScannedCode.Unknown ->
                notice("That QR is not a FlowLink code")
        }
    }

    fun receiveHere() {
        // go(Receive) already starts the service and the discovery. Starting it a second time was
        // the trigger for the dead-QR bug: the second receiver bound another port, the code on
        // screen moved to it, and then the service shut itself down as "already running".
        go(Screen.Receive)
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
                    message = "Allow “Install unknown apps” for FlowLink, then tap Install again",
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
        // An explicit Decline needs a reason the sender can act on; without one it only says
        // "Declined", which tells the other person nothing.
        TransferHub.answerOffer(
            accepted = accepted,
            trust = trust,
            reason = if (accepted) "" else "Declined on the receiving phone — tap Show my code there to retry"
        )
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
        _state.value = _state.value.copy(
            picker = _state.value.picker.copy(
                category = category,
                items = emptyList(),
                folderStack = emptyList(),
                browsePath = null,
                needsAllFiles = false,
                needsMediaAccess = false,
                searchOpen = false
            )
        )
        if (category == PickCategory.Folders) {
            openFolderRoot()
        } else {
            loadCategory()
        }
    }

    /**
     * The primary shared storage. Browsing starts here instead of asking the user to hand FlowLink one
     * folder through the system picker: the file manager is the tree, and it is already on the phone.
     */
    fun storageRoot(): File = android.os.Environment.getExternalStorageDirectory()

    fun hasAllFilesAccess(): Boolean = com.loopjet.app.platform.Perms.hasAllFiles(app)

    /** Opens the Folders category at the storage root (or asks for the one permission it needs). */
    fun openFolderRoot() {
        if (!hasAllFilesAccess()) {
            _state.value = _state.value.copy(
                picker = _state.value.picker.copy(
                    category = PickCategory.Folders,
                    browsePath = null,
                    needsAllFiles = true,
                    items = emptyList(),
                    folderStack = emptyList(),
                    loading = false
                )
            )
            return
        }
        openFolder(storageRoot().absolutePath)
    }

    /** Navigates into [path] and lists it. Folders come first, then files, names case-insensitive. */
    fun openFolder(path: String) {
        if (path.isBlank()) return
        val file = File(path)
        viewModelScope.launch {
            _state.value = _state.value.copy(
                picker = _state.value.picker.copy(
                    category = PickCategory.Folders,
                    needsAllFiles = false,
                    needsMediaAccess = false,
                    loading = true,
                    browsePath = file.absolutePath,
                    error = null
                )
            )
            val items = withContext(Dispatchers.IO) { listFolder(file) }
            val stack = buildStack(file)
            _state.value = _state.value.copy(
                picker = _state.value.picker.copy(loading = false, items = items, folderStack = stack)
            )
        }
    }

    /** Root -> current folder, for the breadcrumb and for the Up button. */
    private fun buildStack(dir: File): List<Pair<String, String>> {
        val chain = ArrayList<Pair<String, String>>()
        var cursor: File? = dir
        val rootPath = storageRoot().absolutePath
        while (cursor != null) {
            val name = when {
                cursor.absolutePath == rootPath -> "Internal storage"
                cursor.absolutePath == File(rootPath).parent -> "Storage"
                else -> cursor.name.ifBlank { cursor.absolutePath }
            }
            chain.add(0, cursor.absolutePath to name)
            cursor = cursor.parentFile
        }
        return chain
    }

    private fun listFolder(dir: File): List<PickItem> {
        val entries = runCatching { dir.listFiles() }.getOrNull() ?: return emptyList()
        val query = _state.value.picker.query.trim().lowercase()
        val sort = _state.value.picker.sort
        val out = ArrayList<PickItem>()
        for (entry in entries) {
            val name = entry.name ?: continue
            if (name.startsWith(".")) continue // keep hidden files out of the way
            val folder = entry.isDirectory
            if (!folder && entry.length() <= 0) continue
            if (query.isNotEmpty() && !name.lowercase().contains(query)) continue
            if (!folder && !entry.canRead()) continue
            out.add(
                PickItem(
                    key = entry.absolutePath,
                    name = name,
                    relativePath = entry.absolutePath.removePrefix(storageRoot().absolutePath).trimStart('/'),
                    size = if (folder) 0 else entry.length(),
                    mtime = entry.lastModified(),
                    mime = if (folder) "inode/directory" else mimeFor(name),
                    uri = if (folder) null else android.net.Uri.fromFile(entry).toString(),
                    filePath = if (folder) null else entry.absolutePath,
                    isFolder = folder,
                    folderUri = if (folder) entry.absolutePath else null
                )
            )
        }
        val sorted = when (sort) {
            SortOrder.Name -> out.sortedBy { it.name.lowercase() }
            SortOrder.Largest -> out.sortedByDescending { it.size }
            SortOrder.Newest -> out.sortedByDescending { it.mtime }
        }
        // Folders first regardless of the sort, or navigation becomes guesswork.
        return sorted.sortedByDescending { it.isFolder }
    }

    private fun mimeFor(name: String): String =
        android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    private var queryJob: kotlinx.coroutines.Job? = null

    fun setQuery(query: String) {
        _state.value = _state.value.copy(picker = _state.value.picker.copy(query = query))
        // Debounced, then a real query -- global when search mode is open, otherwise the current view.
        queryJob?.cancel()
        queryJob = viewModelScope.launch {
            kotlinx.coroutines.delay(220)
            val picker = _state.value.picker
            when {
                picker.searchOpen -> runSearch(picker.searchScope, picker.query)
                picker.category == PickCategory.Folders && picker.browsePath != null -> openFolder(picker.browsePath)
                else -> loadCategory()
            }
        }
    }

    /** Opened by tapping the search field: the category strip gives way to a Files / Apps choice. */
    fun openSearch() {
        _state.value = _state.value.copy(
            picker = _state.value.picker.copy(searchOpen = true, items = emptyList(), loading = false)
        )
        runSearch(_state.value.picker.searchScope, _state.value.picker.query)
        if (_state.value.picker.apps.isEmpty()) loadApps()
    }

    fun closeSearch() {
        queryJob?.cancel()
        _state.value = _state.value.copy(
            picker = _state.value.picker.copy(searchOpen = false, query = "", items = emptyList())
        )
        if (_state.value.picker.category == PickCategory.Folders) openFolderRoot() else loadCategory()
    }

    fun setSearchScope(scope: SearchScope) {
        _state.value = _state.value.copy(picker = _state.value.picker.copy(searchScope = scope, items = emptyList()))
        runSearch(scope, _state.value.picker.query)
    }

    private fun runSearch(scope: SearchScope, query: String) {
        val q = query.trim()
        _state.value = _state.value.copy(picker = _state.value.picker.copy(loading = q.isNotEmpty()))
        if (q.isEmpty()) {
            _state.value = _state.value.copy(picker = _state.value.picker.copy(loading = false, items = emptyList()))
            return
        }
        searchJob?.cancel()
        if (scope == SearchScope.Apps) {
            // Apps are already in memory, so this tab answers instantly and needs no query.
            _state.value = _state.value.copy(
                picker = _state.value.picker.copy(loading = false, items = emptyList(), needsMediaAccess = false)
            )
            return
        }
        searchJob = viewModelScope.launch {
            val results = withContext(Dispatchers.IO) { searchFilesEverywhere(q) }
            // A slower keystroke must not overwrite a newer query's results.
            if (_state.value.picker.query.trim() != q || !_state.value.picker.searchOpen) return@launch
            _state.value = _state.value.copy(
                picker = _state.value.picker.copy(
                    loading = false,
                    items = results,
                    truncated = false,
                    needsMediaAccess = results.isEmpty() && !Perms.canListMedia(app),
                    mediaPartial = results.isEmpty() && Perms.mediaAccessIsPartial(app)
                )
            )
        }
    }

    private var searchJob: kotlinx.coroutines.Job? = null

    /** Apps tab: name or package match, answered from the list already loaded. */
    fun filteredApps(query: String): List<InstalledApp> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return _state.value.picker.apps
        return _state.value.picker.apps.filter {
            it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
        }
    }

    /**
     * File search across the whole phone, not just the open folder.
     *
     * MediaStore is the index Android maintains for [MediaStore.Files] -- it covers photos, video,
     * audio, documents, archives and APKs in every shared directory, so the first pass is a single
     * indexed query. When `all files` access is granted and the index comes back thin, a bounded
     * walk of the real tree fills the gap for files the media index never scanned.
     */
    private fun searchFilesEverywhere(rawQuery: String): List<PickItem> {
        val query = rawQuery.trim()
        if (query.isEmpty()) return emptyList()
        val lower = query.lowercase()
        val resolver = getApplication<Application>().contentResolver
        val out = LinkedHashMap<String, PickItem>()
        val projections = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        runCatching {
            resolver.query(
                MediaStore.Files.getContentUri("external"),
                projections,
                "LOWER(${MediaStore.MediaColumns.DISPLAY_NAME}) LIKE ?",
                arrayOf("%$lower%"),
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val pathIndex = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                var count = 0
                while (cursor.moveToNext() && count < SEARCH_LIMIT) {
                    val name = cursor.getString(nameIndex) ?: continue
                    val size = cursor.getLong(sizeIndex)
                    if (size <= 0) continue
                    val uri = android.content.ContentUris.withAppendedId(
                        MediaStore.Files.getContentUri("external"), cursor.getLong(idIndex)
                    )
                    val folder = if (pathIndex >= 0) cursor.getString(pathIndex) ?: "" else ""
                    val item = PickItem(
                        key = uri.toString(),
                        name = name,
                        relativePath = folder.trimEnd('/') + "/" + name,
                        size = size,
                        mtime = cursor.getLong(dateIndex) * 1000,
                        mime = cursor.getString(mimeIndex) ?: mimeFor(name),
                        uri = uri.toString()
                    )
                    out[item.key] = item
                    count++
                }
            }
        }
        if (hasAllFilesAccess() && out.size < 40) {
            runCatching {
                val deadline = System.currentTimeMillis() + SEARCH_SCAN_BUDGET_MS
                val queue = ArrayDeque<File>()
                queue.add(storageRoot())
                var visited = 0
                while (queue.isNotEmpty() && visited < SEARCH_SCAN_LIMIT && System.currentTimeMillis() < deadline) {
                    val dir = queue.removeFirst()
                    val entries = runCatching { dir.listFiles() }.getOrNull() ?: continue
                    visited++
                    for (entry in entries) {
                        val name = entry.name ?: continue
                        if (name.startsWith(".")) continue
                        if (entry.isDirectory) {
                            if (name != "Android") queue.add(entry)
                            continue
                        }
                        if (entry.length() <= 0 || !entry.canRead()) continue
                        if (!name.lowercase().contains(lower)) continue
                        val path = entry.absolutePath
                        if (out.containsKey(path)) continue
                        out[path] = PickItem(
                            key = path,
                            name = name,
                            relativePath = path.removePrefix(storageRoot().absolutePath).trimStart('/'),
                            size = entry.length(),
                            mtime = entry.lastModified(),
                            mime = mimeFor(name),
                            uri = android.net.Uri.fromFile(entry).toString(),
                            filePath = path
                        )
                    }
                }
            }
        }
        // Exact name first, then "starts with", then the rest -- newest first inside each band.
        return out.values.sortedWith(
            compareBy<PickItem> {
                when {
                    it.name.equals(query, ignoreCase = true) -> 0
                    it.name.startsWith(query, ignoreCase = true) -> 1
                    else -> 2
                }
            }.thenByDescending { it.mtime }
        )
    }

    /**
     * Refreshes the cached facts Home shows. Called on resume and after a session, never from a
     * composable body.
     */
    fun refreshFacts() {
        viewModelScope.launch {
            val summary = withContext(Dispatchers.IO) { runCatching { deviceSummary() }.getOrDefault("") }
            val count = withContext(Dispatchers.IO) { runCatching { app.history.all().size }.getOrDefault(_state.value.historyCount) }
            _state.value = _state.value.copy(
                networkSummary = summary,
                historyCount = count,
                trustedCount = prefs.trustedDevices.size,
                freeSpace = Fmt.bytes(getApplication<Application>().filesDir.usableSpace)
            )
        }
    }

    // --- how we connect -------------------------------------------------------------------------

    /**
     * Joins a hotspot whose name and password the user read off the other phone's screen.
     *
     * The system consent sheet is unavoidable for `requestNetwork`, so this is explicitly "join",
     * not "connect": once the network is up the normal discovery runs on it and the other phone
     * appears in the list like any other peer.
     */
    fun joinHotspot(ssid: String, password: String) {
        val name = ssid.trim()
        if (name.isEmpty()) {
            notice("Type the hotspot name shown on the other phone")
            return
        }
        val app = getApplication<Application>()
        notice("Joining \"$name\" — confirm the Android prompt")
        com.loopjet.app.platform.net.HotspotController.of(app)
            .joinAp(name, password.trim().ifBlank { null }) { joined, message ->
                if (joined == null) {
                    notice("$message · you can still join it from Wi-Fi settings")
                    openWifiSettings()
                    return@joinAp
                }
                // Bind future sockets to the joined network and look for the other phone on it.
                com.loopjet.app.platform.PendingPeer.network = joined
                startDiscovery(advertise = false)
                notice("Joined \"$name\" — looking for the other phone")
            }
    }

    /** Wi-Fi settings, for the cases where the specifier join is refused by the OEM. */
    fun openWifiSettings() {
        val intent = android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { getApplication<Application>().startActivity(intent) }
            .onFailure { notice("Open Wi-Fi settings and join the hotspot shown on the other phone") }
    }

    /**
     * True when [address] is on the same /24 as one of this phone's own IPv4 addresses.
     *
     * This is the "verify they are really on the same network" check the Wi-Fi mode needs: two
     * phones can both be "on Wi-Fi" and still be on different networks (a guest SSID, a router with
     * client isolation, one phone on a hotspot), and then a LAN dial can only time out.
     */
    fun sameSubnet(address: String): Boolean =
        com.loopjet.app.platform.net.Subnet.sharesNetwork(localIpv4Addresses(), address)

    fun refreshPicker() {
        val picker = _state.value.picker
        when {
            picker.searchOpen -> runSearch(picker.searchScope, picker.query)
            picker.category == PickCategory.Folders && picker.browsePath != null -> openFolder(picker.browsePath)
            else -> loadCategory()
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
            // An empty media category is usually a permission answer, not an empty phone. Saying so
            // is the difference between "this app is broken" and "tap this to fix it".
            val mediaCategory = picker.category !in setOf(PickCategory.Folders, PickCategory.Apps)
            _state.value = _state.value.copy(
                picker = _state.value.picker.copy(
                    loading = false,
                    items = items,
                    error = null,
                    truncated = resultTruncated,
                    needsMediaAccess = items.isEmpty() && mediaCategory && !Perms.canListMedia(app),
                    mediaPartial = items.isEmpty() && mediaCategory && Perms.mediaAccessIsPartial(app)
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
        var selection: String?
        var args: Array<String>?
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
        // The name filter used to be applied in memory *after* the cursor had already been limited to
        // the first rows, so searching for an older file always came back empty. It belongs in the
        // query, where MediaStore can use the index and search the whole collection.
        if (query.isNotEmpty()) {
            val nameFilter = "LOWER(${MediaStore.MediaColumns.DISPLAY_NAME}) LIKE ?"
            selection = if (selection.isNullOrBlank()) nameFilter else "($selection) AND $nameFilter"
            args = (args ?: emptyArray()) + "%$query%"
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

    /** Apps are picked with the same tick as files and travel in the same tray. */
    fun toggleApp(app: InstalledApp) {
        val current = _state.value.picker.selectedApps.toMutableSet()
        if (!current.add(app.packageName)) current.remove(app.packageName)
        _state.value = _state.value.copy(picker = _state.value.picker.copy(selectedApps = current))
    }

    fun clearAll() {
        _state.value = _state.value.copy(
            picker = _state.value.picker.copy(selection = emptyMap(), selectedApps = emptySet())
        )
    }

    /**
     * "Send": everything picked in this screen goes out together -- files from any category, folders
     * browsed in the file tree, apps, and anything added from the system gallery.
     */
    fun commitSelection() {
        val picker = _state.value.picker
        val appItems = picker.apps
            .filter { it.packageName in picker.selectedApps }
            .flatMap { appRepo.toSendItems(it) }
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
        SendQueue.add(items + appItems)
        // Count what was actually queued: an app that vanished from the list (uninstalled while the
        // tray was open) must not inflate the number in the notice.
        val queuedApps = picker.selectedApps.count { name -> picker.apps.any { it.packageName == name } }
        val total = items.size + queuedApps
        _state.value = _state.value.copy(
            picker = picker.copy(selection = emptyMap(), selectedApps = emptySet()),
            screen = if (picker.webShareMode) Screen.WebShare else Screen.Nearby,
            notice = if (total > 0) "$total item(s) queued" else "Nothing selected"
        )
        if (picker.webShareMode) {
            WebShareQueue.set(SendQueue.snapshot)
            WebService.startShare(getApplication())
            _state.value = _state.value.copy(screen = Screen.WebShare)
        } else {
            startDiscovery(advertise = false)
        }
    }

    /**
     * Files chosen in the system gallery/photo picker. They are resolved to the same [PickItem] shape
     * as everything else and land in the same selection, so a gallery pick and a folder pick can be
     * sent together.
     */
    fun addPickedUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val picked = withContext(Dispatchers.IO) { uris.mapNotNull { resolvePicked(it) } }
            if (picked.isEmpty()) {
                notice("Those files could not be read")
                return@launch
            }
            val skipped = uris.size - picked.size
            val current = _state.value.picker.selection.toMutableMap()
            picked.forEach { current[it.key] = it }
            _state.value = _state.value.copy(
                picker = _state.value.picker.copy(selection = current),
                notice = if (skipped > 0) {
                    "${picked.size} added · $skipped could not be read"
                } else {
                    "${picked.size} item(s) added"
                }
            )
        }
    }

    private fun resolvePicked(uri: Uri): PickItem? {
        val resolver = getApplication<Application>().contentResolver
        val name = runCatching {
            resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment ?: return null
        var size = 0L
        runCatching {
            resolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
                ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) size = c.getLong(0) }
        }
        if (size <= 0) {
            // A size of 0 is not harmless: the transfer engine skips zero-length files, so the item
            // would be ticked, counted and then silently missing on the other phone. Cloud-backed
            // gallery entries often report no SIZE column, so ask the descriptor itself.
            size = runCatching {
                resolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    val declared = afd.length
                    if (declared > 0) declared else afd.parcelFileDescriptor.statSize.takeIf { it > 0 } ?: 0L
                }
            }.getOrNull() ?: 0L
        }
        // Still unknown (a stream with no length): refuse it here rather than queue a file that
        // cannot be sent, and say so, instead of reporting a count that the other phone never gets.
        if (size <= 0) return null
        val mime = resolver.getType(uri) ?: mimeFor(name)
        val folder = "Gallery/"
        return PickItem(
            key = uri.toString(),
            name = name,
            relativePath = folder + name,
            size = size,
            mtime = System.currentTimeMillis(),
            mime = mime,
            uri = uri.toString()
        )
    }

    fun clearQueue() {
        SendQueue.clear()
    }

    fun removeQueued(key: String) {
        SendQueue.remove(key)
    }

    fun addTextSnippet(text: String) {
        val file = File(getApplication<Application>().cacheDir, "FlowLink note ${System.currentTimeMillis()}.txt")
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

    /**
     * The system folder picker, used only by Settings to choose the *name* of the save folder. It no
     * longer gates the picker: browsing the phone's storage happens inside FlowLink.
     */
    fun openFolderTree(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        val label = runCatching {
            androidx.documentfile.provider.DocumentFile.fromTreeUri(getApplication(), uri)?.name
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast(':')?.ifBlank { null }
        if (label != null) {
            prefs.saveFolderName = com.loopjet.app.core.SafePath.sanitizeComponent(label) ?: prefs.saveFolderName
            _state.value = _state.value.copy(notice = "Received files go to Downloads/${prefs.saveFolderName}")
        }
    }

    fun goUpFolder() {
        val current = _state.value.picker.browsePath ?: return openFolderRoot()
        val root = storageRoot().absolutePath
        if (current == root) return
        val parent = File(current).parentFile?.absolutePath ?: root
        openFolder(parent)
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
        _state.value = _state.value.copy(speedMode = mode)
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
