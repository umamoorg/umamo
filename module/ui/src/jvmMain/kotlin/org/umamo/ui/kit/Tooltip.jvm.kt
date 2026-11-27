package org.umamo.ui.kit

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.window.PopupPositionProvider

/** How long the pointer must dwell over the content before the tooltip appears. */
private const val TOOLTIP_DELAY_MILLIS = 500

/** The gap between the cursor and a tooltip card left of or above it, where no arrow sits between them. */
private val TOOLTIP_GAP_BEFORE_CURSOR = 4.dp

/** The band along the window's edges the tooltip card stays out of. */
private val TOOLTIP_WINDOW_MARGIN = 4.dp

/**
 * Places the tooltip card beside the cursor rather than anchoring the component, matching the
 * Blender-style pointer focus, with the card moving to the cursor's other side near a window edge (see
 * [CursorClearPositionProvider]).  Compose's own cursor placement pulls a card that would overflow back by
 * its size alone, which lands it over the pointer.
 */
@OptIn(ExperimentalFoundationApi::class)
private object CursorClearTooltipPlacement : TooltipPlacement {
	/**
	 * Builds the position provider for the cursor's current point.
	 *
	 * @param Offset cursorPosition The cursor relative to the tooltip area, in pixels.
	 * @return PopupPositionProvider The provider placing the card clear of that point.
	 */
	@Composable
	override fun positionProvider(cursorPosition: Offset): PopupPositionProvider {
		val density = LocalDensity.current
		val gapAfterPx = with(density) { POINTER_ARROW_CLEARANCE.roundToPx() }
		val gapBeforePx = with(density) { TOOLTIP_GAP_BEFORE_CURSOR.roundToPx() }
		val windowMarginPx = with(density) { TOOLTIP_WINDOW_MARGIN.roundToPx() }

		return remember(cursorPosition, gapAfterPx, gapBeforePx, windowMarginPx) {
			CursorClearPositionProvider(cursorPosition.round(), gapAfterPx, gapBeforePx, windowMarginPx)
		}
	}
}

/**
 * Desktop tooltip: [TooltipArea] reveals the label after a mouse-dwell over the content, placed beside
 * the cursor and clear of it.  A blank label attaches nothing (the content is wrapped only to carry
 * [modifier]), so callers can pass an unconditional description.
 *
 * @param String   text     The tooltip label; blank attaches no tooltip.
 * @param Modifier modifier The layout modifier, applied to the wrapper.
 * @param Function content  The control the tooltip describes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
actual fun Tooltip(text: String, modifier: Modifier, content: @Composable () -> Unit) {
	if (text.isBlank()) {
		Box(modifier = modifier) {
			content()
		}

		return
	}

	TooltipArea(
		tooltip = { TooltipCard(text) },
		modifier = modifier,
		delayMillis = TOOLTIP_DELAY_MILLIS,
		tooltipPlacement = CursorClearTooltipPlacement,
		content = content,
	)
}