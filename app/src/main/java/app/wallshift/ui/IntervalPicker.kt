package app.wallshift.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.wallshift.data.CustomRange
import app.wallshift.data.TimeUnitChoice
import app.wallshift.data.splitInterval
import app.wallshift.work.AutoScheduler
import kotlinx.coroutines.launch

private val RowHeight = 44.dp
private const val VisibleRows = 5

/** "Custom" interval: a 1..60 wheel plus Seconds / Minutes / Hours. */
@Composable
fun CustomIntervalDialog(
    initialSeconds: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val (initValue, initUnit) = remember(initialSeconds) { splitInterval(initialSeconds) }
    var unit by rememberSaveable { mutableStateOf(initUnit) }
    val wheel = rememberLazyListState(initialFirstVisibleItemIndex = initValue - CustomRange.first)
    val rowPx = with(LocalDensity.current) { RowHeight.toPx() }
    val value by remember {
        derivedStateOf { CustomRange.first + wheel.centeredIndex(rowPx) }
    }
    val seconds = value * unit.seconds

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom interval") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                NumberWheel(wheel, value)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    TimeUnitChoice.entries.forEachIndexed { i, u ->
                        SegmentedButton(
                            selected = unit == u,
                            onClick = { unit = u },
                            shape = SegmentedButtonDefaults.itemShape(i, TimeUnitChoice.entries.size),
                            icon = {},
                        ) { Text(u.label, maxLines = 1) }
                    }
                }
                if (seconds < AutoScheduler.WORK_MIN_SECONDS) {
                    Text(
                        "Under 15 min, changes run while the screen is on. " +
                            "Android may not fire more often than every ~5 s.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(seconds) }) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun LazyListState.centeredIndex(rowPx: Float): Int {
    val extra = if (firstVisibleItemScrollOffset > rowPx / 2) 1 else 0
    return (firstVisibleItemIndex + extra).coerceIn(0, CustomRange.last - CustomRange.first)
}

@Composable
private fun NumberWheel(state: LazyListState, selected: Int) {
    val scope = rememberCoroutineScope()
    val fling = rememberSnapFlingBehavior(lazyListState = state, snapPosition = SnapPosition.Center)
    Box(Modifier.fillMaxWidth().height(RowHeight * VisibleRows), contentAlignment = Alignment.Center) {
        // Selection band behind the centered row.
        Box(
            Modifier
                .fillMaxWidth()
                .height(RowHeight)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp)),
        )
        LazyColumn(
            state = state,
            flingBehavior = fling,
            contentPadding = PaddingValues(vertical = RowHeight * (VisibleRows / 2)),
            modifier = Modifier.fillMaxWidth().testTag("wheel"),
        ) {
            items(CustomRange.count(), key = { it }) { i ->
                val n = CustomRange.first + i
                val isSel = n == selected
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(RowHeight)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            scope.launch { state.animateScrollToItem(i) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        n.toString(),
                        style = if (isSel) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                        color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = if (isSel) Modifier else Modifier.alpha(0.55f),
                    )
                }
            }
        }
    }
}
