package com.loopjet.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.loopjet.app.core.ProtocolLimits
import com.loopjet.app.platform.TransferHub
import com.loopjet.app.platform.net.ConnectCode
import com.loopjet.app.platform.net.NetworkTools
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Connection doctor.
 *
 * Every field here answers a question that otherwise becomes a guess in a bug report: which interface
 * do we have an address on, is the beacon port reachable, does this ROM allow a local-only hotspot,
 * was Wi-Fi Direct refused, and what did the last session actually use. The whole screen can be
 * copied in one tap, so "it does not connect" becomes something fixable.
 *
 * Deliberately read-only: it starts nothing, changes nothing, and never shows a passphrase.
 */
@Composable
fun ConnectionDoctorScreen(vm: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val tools = remember(context) { NetworkTools(context) }
    val session by TransferHub.session.collectAsStateWithLifecycle()
    var copied by remember { mutableStateOf(false) }

    val binding = remember(context, session.mode) { tools.sessionBinding() }
    val addresses = remember(context) { tools.allLocalAddresses() }
    val interfaces = remember(context) { tools.wifiLikeInterfaces().map { it.name } }
    val code = remember(session.receiveQr) { ConnectCode.decode(session.receiveQr) }

    val rows = buildList {
        add("Build" to "SDK ${Build.VERSION.SDK_INT} · ${Build.MANUFACTURER} ${Build.MODEL}")
        add("Wi-Fi" to if (tools.wifiEnabled()) "on" else "off")
        add("SSID" to (tools.ssid() ?: "not reported (Android hides it without location)"))
        add("Band" to tools.bandLabel())
        add("Link speed" to "${tools.linkSpeedMbps()} Mbps")
        add("Session address" to (binding?.address?.hostAddress ?: "none — no Wi-Fi address"))
        add("Interface" to (binding?.interfaceName ?: "none"))
        add("Local addresses" to addresses.joinToString(", ") { "${it.first}=${it.second.hostAddress}" }.ifBlank { "none" })
        add("Wi-Fi-like interfaces" to interfaces.joinToString(", ").ifBlank { "none" })
        add("Wi-Fi Direct" to if (tools.supportsWifiDirect()) "supported" else "not supported")
        add("5 GHz" to if (tools.supports5Ghz()) "yes" else "no")
        add("6 GHz" to if (tools.supports6Ghz()) "yes" else "no")
        add("Bluetooth LE" to if (tools.supportsBle()) "yes" else "no")
        add("Ports" to "control ${ProtocolLimits.DEFAULT_PORT}, beacon ${ProtocolLimits.BEACON_PORT}")
        add("Session" to when {
            session.mode == com.loopjet.app.platform.SessionMode.IDLE -> "idle"
            else -> "${session.mode} · ${session.state} · ${session.linkLabel.ifBlank { "?" }}"
        })
        add("Files in session" to if (session.files.isEmpty()) "none" else "${session.files.size}")
        if (session.message.isNotBlank()) add("Last message" to session.message)
        // What the other phone is told. Never the passphrase.
        add(
            "Code offers" to when {
                code == null -> if (session.receiveQr.isBlank()) "no code yet" else "v1 link (same network only)"
                code.network != null -> "network \"${code.network.ssid}\" (${code.network.kind}) + address ${code.network.host}"
                code.lanHost.isNotBlank() -> "same network ${code.lanHost}"
                else -> "nothing yet"
            }
        )
    }

    val report = buildString {
        appendLine("FlowLink connection report")
        rows.forEach { (k, v) -> appendLine("$k: $v") }
    }

    ScreenFrame(title = "Connection doctor", onBack = onBack) {
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp)) {
            item {
                SectionCard {
                    Text("What this phone can do", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Read-only. Nothing here changes your network.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
            items(rows) { (label, value) ->
                SectionCard(Modifier.padding(vertical = 3.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            value,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1.4f),
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            item {
                Spacer(Modifier.height(12.dp))
                com.loopjet.app.ui.GradientButton(
                    text = if (copied) "Copied" else "Copy report",
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(ClipData.newPlainText("FlowLink connection report", report))
                    copied = true
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text("Back")
                }
            }
        }
    }
}
