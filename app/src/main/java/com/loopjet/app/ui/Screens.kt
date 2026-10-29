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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.filled.DateRange
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
import androidx.compose.material3.ripple
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.loopjet.app.core.FileResult
import com.loopjet.app.core.Fmt
import com.loopjet.app.core.TransferState
import kotlinx.coroutines.withContext
import com.loopjet.app.platform.HistoryEntry
import com.loopjet.app.platform.IncomingOfferUi
import com.loopjet.app.platform.ReceivedAppUi
import com.loopjet.app.platform.SessionFileUi
import com.loopjet.app.platform.SessionMode
import com.loopjet.app.platform.SessionUi
import com.loopjet.app.platform.percent
import com.loopjet.app.platform.apps.InstalledApp
import com.loopjet.app.platform.net.ConnectCode
import com.loopjet.app.platform.net.DiscoveredPeer
import com.loopjet.app.platform.net.PeerKind
import kotlinx.coroutines.flow.StateFlow

// --- shared scaffolding ---------------------------------------------------------------------------

@Composable
internal fun ScreenFrame(
    title: String? = null,
    onBack: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            // The root Surface already paints the window, so no per-screen background is needed.
            .statusBarsPadding()
            // Insets are consumed here, so a nested navigationBarsPadding (the picker tray) will not
            // double up — but no screen can end underneath the system bar any more.
            .navigationBarsPadding()
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

@Suppress("ModifierParameter")
@Composable
private fun PrimaryAction(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    GradientButton(text = text, modifier = modifier, enabled = enabled, onClick = onClick)
}

// --- home -----------------------------------------------------------------------------------------

@Composable
fun HomeScreen(vm: AppViewModel, state: AppState, onSend: () -> Unit, onReceive: () -> Unit) {
    val session by vm.session.collectAsStateSafe()
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 28.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlowLinkMark(size = 52)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("FlowLink", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        state.deviceName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // The refresh glyph here read as "reload", not "theme": the theme selector lives in
                // Settings, where the System/Light/Dark choice is labelled.
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
                    icon = Icons.Filled.KeyboardArrowDown,
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
                SmallAction("History", "${state.historyCount} sessions", Icons.Filled.DateRange, Modifier.weight(1f)) { vm.go(Screen.History) }
                SmallAction("Settings", "Speed, trust, theme", Icons.Filled.Settings, Modifier.weight(1f)) { vm.go(Screen.Settings) }
            }
            Spacer(Modifier.height(18.dp))
        }
        item {
            SectionCard {
                Text("This phone", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                StatRow("Network", state.networkSummary.ifBlank { vm.deviceSummary() })
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
            .heightIn(min = 160.dp)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(
                if (accent) Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary))
                else Brush.linearGradient(listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surface))
            )
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraLarge)
            .clickable(role = Role.Button, interactionSource = interaction, indication = ripple()) { onClick() }
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom)
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
                color = if (accent) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
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
            .clickable(role = Role.Button, interactionSource = interaction, indication = ripple()) { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
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
                if (session.sending) "Sending to ${session.peerName.ifBlank { "the other phone" }}"
                else if (session.mode == SessionMode.RECEIVE) "Receiving from ${session.peerName.ifBlank { "the other phone" }}"
                else "Session",
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

/**
 * @param onOpenGallery launches the system photo picker. Files chosen there are added to the same
 *        tray as everything else, so a gallery pick, a folder pick and an app can go in one send.
 */
@Composable
fun PickerScreen(
    vm: AppViewModel,
    state: AppState,
    onPickFolder: () -> Unit,
    onOpenGallery: () -> Unit = {},
    onGrantMedia: () -> Unit = {},
    onAppSettings: () -> Unit = {}
) {
    val picker = state.picker
    val haptics = rememberHaptics()
    val trayHeight = if (picker.totalSelected > 0) 92.dp else 0.dp
    ScreenFrame(title = if (picker.webShareMode) "Web Share files" else "Send", onBack = { vm.back() }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (picker.searchOpen) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                OutlinedTextField(
                    value = picker.query,
                    onValueChange = { vm.setQuery(it) },
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    placeholder = { Text("Find any file or app") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (picker.query.isNotEmpty()) {
                            IconButton(onClick = { vm.setQuery("") }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    shape = MaterialTheme.shapes.large
                )
                Spacer(Modifier.width(6.dp))
                IconButton(onClick = { vm.closeSearch() }) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Close search")
                }
            } else {
                // A doorway, not a filter: tapping it opens the dedicated search area instead of
                // quietly filtering whichever category happened to be on screen.
                Row(
                    Modifier.weight(1f)
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { vm.openSearch() }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Search files and apps",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                // Gallery: one tap to the phone's own photo picker, which needs no permission at all.
                // A labelled chip rather than an icon: "the button next to search" has to be obvious.
                Text(
                    "Gallery",
                    Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .clickable { onOpenGallery() }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
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
        var snippetDialog by remember { mutableStateOf(false) }
        if (snippetDialog) {
            TextSnippetDialog(
                onDismiss = { snippetDialog = false },
                onAdd = { text -> vm.addTextSnippet(text); snippetDialog = false }
            )
        }
        if (picker.searchOpen) {
            // Search mode: the category strip is replaced by the only two things a search can return.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SearchScope.entries.forEach { scope ->
                    val active = scope == picker.searchScope
                    Text(
                        scope.label,
                        Modifier
                            .weight(1f)
                            .clip(CircleShape)
                            .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { vm.setSearchScope(scope) }
                            .padding(vertical = 10.dp),
                        color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    "Text / link",
                    Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .clickable { snippetDialog = true }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            items(PickCategory.entries) { category ->
                val selected = category == picker.category
                Text(
                    category.label,
                    Modifier
                        .clip(CircleShape)
                        .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable {
                            // Folders opens the file tree inside FlowLink. It used to fire the system
                            // folder picker, which asked the user to hand over exactly one folder
                            // before they could see anything.
                            if (category == PickCategory.Folders) vm.openFolderRoot() else vm.setCategory(category)
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
        if (!picker.searchOpen && picker.category == PickCategory.Folders && !picker.needsAllFiles) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Up is enabled only when there is a parent: the root of the tree is as far as it goes.
                IconButton(
                    onClick = { vm.goUpFolder() },
                    enabled = (picker.folderStack.size > 1)
                ) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Up one folder")
                }
                Text(
                    picker.folderStack.lastOrNull()?.second ?: "Internal storage",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    picker.folderStack.dropLast(1).takeLast(1).firstOrNull()?.second ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        // A partial photo grant is a trap that looks like a bug: the list is short, the permission
        // reads as granted, and nothing on screen says why. This banner says why, wherever the user
        // is in a media category -- not only when the list happens to be empty.
        if (picker.mediaPartial && !picker.searchOpen) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Only the photos you picked are visible",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onAppSettings) { Text("Allow all") }
            }
        }
        Box(Modifier.weight(1f)) {
            val searchApps = picker.searchOpen && picker.searchScope == SearchScope.Apps
            val visibleApps = if (searchApps) vm.filteredApps(picker.query) else picker.apps
            when {
                picker.loading -> RadarPulse(active = true, modifier = Modifier.size(120.dp).align(Alignment.Center))
                searchApps -> AppList(
                    apps = visibleApps,
                    selected = picker.selectedApps,
                    onToggle = { app ->
                        if (picker.selectedApps.contains(app.packageName)) haptics.toggleOff() else haptics.toggleOn()
                        vm.toggleApp(app)
                    },
                    emptyTitle = if (picker.query.isBlank()) "Type to find an app" else "No app called \"${picker.query}\"",
                    emptyBody = if (picker.query.isBlank()) "Apps are matched by name or package."
                    else "Clear the search to see every app on this phone."
                )
                picker.searchOpen && picker.query.isBlank() -> EmptyState(
                    title = "Search the whole phone",
                    body = "Type part of a name. Files are found wherever they live, not only in the folder you had open."
                )
                !picker.searchOpen && picker.category == PickCategory.Folders && picker.needsAllFiles -> AllFilesPrompt(onGrant = onPickFolder)
                !picker.searchOpen && picker.category == PickCategory.Apps -> AppList(
                    apps = picker.apps,
                    selected = picker.selectedApps,
                    onToggle = { vm.toggleApp(it) }
                )
                // Android 14 answers "Allow" with a second question: all photos, or only the ones
                // the user ticks. A partial grant makes media queries succeed, so the category looks
                // empty or half-full forever -- unless it is named and pointed at system settings,
                // where a partial grant can still be widened.
                picker.items.isEmpty() && picker.mediaPartial -> MediaAccessPrompt(
                    onGrant = onAppSettings,
                    title = "Only a few photos are visible",
                    body = "Android is letting FlowLink see just the items you ticked. Choose " +
                        "\"Photos and videos\" then \"Allow all\" in settings to browse everything. " +
                        "The Files and Folders tabs already show the whole phone.",
                    action = "Open settings"
                )
                picker.items.isEmpty() && picker.needsMediaAccess -> MediaAccessPrompt(onGrant = onGrantMedia)
                picker.items.isEmpty() -> EmptyState(
                    title = if (picker.query.isNotBlank()) "No match for \"${picker.query}\"" else "Nothing here yet",
                    body = when {
                        picker.searchOpen -> "No file on this phone matches that name."
                        picker.query.isNotBlank() -> "Nothing in this view has that name. Clear the search to see everything."
                        picker.category == PickCategory.Folders -> "This folder has no readable files."
                        else -> "No files in this category yet."
                    }
                )
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    if (picker.truncated) {
                        item {
                            Text(
                                "Showing the first ${picker.items.size} items. Use search to narrow down.",
                                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    itemsIndexed(picker.items, key = { _, item -> item.key }) { index, item ->
                        StaggeredEntrance(index) {
                            val isPicked = picker.selection.containsKey(item.key)
                            PickRow(item, isPicked) {
                                if (item.isFolder) {
                                    haptics.tap()
                                    vm.openFolder(item.folderUri ?: "")
                                } else {
                                    if (isPicked) haptics.toggleOff() else haptics.toggleOn()
                                    vm.toggleSelection(item)
                                }
                            }
                        }
                    }
                }
            }
        }
        AnimatedVisibility(visible = picker.totalSelected > 0, enter = slideInVertically { it }, exit = fadeOut()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = trayHeight)
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${picker.totalSelected} selected",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    Text(
                        Fmt.bytes(picker.totalBytes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                TextButton(onClick = { vm.clearAll() }) { Text("Clear") }
                Spacer(Modifier.width(8.dp))
                PrimaryAction(
                    text = if (picker.webShareMode) "Share" else "Send",
                    modifier = Modifier.widthIn(min = 116.dp)
                ) {
                    haptics.confirm()
                    vm.commitSelection()
                }
            }
        }
    }
}

/** Sends a pasted snippet or link as a small text file, which every platform can open. */
@Composable
private fun TextSnippetDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(22.dp)) {
                Text("Send text or a link", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                    placeholder = { Text("Paste anything…") },
                    shape = MaterialTheme.shapes.medium
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    PrimaryAction("Add", Modifier.weight(1f), enabled = text.isNotBlank()) { onAdd(text) }
                }
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
        Thumbnail(item = item, badge = if (item.isFolder) "📁" else extensionBadge(item.name))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (item.isFolder) "Folder" else "${Fmt.bytes(item.size)} · ${Fmt.date(item.mtime)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        SelectionTick(selected)
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
private fun AppList(
    apps: List<InstalledApp>,
    selected: Set<String>,
    onToggle: (InstalledApp) -> Unit,
    emptyTitle: String = "Reading installed apps",
    emptyBody: String = "This can take a moment on phones with many apps."
) {
    if (apps.isEmpty()) {
        EmptyState(emptyTitle, emptyBody)
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        items(apps.size) { index ->
            val app = apps[index]
            val isSelected = app.packageName in selected
            StaggeredEntrance(index) {
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { onToggle(app) }
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                            else Color.Transparent
                        )
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        val context = LocalContext.current
                        val icon by produceState<Bitmap?>(initialValue = null, app.packageName) {
                            value = AppIconCache.load(context, app.packageName)
                        }
                        val bitmap = icon
                        if (bitmap != null) {
                            Image(
                                bitmap.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.size(30.dp)
                            )
                        } else {
                            Text(
                                app.label.take(1).uppercase(),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${app.versionName} · ${Fmt.bytes(app.size)} · ${app.apkPaths.size} APK",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    // The same tick circle a photo or a document gets: apps are just another item in the
                    // tray, and tapping one used to queue it instantly with no way to untick it.
                    SelectionTick(isSelected)
                }
            }
        }
    }
}

/** The one selection indicator, shared by files, folders and apps. */
@Composable
private fun SelectionTick(selected: Boolean) {
    val motion = LocalMotion.current
    // The tick lands with a small overshoot on the shared fast-spatial spring; the check itself
    // scales in behind it, so tapping a row reads as a physical snap rather than a repaint.
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.86f,
        animationSpec = motion.spec(JetMotion.Kind.FastSpatial),
        label = "tick"
    )
    val fill by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = motion.spec(JetMotion.Kind.FastEffects),
        label = "tickFill"
    )
    Box(
        Modifier.size(26.dp).scale(scale).clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = fill))
            .border(
                1.5.dp,
                if (selected) Color.Transparent else MaterialTheme.colorScheme.outline,
                CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (fill > 0.02f) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = fill),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/** Shown when a media category is empty because Android has not granted access to the library. */
@Composable
private fun MediaAccessPrompt(
    onGrant: () -> Unit,
    title: String = "Allow access to your files",
    body: String = "Photos, videos, music and documents live behind an Android permission. " +
        "Folders and Apps already work because they are read a different way.",
    action: String = "Allow access"
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        PrimaryAction(action, enabled = true) { onGrant() }
    }
}

/** Shown in the Folders category until the phone grants all-files access. */
@Composable
private fun AllFilesPrompt(onGrant: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Browse every folder", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Photos, Videos and the other categories work already. Walking the whole file manager inside " +
                "FlowLink needs one all-files permission — the same switch every file manager uses.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        PrimaryAction("Allow all files", enabled = true) { onGrant() }
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
    // Hotspot is the default: it is the only route that works whatever the two phones happen to be
    // connected to. Same-Wi-Fi is the faster one, and it is now chosen deliberately.
    val hotspotMode = state.connectMode != "wifi"
    ScreenFrame(title = "Connect", onBack = { vm.back() }) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ConnectModeChip(
                label = "Hotspot",
                selected = hotspotMode,
                modifier = Modifier.weight(1f)
            ) { vm.setConnectMode("hotspot") }
            ConnectModeChip(
                label = "Same Wi-Fi",
                selected = !hotspotMode,
                modifier = Modifier.weight(1f)
            ) { vm.setConnectMode("wifi") }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (hotspotMode) {
                "Tap Receive on the other phone, then join the hotspot it shows."
            } else {
                "Both phones must be on the same Wi-Fi. Faster, when it applies."
            },
            Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            if (hotspotMode) {
                item { HotspotJoinCard(vm) }
                item { Spacer(Modifier.height(12.dp)) }
            } else {
                item {
                    Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                        RadarPulse(active = discovery.active, modifier = Modifier.size(170.dp))
                        FlowLinkMark(size = 72)
                    }
                    Text(
                        if (discovery.peers.isEmpty()) "Looking for phones on this Wi-Fi…"
                        else "${discovery.peers.size} phone(s) found",
                        Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        discovery.log.ifBlank { "Keep FlowLink open on the other phone" },
                        Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
            if (discovery.peers.isNotEmpty()) {
                item {
                    Text(
                        if (hotspotMode) "Found on this hotspot" else "On this network",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }
            }
            items(discovery.peers.size) { index ->
                val peer = discovery.peers[index]
                StaggeredEntrance(index) {
                    PeerRow(peer, verified = if (hotspotMode) null else vm.sameSubnet(peer.address)) {
                        vm.sendTo(peer)
                    }
                }
            }
            item {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PrimerButton(
                        title = "Scan a code",
                        subtitle = "Fastest way in",
                        icon = Icons.Filled.Search,
                        accent = true,
                        modifier = Modifier.weight(1f)
                    ) { vm.go(Screen.Scanner) }
                    PrimerButton(
                        title = "Be the receiver",
                        subtitle = "Show my code",
                        icon = Icons.Filled.Share,
                        modifier = Modifier.weight(1f)
                    ) { vm.receiveHere() }
                }
                Spacer(Modifier.height(10.dp))
                SectionCard {
                    Text(
                        if (hotspotMode) "No hotspot shown on the other phone?" else "Nothing in the list?",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (hotspotMode) {
                            "The other phone shows its hotspot name and password under the QR on its " +
                                "Receive screen. If the Android prompt refuses to join, join it once from " +
                                "Wi-Fi settings — FlowLink finds the phone by itself after that."
                        } else {
                            "Two phones can be on Wi-Fi and still not see each other (guest networks and " +
                                "client isolation do this). Switch to Hotspot: it does not need any Wi-Fi."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * The hotspot the OS created for this phone, spelled out.
 *
 * "Which network, which password?" is the question every transfer app answers with "scan the QR".
 * That is one device away from useless: the camera is busy, the other phone is an iPhone, the code
 * expired. The credentials Android generated are right here, so the other phone can simply join.
 */
@Composable
private fun HotspotCredentials(ssid: String, passphrase: String, address: String) {
    val clipboard = LocalClipboardManager.current
    val haptics = rememberHaptics()
    SectionCard {
        Text("This phone's hotspot", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        CredentialRow("Name", ssid, clipboard)
        if (passphrase.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            CredentialRow("Password", passphrase, clipboard)
        }
        if (address.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            CredentialRow("Address", address, clipboard)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Join it on the other phone (Android shows it in Wi-Fi settings). FlowLink finds this " +
                "phone on it by itself.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        TextButton(
            onClick = {
                haptics.confirm()
                clipboard.setText(AnnotatedString("$ssid\n$passphrase"))
            }
        ) { Text("Copy name and password") }
    }
}

@Composable
private fun CredentialRow(label: String, value: String, clipboard: androidx.compose.ui.platform.ClipboardManager) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            Modifier.width(78.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            Modifier.weight(1f),
            style = MaterialTheme.typography.numeral,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        TextButton(
            onClick = { clipboard.setText(AnnotatedString(value)) },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text("Copy", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** The two ways in, side by side. A chip, not a switch: these are peers, not an on/off state. */
@Composable
private fun ConnectModeChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier
            .clip(MaterialTheme.shapes.large)
            .background(bg)
            .clickable(role = Role.Button) { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
    }
}

/**
 * Joining the other phone's hotspot by hand.
 *
 * The system prompt from `requestNetwork` is the happy path, but it is refused outright on some
 * OEMs and unavailable when the phone's Wi-Fi is off. The credentials are on the other phone's
 * screen, so they are also the manual route: read them, join, and discovery does the rest.
 */
@Composable
private fun HotspotJoinCard(vm: AppViewModel) {
    var ssid by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    SectionCard {
        Text("Join the other phone's hotspot", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Its Receive screen shows a name like \"AndroidAP_1234\" and a password. Type them here once.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = ssid,
            onValueChange = { ssid = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Hotspot name") },
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Password") },
            singleLine = true
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryAction(
                text = "Join",
                modifier = Modifier.weight(1f),
                enabled = ssid.isNotBlank()
            ) { vm.joinHotspot(ssid, pass) }
            TextButton(onClick = { vm.openWifiSettings() }) { Text("Wi-Fi settings") }
        }
    }
}

/**
 * A large two-line choice card. Used for the two ways to connect, so the decision is one tap and
 * needs no reading of a list of discovered devices.
 */
@Composable
private fun PrimerButton(
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
            .heightIn(min = 104.dp)
            .clip(MaterialTheme.shapes.large)
            .background(
                if (accent) BrandGradient
                else Brush.linearGradient(
                    listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surface)
                )
            )
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
            .clickable(role = Role.Button, interactionSource = interaction, indication = ripple()) { onClick() }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (accent) Color.White else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = if (accent) Color.White else MaterialTheme.colorScheme.onSurface
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.labelMedium,
            color = if (accent) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PeerRow(peer: DiscoveredPeer, verified: Boolean? = null, onClick: () -> Unit) {
    val (pressModifier, interaction) = rememberPressScale()
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp)
            .then(pressModifier)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
            .clickable(role = Role.Button, interactionSource = interaction, indication = ripple()) { onClick() }
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
                when {
                    verified == false -> "Different network · ${peer.address}"
                    verified == true -> "Same Wi-Fi verified · ${peer.rttMillis.toInt()} ms"
                    peer.kind == PeerKind.DIRECT -> "Wi-Fi Direct · ${peer.address}"
                    peer.kind == PeerKind.HOTSPOT -> "Hotspot · ${peer.address}"
                    peer.kind == PeerKind.BLE -> "Bluetooth discovery"
                    else -> "Same Wi-Fi · ${peer.rttMillis.toInt()} ms"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (verified == false) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
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
                    if (session.receiveCode.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            session.receiveCode.chunked(3).joinToString(" "),
                            style = MaterialTheme.typography.displaySmall,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                        Text(
                            "Shown to match against the other phone, not typed anywhere",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    // A code with no receiver behind it is the worst possible thing to show: the other
                    // phone scans it, fails to connect, and nothing on this screen ever admits why.
                    if (!session.receiveReady) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Receiver stopped — this code will not connect",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f)
                            )
                            PrimaryAction("Restart", enabled = true) { vm.receiveHere() }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    // Keep the screen awake while a code is on it: a dimmed phone is a phone the other
                    // user cannot scan.
                    KeepScreenOn()
                    QrPanel(session.receiveQr.ifBlank { "loopjet://waiting" })
                    Spacer(Modifier.height(8.dp))
                    Text(
                        ConnectCode.decode(session.receiveQr)?.describe()
                            ?: "Same Wi-Fi · ${session.linkLabel.ifBlank { "finding this phone" }}",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    if (session.hotspotSsid.isNotBlank()) {
                        Spacer(Modifier.height(14.dp))
                        HotspotCredentials(
                            ssid = session.hotspotSsid,
                            passphrase = session.hotspotPass,
                            address = session.hotspotAddress
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    Text(
                        "On the other phone: FlowLink › Send › Scan a code — or join this hotspot from " +
                            "its Wi-Fi settings and pick this phone from the list. Nothing to type.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    StatRow("Saving to", session.pathLabel.ifBlank { "Downloads/FlowLink" })
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
    // A stray tap outside or a back press used to *decline the whole transfer* with a blank reason,
    // which showed up on the sending phone as a bare "Declined". Answering is now an explicit choice.
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { },
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
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
                    style = MaterialTheme.typography.headlineLarge,
                    textAlign = TextAlign.Center,
                    letterSpacing = 3.sp,
                    maxLines = 1
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

/** Holds the display awake for as long as this composable is on screen. */
@Composable
private fun KeepScreenOn() {
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
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
    val haptics = rememberHaptics()
    // One buzz at the moment the session settles -- completion or failure, never both, never twice.
    LaunchedEffect(session.state) {
        when (session.state) {
            TransferState.DONE -> haptics.confirm()
            TransferState.FAILED, TransferState.CANCELLED -> haptics.reject()
            else -> Unit
        }
    }
    // The role, not the mode: see SessionUi.sending.
    val sending = session.sending
    // Both phones now carry the same total (see SessionUi.absorbProgress), so this is the same number
    // on both screens -- which is the point: 16% here used to be 0% on the other phone.
    val percent = session.percent
    val progress = percent / 100f
    val live = session.state == TransferState.TRANSFERRING
    val finished = session.state == TransferState.DONE
    // Finished-but-failed files, keyed by id, so a row can show why it did not make it.
    val resultsById = remember(session.fileResults) { session.fileResults.associateBy { it.fileId } }
    ScreenFrame(
        title = when {
            session.state == TransferState.DONE -> "Complete"
            session.state == TransferState.FAILED -> "Stopped"
            session.state == TransferState.CANCELLED -> "Cancelled"
            sending -> "Sending"
            else -> "Receiving"
        },
        onBack = { vm.back() }
    ) {
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp)
        ) {
            item {
                TransferSummary(
                    peerName = session.peerName.ifBlank { state.selectedPeerName },
                    sending = sending,
                    progress = progress,
                    percent = percent,
                    linkLabel = session.linkLabel,
                    doneBytes = session.doneBytes,
                    totalBytes = session.totalBytes,
                    instantBps = session.instantBps,
                    etaMs = session.etaMs,
                    filesDone = session.filesDone,
                    filesTotal = maxOf(session.filesTotal, session.files.size),
                    live = live,
                    state = session.state,
                    message = session.message
                )
                Spacer(Modifier.height(14.dp))
            }
            if (session.speedHistory.size > 2) {
                item {
                    SpeedGraph(
                        values = session.speedHistory,
                        modifier = Modifier.fillMaxWidth().height(64.dp)
                    )
                    Spacer(Modifier.height(14.dp))
                }
            }
            if (session.files.isNotEmpty()) {
                item {
                    Text(
                        if (sending) "Files being sent" else "Files being received",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                }
                // keyed, so rows never jump and a ring stays attached to its file while scrolling
                items(session.files, key = { it.id }) { file ->
                    FileTimelineRow(
                        file = file,
                        sentBytes = session.fileProgress[file.id] ?: 0L,
                        result = resultsById[file.id],
                        live = live,
                        isLast = file.id == session.files.lastOrNull()?.id
                    )
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
            item {
                SectionCard {
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
                    StatRow("Target", session.pathLabel.ifBlank { "Downloads/FlowLink" })
                }
                Spacer(Modifier.height(14.dp))
            }
            if (session.receivedApps.isNotEmpty()) {
                item {
                    Text("Apps received", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    session.receivedApps.forEach { received -> ReceivedAppRow(vm, received) }
                    Spacer(Modifier.height(10.dp))
                }
            }
            item {
                when (session.state) {
                    TransferState.TRANSFERRING, TransferState.CONNECTING, TransferState.WAITING_ACCEPT -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PrimaryAction("Pause", Modifier.weight(1f)) { vm.pauseTransfer() }
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
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/**
 * The header both phones show: one gradient bar with the percentage *inside* it, then speed, bytes and
 * file counts. The percentage sits in the bar rather than beside it so the answer to "is anything
 * happening?" is the largest thing on the screen.
 */
@Composable
private fun TransferSummary(
    peerName: String,
    sending: Boolean,
    progress: Float,
    percent: Int,
    linkLabel: String,
    doneBytes: Long,
    totalBytes: Long,
    instantBps: Long,
    etaMs: Long,
    filesDone: Int,
    filesTotal: Int,
    live: Boolean,
    state: TransferState,
    message: String
) {
    val accent = when (state) {
        TransferState.FAILED -> MaterialTheme.colorScheme.error
        TransferState.DONE -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.primary
    }
    SectionCard {
        Text(
            if (sending) "Sending to ${peerName.ifBlank { "device" }}" else "Receiving from ${peerName.ifBlank { "device" }}",
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(12.dp))
        // The bar itself: a rounded track with a gradient fill and the percentage drawn inside.
        Box(
            Modifier.fillMaxWidth().height(52.dp).clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .clip(MaterialTheme.shapes.medium)
                    .background(if (state == TransferState.FAILED) Brush.linearGradient(listOf(accent, accent)) else BrandGradient)
            )
            Text(
                "$percent%",
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 18.dp),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = if (progress > 0.35f || state == TransferState.FAILED) Color.White
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // A single word in a weight(1f) slot used to be broken wherever it ran out of room, so a
            // wide ETA on the right could leave "Connecte" on one line and "d" on the next. One line,
            // never wrapped mid-word; the ETA gives way first.
            Text(
                if (live) Fmt.rate(instantBps.toDouble()) else when (state) {
                    TransferState.DONE -> "Finished"
                    TransferState.PAUSED -> "Paused"
                    TransferState.FAILED -> "Stopped"
                    TransferState.CANCELLED -> "Cancelled"
                    else -> "Preparing…"
                },
                // The live speed is the number this product is judged on: it gets the hero style
                // and tabular digits, so it glides instead of shivering between frames.
                style = if (live) MaterialTheme.typography.hero else MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (live && etaMs > 0) {
                Text(
                    "${Fmt.duration(etaMs)} left",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "${Fmt.bytes(doneBytes)} of ${Fmt.bytes(totalBytes)}  ·  $percent%" +
                if (filesTotal > 0) "  ·  $filesDone of $filesTotal files" else "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (linkLabel.isNotBlank()) {
            // Which link the bytes are crossing. Without it "why is it slow" has no answer on screen:
            // a hotspot is a fraction of the speed of both phones sharing one Wi-Fi.
            Spacer(Modifier.height(2.dp))
            Text(
                linkLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (message.isNotBlank() && state != TransferState.DONE) {
            Spacer(Modifier.height(6.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (state == TransferState.FAILED) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * One file in the vertical timeline: a connector line, a circular ring that fills with that file's
 * own progress, and the name with its size. A finished file shows a check inside the ring.
 */
@Composable
private fun FileTimelineRow(
    file: SessionFileUi,
    sentBytes: Long,
    result: FileResult?,
    live: Boolean,
    isLast: Boolean
) {
    val fraction = when {
        result?.ok == true -> 1f
        result != null -> 1f
        file.size > 0 -> (sentBytes.toFloat() / file.size).coerceIn(0f, 1f)
        else -> 0f
    }
    val failed = result != null && !result.ok
    val done = result?.ok == true
    val inFlight = sentBytes > 0 && !done && !failed
    val ringColor = when {
        failed -> MaterialTheme.colorScheme.error
        done -> MaterialTheme.colorScheme.primary
        inFlight -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    // Canvas draws outside composition, so every theme colour it needs is read here.
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Row(Modifier.fillMaxWidth()) {
        // Timeline gutter: the ring is the node, the line runs through it.
        Box(Modifier.width(56.dp).heightIn(min = 64.dp), contentAlignment = Alignment.TopCenter) {
            Canvas(Modifier.width(2.dp).fillMaxHeight().padding(top = 40.dp)) {
                if (!isLast) {
                    drawRect(color = lineColor)
                }
            }
            Box(Modifier.padding(top = 6.dp).size(40.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 4f
                    val inset = stroke / 2
                    drawArc(
                        color = trackColor,
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                    if (fraction > 0f) {
                        drawArc(
                            color = ringColor,
                            startAngle = -90f,
                            sweepAngle = 360f * fraction,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = Size(size.width - stroke, size.height - stroke),
                            style = Stroke(width = stroke, cap = StrokeCap.Round)
                        )
                    }
                }
                if (done) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    Text(
                        extensionBadge(file.name).take(2),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (inFlight) ringColor else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Column(Modifier.weight(1f).padding(start = 4.dp, top = 10.dp, bottom = 10.dp)) {
            Text(
                file.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                when {
                    failed -> "Failed · ${result?.message?.ifBlank { "error" } ?: "error"}"
                    done -> "${Fmt.bytes(file.size)} · done"
                    inFlight -> "${Fmt.bytes(sentBytes)} of ${Fmt.bytes(file.size)} · ${(fraction * 100).toInt()}%"
                    live -> "${Fmt.bytes(file.size)} · waiting"
                    else -> Fmt.bytes(file.size)
                },
                style = MaterialTheme.typography.labelMedium,
                color = when {
                    failed -> MaterialTheme.colorScheme.error
                    done -> MaterialTheme.colorScheme.onSurfaceVariant
                    inFlight -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * One received app: a single Install action that feeds the base APK and every split to one
 * PackageInstaller session, with the honest state of that install underneath.
 */
@Composable
private fun ReceivedAppRow(vm: AppViewModel, received: ReceivedAppUi) {
    val installs by vm.installs.collectAsStateSafe()
    val ui = installs[received.packageName] ?: InstallUi()
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Add,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(received.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${received.files.size} APK(s)" + if (received.versionName.isNotBlank()) " · v${received.versionName}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(
                onClick = {
                    if (ui.needsPermission) {
                        vm.openInstallPermission()
                    } else {
                        vm.installReceivedApp(received)
                    }
                },
                enabled = !ui.running
            ) {
                Text(if (ui.needsPermission) "Allow" else "Install")
            }
        }
        if (ui.running) {
            androidx.compose.material3.LinearProgressIndicator(
                progress = { ui.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
        }
        val note = ui.message.ifBlank { received.message }
        if (note.isNotBlank()) {
            Text(
                note,
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    received.message.isNotBlank() && ui.message.isBlank() -> MaterialTheme.colorScheme.error
                    ui.done -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
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
                if (web.pendingBrowser.isNotBlank()) {
                    SectionCard {
                        Text("A browser wants in", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${web.pendingBrowser} is asking to ${if (invite) "download" else "open your files"}.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PrimaryAction("Allow", Modifier.weight(1f)) { vm.answerBrowser(true) }
                            TextButton(onClick = { vm.answerBrowser(false) }, modifier = Modifier.weight(1f)) {
                                Text("Deny")
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                if (!web.active) {
                    EmptyState(
                        title = if (invite) "Share the app" else "Share files with a PC",
                        body = if (invite) "Start the page and let the other phone install FlowLink." else "Open the page in any browser on this Wi-Fi."
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
                        StatRow("Browsers", "${web.clients}")   // "Connected browsers" got ellipsised in the 40% label column
                        Spacer(Modifier.height(6.dp))
                        StatRow("Served", Fmt.bytes(web.bytesServed))
                        if (web.pin.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            StatRow("PIN", web.pin)
                        }
                        Spacer(Modifier.height(6.dp))
                        StatRow(
                            "Session ends in",
                            Fmt.duration((web.expiresAt - System.currentTimeMillis()).coerceAtLeast(0))
                        )
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
    // Reading the history file used to happen inside composition, on the main thread, every time any
    // state changed. It is IO, so it belongs off the frame.
    val entries by produceState(initialValue = emptyList<HistoryEntry>(), state.historyCount, state.screen) {
        value = withContext(kotlinx.coroutines.Dispatchers.IO) { vm.history() }
    }
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
    val prefs = com.loopjet.app.platform.FlowLinkApp.instance.prefs
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
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(listOf("🚀", "⚡", "🐉", "🌊", "🎧", "📷", "🦉", "🍀")) { candidate ->
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
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(listOf("system" to "System", "light" to "Light", "dark" to "Dark")) { (mode, label) ->
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
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(listOf("balanced" to "Balanced", "fast" to "Fast", "max" to "Max")) { (mode, label) ->
                            // state.speedMode, not vm.speedMode(): a pref read is invisible to Compose.
                            val selected = state.speedMode == mode
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
                        "Fast uses 6 parallel streams. Max uses 8 for the highest throughput on a strong Wi-Fi link; Balanced uses 2 to save battery.",
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
                        Column(Modifier.weight(1f)) {
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
                        Column(Modifier.weight(1f)) {
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
                    Text("Connection", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "If a transfer will not start, the doctor lists this phone's addresses, bands and " +
                            "which paths are available — copyable in one tap.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    PrimaryAction("Open connection doctor") { vm.go(Screen.Doctor) }
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
                    StatRow("App", "FlowLink ${com.loopjet.app.BuildConfig.VERSION_NAME}")
                    Spacer(Modifier.height(6.dp))
                    StatRow("Identity", if (com.loopjet.app.platform.IdentityFactory.isHardwareBacked()) "Keystore (hardware)" else "Software key")
                    Spacer(Modifier.height(6.dp))
                    StatRow("Fingerprint", com.loopjet.app.platform.FlowLinkApp.instance.identity.fingerprintHex.take(24) + "…")
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
                        FlowLinkMark(56)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("FlowLink", style = MaterialTheme.typography.headlineMedium)
                            Text("Fast local transfers", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "FlowLink moves files phone-to-phone over Wi-Fi Direct, your own Wi-Fi, or a hotspot. " +
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
    collectAsStateWithLifecycle()
