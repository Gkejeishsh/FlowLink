package com.flowlink.drop

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.flowlink.drop.platform.DropService
import com.flowlink.drop.ui.DropTheme
import com.flowlink.drop.ui.HostScreen
import com.flowlink.drop.ui.ReceivedScreen
import com.flowlink.drop.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        // Whatever the answer, the service starts: it can explain a missing grant far better than a
        // silent screen, and the hotspot error message names the permission by name.
        // Restart, not start-then-restart: a grant that arrived after the first attempt has to be
        // picked up by a fresh attempt, and starting twice bound two servers.
        if (granted.values.any { it }) DropService.restart(this) else DropService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DropTheme {
                val prefs = (application as DropApp).prefs
                AppRoot(
                    prefs = prefs,
                    onStart = {
                        DropService.start(this)
                        requestNeeded()
                    },
                    onStop = { DropService.stop(this) },
                    onRestart = { DropService.restart(this) },
                    onOpenLocationSettings = {
                        startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    },
                    onOpenWifiSettings = { openWifiSettings() },
                    onRequestPermission = { requestNeeded(force = true) },
                    onClearCrash = ::clearCrash
                )
            }
        }
        requestNeeded()
    }

    override fun onStart() {
        super.onStart()
        DropService.start(this)
    }

    override fun onResume() {
        super.onResume()
        // The user has just come back from Settings. If the thing that was blocking hosting is fixed,
        // start it without making them press anything.
        val state = DropHub.state.value
        if (state.phase == "blocked" || state.phase == "idle") DropService.restart(this)
    }

    /**
     * The system Wi-Fi panel is a toggle attached to the notification shade (API 29+); older
     * versions only have the settings screen. Either way the user lands on something they can flip,
     * rather than being told "turn Wi-Fi on" with no way to do it.
     */
    private fun openWifiSettings() {
        val intent = if (Build.VERSION.SDK_INT >= 29) {
            Intent(android.provider.Settings.Panel.ACTION_WIFI)
        } else {
            Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
        }
        runCatching { startActivity(intent) }
            .onFailure { runCatching { startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)) } }
    }

    private fun requestNeeded(force: Boolean = false) {
        val needed = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            if (!granted(Manifest.permission.NEARBY_WIFI_DEVICES)) {
                needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            if (!granted(Manifest.permission.POST_NOTIFICATIONS)) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (needed.isNotEmpty()) {
            permissions.launch(needed.toTypedArray())
        } else if (force) {
            // Nothing left to ask for: the setting lives in the system UI, so open it.
            startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
    }

    private fun clearCrash() {
        runCatching { java.io.File(filesDir, "last-crash.txt").delete() }
        DropHub.update { it.copy(lastCrash = "") }
    }

    private fun granted(permission: String): Boolean =
        checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
}

@Composable
private fun AppRoot(
    prefs: Prefs,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onRequestPermission: () -> Unit,
    onClearCrash: () -> Unit
) {
    val state by DropHub.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(0) }
    var refresh by rememberSaveable { mutableStateOf(0) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text("Host") }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.List, contentDescription = null) },
                    label = { Text("Received") }
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("Settings") }
                )
            }
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            when (tab) {
                0 -> HostScreen(
                    state = state,
                    onStart = onStart,
                    onStop = onStop,
                    onRestart = onRestart,
                    onOpenLocationSettings = onOpenLocationSettings,
                    onOpenWifiSettings = onOpenWifiSettings,
                    onRequestPermission = onRequestPermission
                )
                1 -> ReceivedScreen(state = state, onClear = {
                    prefs.clearHistory()
                    DropHub.update { it.copy(history = emptyList()) }
                })
                else -> {
                    // `refresh` is a cheap way to re-read the settings screen after a change writes
                    // to preferences; the screen keeps its own local state.
                    key(refresh) {
                        SettingsScreen(
                            prefs = prefs,
                            state = state,
                            onChanged = {
                                refresh++
                                // The name is shown on the guest's page, so the hub must know now,
                                // not at the next service start.
                                DropHub.update { it.copy(phoneName = prefs.phoneName) }
                            },
                            onClearCrash = onClearCrash
                        )
                    }
                }
            }
        }
    }
}
