package org.umamo.ui.workspace.spaces.parameters

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.umamo.edit.EditorSession
import org.umamo.edit.ParameterMoveSubject
import org.umamo.edit.parameter.moveParameterRow
import org.umamo.edit.structure.RowDropBand
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.kit.Tooltip
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.drawIcon
import org.umamo.ui.workspace.rowdrag.RowCoordinatesHolder
import org.umamo.ui.workspace.rowdrag.RowDragController

/**
 * The leading drag handle of a parameter row (a slider, a pad, or a group header). Dragging it reorders
 * the row or moves it into / out of a group. Uses raw pointerInput (immediate drag on a dedicated grip,
 * since the row body carries sliders and pads), which also never requests focus: a focused node that
 * leaves composition takes keyboard focus with it, and every shortcut then stays dead until the next click.
 *
 * @param String gripLabel The localized accessible label for the handle.
 * @param ParameterMoveSubject subject What a drag of this row relocates.
 * @param String rowKey The row's stable key (the drag / drop identity).
 * @param RowDragController<ParameterMoveSubject> dragController The shared drag state.
 * @param Function onDrop Invoked on release to apply the move.
 * @param Function onSelect Invoked when the handle is clicked rather than dragged.
 */
@Composable
internal fun ParameterGripHandle(
	gripLabel: String,
	subject: ParameterMoveSubject,
	rowKey: String,
	dragController: RowDragController<ParameterMoveSubject>,
	onDrop: () -> Unit,
	onSelect: () -> Unit,
) {
	val colors = LocalUmamoColors.current
	val gripCoordinates = remember { RowCoordinatesHolder() }
	val touchSlop = LocalViewConfiguration.current.touchSlop
	// Read at gesture time, not keyed into pointerInput: the callbacks are rebuilt on every recomposition
	// of the panel, and keying the gesture to them would cancel an in-flight drag the moment anything
	// upstream recomposed.
	val latestSelect by rememberUpdatedState(onSelect)
	val latestDrop by rememberUpdatedState(onDrop)
	// Tooltipped as well as named, unlike the disclosure chevrons: a grip's two jobs (drag to reorder, click
	// to target the row) are not something the parameter name beside it conveys.  The wrapper only wraps -
	// the drag's onGloballyPositioned stays on the fixed-size face, so grip coordinates are unchanged.
	Tooltip(text = gripLabel) {
		Box(
			modifier =
				Modifier
					.size(width = 18.dp, height = 24.dp)
					// A hand cursor on hover signals the row is grabbable here (desktop only; touch has no hover).
					.pointerHoverIcon(PointerIcon.Hand)
					.onGloballyPositioned { coordinates -> gripCoordinates.coordinates = coordinates }
					// ONE handler resolving tap versus drag off a single stream.  A separate tap detector beside
					// detectDragGestures loses the race - the drag detector consumes the press first - which is
					// the same trap the keyform sheet's lanes fell into.
					.pointerInput(rowKey) {
						awaitEachGesture {
							val down = awaitFirstDown(requireUnconsumed = false)
							val origin = gripCoordinates.coordinates?.boundsInWindow()
							var dragging = false
							// Only a REAL release completes the gesture.  Everything else - the coroutine
							// cancelled mid-drag (the row scrolled out of composition), the pointer id lost, a
							// change consumed by another handler - must cancel a live drag rather than drop it,
							// and must never turn a press into a click.  The finally is what runs the cancel on
							// cancellation, which awaitEachGesture itself gives no hook for.
							var released = false
							try {
								while (true) {
									val event = awaitPointerEvent()
									val change = event.changes.firstOrNull { candidate -> candidate.id == down.id } ?: break
									if (change.isConsumed) {
										break
									}
									if (!change.pressed) {
										released = true
										break
									}
									if (!dragging && (change.position - down.position).getDistance() > touchSlop) {
										dragging = true
										dragController.start(
											rowKey,
											subject,
											(origin?.left ?: 0f) + down.position.x,
											(origin?.top ?: 0f) + down.position.y,
										)
									}
									if (dragging) {
										dragController.drag(
											(origin?.left ?: 0f) + change.position.x,
											(origin?.top ?: 0f) + change.position.y,
										)
										change.consume()
									}
								}
							} finally {
								when {
									dragging && released -> latestDrop()
									dragging -> dragController.end()
									released -> latestSelect()
									// A lost pointer id or a consumed press ends the gesture with no action.
									else -> Unit
								}
							}
						}
					}
					.semantics { contentDescription = gripLabel },
			contentAlignment = Alignment.Center,
		) {
			Canvas(modifier = Modifier.size(14.dp)) {
				drawIcon(LocalUmamoIcons.gripVertical, colors.textMuted)
			}
		}
	}
}

/**
 * Draws the before / after insertion line for a drop-target row. Into is drawn by the group header's own
 * fill, so this only handles the reorder bands; a null band or an Into draws nothing.
 *
 * Drawn OVER the row's content, not behind it: the line sits on the row's top / bottom edge, and the
 * ParameterIsland inside fills that same edge with an opaque elevation color, so a drawBehind line is
 * completely hidden by its own child.
 *
 * @param RowDropBand band The row's current drop band, or null when it is not the target.
 * @param Color accentColor The insertion-line color.
 * @return Modifier The modifier drawing the line over the row.
 */
internal fun Modifier.parameterDropLine(band: RowDropBand?, accentColor: Color): Modifier =
	this.drawWithContent {
		drawContent()
		if (band == RowDropBand.Before || band == RowDropBand.After) {
			val strokeWidth = 2.5.dp.toPx()
			val lineY =
				if (band == RowDropBand.Before) {
					strokeWidth / 2f
				} else {
					size.height - strokeWidth / 2f
				}
			drawLine(accentColor, Offset(0f, lineY), Offset(size.width, lineY), strokeWidth)
		}
	}

/**
 * Applies a parameter-row drag drop: resolves the target row and band, then dispatches the tree move.
 * Ends the controller first (after capturing its state), so a no-op / illegal drop simply clears the
 * drag. An Into drop expands the destination group.
 *
 * @param RowDragController<ParameterMoveSubject> dragController The drag state (read then cleared).
 * @param List rows The current render rows.
 * @param PuppetModel puppet The open model (its tree resolves the drop anchor).
 * @param EditorSession? session The session the move dispatches through.
 * @param Function onExpandGroup Expands a group id (for an Into drop).
 */
internal fun performParameterDrop(
	dragController: RowDragController<ParameterMoveSubject>,
	rows: List<ParameterRow>,
	puppet: PuppetModel,
	session: EditorSession?,
	onExpandGroup: (ParameterGroupId) -> Unit,
) {
	val targetKey = dragController.dropTargetKey
	val subject = dragController.draggedPayload
	val fraction = dragController.dropTargetFraction ?: 0.5f
	dragController.end()
	if (targetKey == null || subject == null || session == null) {
		return
	}
	val drop = resolveParameterDrop(puppet, rows, subject, targetKey, fraction) ?: return
	session.moveParameterRow(subject, drop.newParentGroupId, drop.before)
	if (drop.expandsGroupId != null) {
		onExpandGroup(drop.expandsGroupId)
	}
}