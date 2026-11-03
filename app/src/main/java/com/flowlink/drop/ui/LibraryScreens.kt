package com.flowlink.drop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.flowlink.drop.platform.MediaOpen
import com.flowlink.drop.DropState
import com.flowlink.drop.Prefs
import com.flowlink.drop.ReceivedItem
import com.flowlink.drop.core.Fmt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ReceivedScreen(state: DropState, onClear: () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (state.history.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Nothing received yet", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Files sent from a browser land here, and in Photos or Downloads where they belong.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state.url.isNotBlank()) {
                    Spacer(Modifier.height(18.dp))
                    Text(
                        state.url,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Send the other person here while the hotspot is running.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${state.history.size} file${if (state.history.size == 1) "" else "s"}",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleLarge
                        )
                        TextButton(onClick = onClear) { Text("Clear list") }
                    }
                }
                items(state.history.asReversed()) { item -> ReceivedRow(item) }
            }
        }
    }
}

@Composable
private fun ReceivedRow(item: ReceivedItem) {
    val format = remember { SimpleDateFormat("d MMM · HH:mm", Locale.getDefault()) }
    val context = LocalContext.current
    Surface(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable {
                // A list of things you cannot open is a list of regrets.
                if (!MediaOpen.open(context, item)) {
                    Toast.makeText(context, "Saved to ${'$'}{item.where}", Toast.LENGTH_SHORT).show()
                }
            }
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${Fmt.bytes(item.bytes)} · ${item.where} · ${format.format(Date(item.time))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun SettingsScreen(prefs: Prefs, state: DropState, onChanged: () -> Unit) {
    var name by remember { mutableStateOf(prefs.phoneName) }
    var keepAwake by remember { mutableStateOf(prefs.keepAwake) }
    var fixed by remember { mutableStateOf(prefs.useFixedGroup) }
    var fixedName by remember { mutableStateOf(prefs.fixedName) }
    var fixedPass by remember { mutableStateOf(prefs.fixedPass) }

    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Text("This phone", style = MaterialTheme.typography.titleLarge) }
        item {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    prefs.phoneName = it
                    onChanged()
                },
                label = { Text("Name the other person sees") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth()
            )
        }

        item { Spacer(Modifier.height(6.dp)); Text("Hotspot", style = MaterialTheme.typography.titleLarge) }
        item {
            ToggleRow(
                title = "Keep a fixed network name",
                subtitle = "Wi-Fi Direct group with a name and password you choose. " +
                    "Off: Android invents new ones every session.",
                checked = fixed
            ) { value ->
                fixed = value
                prefs.useFixedGroup = value
                onChanged()
            }
        }
        if (fixed) {
            item {
                OutlinedTextField(
                    value = fixedName,
                    onValueChange = {
                        fixedName = it
                        prefs.fixedName = it
                        onChanged()
                    },
                    label = { Text("Network name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = fixedPass,
                    onValueChange = {
                        fixedPass = it
                        prefs.fixedPass = it
                        onChanged()
                    },
                    label = { Text("Password (8+ characters)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        item { Spacer(Modifier.height(6.dp)); Text("While hosting", style = MaterialTheme.typography.titleLarge) }
        item {
            ToggleRow(
                title = "Keep this phone awake",
                subtitle = "A transfer stops if the phone sleeps mid-file.",
                checked = keepAwake
            ) { value ->
                keepAwake = value
                prefs.keepAwake = value
                onChanged()
            }
        }

        item { Spacer(Modifier.height(6.dp)); Text("Session", style = MaterialTheme.typography.titleLarge) }
        item {
            InfoRow(
                "Address",
                state.url.ifBlank { "not hosting" }
            )
        }
        item { InfoRow("Requests served", state.requests.toString()) }
        item {
            InfoRow(
                "Reachability self-test",
                when (state.reachable) {
                    true -> "this phone can reach its own page ✓"
                    false -> "the page did not answer on its own address"
                    null -> "not run yet"
                }
            )
        }
        item {
            InfoRow(
                "Hotspot",
                if (state.ssid.isBlank()) "—" else "${state.ssid} · ${state.band} · ${state.route} route"
            )
        }
        item {
            Text(
                "FlowLink Drop ${state.version} · files travel one way, into this phone. " +
                    "Nothing is uploaded anywhere else, and the app makes no other network requests.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Surface(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            label,
            Modifier.width(150.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
