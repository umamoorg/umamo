package org.umamo.ui.tracks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import org.umamo.ui.kit.AtPointPositionProvider
import org.umamo.ui.kit.menu.Menu
import org.umamo.ui.kit.menu.MenuItem
import org.umamo.ui.kit.menu.contextMenuGesture
import org.umamo.ui.theme.LocalUmamoColors

/**
 * One row's track lane: its marks positioned along the axis, over the playhead.
 *
 * @param TrackRow row The row the lane belongs to.
 * @param List<TrackKeyMark> marks The marks to draw (a collapsed group draws its subtree's).
 * @param TrackAxis axis The domain.
 * @param Float? playhead The playhead's domain value, or null.
 * @param Color background The row's tone fill.
 * @param Modifier modifier The layout modifier.
 * @param Function? onMarkClick Invoked when a click lands on a mark, with whether Shift was held.
 * @param Function? onTrackScrub Invoked as an empty-track drag moves, with the gesture's Shift state.
 * @param Function? onTrackScrubEnd Invoked when an empty-track drag is released.
 * @param Function? onMarkDrag Invoked on every move of a mark drag.
 * @param Function? onMarkDragEnd Invoked when a mark drag is released.
 * @param Function selectedMarkDragDelta The in-flight group-drag offset drawn on selected marks; null for none.
 * @param Function? laneMenuItems Builds the lane's context-menu items.
 * @param Function? onLaneHover Reports the live hit under the pointer, or null on exit.
 * @param Function? onLaneBounds Reports the lane's window bounds.
 * @param Dp markRadius Half-extent of a drawn mark, and the track region's end inset.
 */
@Composable
internal fun TrackLane(
	row: TrackRow,
	marks: List<TrackKeyMark>,
	axis: TrackAxis,
	playhead: Float?,
	background: Color,
	modifier: Modifier = Modifier,
	onMarkClick: ((TrackRow, TrackKeyMark, Boolean) -> Unit)?,
	onTrackScrub: ((TrackRow, Float, Boolean) -> Unit)?,
	onTrackScrubEnd: ((TrackRow, Float) -> Unit)?,
	onMarkDrag: ((TrackRow, TrackKeyMark, Float) -> Unit)? = null,
	onMarkDragEnd: ((TrackRow, TrackKeyMark, Float) -> Unit)? = null,
	selectedMarkDragDelta: () -> Float? = { null },
	laneMenuItems: ((TrackLaneHit) -> List<MenuItem>)? = null,
	onLaneHover: ((TrackRow, TrackLaneHit?) -> Unit)? = null,
	onLaneBounds: ((TrackRow, Rect) -> Unit)? = null,
	markRadius: Dp,
) {
	val colors = LocalUmamoColors.current
	val density = LocalDensity.current
	// The marks and the callbacks reach the pointer loops through latest-state holders, never as keys; see
	// TrackLaneCallbacks for why that is load-bearing.
	val editableMarks = remember(marks) { marks.filter { candidate -> candidate.editable } }
	val latestEditableMarks = rememberUpdatedState(editableMarks)
	val latestCallbacks =
		rememberUpdatedState(
			TrackLaneCallbacks(
				onMarkClick = onMarkClick,
				onTrackScrub = onTrackScrub,
				onTrackScrubEnd = onTrackScrubEnd,
				onMarkDrag = onMarkDrag,
				onMarkDragEnd = onMarkDragEnd,
				onLaneHover = onLaneHover,
			),
		)
	// The row rides along on the exit report, so a receiver can ignore an exit for a row it is no longer
	// holding - adjacent rows interleave, with the new row's enter arriving before the old row's exit.
	DisposableEffect(row.key) {
		onDispose { latestCallbacks.value.onLaneHover?.invoke(row, null) }
	}
	val drag = remember(row.key) { TrackLaneDragFeedback() }
	// The context menu's items depend on WHERE it was opened (over a key, or over empty track), so the
	// gesture records only the anchor and the hit is resolved below, at composition time.  Resolving it
	// inside the gesture lambda would read whatever `marks` and `axis` were when that lambda was created -
	// contextMenuGesture keys its pointerInput on Unit, so the first lambda is the one that runs forever.
	var menuOpen by remember(row.key) { mutableStateOf(false) }
	var menuAnchor by remember(row.key) { mutableStateOf(IntOffset.Zero) }
	// The lane's measured width, so the pixel->domain mapping is available OUTSIDE a draw or pointer scope
	// (the context-menu gesture reports a raw offset and has neither).
	var laneWidth by remember(row.key) { mutableStateOf(0) }
	val touchSlop = LocalViewConfiguration.current.touchSlop
	val markRadiusPx = with(density) { markRadius.toPx() }
	Box(
		modifier =
			modifier
				.fillMaxHeight()
				.background(background)
				.clipToBounds()
				// Window coordinates, so a marquee drawn over the whole scrolling sheet and a lane inside it
				// are comparable without either knowing the other's scroll offset.
				.onGloballyPositioned { coordinates -> onLaneBounds?.invoke(row, coordinates.boundsInWindow()) },
	) {
		Canvas(
			modifier =
				Modifier
					.fillMaxSize()
					.onSizeChanged { measured -> laneWidth = measured.width }
					// Hover reporting is its own handler and consumes nothing: it has to see the pointer
					// wherever it is, including mid-drag, and must never take an event from the gestures.
					.pointerInput(row.key, axis, markRadiusPx) {
						trackLaneHoverLoop(row, axis, markRadiusPx, latestEditableMarks, latestCallbacks)
					}
					.then(
						if (laneMenuItems == null) {
							Modifier
						} else {
							Modifier.contextMenuGesture { localOffset ->
								menuAnchor = localOffset
								menuOpen = true
							}
						},
					)
					// ONE gesture handler for tap AND drag. Two separate pointerInput blocks would race: a drag
					// detector would consume the down before a tap detector saw it, so clicking a mark would do
					// nothing and neither selection nor dragging would work. Deciding between them from a single
					// stream is the only way they cannot fight.
					.pointerInput(row.key, axis, markRadiusPx) {
						trackLaneGestureLoop(row, axis, markRadiusPx, touchSlop, latestEditableMarks, latestCallbacks, drag)
					},
		) {
			// Read HERE rather than in composition: a group drag changes its offset, and a mark drag its
			// feedback, every pointer frame; a draw scope read redraws the lane without recomposing the
			// sheet above it.
			drawTrackLane(marks, axis, playhead, markRadiusPx, colors, drag.mark, drag.value, selectedMarkDragDelta())
		}
		if (laneMenuItems != null && menuOpen && laneWidth > 0) {
			val menuValue = domainAt(menuAnchor.x.toFloat(), axis, laneWidth, markRadiusPx)
			val menuMark = nearestMark(marks, menuValue, pickTolerance(axis, laneWidth, markRadiusPx))
			Menu(
				items = laneMenuItems(TrackLaneHit(row, menuValue, menuMark)),
				onDismissRequest = { menuOpen = false },
				positionProvider = AtPointPositionProvider(menuAnchor),
				focusable = true,
			)
		}
	}
}