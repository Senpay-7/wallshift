package app.wallshift.ui

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wallshift.data.Intervals
import app.wallshift.data.Settings
import app.wallshift.data.Target
import app.wallshift.data.intervalLabel
import app.wallshift.tap.TapStats
import app.wallshift.wallpaper.WallshiftWallpaperService
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.launch
import java.io.File

private val TileShape = RoundedCornerShape(12.dp)
private val SectionShape = RoundedCornerShape(20.dp)
private val pickRequest = PickVisualMediaRequest(PickVisualMedia.ImageOnly)

private val StatusOn = Color(0xFF7BD88F)
private val StatusOff = Color(0xFFE57373)
private val StatusPartial = Color(0xFFF2C46D)

/** Standard "press" feedback: the element shrinks a little while pressed and springs back. */
@Composable
private fun Modifier.pressScale(source: InteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

private val isXiaomi: Boolean = Build.MANUFACTURER.lowercase().let {
    it == "xiaomi" || it == "redmi" || it == "poco"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val changing by vm.changing.collectAsStateWithLifecycle()
    val liveState by vm.liveState.collectAsStateWithLifecycle()
    val tapStats by vm.tapStats.collectAsStateWithLifecycle()
    val currentId by vm.currentId.collectAsStateWithLifecycle()
    val currentImage by vm.currentImage.collectAsStateWithLifecycle()
    val leftovers by vm.leftoverTapAreas.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    fun message(text: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(text)
        }
    }

    val livePicker = rememberLauncherForActivityResult(StartActivityForResult()) { vm.refreshLiveActive() }
    val openLivePicker = {
        if (!launchLivePicker(context, livePicker::launch)) {
            message("Couldn't open the picker. Settings → Wallpaper → Live wallpapers → Wallshift.")
        }
    }
    val picker = rememberLauncherForActivityResult(PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) {
            vm.import(uris)
            message(if (uris.size == 1) "Adding 1 wallpaper…" else "Adding ${uris.size} wallpapers…")
        }
    }
    val selecting = selection.isNotEmpty()
    BackHandler(enabled = selecting) { vm.clearSelection() }
    // No-op unless old tap-area widgets were found (then: did the user remove them meanwhile?).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.recountLeftovers() }

    // Keeps the last count visible while the selection bar fades out.
    var shownCount by remember { mutableIntStateOf(0) }
    if (selecting) shownCount = selection.size

    Scaffold(
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            AnimatedContent(
                targetState = selecting,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
                label = "topBar",
            ) { sel ->
                if (sel) {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = vm::clearSelection) { Icon(AppIcons.Close, "Cancel") }
                        },
                        title = {
                            Crossfade(shownCount, label = "count") { n -> Text("$n selected") }
                        },
                        actions = {
                            IconButton(onClick = {
                                val n = selection.size
                                vm.deleteSelected()
                                message(if (n == 1) "1 wallpaper removed" else "$n wallpapers removed")
                            }) { Icon(AppIcons.Delete, "Delete") }
                        },
                    )
                } else {
                    TopAppBar(title = { Text("Wallshift") })
                }
            }
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = !selecting,
                enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeIn(),
                exit = scaleOut() + fadeOut(),
            ) {
                val src = remember { MutableInteractionSource() }
                FloatingActionButton(
                    onClick = { picker.launch(pickRequest) },
                    interactionSource = src,
                    modifier = Modifier.pressScale(src),
                ) {
                    Icon(AppIcons.Add, "Add wallpapers")
                }
            }
        },
    ) { padding ->
        val list = entries
        val layoutDirection = LocalLayoutDirection.current
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 104.dp),
            state = rememberLazyGridState(),
            modifier = Modifier.fillMaxSize().testTag("grid"),
            contentPadding = PaddingValues(
                start = 16.dp + padding.calculateStartPadding(layoutDirection),
                end = 16.dp + padding.calculateEndPadding(layoutDirection),
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "now", span = { GridItemSpan(maxLineSpan) }, contentType = "now") {
                NowCard(
                    image = currentImage,
                    hasItems = !list.isNullOrEmpty(),
                    changing = changing,
                    onChangeNow = vm::changeNow,
                )
            }
            item(key = "settings", span = { GridItemSpan(maxLineSpan) }, contentType = "settings") {
                // Settings arrive from DataStore within a few ms: fade in instead of flipping values.
                val s = settings
                val alpha by animateFloatAsState(if (s == null) 0f else 1f, tween(200), label = "settings")
                SettingsSections(
                    s = s ?: Settings(),
                    modifier = Modifier.graphicsLayer { this.alpha = alpha },
                    onTarget = vm::setTarget,
                    onAuto = vm::setAuto,
                    onInterval = vm::setInterval,
                    onDoubleTap = vm::setDoubleTap,
                    onDismissHint = vm::dismissHint,
                    liveState = liveState,
                    tapStats = tapStats,
                    onSetupLive = openLivePicker,
                    leftovers = leftovers,
                )
            }
            item(key = "libraryHeader", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                LibraryHeader(count = list?.size)
            }
            if (list != null && list.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }, contentType = "empty") {
                    EmptyHint(Modifier.animateItem())
                }
            }
            items(list.orEmpty(), key = { it.id }, contentType = { "tile" }) { e ->
                Tile(
                    entry = e,
                    selecting = selecting,
                    selected = e.id in selection,
                    isCurrent = e.id == currentId,
                    onToggle = vm::toggleSelection,
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

/** The image on screen right now, with "Change now" on it: every change is visible here. */
@Composable
private fun NowCard(image: File?, hasItems: Boolean, changing: Boolean, onChangeNow: () -> Unit) {
    val platform = LocalPlatformContext.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        if (image != null) {
            AnimatedContent(
                targetState = image,
                transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(350)) },
                label = "now",
            ) { file ->
                AsyncImage(
                    model = remember(file) { ImageRequest.Builder(platform).data(file).crossfade(false).build() },
                    contentDescription = "Current wallpaper",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.5f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.72f),
                        ),
                    ),
            )
        } else {
            Column(
                Modifier.align(Alignment.Center).padding(bottom = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    AppIcons.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(36.dp),
                )
            }
        }
        Text(
            if (image != null) "On screen now" else "No wallpaper yet",
            style = MaterialTheme.typography.titleSmall,
            color = if (image != null) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 18.dp, bottom = 22.dp),
        )
        val src = remember { MutableInteractionSource() }
        Button(
            onClick = onChangeNow,
            enabled = hasItems && !changing,
            interactionSource = src,
            contentPadding = PaddingValues(start = 14.dp, end = 18.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp)
                .height(44.dp)
                .pressScale(src),
        ) {
            AnimatedContent(
                targetState = changing,
                transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
                label = "changeNow",
            ) { busy ->
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(AppIcons.SkipNext, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Change now")
                    }
                }
            }
        }
    }
}

/** A titled, rounded group of settings. */
@Composable
private fun Section(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(shape = SectionShape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp).animateContentSize()) {
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
            }
            content()
        }
    }
}

@Composable
private fun SettingsSections(
    s: Settings,
    modifier: Modifier,
    onTarget: (Target) -> Unit,
    onAuto: (Boolean) -> Unit,
    onInterval: (Int) -> Unit,
    onDoubleTap: (Boolean) -> Unit,
    onDismissHint: () -> Unit,
    liveState: LiveState,
    tapStats: TapStats,
    onSetupLive: () -> Unit,
    leftovers: Int,
) {
    Column(
        modifier.fillMaxWidth().padding(top = 4.dp).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Section(title = "Apply to") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Target.entries.forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = s.target == t,
                        onClick = { onTarget(t) },
                        shape = SegmentedButtonDefaults.itemShape(i, Target.entries.size),
                    ) { Text(t.label) }
                }
            }
        }

        Section {
            SwitchRow(
                title = "Auto-change",
                subtitle = if (s.autoEnabled) "Every ${intervalLabel(s.intervalSeconds)}" else "Off",
                checked = s.autoEnabled,
                onChange = onAuto,
            )
            AnimatedVisibility(
                visible = s.autoEnabled,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    IntervalChips(current = s.intervalSeconds, onInterval = onInterval)
                }
            }
        }

        Section {
            SwitchRow(
                title = "Double tap",
                subtitle = "Anywhere on the Home screen — next wallpaper",
                checked = s.doubleTap,
                onChange = onDoubleTap,
            )
            AnimatedVisibility(
                visible = s.doubleTap,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    LiveStatus(liveState, tapStats, s.target, onSetupLive)
                }
            }
        }

        AnimatedVisibility(
            visible = leftovers > 0,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            LeftoverTapAreasCard(leftovers)
        }

        AnimatedVisibility(
            visible = isXiaomi && (s.autoEnabled || liveState != LiveState.OFF) && !s.hintDismissed,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            XiaomiHint(onDismissHint)
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    // No clip here: a rounded clip cut the first letter of the subtitle at the corner. The
    // section card already clips the press ripple to its rounded shape.
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                // Crossfade, not AnimatedContent: no clipping of the first glyph during the change.
                Crossfade(subtitle, label = "subtitle") { text ->
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun IntervalChips(current: Int, onInterval: (Int) -> Unit) {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val isCustom = current !in Intervals
    // Wraps to a second line so every option, including Custom, is visible without scrolling.
    FlowRow(
        Modifier.fillMaxWidth().animateContentSize(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Intervals.forEach { sec ->
            FilterChip(
                selected = current == sec,
                onClick = { onInterval(sec) },
                label = { Text(intervalLabel(sec)) },
            )
        }
        FilterChip(
            selected = isCustom,
            onClick = { showPicker = true },
            label = { Text(if (isCustom) "Custom: ${intervalLabel(current)}" else "Custom…") },
        )
    }
    if (showPicker) {
        CustomIntervalDialog(
            initialSeconds = current,
            onDismiss = { showPicker = false },
            onConfirm = {
                showPicker = false
                onInterval(it)
            },
        )
    }
}

/**
 * Opens the system preview for our live wallpaper; falls back to the live wallpaper list.
 * @return false if neither could be opened (the caller tells the user what to do instead).
 */
private fun launchLivePicker(context: Context, launch: (Intent) -> Unit): Boolean {
    val component = ComponentName(context, WallshiftWallpaperService::class.java)
    val direct = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
        .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
    val chooser = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
    return runCatching { launch(direct) }.isSuccess || runCatching { launch(chooser) }.isSuccess
}

/** Where the live wallpaper runs (double tap needs it on the Home screen), plus the fix. */
@Composable
private fun LiveStatus(state: LiveState, stats: TapStats, target: Target, onSetup: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val dot by animateColorAsState(
        when (state) {
            LiveState.BOTH, LiveState.HOME_ONLY -> StatusOn
            LiveState.LOCK_ONLY -> StatusPartial
            LiveState.OFF -> StatusOff
            LiveState.CHECKING -> muted
        },
        label = "liveDot",
    )
    val title = when (state) {
        LiveState.CHECKING -> "Checking the live wallpaper…"
        LiveState.OFF -> "Live wallpaper is off"
        LiveState.LOCK_ONLY -> "Live wallpaper is on the lock screen only"
        LiveState.HOME_ONLY -> "Live wallpaper is on · Home screen"
        LiveState.BOTH -> "Live wallpaper is on · Home and lock screen"
    }
    val detail = when (state) {
        LiveState.CHECKING -> null
        LiveState.OFF -> "Double tap works through the Wallshift live wallpaper. Turn it on once — nothing else to add."
        LiveState.LOCK_ONLY -> "Set it for the Home screen too, then double-tap anywhere there."
        LiveState.HOME_ONLY, LiveState.BOTH -> "Home screen taps received: ${stats.homeTaps}"
    }
    val action = when {
        state == LiveState.OFF -> "Turn on"
        state == LiveState.LOCK_ONLY -> "Set for Home screen"
        state == LiveState.HOME_ONLY && target != Target.HOME -> "Also on lock screen"
        else -> null
    }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 6.dp).size(10.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Crossfade(title, label = "liveTitle") { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (detail != null) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = muted)
                }
            }
        }
        AnimatedVisibility(visible = action != null) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                val src = remember { MutableInteractionSource() }
                FilledTonalButton(onClick = onSetup, interactionSource = src, modifier = Modifier.pressScale(src)) {
                    Text(action ?: "")
                }
            }
        }
    }
}

@Composable
private fun LibraryHeader(count: Int?) {
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp, start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Library", style = MaterialTheme.typography.titleMedium)
        if (count != null && count > 0) {
            Spacer(Modifier.width(8.dp))
            Crossfade(count, label = "libraryCount") {
                Text(
                    "$it",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "Long-press to select",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Old invisible tap-area widgets (they made extra Home screen pages): how to remove them. */
@Composable
private fun LeftoverTapAreasCard(count: Int) {
    Section {
        Text(
            if (count == 1) "1 old tap area on your Home screen" else "$count old tap areas on your Home screen",
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Not needed anymore — they caused the extra Home screen pages. They now show a dashed " +
                "frame: long-press each → Remove, then delete the empty pages.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun XiaomiHint(onDismiss: () -> Unit) {
    val context = LocalContext.current
    Section {
        Text(
            "On Xiaomi / HyperOS enable Autostart and set Battery saver to “No restrictions” for " +
                "Wallshift. Otherwise the system may freeze it in the background.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("Hide") }
            TextButton(onClick = {
                val intent = Intent(
                    AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
            }) { Text("Open settings") }
        }
    }
}

@Composable
private fun EmptyHint(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(top = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Add wallpapers", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            "Tap + to pick images from your gallery.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(
    entry: GridEntry,
    selecting: Boolean,
    selected: Boolean,
    isCurrent: Boolean,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val ready = entry.thumb != null
    val primary = MaterialTheme.colorScheme.primary
    val border by animateColorAsState(if (selected) primary else Color.Transparent, label = "border")
    val dim by animateFloatAsState(if (selected) 0.35f else 0f, label = "dim")
    val scale by animateFloatAsState(
        if (selected) 0.94f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "scale",
    )
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .aspectRatio(0.62f)
            .clip(TileShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(3.dp, border, TileShape)
            .then(
                if (ready) {
                    Modifier.combinedClickable(
                        onClick = { if (selecting) onToggle(entry.id) },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onToggle(entry.id)
                        },
                    )
                } else Modifier
            ),
    ) {
        if (entry.thumb != null) {
            AsyncImage(
                model = entry.thumb,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (dim > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
        } else {
            PendingPulse()
        }
        AnimatedVisibility(
            visible = selected,
            enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeIn(),
            exit = scaleOut() + fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
        ) {
            Icon(AppIcons.CheckCircle, contentDescription = "Selected", tint = primary, modifier = Modifier.size(22.dp))
        }
        AnimatedVisibility(
            visible = isCurrent && !selecting,
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut(),
            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
        ) {
            Text(
                "Now",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(primary)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/** Import in progress: a soft pulse instead of a spinner. */
@Composable
private fun PendingPulse() {
    val t = rememberInfiniteTransition(label = "pending")
    val a by t.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = a }.background(MaterialTheme.colorScheme.surfaceContainerHighest))
}
