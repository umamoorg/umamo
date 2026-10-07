package org.umamo.ui.tracks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import org.umamo.ui.kit.BoxGestureFlow
import org.umamo.ui.kit.BoxGestureSurface
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoCursors
import org.umamo.ui.theme.drawCrosshairGuides
import org.umamo.ui.theme.drawCursor
import org.umamo.ui.theme.drawRubberBand
import org.umamo.ui.theme.hiddenPointerIcon
import org.umamo.ui.theme.selectionOverlayStyle

/**
 * The box-select marquee: a rubber band over a whole scrolling sheet, reporting the region it enclosed.
 *
 * ARMED rather than always-on, because an unmodified drag on a lane already means something (drag a key,
 * or scrub the playhead), and a marquee that pre-empted those would cost the common gesture to serve the
 * rarer one.  Arming through a command means the same `B` and the same registry route the viewport's box
 * select uses, so the muscle memory carries.
 *
 * The gesture itself is kit's BoxGestureFlow, the rules every box select in the editor answers to: a drag
 * past the click threshold lands the band, with Shift at the release adding; a release under it is a
 * click, which only disarms; a right-click mid-drag abandons the band and disarms; and a release that
 * arrives already consumed abandons it too - what Compose sends the loop when the overlay leaves
 * composition under the pressed pointer, as a disarm from the keyboard does - so nothing lands that the
 * hand never let go of.  [onDismiss] follows every end, landed or abandoned.
 *
 * Reports WINDOW coordinates - the same space the lanes report their bounds in - so the owner can resolve
 * the region against rows that live inside a scroll it knows nothing about.
 *
 * While armed and before the drag begins it shows the same affordance the viewport's armed box select does:
 * full-width / full-height marching-ants guides through the pointer, and the drawn crosshair standing in
 * for a hidden OS cursor.  Without it nothing on screen said the mode was active at all.  The chrome comes
 * from the theme kit ([drawCrosshairGuides] / [drawRubberBand]), shared with the viewport rather than
 * reimplemented here - this package stays domain-free, but drawing primitives are not domain.
 *
 * @param Boolean armed Whether a marquee is awaiting its drag.
 * @param Function onSelect Receives the enclosed region and whether the gesture was additive (Shift).
 * @param Function onDismiss Called when the gesture ends, landed or abandoned, so the caller can disarm.
 * @param Modifier modifier The layout modifier.
 */
@Composable
fun TrackSheetMarqueeOverlay(
	armed: Boolean,
	onSelect: (Rect, Boolean) -> Unit,
	onDismiss: () -> Unit,
	modifier: Modifier = Modifier,
) {
	if (!armed) {
		return
	}
	val style = selectionOverlayStyle(LocalUmamoColors.current)
	val crosshairCursor = LocalUmamoCursors.crosshair
	val latestSelect by rememberUpdatedState(onSelect)
	val latestDismiss by rememberUpdatedState(onDismiss)
	var windowOffset by remember { mutableStateOf(Offset.Zero) }
	// The band and the flow live with the overlay and need no dispose guard: a disarm unmounts it, and the
	// loop's last event on the way out - the release Compose sends already consumed - abandons the band
	// through the flow before the two are discarded with the composition.
	val band =
		remember {
			TrackMarqueeBand(
				onLand = { region, additive -> latestSelect(region, additive) },
				onDisarm = { latestDismiss() },
				windowOffset = { windowOffset },
			)
		}
	val flow = remember { BoxGestureFlow(band) }
	// Null until the pointer is first seen, so an arm from the keyboard does not paint guides through the
	// top-left corner before the hand has arrived.
	var hoverPoint by remember { mutableStateOf<Offset?>(null) }
	Canvas(
		modifier =
			modifier
				.fillMaxSize()
				.onGloballyPositioned { coordinates -> windowOffset = coordinates.boundsInWindow().topLeft }
				// Hide the OS cursor so only the drawn crosshair shows, matching the armed viewport gesture.
				.pointerHoverIcon(hiddenPointerIcon(), overrideDescendants = true)
				// A SECOND pointerInput, on the Initial pass and consuming nothing: the flow below only reads
				// the pointer while a button is down, which is why the guides need their own observer rather
				// than a position latched inside the drag.
				.pointerInput(Unit) {
					awaitPointerEventScope {
						while (true) {
							val event = awaitPointerEvent(PointerEventPass.Initial)
							if (event.type == PointerEventType.Exit) {
								hoverPoint = null
							} else {
								event.changes.lastOrNull()?.let { change -> hoverPoint = change.position }
							}
						}
					}
				}
				.pointerInput(Unit) {
					awaitPointerEventScope {
						while (true) {
							val event = awaitPointerEvent()
							val change = event.changes.firstOrNull() ?: continue
							// Always armed: the overlay composes only while it is.
							flow.handleEvent(event, change, armed = true, Unit)
						}
					}
				},
	) {
		val pointer = band.current ?: hoverPoint
		// Guides only BEFORE the drag: once a band exists it says everything the guides did, and keeping
		// both draws four lines through a box that is already outlined.
		if (band.origin == null && pointer != null) {
			drawCrosshairGuides(pointer, size, style)
		}
		drawRubberBand(band.origin, band.current, style)
		if (pointer != null) {
			drawCursor(crosshairCursor, pointer)
		}
	}
}

/**
 * The marquee's side of the box gesture: the band's two corners, held as snapshot state so the draw pass
 * follows the drag, and what a landed box and a disarm mean to the sheet's owner.
 *
 * @param Function onLand Receives the enclosed region, in window coordinates, and whether Shift added.
 * @param Function onDisarm Invoked as the gesture ends, landed or abandoned.
 * @param Function windowOffset Reads the overlay's window origin, which the reported region is offset by.
 */
private class TrackMarqueeBand(
	private val onLand: (Rect, Boolean) -> Unit,
	private val onDisarm: () -> Unit,
	private val windowOffset: () -> Offset,
) : BoxGestureSurface<Unit> {
	/** The press corner, or null while no band is in flight. */
	var origin: Offset? by mutableStateOf(null)
		private set

	/** The corner under the pointer, or null while no band is in flight. */
	var current: Offset? by mutableStateOf(null)
		private set

	override fun beginBox(position: Offset) {
		origin = position
		current = position
	}

	override fun dragBox(position: Offset) {
		current = position
	}

	override fun landBox(start: Offset, end: Offset, additive: Boolean, frame: Unit) {
		origin = null
		current = null
		val offset = windowOffset()
		onLand(
			Rect(
				topLeft = Offset(minOf(start.x, end.x), minOf(start.y, end.y)) + offset,
				bottomRight = Offset(maxOf(start.x, end.x), maxOf(start.y, end.y)) + offset,
			),
			additive,
		)
	}

	override fun abandonBox() {
		origin = null
		current = null
	}

	override fun click(event: PointerEvent, change: PointerInputChange) {
		// Unreachable: the overlay composes only while armed, and an armed click only disarms.
	}

	override fun disarm() {
		onDisarm()
	}
}