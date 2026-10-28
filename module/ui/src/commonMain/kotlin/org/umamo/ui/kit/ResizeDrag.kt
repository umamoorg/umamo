package org.umamo.ui.kit

import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Makes this element a resize grip: dragging it along [orientation] changes a size by the distance dragged, held
 * between [min] and [max].  Each step starts from the size as it stands, itself held to the limits, so a stored
 * size the limits cut down never leaves a dead zone at the start of a drag.  The size, the limits, and the
 * callbacks are read when each step lands rather than captured when the drag began, since the drag outlives the
 * composition that started it.  The grip shows [hoverIcon] while the pointer is over it.
 *
 * With [collapseBelow] set, the grip can also fold what it sizes away, as dragging a Blender region's edge far
 * enough hides the region: once the size the pointer asks for, followed from where the drag began without the
 * limits, falls below [collapseBelow], [onCollapse] is called once and the rest of that drag changes nothing.
 *
 * @param Function size The size the drag changes, read at each step.
 * @param Dp min The smallest the size may become.
 * @param Dp max The largest the size may become; at least [min].
 * @param Orientation orientation The axis the grip drags along.
 * @param PointerIcon hoverIcon The cursor over the grip.
 * @param Function onResize Receives each new size, already held to the limits.
 * @param Boolean growsBackward True when dragging toward the start (left, or up) grows the size, as for a grip on
 *   the start edge of something anchored at its end.
 * @param Function onDraggingChange Told when a drag starts and when it ends, to hold a cursor meanwhile.
 * @param Dp? collapseBelow The asked-for size under which the drag collapses, or null for a grip that never does.
 * @param Function onCollapse Called once when a drag collapses.
 * @return Modifier This modifier with the grip appended.
 */
@Composable
fun Modifier.dragResizes(
	size: () -> Dp,
	min: Dp,
	max: Dp,
	orientation: Orientation,
	hoverIcon: PointerIcon,
	onResize: (Dp) -> Unit,
	growsBackward: Boolean = false,
	onDraggingChange: (Boolean) -> Unit = {},
	collapseBelow: Dp? = null,
	onCollapse: () -> Unit = {},
): Modifier {
	val density = LocalDensity.current
	val latestSize by rememberUpdatedState(size)
	val latestMin by rememberUpdatedState(min)
	val latestMax by rememberUpdatedState(max)
	val latestOnResize by rememberUpdatedState(onResize)
	val latestOnDraggingChange by rememberUpdatedState(onDraggingChange)
	val latestCollapseBelow by rememberUpdatedState(collapseBelow)
	val latestOnCollapse by rememberUpdatedState(onCollapse)
	val stroke = remember { ResizeStroke() }
	val dragState =
		remember(density, growsBackward) {
			DraggableState { deltaPx ->
				if (stroke.collapsed) {
					return@DraggableState
				}

				val delta = with(density) { deltaPx.toDp() }
				val signedDelta = if (growsBackward) -delta else delta
				stroke.requested += signedDelta
				val threshold = latestCollapseBelow

				if (threshold != null && stroke.requested < threshold) {
					stroke.collapsed = true
					latestOnCollapse()

					return@DraggableState
				}

				val shown = latestSize().coerceIn(latestMin, latestMax)
				latestOnResize((shown + signedDelta).coerceIn(latestMin, latestMax))
			}
		}

	return this
		.pointerHoverIcon(hoverIcon)
		.draggable(
			state = dragState,
			orientation = orientation,
			onDragStarted = {
				stroke.requested = latestSize().coerceIn(latestMin, latestMax)
				stroke.collapsed = false
				latestOnDraggingChange(true)
			},
			onDragStopped = { latestOnDraggingChange(false) },
		)
}

/**
 * One drag's bookkeeping for [dragResizes]' collapse: the size the pointer asks for, followed from where the drag
 * began without the limits, and whether the drag has collapsed.  Plain fields, since nothing draws from them.
 */
private class ResizeStroke {
	/** The size the pointer asks for, unheld by the limits. */
	var requested: Dp = 0.dp

	/** Whether this drag has collapsed, after which it changes nothing. */
	var collapsed: Boolean = false
}

/**
 * Holds [icon] over this element and everything inside it while [held], and leaves the descendants' own cursors
 * alone otherwise: the cursor a drag shows for as long as it runs.  The icon is declared on a node present the
 * whole time, because a hover icon that appears only mid-drag is not resolved again until the pointer crosses a
 * node boundary, which mid-drag it may never do.
 *
 * @param PointerIcon icon The cursor to hold.
 * @param Boolean held Whether a drag is running.
 * @return Modifier This modifier with the hover icon appended.
 */
fun Modifier.holdsHoverIcon(icon: PointerIcon, held: Boolean): Modifier =
	pointerHoverIcon(icon = if (held) icon else PointerIcon.Default, overrideDescendants = held)