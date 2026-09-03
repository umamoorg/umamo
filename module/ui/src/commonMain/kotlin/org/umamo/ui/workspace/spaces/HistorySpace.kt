package org.umamo.ui.workspace.spaces

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.VerticalScrollbarOverlay
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.workspace.changeLabel

/**
 * The history space: the session's undo stack as a clickable list, oldest at the top and the live step
 * highlighted (Blender's Undo History / Photoshop's History panel). Clicking a row jumps the session
 * straight to that step — undoing or redoing several levels at once — and the steps ahead of the cursor
 * (the redo branch) are dimmed. A trailing dot marks the last-saved step. The whole list is a projection
 * the session republishes ([org.umamo.edit.EditorSession.historyView]), so it tracks edits made anywhere.
 *
 * @param Modifier modifier The layout modifier.
 */
@Composable
fun HistorySpace(modifier: Modifier = Modifier) {
	val session = LocalEditorSession.current
	if (session == null) {
		Box(modifier = modifier.fillMaxSize().zebraFill(rememberLazyListState(), ROW_HEIGHT, LocalUmamoColors.current.rowStripe))
		return
	}
	val view by session.historyView.collectAsState()
	val listState = rememberLazyListState()
	// Keep the live step on screen as the cursor moves (a row click, an undo / redo from the keyboard),
	// but only scroll when it has actually left the viewport so manual scrolling is never fought.
	LaunchedEffect(view.cursor, view.steps.size) {
		val visible = listState.layoutInfo.visibleItemsInfo
		val firstVisible = visible.firstOrNull()?.index ?: 0
		val lastVisible = visible.lastOrNull()?.index ?: 0
		if (view.steps.isNotEmpty() && (view.cursor < firstVisible || view.cursor > lastVisible)) {
			listState.animateScrollToItem(view.cursor)
		}
	}
	val stripeColor = LocalUmamoColors.current.rowStripe
	Box(modifier = modifier.fillMaxSize().zebraFill(listState, ROW_HEIGHT, stripeColor)) {
		LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
			itemsIndexed(view.steps) { index, step ->
				HistoryStepRow(
					label = changeLabel(step.labelKey),
					isCurrent = index == view.cursor,
					isFuture = index > view.cursor,
					saved = step.saved,
					onClick = { session.jumpTo(index) },
				)
			}
		}
		VerticalScrollbarOverlay(listState)
	}
}

/**
 * One step row: its [label], styled by where it sits relative to the cursor (current is the accent band,
 * a step ahead of the cursor is dimmed as a pending redo), with a hover highlight and an optional saved
 * dot. Clicking jumps the session to this step.
 *
 * @param String label The localized step label.
 * @param Boolean isCurrent Whether this is the live step (the highlighted row).
 * @param Boolean isFuture Whether this step is ahead of the cursor (a dimmed redo step).
 * @param Boolean saved Whether this is the last-saved step (draws the trailing dot).
 * @param Function onClick Jumps the session to this step.
 */
@Composable
private fun HistoryStepRow(
	label: String,
	isCurrent: Boolean,
	isFuture: Boolean,
	saved: Boolean,
	onClick: () -> Unit,
) {
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	val typography = LocalUmamoTypography.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	val background =
		when {
			isCurrent -> colors.selection
			hovered -> colors.rowHover
			else -> Color.Transparent
		}
	val labelColor =
		when {
			isCurrent -> colors.selectionText
			isFuture -> colors.textMuted
			else -> colors.text
		}
	Row(
		modifier =
			Modifier.fillMaxWidth()
				.height(ROW_HEIGHT)
				.background(background, shape = shapes.medium)
				// NOT focusable: jumping to an entry can truncate the stack, disposing the very row that
				// holds focus, and Compose leaves focus null when that happens - every keyboard shortcut
				// then dies until something focusable is clicked again.
				.focusProperties { canFocus = false }
				.clickable(interactionSource = interaction, indication = null, onClick = onClick)
				.padding(horizontal = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text = label,
			style = typography.bodySmall,
			color = labelColor,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f),
		)
		// Fixed trailing slot so labels align whether or not the dot is present; the dot marks the saved step.
		Box(modifier = Modifier.size(SAVED_DOT_SLOT), contentAlignment = Alignment.Center) {
			if (saved) {
				Box(modifier = Modifier.size(SAVED_DOT).background(if (isCurrent) colors.selectionText else colors.accent, CircleShape))
			}
		}
	}
}

/** The fixed height of one history step row. */
private val ROW_HEIGHT = 22.dp

/** The diameter of the saved-step dot. */
private val SAVED_DOT = 6.dp

/** The fixed width reserved at the row's trailing edge for the saved dot, so labels align regardless. */
private val SAVED_DOT_SLOT = 14.dp