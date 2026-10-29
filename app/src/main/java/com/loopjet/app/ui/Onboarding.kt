package com.loopjet.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.loopjet.app.platform.FlowLinkApp
import com.loopjet.app.platform.Perms

/**
 * Onboarding is a guided permission walk, not a dump. Each step names one capability, says why it
 * is needed and what still works if it is refused; nothing is requested until the user taps.
 */
@Composable
fun OnboardingScreen(
    vm: AppViewModel,
    state: AppState,
    onRequestPermissions: () -> Unit,
    onPickSaveFolder: () -> Unit,
    onBatterySettings: () -> Unit
) {
    val context = LocalContext.current
    val requirements = remember { Perms.runtimeRequirements(context, forReceive = true) }
    val missing = remember(state.screen) { Perms.missing(context, requirements) }
    var step by remember { mutableIntStateOf(0) }
    val steps = listOf("Welcome", "Find phones", "Save files", "Battery")
    val prefs = FlowLinkApp.instance.prefs
    LaunchedEffect(step) {
        if (step == steps.size) {
            prefs.onboardingDone = true
            vm.go(Screen.Home)
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            steps.indices.forEach { index ->
                Box(
                    Modifier
                        .height(4.dp)
                        .weight(1f)
                        .clip(CircleShape)
                        .background(
                            if (index <= step) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                )
            }
        }
        Spacer(Modifier.height(28.dp))
        when (step) {
            0 -> {
                FlowLinkMark(96)
                Spacer(Modifier.height(18.dp))
                Text("FlowLink", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Phone-to-phone transfers at the speed of your Wi-Fi. No account, no cloud, no ads — " +
                        "files never leave the room.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(20.dp))
                Feature("Direct 5 GHz link", "Wi-Fi Direct, your Wi-Fi, or a hotspot — in that order, ranked by a real probe.")
                Feature("Encrypted", "Every byte is sealed with AES-256-GCM and a per-install key.")
                Feature("Resumable", "Drop the link and the chunk map picks up where it stopped.")
                Feature("Works offline", "No internet permission is used for transfers, ever.")
            }
            1 -> {
                StepTitle("Let phones find each other")
                Text(
                    "FlowLink uses nearby Wi-Fi to create the direct link, plus Bluetooth to advertise when there is no network at all. " +
                        "Android asks once per capability.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(18.dp))
                if (missing.isEmpty()) {
                    Granted()
                } else {
                    missing.forEach { requirement ->
                        StepCard(requirement.title, requirement.why, false)
                    }
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton("Grant nearby access") { onRequestPermissions() }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Refused? FlowLink still works on an existing Wi-Fi network and through Web Share — " +
                            "Wi-Fi Direct and the hotspot stay unavailable.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            2 -> {
                StepTitle("Send and save")
                // No "pick a folder" step: it asked for a folder before the user had seen a single
                // file, and the picker now browses the whole phone's storage by itself.
                Text(
                    "Nothing to set up. Received files land in Downloads/${vm.saveFolder()} and appear in " +
                        "the gallery and every file manager immediately.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(18.dp))
                StepCard("Where received files go", "Downloads/${vm.saveFolder()} — no permission needed to write there.", true)
                val allFiles = com.loopjet.app.platform.Perms.hasAllFiles(context)
                StepCard(
                    "Browsing every folder",
                    if (allFiles) "Granted — Send › Folders shows the whole file manager."
                    else "Optional. Photos, videos, music and apps already work without it; this only adds " +
                        "the full file tree to Send › Folders.",
                    allFiles
                )
                Spacer(Modifier.height(12.dp))
                PrimaryButton(if (allFiles) "All files already allowed" else "Allow all files (optional)") { onPickSaveFolder() }
            }
            else -> {
                StepTitle("Keep transfers alive")
                Text(
                    "Some manufacturers stop background apps mid-transfer. Exempting FlowLink from battery " +
                        "optimisation is the single biggest reliability win on those phones.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                StepCard(
                    "Battery exemption",
                    if (Perms.isIgnoringBatteryOptimizations(context)) "Already exempted — transfers survive a locked screen."
                    else "Not exempted yet. Transfers still run in the foreground with a live notification.",
                    Perms.isIgnoringBatteryOptimizations(context)
                )
                Spacer(Modifier.height(12.dp))
                PrimaryButton("Open battery settings") { onBatterySettings() }
                Spacer(Modifier.height(10.dp))
                Text(
                    "OEM guides live in Settings › Battery killers. Nothing is requested silently and you can skip every step.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(28.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = { if (step > 0) step-- else step = steps.size },
                modifier = Modifier.defaultMinSize(minWidth = 72.dp)
            ) {
                Text(if (step > 0) "Back" else "Skip")
            }
            // weight, not a fixed width: the two controls now share the row at any screen width or
            // font scale instead of pushing each other off the edge.
            PrimaryButton(
                if (step == steps.size - 1) "Start" else "Next",
                Modifier.weight(1f)
            ) {
                step++
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "No analytics, no account, no ads. FlowLink has no internet transfer path.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StepTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Feature(title: String, body: String) {
    Row(Modifier.padding(vertical = 7.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StepCard(title: String, body: String, done: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                if (done) Icons.Filled.Check else Icons.Filled.Warning,
                contentDescription = null,
                tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(top = 2.dp)
            )
            Spacer(Modifier.width(12.dp))
            // weight(1f) lets the explanatory text wrap inside the card instead of running past the
            // rounded edge, which is what made the permission cards look misaligned.
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun Granted() {
    StepCard("Ready", "Nearby Wi-Fi, notifications and Bluetooth access are granted.", true)
}

@Suppress("ModifierParameter")
@Composable
private fun PrimaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    GradientButton(text = text, modifier = modifier, onClick = onClick)
}
