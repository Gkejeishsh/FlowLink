package com.loopjet.app.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.loopjet.app.core.FileResult
import com.loopjet.app.core.Fmt
import com.loopjet.app.core.TransferState
import com.loopjet.app.platform.IncomingOfferUi
import com.loopjet.app.platform.SessionMode
import com.loopjet.app.platform.SessionUi
import com.loopjet.app.platform.apps.InstalledApp
import com.loopjet.app.platform.net.DiscoveredPeer
import com.loopjet.app.platform.net.PeerKind
import kotlinx.coroutines.flow.StateFlow

// --- shared scaffolding ---------------------------------------------------------------------------

@Composable
private fun ScreenFrame(
    title: String? = null,
    onBack: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        if (title != null) {
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground)
                    }
                } else Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.headlineMedium)
            }
        }
        content()
    }
}

@Composable
private fun PrimaryAction(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val (pressModifier, interaction) = rememberPressScale()
    Box(
        modifier
            .then(pressModifier)
            .height(58.dp)
            .clip(MaterialTheme.shapes.large)
            .background(if (enabled) BrandGradient else Brush.linearGradient(listOf(Color(0xFF9AA4BD), Color(0xFF9AA4BD))))
            .clickable(enabled = enabled, interactionSource = interaction, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}

// --- home -----------------------------------------------------------------------------------------

@Composable
fun HomeScreen(vm: AppViewModel, state: AppState, onSend: () -> Unit, onReceive: () -> Unit) {
    val session by vm.session.collectAsStateSafe()
    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding(),
        contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 28.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LoopJetMark(size = 52)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("LoopJet", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        state.deviceName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = { vm.toggleTheme() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Theme", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { vm.go(Screen.Settings) }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(18.dp))
        }
        if (session.active && session.mode != SessionMode.IDLE) {
            item {
                ActiveSessionBanner(session) { vm.go(Screen.Transfer) }
                Spacer(Modifier.height(14.dp))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                BigAction(
                    title = "Send",
                    subtitle = "Pick files, then a phone",
                    icon = Icons.Filled.Send,
                    modifier = Modifier.weight(1f),
                    onClick = onSend
                )
                BigAction(
                    title = "Receive",
                    subtitle = "Show a code, get files",
                    icon = Icons.Filled.PlayArrow,
                    modifier = Modifier.weight(1f),
                    onClick = onReceive,
                    accent = true
                )
            }
            Spacer(Modifier.height(18.dp))
        }
        item {
            Text(
                "MORE WAYS",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallAction("Invite", "Install on a phone", Icons.Filled.Share, Modifier.weight(1f)) { vm.startInvite(false) }
                SmallAction("Web Share", "PC browser", Icons.Filled.List, Modifier.weight(1f)) { vm.startWebShare() }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallAction("History", "${vm.history().size} sessions", Icons.Filled.Home, Modifier.weight(1f)) { vm.go(Screen.History) }
                SmallAction("Settings", "Speed, trust, theme", Icons.Filled.Settings, Modifier.weight(1f)) { vm.go(Screen.Settings) }
            }
            Spacer(Modifier.height(18.dp))
        }
        item {
            SectionCard {
                Text("This phone", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                StatRow("Network", vm.deviceSummary())
                Spacer(Modifier.height(6.dp))
                StatRow("Free space", state.freeSpace)
                Spacer(Modifier.height(6.dp))
                StatRow("Trusted devices", state.trustedCount.toString())
                Spacer(Modifier.height(12.dp))
                Text(
                    "No account, no ads, no internet. Everything stays on your local link.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun BigAction(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    onClick: () -> Unit
) {
    val (pressModifier, interaction) = rememberPressScale()
    Column(
        modifier
            .then(pressModifier)
            .height(168.dp)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(
                if (accent) Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary))
                else Brush.linearGradient(listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surface))
            )
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraLarge)
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(18.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Box(
            Modifier.size(46.dp).clip(CircleShape)
                .background(if (accent) Color.White.copy(alpha = 0.22f) else MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = if (accent) Color.White else MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Column {
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                color = if (accent) Color.White else MaterialTheme.colorScheme.onSurface
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = if (accent) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SmallAction(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val (pressModifier, interaction) = rememberPressScale()
    Row(
        modifier
            .then(pressModifier)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ActiveSessionBanner(session: SessionUi, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadarPulse(active = session.state == TransferState.TRANSFERRING, Modifier.size(34.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when (session.mode) {
                    SessionMode.SEND -> "Sending to ${session.peerName}"
                    SessionMode.RECEIVE -> "Receiving from ${session.peerName}"
                    else -> "Session"
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                "${Fmt.rate(session.instantBps.toDouble())} · ${Fmt.bytes(session.doneBytes)} of ${Fmt.bytes(session.totalBytes)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
            )
        }
        Text("Open", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

// --- picker ---------------------------------------------------------------------------------------

@Composable
fun PickerScreen(vm: AppViewModel, state: AppState, onPickFolder: () -> Unit) {
    val picker = state.picker
    val trayHeight = if (picker.selection.isNotEmpty()) 92.dp else 0.dp
    ScreenFrame(title = if (picker.webShareMode) "Web Share files" else "Send", onBack = { vm.back() }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = picker.query,
                onValueChange = { vm.setQuery(it) },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search name") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                shape = MaterialTheme.shapes.large
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = {
                val next = when (picker.sort) {
                    SortOrder.Newest -> SortOrder.Largest
                    SortOrder.Largest -> SortOrder.Name
                    SortOrder.Name -> SortOrder.Newest
                }
                vm.setSort(next)
            }) {
                Icon(Icons.Filled.List, contentDescription = "Sort: ${picker.sort.label}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            picker.sort.label,
            Modifier.padding(start = 20.dp, top = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(PickCategory.entries) { category ->
                val selected = category == picker.category
                Text(
                    category.label,
                    Modifier
                        .clip(CircleShape)
                        .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { if (category == PickCategory.Folders) onPickFolder() else vm.setCategory(category) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
        if (picker.category == PickCategory.Folders) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { vm.goUpFolder() }) { Text("Up") }
                Text(
                    picker.folderStack.lastOrNull()?.second ?: "No folder opened",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onPickFolder) { Text("Choose folder") }
            }
        }
        Box(Modifier.weight(1f)) {
            when {
                picker.loading -> RadarPulse(active = true, modifier = Modifier.size(120.dp).align(Alignment.Center))
                picker.category == PickCategory.Apps -> AppList(
                    apps = picker.apps,
                    onAdd = { vm.addApp(it) }
                )
                picker.items.isEmpty() -> EmptyState(
                    title = "Nothing here yet",
                    body = if (picker.category == PickCategory.Folders) "Choose a folder to browse this phone's storage."
                    else "No files matched. Try another category or search."
                )
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    itemsIndexed(picker.items, key = { _, item -> item.key }) { index, item ->
                        StaggeredEntrance(index) {
                            PickRow(item, picker.selection.containsKey(item.key)) {
                                if (item.isFolder) vm.browseFolder(item.folderUri ?: "", item.name) else vm.toggleSelection(item)
                            }
                        }
                    }
                }
            }
        }
        AnimatedVisibility(visible = picker.selection.isNotEmpty(), enter = slideInVertically { it }, exit = fadeOut()) {
            Row(
                Modifier.fillMaxWidth().height(trayHeight)
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${picker.selection.size} selected", style = MaterialTheme.typography.titleMedium)
                    Text(
                        Fmt.bytes(picker.selectedBytes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { vm.clearSelection() }) { Text("Clear") }
                Spacer(Modifier.width(8.dp))
                PrimaryAction(
                    text = if (picker.webShareMode) "Share" else "Next",
                    modifier = Modifier.width(132.dp)
                ) { vm.commitSelection() }
            }
        }
    }
}

@Composable
private fun StaggeredEntrance(index: Int, content: @Composable () -> Unit) {
    val motion = LocalMotion.current
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(index) { visible = true }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = if (motion.enabled) tween(220, delayMillis = (index % 12) * 22) else tween(0),
        label = "alpha"
    )
    Box(Modifier.fillMaxWidth().graphicsLayer { this.alpha = alpha }) { content() }
}

@Composable
private fun PickRow(item: PickItem, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable { onClick() }
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else Color.Transparent)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (item.isFolder) "📁" else extensionBadge(item.name),
                style = MaterialTheme.typography.labelMedium
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (item.isFolder) "Folder" else "${Fmt.bytes(item.size)} · ${Fmt.date(item.mtime)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box(
            Modifier.size(26.dp).clip(CircleShape)
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                .border(1.5.dp, if (selected) Color.Transparent else MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
        }
    }
}

private fun extensionBadge(name: String): String {
    val ext = name.substringAfterLast('.', "").uppercase()
    return when (ext) {
        "JPG", "JPEG", "PNG", "WEBP", "HEIC", "GIF" -> "IMG"
        "MP4", "MKV", "MOV", "WEBM", "3GP" -> "VID"
        "MP3", "FLAC", "WAV", "M4A", "OGG" -> "AUD"
        "PDF" -> "PDF"
        "ZIP", "RAR", "7Z", "TAR" -> "ZIP"
        "APK", "APKS", "XAPK" -> "APK"
        "DOC", "DOCX", "TXT", "MD", "CSV" -> "DOC"
        else -> if (ext.isEmpty()) "FILE" else ext.take(3)
    }
}

@Composable
private fun AppList(apps: List<InstalledApp>, onAdd: (InstalledApp) -> Unit) {
    if (apps.isEmpty()) {
        EmptyState("Reading installed apps", "This can take a moment on phones with many apps.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        items(apps.size) { index ->
            val app = apps[index]
            StaggeredEntrance(index) {
                Row(
                    Modifier.fillMaxWidth().clickable { onAdd(app) }
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        val bitmap = remember(app.packageName) { app.icon?.let { drawableToBitmap(it) } }
                        if (bitmap != null) {
                            Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(30.dp))
                        } else {
                            Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${app.versionName} · ${Fmt.bytes(app.size)} · ${app.apkPaths.size} APK",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(Icons.Filled.Add, contentDescription = "Add", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

fun drawableToBitmap(drawable: android.graphics.drawable.Drawable): Bitmap {
    if (drawable is android.graphics.drawable.BitmapDrawable) return drawable.bitmap
    val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
    val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
        val canvas = android.graphics.Canvas(it)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
    }
}

// --- nearby ---------------------------------------------------------------------------------------

@Composable
fun NearbyScreen(vm: AppViewModel, state: AppState) {
    val discovery = state.discovery
    var manualCode by remember { mutableStateOf("") }
    ScreenFrame(title = "Send to", onBack = { vm.back() }) {
        Box(Modifier.fillMaxWidth().height(230.dp), contentAlignment = Alignment.Center) {
            RadarPulse(active = discovery.active, modifier = Modifier.size(210.dp))
            LoopJetMark(size = 86)
        }
        Text(
            if (discovery.peers.isEmpty()) "Looking for nearby phones…" else "${discovery.peers.size} phone(s) found",
            Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            discovery.log.ifBlank { "Keep LoopJet open on the other phone" },
            Modifier.fillMaxWidth().padding(horizontal = 28.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(14.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            items(discovery.peers.size) { index ->
                val peer = discovery.peers[index]
                StaggeredEntrance(index) { PeerRow(peer) { vm.sendTo(peer) } }
            }
            item {
                Spacer(Modifier.height(12.dp))
                SectionCard {
                    Text("Pair without discovery", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Type the 6-digit code shown on the other phone if the radar finds nothing.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = manualCode,
                            onValueChange = { manualCode = it.filter { ch -> ch.isDigit() }.take(6) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            placeholder = { Text("000000") },
                            shape = MaterialTheme.shapes.large
                        )
                        Spacer(Modifier.width(10.dp))
                        PrimaryAction("Connect", Modifier.width(120.dp), enabled = manualCode.length == 6) {
                            vm.notice("Manual pairing uses the code shown on the receiving phone")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PeerRow(peer: DiscoveredPeer, onClick: () -> Unit) {
    val (pressModifier, interaction) = rememberPressScale()
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp)
            .then(pressModifier)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape)
                .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary))),
            contentAlignment = Alignment.Center
        ) {
            Text(peer.name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(peer.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when (peer.kind) {
                    PeerKind.DIRECT -> "Wi-Fi Direct · ${peer.address}"
                    PeerKind.HOTSPOT -> "Hotspot · ${peer.address}"
                    PeerKind.BLE -> "Bluetooth discovery"
                    PeerKind.LAN -> "Same Wi-Fi · ${peer.rttMicros / 1000} ms"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(Icons.Filled.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary)
    }
}

// --- receive --------------------------------------------------------------------------------------

@Composable
fun ReceiveScreen(vm: AppViewModel, state: AppState) {
    val session by vm.session.collectAsStateSafe()
    ScreenFrame(title = "Receive", onBack = { vm.back() }) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp)) {
            item {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadarPulse(active = session.receiveReady, Modifier.size(56.dp))
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("Discoverable", style = MaterialTheme.typography.titleLarge)
                            Text(
                                state.deviceName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    QrPanel(session.receiveQr.ifBlank { "loopjet://waiting" })
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "On the other phone: LoopJet › Send › pick this name. Or scan the code with the LoopJet camera.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    StatRow("Saving to", session.pathLabel.ifBlank { "Downloads/LoopJet" })
                    Spacer(Modifier.height(6.dp))
                    StatRow("Link", session.linkLabel.ifBlank { "waiting" })
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "Files are accepted one session at a time. Every sender is approved by you; tick “trust” to auto-accept next time.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(18.dp))
                PrimaryAction("Stop receiving") { vm.cancelTransfer(); vm.back() }
            }
        }
    }
}

@Composable
fun OfferDialog(offer: IncomingOfferUi, onAnswer: (Boolean, Boolean) -> Unit) {
    var trust by remember(offer.peerName) { mutableStateOf(false) }
    androidx.compose.ui.window.Dialog(onDismissRequest = { onAnswer(false, false) }) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(46.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) { Text(offer.peerName.take(1).uppercase(), fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(offer.peerName, style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${offer.files} file(s) · ${Fmt.bytes(offer.bytes)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text("Confirm this code matches the other phone", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    offer.sas.chunked(3).joinToString(" "),
                    Modifier.fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(vertical = 14.dp),
                    style = MaterialTheme.typography.displaySmall,
                    textAlign = TextAlign.Center,
                    letterSpacing = 6.sp
                )
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = trust, onCheckedChange = { trust = it })
                    Spacer(Modifier.width(10.dp))
                    Text("Trust this device", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = { onAnswer(false, false) }, modifier = Modifier.weight(1f)) {
                        Text("Decline")
                    }
                    PrimaryAction("Accept", Modifier.weight(1f)) { onAnswer(true, trust) }
                }
            }
        }
    }
}

@Composable
fun QrPanel(payload: String) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val bitmap = remember(payload, dark) { qrBitmap(payload, 640, dark) }
    Box(
        Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(Color.White)
            .padding(14.dp),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), contentDescription = "QR code", modifier = Modifier.fillMaxWidth().height(240.dp), contentScale = ContentScale.Fit)
        } else {
            Text("QR unavailable", color = Color.Black)
        }
    }
}

private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue

fun qrBitmap(text: String, size: Int, dark: Boolean): Bitmap? {
    if (text.isBlank()) return null
    return runCatching {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(matrix.width * matrix.height)
        val fg = if (dark) 0xFF0B1020.toInt() else 0xFF101522.toInt()
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                pixels[y * matrix.width + x] = if (matrix.get(x, y)) fg else 0xFFFFFFFF.toInt()
            }
        }
        Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
        }
    }.getOrNull()
}

// --- transfer -------------------------------------------------------------------------------------

@Composable
fun TransferScreen(vm: AppViewModel, state: AppState) {
    val session by vm.session.collectAsStateSafe()
    val sending = session.mode == SessionMode.SEND
    val total = session.totalBytes.coerceAtLeast(1)
    val progress = (session.doneBytes.toFloat() / total).coerceIn(0f, 1f)
    ScreenFrame(
        title = when {
            session.state == TransferState.DONE -> "Complete"
            session.state == TransferState.FAILED -> "Stopped"
            sending -> "Sending"
            else -> "Receiving"
        },
        onBack = { vm.back() }
    ) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp)) {
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    ProgressRing(progress = progress) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "${(progress * 100).toInt()}%",
                                style = MaterialTheme.typography.displaySmall
                            )
                            Text(
                                Fmt.rate(session.instantBps.toDouble()),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "${Fmt.bytes(session.doneBytes)} of ${Fmt.bytes(session.totalBytes)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                SpeedGraph(
                    values = session.speedHistory,
                    modifier = Modifier.fillMaxWidth().height(84.dp)
                )
                Spacer(Modifier.height(10.dp))
                SectionCard {
                    StatRow("Peer", session.peerName.ifBlank { state.selectedPeerName })
                    Spacer(Modifier.height(6.dp))
                    StatRow("Path", session.linkLabel.ifBlank { "local link" })
                    Spacer(Modifier.height(6.dp))
                    StatRow("Average", Fmt.rate(session.averageBps.toDouble()))
                    Spacer(Modifier.height(6.dp))
                    StatRow("Remaining", Fmt.duration(session.etaMs))
                    if (session.sas.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        StatRow("Code", session.sas.chunked(3).joinToString(" "))
                    }
                    Spacer(Modifier.height(6.dp))
                    StatRow("Target", session.pathLabel.ifBlank { "Downloads/LoopJet" })
                }
                Spacer(Modifier.height(14.dp))
                LinkDashes(
                    progress = progress,
                    active = session.state == TransferState.TRANSFERRING,
                    modifier = Modifier.fillMaxWidth().height(20.dp)
                )
                Spacer(Modifier.height(14.dp))
                if (session.fileResults.isNotEmpty()) {
                    Text("Files", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    session.fileResults.takeLast(40).forEach { result -> FileResultRow(result) }
                }
                Spacer(Modifier.height(16.dp))
                when (session.state) {
                    TransferState.TRANSFERRING, TransferState.CONNECTING, TransferState.WAITING_ACCEPT -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PrimaryAction(
                                if (session.state == TransferState.PAUSED) "Resume" else "Pause",
                                Modifier.weight(1f)
                            ) { if (session.state == TransferState.PAUSED) vm.resumeTransfer() else vm.pauseTransfer() }
                            TextButton(onClick = { vm.cancelTransfer() }, modifier = Modifier.weight(1f)) {
                                Text("Cancel")
                            }
                        }
                    }
                    TransferState.PAUSED -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PrimaryAction("Resume", Modifier.weight(1f)) { vm.resumeTransfer() }
                            TextButton(onClick = { vm.cancelTransfer() }, modifier = Modifier.weight(1f)) { Text("Cancel") }
                        }
                    }
                    else -> {
                        PrimaryAction("Done") { vm.back() }
                        Spacer(Modifier.height(10.dp))
                        TextButton(onClick = { vm.go(Screen.History) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Open history")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileResultRow(result: FileResult) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(22.dp).clip(CircleShape)
                .background(if (result.ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (result.ok) Icons.Filled.Check else Icons.Filled.Warning,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = if (result.ok) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            result.relPath,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(Fmt.bytes(result.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// --- web / invite ---------------------------------------------------------------------------------

@Composable
fun WebScreen(vm: AppViewModel, state: AppState, invite: Boolean) {
    val web by vm.web.collectAsStateSafe()
    ScreenFrame(title = if (invite) "Invite" else "Web Share", onBack = { vm.back() }) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp)) {
            item {
                if (!web.active) {
                    EmptyState(
                        title = if (invite) "Share the app" else "Share files with a PC",
                        body = if (invite) "Start the page and let the other phone install LoopJet." else "Open the page in any browser on this Wi-Fi."
                    )
                    Spacer(Modifier.height(16.dp))
                    PrimaryAction(if (invite) "Start invite page" else "Start Web Share") {
                        if (invite) vm.startInvite(false) else vm.startWebShare()
                    }
                } else {
                    SectionCard {
                        Text(if (invite) "Install page is live" else "Web Share is live", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(4.dp))
                        Text(web.url, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(14.dp))
                        QrPanel(web.qr)
                        if (web.wifiQr.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Text("Join the hotspot first", style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.height(8.dp))
                            QrPanel(web.wifiQr)
                            Text(
                                "Scan with the other phone's camera to join “${web.hotspotSsid}”.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        StatRow("Connected browsers", "${web.clients}")
                        Spacer(Modifier.height(6.dp))
                        StatRow("Served", Fmt.bytes(web.bytesServed))
                        if (web.pin.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            StatRow("PIN", web.pin)
                        }
                        Spacer(Modifier.height(6.dp))
                        StatRow("Network", web.addressLabel.ifBlank { "local" })
                        Spacer(Modifier.height(16.dp))
                        PrimaryAction("Stop sharing") { vm.stopWeb() }
                    }
                }
            }
        }
    }
}

// --- history ---------------------------------------------------------------------------------------

@Composable
fun HistoryScreen(vm: AppViewModel, state: AppState) {
    val entries = remember(state) { vm.history() }
    ScreenFrame(title = "History", onBack = { vm.back() }) {
        if (entries.isEmpty()) {
            EmptyState("No transfers yet", "Finished and failed sessions appear here with their speed and target folder.")
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp)) {
                items(entries.size) { index ->
                    val entry = entries[index]
                    StaggeredEntrance(index) {
                        SectionCard(Modifier.padding(vertical = 5.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (entry.direction == "send") Icons.Filled.Send else Icons.Filled.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "${if (entry.direction == "send") "To" else "From"} ${entry.peerName.ifBlank { "device" }}",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        "${entry.files} file(s) · ${Fmt.bytes(entry.bytes)} · ${Fmt.date(entry.startedAt)}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    if (entry.ok) Fmt.rate(if (entry.durationMs > 0) entry.bytes * 1000.0 / entry.durationMs else 0.0) else "failed",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (entry.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                            }
                            if (entry.fileNames.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    entry.fileNames.take(3).joinToString(", ") + if (entry.fileNames.size > 3) " …" else "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.height(5.dp))
                            TextButton(onClick = { vm.resend(entry) }) { Text("Send again") }
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = { vm.clearHistory() }, modifier = Modifier.fillMaxWidth()) { Text("Clear history") }
                }
            }
        }
    }
}

// --- settings ---------------------------------------------------------------------------------------

@Composable
fun SettingsScreen(vm: AppViewModel, state: AppState, permissions: @Composable () -> Unit) {
    val prefs = com.loopjet.app.platform.LoopJetApp.instance.prefs
    var name by remember { mutableStateOf(state.deviceName) }
    var saveFolder by remember { mutableStateOf(vm.saveFolder()) }
    var pin by remember { mutableStateOf(vm.webPin()) }
    var requirePin by remember { mutableStateOf(vm.webRequirePin()) }
    var autoAccept by remember { mutableStateOf(vm.autoAccept()) }
    var verify by remember { mutableStateOf(vm.verifyAfter()) }
    var avatar by remember { mutableStateOf(state.avatar) }
    ScreenFrame(title = "Settings", onBack = { vm.back() }) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp)) {
            item {
                SectionCard {
                    Text("This device", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(48.dp).clip(CircleShape)
                                .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary))),
                            contentAlignment = Alignment.Center
                        ) { Text(avatar, fontSize = 22.sp) }
                        Spacer(Modifier.width(12.dp))
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it.take(28) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("Device name") },
                            shape = MaterialTheme.shapes.medium
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("🚀", "⚡", "🐉", "🌊", "🎧", "📷", "🦉", "🍀").forEach { candidate ->
                            Text(
                                candidate,
                                Modifier.clip(CircleShape)
                                    .background(if (avatar == candidate) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { avatar = candidate; vm.setAvatar(candidate) }
                                    .padding(8.dp),
                                fontSize = 18.sp
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    PrimaryAction("Save name", enabled = name.isNotBlank()) { vm.setDeviceName(name) }
                }
                Spacer(Modifier.height(14.dp))
            }
            item {
                SectionCard {
                    Text("Theme", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (mode, label) ->
                            val selected = state.themeMode == mode
                            Text(
                                label,
                                Modifier.clip(CircleShape)
                                    .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { vm.setTheme(mode) }
                                    .padding(horizontal = 16.dp, vertical = 9.dp),
                                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("Speed mode", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("balanced" to "Balanced", "fast" to "Fast", "max" to "Max").forEach { (mode, label) ->
                            val selected = vm.speedMode() == mode
                            Text(
                                label,
                                Modifier.clip(CircleShape)
                                    .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { vm.setSpeedMode(mode) }
                                    .padding(horizontal = 16.dp, vertical = 9.dp),
                                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                    Text(
                        "Fast uses 4 parallel streams; Max uses 6 and keeps the screen awake for the highest Wi-Fi mode.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(14.dp))
            }
            item {
                SectionCard {
                    Text("Saving", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = saveFolder,
                        onValueChange = { saveFolder = it },
                        label = { Text("Folder in Downloads") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium
                    )
                    Spacer(Modifier.height(8.dp))
                    PrimaryAction("Save folder") { vm.setSaveFolder(saveFolder); vm.notice("New files go to Downloads/$saveFolder") }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = verify, onCheckedChange = { verify = it; vm.setVerifyAfter(it) })
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Verify after transfer", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Re-hash finished files before they are renamed. Slower on very large files.",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = autoAccept, onCheckedChange = { autoAccept = it; vm.setAutoAccept(it) })
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Auto-accept trusted devices", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Everyone else still needs one tap.",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
            item {
                SectionCard {
                    Text("Web Share security", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = requirePin, onCheckedChange = { requirePin = it; vm.setWebPin(pin, it) })
                        Spacer(Modifier.width(10.dp))
                        Text("Require a PIN for browsers", style = MaterialTheme.typography.bodyLarge)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it.filter { c -> c.isDigit() }.take(6); vm.setWebPin(pin, requirePin) },
                        label = { Text("PIN (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium
                    )
                }
                Spacer(Modifier.height(14.dp))
            }
            item {
                SectionCard {
                    Text("Trusted devices", style = MaterialTheme.typography.titleMedium)
                    val trusted = vm.trustedDevices()
                    if (trusted.isEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "No trusted devices yet. Accept a transfer and tick “Trust” to add one.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        trusted.forEach { (fingerprint, name) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(name, style = MaterialTheme.typography.bodyLarge)
                                    Text(fingerprint.take(16) + "…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { vm.untrust(fingerprint) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
            item {
                SectionCard {
                    Text("Benchmark", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Measures storage write/read, AES-GCM sealing and the real transfer engine on this phone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    PrimaryAction(if (state.benchRunning) "Running…" else "Run benchmark", enabled = !state.benchRunning) { vm.runBenchmark() }
                    state.benchmark?.let { text ->
                        Spacer(Modifier.height(12.dp))
                        Text(text, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(10.dp))
                    val save = prefs.lastBenchmark
                    if (save != null && state.benchmark == null) {
                        Text("Last run:\n$save", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
            item {
                SectionCard {
                    Text("Battery killers", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Some phones stop background transfers. Fix it once per device:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    com.loopjet.app.platform.Perms.batteryGuide.forEach { (oem, tip) ->
                        Text(oem, style = MaterialTheme.typography.labelLarge)
                        Text(tip, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                    }
                }
                Spacer(Modifier.height(14.dp))
                permissions()
                Spacer(Modifier.height(14.dp))
            }
            item {
                SectionCard {
                    Text("About", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    StatRow("App", "LoopJet ${com.loopjet.app.BuildConfig.VERSION_NAME}")
                    Spacer(Modifier.height(6.dp))
                    StatRow("Identity", if (com.loopjet.app.platform.IdentityFactory.isHardwareBacked()) "Keystore (hardware)" else "Software key")
                    Spacer(Modifier.height(6.dp))
                    StatRow("Fingerprint", com.loopjet.app.platform.LoopJetApp.instance.identity.fingerprintHex.take(24) + "…")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Local-only transfers: AES-256-GCM sealed, ECDH P-256 session keys, pinned identities. No analytics, no ads, no accounts, no internet access.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = { vm.go(Screen.About) }) { Text("How it works") }
                }
            }
        }
    }
}

@Composable
fun AboutScreen(vm: AppViewModel) {
    ScreenFrame(title = "About", onBack = { vm.back() }) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp)) {
            item {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LoopJetMark(56)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("LoopJet", style = MaterialTheme.typography.headlineMedium)
                            Text("Fast local transfers", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "LoopJet moves files phone-to-phone over Wi-Fi Direct, your own Wi-Fi, or a hotspot. " +
                            "The engine keeps four stages busy (read, seal, send, write) so the radio stays full, " +
                            "splits big files across parallel streams, batches thousands of small files, and resumes " +
                            "from a chunk map after any interruption.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Protocol", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "LJP/1: length-prefixed records, AES-256-GCM per 256 KiB with AAD binding file and offset, " +
                            "ECDH P-256 handshake signed by a Keystore identity, six-digit code for visual confirmation.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Honesty", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Speed numbers in Settings › Benchmark are measured on this device. Wi-Fi Direct and hotspot " +
                            "paths are implemented but only real hardware can confirm a specific phone's behaviour.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// --- helpers ---------------------------------------------------------------------------------------

@Composable
fun <T> StateFlow<T>.collectAsStateSafe(): androidx.compose.runtime.State<T> =
    androidx.lifecycle.compose.collectAsStateWithLifecycle()
