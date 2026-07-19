package org.umamo.ui.workspace.spaces

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.storage.LogEntry
import org.umamo.storage.LogLevel
import org.umamo.storage.UmamoLog
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.VerticalScrollbarOverlay
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoTypography

/**
 * The logs space: the retained UmamoLog output as a scrolling console, so a user who launched without a
 * terminal (a desktop shortcut, or Android) can still read the diagnostics.  Each line is tinted by
 * severity - routine info is de-emphasised, a warning reads at full weight, and an error is accented -
 * escalating with the flat palette's existing tokens rather than introducing semantic colours.
 *
 * The list follows the tail as new lines arrive, but only while the user is already at the bottom, so
 * scrolling up to read history is never fought (the same restraint HistorySpace uses for its cursor).
 * It reads no document state - logs exist from startup, before and independent of any open puppet.
 *
 * @param Modifier modifier The layout modifier.
 */
@Composable
fun LogsSpace(modifier: Modifier = Modifier) {
	val entries by UmamoLog.entries.collectAsState()
	if (entries.isEmpty()) {
		Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
			Text(text = stringResource(Res.string.logs_empty), color = LocalUmamoColors.current.textMuted)
		}
		return
	}
	val listState = rememberLazyListState()
	// Follow the tail only when the user is already viewing the bottom: if the previously-last line was
	// visible when this new line arrived, jump to the new last line; otherwise leave the scroll alone.
	LaunchedEffect(entries.size) {
		val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
		val previousLastIndex = entries.size - 2
		if (lastVisibleIndex >= previousLastIndex) {
			listState.scrollToItem(entries.lastIndex)
		}
	}
	Box(modifier = modifier.fillMaxSize()) {
		LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
			items(entries) { entry ->
				LogLineRow(entry)
			}
		}
		VerticalScrollbarOverlay(listState)
	}
}

/**
 * One log line, tinted by its [LogEntry.level] and left free to wrap so a long message stays fully
 * readable rather than truncating.
 *
 * @param LogEntry entry The line to render.
 */
@Composable
private fun LogLineRow(entry: LogEntry) {
	val colors = LocalUmamoColors.current
	val lineColor =
		when (entry.level) {
			LogLevel.Info -> colors.textMuted
			LogLevel.Warn -> colors.text
			LogLevel.Error -> colors.accent
		}
	Text(
		text = entry.message,
		style = LocalUmamoTypography.current.bodySmall,
		color = lineColor,
		modifier =
			Modifier.fillMaxWidth()
				.padding(horizontal = 8.dp, vertical = 1.dp),
	)
}
