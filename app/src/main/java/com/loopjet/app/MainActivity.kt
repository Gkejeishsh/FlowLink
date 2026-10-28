package com.loopjet.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loopjet.app.core.ProtocolLimits
import com.loopjet.app.platform.IncomingOfferUi
import com.loopjet.app.platform.LoopJetApp
import com.loopjet.app.platform.Perms
import com.loopjet.app.platform.TransferHub
import com.loopjet.app.ui.AboutScreen
import com.loopjet.app.ui.AppState
import com.loopjet.app.ui.AppViewModel
import com.loopjet.app.ui.HistoryScreen
import com.loopjet.app.ui.HomeScreen
import com.loopjet.app.ui.LocalMotion
import com.loopjet.app.ui.LoopJetTheme
import com.loopjet.app.ui.NearbyScreen
import com.loopjet.app.ui.OfferDialog
import com.loopjet.app.ui.OnboardingScreen
import com.loopjet.app.ui.PickerScreen
import com.loopjet.app.ui.ReceiveScreen
import com.loopjet.app.ui.Screen
import com.loopjet.app.ui.SectionCard
import com.loopjet.app.ui.SettingsScreen
import com.loopjet.app.ui.TransferScreen
import com.loopjet.app.ui.WebScreen

class MainActivity : ComponentActivity() {

    private var incomingUris by mutableStateOf<List<Uri>>(emptyList())
    private var pendingReceive by mutableStateOf(false)
    private var vmRef: AppViewModel? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val denied = granted.filterValues { !it }
        if (denied.isNotEmpty()) {
            vmRef?.notice("Some permissions were refused — falling back to the paths that still work")
        }
        if (pendingReceive) {
            pendingReceive = false
            vmRef?.startDiscovery(advertise = false)
        }
    }

    private val folderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            vmRef?.openFolderTree(uri)
        }
    }

    private val settingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        val activity = this
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        incomingUris = extractUris(intent)
        setContent {
            val vm: AppViewModel = viewModel()
            vmRef = vm
            val state by vm.state.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val dark = when (state.themeMode) {
                "light" -> false
                "dark" -> true
                else -> systemDark
            }
            LaunchedEffect(dark) { vm.setDark(dark) }
            LaunchedEffect(incomingUris) {
                if (incomingUris.isNotEmpty()) {
                    vm.addSharedUris(incomingUris)
                    incomingUris = emptyList()
                }
            }
            LoopJetTheme(dark = dark) {
                val motion = LocalMotion.current
                val snackbar = remember { SnackbarHostState() }
                LaunchedEffect(state.notice) {
                    val message = state.notice
                    if (message != null) {
                        snackbar.showSnackbar(message)
                        vm.notice(null)
                    }
                }
                Box(Modifier) {
                    Crossfade(
                        targetState = state.screen,
                        animationSpec = tween(if (motion.enabled) 240 else 0),
                        label = "screen"
                    ) { screen ->
                        when (screen) {
                            Screen.Onboarding -> OnboardingScreen(
                                vm = vm,
                                state = state,
                                onRequestPermissions = { requestPermissions(receive = true) },
                                onPickSaveFolder = { folderLauncher.launch(null) },
                                onBatterySettings = { runCatching { settingsLauncher.launch(Perms.batteryIntent(activity)) } }
                            )
                            Screen.Home -> HomeScreen(
                                vm, state,
                                onSend = { vm.openPicker() },
                                onReceive = {
                                    vm.go(Screen.Receive)
                                    requestPermissions(receive = true)
                                }
                            )
                            Screen.Picker -> PickerScreen(vm, state, onPickFolder = { folderLauncher.launch(null) })
                            Screen.Nearby -> NearbyScreen(vm, state)
                            Screen.Receive -> ReceiveScreen(vm, state)
                            Screen.Transfer -> TransferScreen(vm, state)
                            Screen.Invite -> WebScreen(vm, state, invite = true)
                            Screen.WebShare -> WebScreen(vm, state, invite = false)
                            Screen.History -> HistoryScreen(vm, state)
                            Screen.Settings -> SettingsScreen(vm, state) {
                                PermissionsCard(
                                    onGrant = { requestPermissions(receive = false) },
                                    onPickFolder = { folderLauncher.launch(null) },
                                    onBattery = { runCatching { settingsLauncher.launch(Perms.batteryIntent(activity)) } },
                                    onAllFiles = { runCatching { settingsLauncher.launch(Perms.allFilesIntent(activity)) } },
                                    onInstallApps = { runCatching { settingsLauncher.launch(Perms.installIntent(activity)) } }
                                )
                            }
                            Screen.About -> AboutScreen(vm)
                        }
                    }
                    SnackbarHost(
                        snackbar,
                        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                    ) { data -> Snackbar(snackbarData = data) }
                    Offers()
                }
                BackHandler(enabled = state.screen != Screen.Home && state.screen != Screen.Onboarding) {
                    vm.back()
                }
            }
        }
    }

    @Composable
    private fun Offers() {
        val session by TransferHub.session.collectAsStateWithLifecycle()
        val offer: IncomingOfferUi? = session.offer
        if (offer != null) {
            OfferDialog(offer) { accepted, trust -> TransferHub.answerOffer(accepted, trust) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val uris = extractUris(intent)
        if (uris.isNotEmpty()) incomingUris = uris
    }

    override fun onResume() {
        super.onResume()
        vmRef?.let { vm ->
            // Permissions may have been changed in system settings while we were backgrounded.
            if (Perms.missing(this, Perms.runtimeRequirements(this, false)).isEmpty() &&
                vm.state.value.screen == Screen.Receive
            ) {
                vm.startDiscovery(advertise = false)
            }
        }
    }

    private fun requestPermissions(receive: Boolean) {
        pendingReceive = receive
        val missing = Perms.missing(this, Perms.runtimeRequirements(this, receive))
        if (missing.isEmpty()) {
            vmRef?.notice(null)
            return
        }
        val array = missing.map { it.permission }.toTypedArray()
        if (Build.VERSION.SDK_INT >= 30 && array.any { it == "android.permission.ACCESS_LOCAL_NETWORK" }) {
            // Never bundled silently with the rest: Android 17 shows a dedicated local-network prompt.
        }
        permissionLauncher.launch(array)
    }

    private fun extractUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        return when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            )
            Intent.ACTION_SEND_MULTIPLE -> @Suppress("DEPRECATION")
            (intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.filterNotNull() ?: emptyList())
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
    }
}

/** Permission card used inside Settings, with a working denial path for each capability. */
@Composable
private fun PermissionsCard(
    onGrant: () -> Unit,
    onPickFolder: () -> Unit,
    onBattery: () -> Unit,
    onAllFiles: () -> Unit,
    onInstallApps: () -> Unit
) {
    val context = LocalContext.current
    val requirements = Perms.runtimeRequirements(context, false)
    val missing = Perms.missing(context, requirements)
    Column {
        SectionCard {
            Text("Permissions", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (missing.isEmpty()) {
                Text(
                    "Every capability LoopJet needs is granted.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                missing.forEach { requirement ->
                    Text(
                        "• ${requirement.title} — ${requirement.why}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onGrant) { Text("Grant what is missing") }
            }
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onPickFolder) { Text("Choose an exact save folder (SAF)") }
            TextButton(onClick = onBattery) { Text("Battery optimisation settings") }
            if (Build.VERSION.SDK_INT >= 30 && !Perms.hasAllFiles(context)) {
                TextButton(onClick = onAllFiles) { Text("All-files access for full storage browsing (optional)") }
            }
            TextButton(onClick = onInstallApps) { Text("Allow installing received apps") }
            Text(
                "Ports: control ${ProtocolLimits.DEFAULT_PORT}, discovery ${ProtocolLimits.BEACON_PORT}. Nothing leaves the local link.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
