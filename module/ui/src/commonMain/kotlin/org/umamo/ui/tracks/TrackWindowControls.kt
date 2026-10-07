package org.umamo.ui.tracks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.umamo.ui.kit.SCROLLBAR_CORNER_RADIUS
import org.umamo.ui.kit.SCROLLBAR_MIN_THUMB
import org.umamo.ui.kit.SCROLLBAR_THICKNESS
import org.umamo.ui.theme.LocalUmamoColors

/** How much one wheel notch zooms; a fixed ratio, so a notch feels the same however far in you are. */
private const val ZOOM_STEP: Float = 0.85f

/** How far one wheel notch pans, as a fraction of the VISIBLE width. */
private const val PAN_STEP: Float = 0.15f

/**
 * The zoom / pan gestures for a whole track sheet: Ctrl+wheel zooms about the pointer, Shift+wheel pans,
 * and a tertiary (middle) drag pans.
 *
 * Attached by the sheet's owner across its whole scrolling region, not per lane, because the window is
 * shared by every track and section - the zoom belongs to the view, not to a row.
 *
 * PLAIN wheel is deliberately left alone: the sheet scrolls vertically through hundreds of tracks, and
 * taking the unmodified wheel for zoom would cost the more common gesture to serve the rarer one.  This
 * matches every NLE that has real vertical overflow.
 *
 * @param TrackWindow window The current window.
 * @param Function onWindowChange Receives the new window.
 * @param Dp labelColumnWidth The label column's width, so a gesture over the labels is ignored.
 * @param Function onPanningChange Reports whether a pan is in flight, so the caller can hold the cursor.
 * @return Modifier The modifier carrying both gestures.
 */
@Composable
fun Modifier.trackWindowGestures(
	window: TrackWindow,
	onWindowChange: (TrackWindow) -> Unit,
	labelColumnWidth: Dp,
	onPanningChange: (Boolean) -> Unit = {},
): Modifier {
	val density = LocalDensity.current
	val latestWindow by rememberUpdatedState(window)
	val latestCallback by rememberUpdatedState(onWindowChange)
	val latestPanning by rememberUpdatedState(onPanningChange)
	val labelWidthPx = with(density) { labelColumnWidth.toPx() }
	return this
		.pointerInput(labelWidthPx) {
			awaitPointerEventScope {
				while (true) {
					// The INITIAL pass, deliberately: the Main pass runs child-first, so a Main-pass handler here
					// would see the wheel only after the vertical scroll container had already claimed it, and
					// Ctrl+wheel would zoom and scroll at once.
					val event = awaitPointerEvent(PointerEventPass.Initial)
					if (event.type != PointerEventType.Scroll) {
						continue
					}
					val change = event.changes.firstOrNull() ?: continue
					val laneWidth = size.width - labelWidthPx
					if (change.position.x < labelWidthPx || laneWidth <= 0f) {
						continue
					}
					val horizontal = change.scrollDelta.x
					if (horizontal != 0f) {
						latestCallback(latestWindow.pannedBy(horizontal * latestWindow.span * PAN_STEP))
						change.consume()
						continue
					}
					val scroll = change.scrollDelta.y
					if (scroll == 0f) {
						continue
					}
					when {
						event.keyboardModifiers.isCtrlPressed -> {
							val focus = ((change.position.x - labelWidthPx) / laneWidth).coerceIn(0f, 1f)
							// A notch is a fixed RATIO, so zooming feels the same however far in you are.
							latestCallback(latestWindow.zoomedBy(if (scroll > 0f) 1f / ZOOM_STEP else ZOOM_STEP, focus))
							change.consume()
						}

						event.keyboardModifiers.isShiftPressed -> {
							latestCallback(latestWindow.pannedBy(scroll * latestWindow.span * PAN_STEP))
							change.consume()
						}

						// Anything else is the vertical scroll, which belongs to the scroll container.
						else -> Unit
					}
				}
			}
		}
		.pointerInput(labelWidthPx) {
			awaitPointerEventScope {
				while (true) {
					// The raw stream on the INITIAL pass, not awaitFirstDown.  Two reasons, both load-bearing:
					// Initial is the only pass that beats the lanes and the scroll container, which are
					// children and would otherwise claim a middle press first; and awaitFirstDown does not
					// resolve a down on that pass at all - it waits forever, so calling it here would silently
					// hang.  contextMenuGesture watches the raw stream for the same reason.
					val press = awaitPointerEvent(PointerEventPass.Initial)
					if (press.type != PointerEventType.Press || !press.buttons.isTertiaryPressed) {
						continue
					}
					val down = press.changes.first()
					if (down.position.x < labelWidthPx) {
						continue
					}
					down.consume()
					latestPanning(true)
					val laneWidth = size.width - labelWidthPx
					var lastX = down.position.x
					while (true) {
						val event = awaitPointerEvent(PointerEventPass.Initial)
						val change = event.changes.firstOrNull { candidate -> candidate.id == down.id } ?: break
						if (!change.pressed) {
							change.consume()
							break
						}
						if (laneWidth > 0f && change.position.x != lastX) {
							// Dragging right moves the CONTENT right, so the window moves left - the direct
							// manipulation a hand tool has, not a scrollbar's inverted sense.
							val moved = (change.position.x - lastX) / laneWidth
							latestCallback(latestWindow.pannedBy(-moved * latestWindow.span))
							lastX = change.position.x
						}
						change.consume()
					}
					latestPanning(false)
				}
			}
		}
}

/**
 * The horizontal window indicator under a track sheet: a thumb showing which slice of the domain is on
 * screen, draggable to pan.
 *
 * Not a scrollbar over a scroll container - there is no scrolling content to attach to - so it reads the
 * window directly and reports a new one.  Hidden when the whole domain is framed, since a full-width thumb
 * says nothing.
 *
 * @param TrackWindow window The current window.
 * @param Function onWindowChange Receives the new window as the thumb is dragged.
 * @param Dp labelColumnWidth The label column's width, so the track starts under the tracks.
 * @param Modifier modifier The layout modifier.
 */
@Composable
fun TrackWindowScrollbar(
	window: TrackWindow,
	onWindowChange: (TrackWindow) -> Unit,
	labelColumnWidth: Dp,
	modifier: Modifier = Modifier,
) {
	val colors = LocalUmamoColors.current
	if (window.span >= 1f) {
		return
	}
	val latestWindow by rememberUpdatedState(window)
	val latestCallback by rememberUpdatedState(onWindowChange)
	var trackWidth by remember { mutableStateOf(0) }
	val dragState =
		remember {
			DraggableState { deltaPx ->
				if (trackWidth > 0) {
					latestCallback(latestWindow.pannedBy(deltaPx / trackWidth))
				}
			}
		}
	val minimumThumbWidth = with(LocalDensity.current) { SCROLLBAR_MIN_THUMB.toPx() }
	val cornerRadius = with(LocalDensity.current) { SCROLLBAR_CORNER_RADIUS.toPx() }
	Row(modifier = modifier.fillMaxWidth().height(SCROLLBAR_THICKNESS)) {
		Spacer(modifier = Modifier.width(labelColumnWidth + 1.dp))
		Box(
			modifier =
				Modifier
					.weight(1f)
					.fillMaxHeight()
					.onSizeChanged { measured -> trackWidth = measured.width }
					.draggable(state = dragState, orientation = Orientation.Horizontal),
		) {
			Canvas(modifier = Modifier.fillMaxSize()) {
				val thumbWidth = maxOf(size.width * latestWindow.span, minimumThumbWidth)
				val thumbLeft = (size.width * latestWindow.start).coerceIn(0f, maxOf(0f, size.width - thumbWidth))
				// Full thickness, matching the panel scrollbars: a bar is a pointer target first, and one
				// inset thinner than its neighbours is harder to hit for no reason the user can see.
				drawRoundRect(
					color = colors.scrollbarThumb,
					topLeft = Offset(thumbLeft, 0f),
					size = Size(thumbWidth, size.height),
					cornerRadius = CornerRadius(cornerRadius),
				)
			}
		}
	}
}