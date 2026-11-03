package com.flowlink.drop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowlink.drop.DropState
import com.flowlink.drop.Incoming
import com.flowlink.drop.core.Fmt

/**
 * The whole app, essentially: join this Wi-Fi, then open this page.
 *
 * The `Next` button is the flow, not a fallback. Detection only changes its label — no screen ever
 * rearranges itself under someone's finger, and no ROM can break the flow by failing to report a
 * client.
 */
@Composable
fun HostScreen(
    state: DropState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onRequestPermission: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var step by rememberSaveable { mutableStateOf(1) }

    // A hotspot restart invalidates the credentials: go back to step 1 rather than show a dead QR.
    LaunchedEffect(state.phase) {
        if (state.phase != "ready") step = 1
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.background
                    )
                )
            )
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(top = 18.dp, bottom = 28.dp)
        ) {
            Header(state)

            when (state.phase) {
                "idle" -> IdleCard(onStart)
                "blocked" -> BlockedCard(state, onOpenLocationSettings, onOpenWifiSettings, onRequestPermission)
                "starting" -> StartingCard(state)
                "failed" -> FailedCard(state, onRestart)
                else -> {
                    Spacer(Modifier.height(16.dp))
                    Stepper(step) { step = it }
                    Spacer(Modifier.height(14.dp))
                    AnimatedContent(
                        targetState = step,
                        transitionSpec = {
                            val forward = targetState > initialState
                            val direction = if (forward) 1 else -1
                            (slideInHorizontally(spring(stiffness = 420f)) { it / 4 * direction } +
                                fadeIn(tween(160)))
                                .togetherWith(
                                    slideOutHorizontally(tween(160)) { -it / 6 * direction } +
                                        fadeOut(tween(140))
                                )
                        },
                        label = "step"
                    ) { current ->
                        if (current == 1) JoinStep(state, clipboard) { step = 2 }
                        else OpenStep(state, clipboard) { step = 1 }
                    }

                    if (state.clients.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        JoinedStrip(state)
                    }

                    if (state.incoming.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        IncomingList(state.incoming)
                    }

                    if (state.message.isNotBlank() && state.phase == "ready") {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(18.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { clipboard.setText(AnnotatedString(state.instructions)) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Copy details") }
                        OutlinedButton(
                            onClick = onRestart,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Restart")
                        }
                    }
                    TextButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                        Text("Stop hosting", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(state: DropState) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.Brand),
            contentAlignment = Alignment.Center
        ) {
            Text("FL", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                state.phoneName,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                when (state.phase) {
                    "ready" -> if (state.clients.isEmpty()) "Waiting for someone to join"
                    else "${state.clients.size} device${if (state.clients.size == 1) "" else "s"} joined"
                    "starting" -> "Starting the hotspot…"
                    "failed" -> "Hotspot not available"
                    else -> "Not hosting"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        StatusDot(active = state.phase == "ready")
    }
}

@Composable
private fun StatusDot(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "dot")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_400), RepeatMode.Reverse),
        label = "pulse"
    )
    val colour = when {
        !active -> MaterialTheme.colorScheme.outline
        else -> Palette.Success
    }
    Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
        if (active) {
            Box(
                Modifier
                    .size(22.dp)
                    .alpha(pulse * 0.5f)
                    .clip(CircleShape)
                    .background(colour)
            )
        }
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(colour)
        )
    }
}

@Composable
private fun Stepper(step: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        StepPill("1 · Join", step == 1, Modifier.weight(1f)) { onSelect(1) }
        StepPill("2 · Open", step == 2, Modifier.weight(1f)) { onSelect(2) }
    }
}

@Composable
private fun StepPill(label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent)
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (active) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun JoinStep(
    state: DropState,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
    onNext: () -> Unit
) {
    Column {
        Text(
            "Join this Wi-Fi",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Scan with the other phone's camera — Android and iPhone both offer to connect.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))
        if (state.joinQr.isNotBlank()) {
            QrPanel(state.joinQr, Modifier.fillMaxWidth().aspectRatio(1f))
        }
        Spacer(Modifier.height(14.dp))
        ValueRow("Network", state.ssid.ifBlank { "—" }, clipboard, state.ssid)
        Spacer(Modifier.height(8.dp))
        ValueRow("Password", state.password.ifBlank { "open network" }, clipboard, state.password)
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text(
                if (state.clients.isEmpty()) "Next"
                else "Next · someone joined",
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

@Composable
private fun OpenStep(
    state: DropState,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
    onBack: () -> Unit
) {
    Column {
        Text("Open this page", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Scan it with the camera, or type the address in Chrome.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))
        if (state.url.isNotBlank()) {
            QrPanel(state.url, Modifier.fillMaxWidth().aspectRatio(1f))
        }
        Spacer(Modifier.height(14.dp))
        ValueRow("Address", state.url.ifBlank { "starting…" }, clipboard, state.url)
        if (state.band.isNotBlank() && state.band != "unknown band") {
            Spacer(Modifier.height(8.dp))
            Text(
                "Hotspot band: ${state.band}" +
                    if (state.band == "2.4 GHz") " · transfers are limited by the radio" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(16.dp))
        Surface(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Text(
                "The other device will say this network has no internet. That is correct — it exists " +
                    "only to reach this phone.",
                Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Back to the Wi-Fi code")
        }
    }
}

@Composable
private fun ValueRow(
    label: String,
    value: String,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
    copyValue: String = value
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        // Copy what is real, not the placeholder: copying "open network" into a password field is
        // a small betrayal of the user's time.
        if (copyValue.isNotBlank()) {
            TextButton(onClick = { clipboard.setText(AnnotatedString(copyValue)) }) {
                Text("Copy", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun JoinedStrip(state: DropState) {
    val first = state.clients.first()
    Surface(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, Palette.Success.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .background(Palette.Success.copy(alpha = 0.10f))
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Palette.Success)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (first.agent.isBlank()) "A device joined"
                    else browserName(first.agent),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    first.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun IncomingList(items: List<Incoming>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Arriving",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        items.forEach { item ->
            Surface(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            item.name,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            when (item.state) {
                                "done" -> "saved"
                                "failed" -> "failed"
                                else -> Fmt.percent(item.bytes, item.total).let { if (it > 0) "$it%" else "" }
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = when (item.state) {
                                "failed" -> MaterialTheme.colorScheme.error
                                "done" -> Palette.Success
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${Fmt.bytes(item.bytes)} of ${Fmt.bytes(item.total)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = {
                            if (item.total <= 0) 0f
                            else (item.bytes.toFloat() / item.total.toFloat()).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(99.dp))
                    )
                }
            }
        }
    }
}

@Composable
private fun IdleCard(onStart: () -> Unit) {
    Spacer(Modifier.height(40.dp))
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Not hosting", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            "Start the hotspot and this phone can receive files from any browser.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(18.dp))
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Start hosting")
        }
    }
}

/**
 * A required setting is off. Android will not create a hotspot without location services (and the
 * permission) or with the radio off — on several ROMs the refusal arrives as an exception inside the
 * platform itself, so the app refuses first and explains, with the switch one tap away.
 */
@Composable
private fun BlockedCard(
    state: DropState,
    onOpenLocationSettings: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onRequestPermission: () -> Unit
) {
    Spacer(Modifier.height(28.dp))
    val title = when (state.blocker) {
        "location-permission" -> "Allow location"
        "location-off" -> "Turn location on"
        "wifi-off" -> "Turn Wi-Fi on"
        else -> "One setting is missing"
    }
    Surface(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(state.message, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "This is an Android requirement for creating a hotspot, not a FlowLink choice. " +
                    "Nothing about your location is read or stored.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    when (state.blocker) {
                        "location-permission" -> onRequestPermission()
                        "wifi-off" -> onOpenWifiSettings()
                        else -> onOpenLocationSettings()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) { Text("Open the setting") }
            Spacer(Modifier.height(6.dp))
            Text(
                "Then come back — hosting starts by itself.",
                Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun StartingCard(state: DropState) {
    Spacer(Modifier.height(40.dp))
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        LinearProgressIndicator(Modifier.fillMaxWidth(0.6f).height(6.dp).clip(RoundedCornerShape(99.dp)))
        Spacer(Modifier.height(16.dp))
        Text(
            state.message.ifBlank { "Starting the hotspot…" },
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "This takes a second or two.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FailedCard(state: DropState, onRetry: () -> Unit) {
    Spacer(Modifier.height(32.dp))
    Surface(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.10f))
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                "The hotspot could not start",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(8.dp))
            Text(
                state.error.ifBlank { "Try again, or turn Wi-Fi off and on." },
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "If another hotspot or Wi-Fi Direct group is already running on this phone, turn it off first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
        }
    }
}

private fun browserName(agent: String): String = when {
    agent.contains("Edg", true) -> "Edge"
    agent.contains("Chrome", true) -> "Chrome"
    agent.contains("Firefox", true) -> "Firefox"
    agent.contains("Safari", true) -> "Safari"
    agent.contains("curl", true) -> "curl"
    agent.isBlank() -> "A device"
    else -> "A browser"
}
