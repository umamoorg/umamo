package org.umamo.ui.workspace.spaces.outliner

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.unit.dp
import org.umamo.edit.EditorSession
import org.umamo.edit.SelectionTarget
import org.umamo.edit.structure.RowDropBand
import org.umamo.edit.structure.applyOutlinerDrop
import org.umamo.edit.structure.resolveOutlinerDrop
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.workspace.rowdrag.RowDragController

/**
 * Draws the drop insertion line for a drop-target row: along the top edge for a Before drop, the bottom
 * edge for After.  An Into drop (a part's middle band) shows the whole-row fill instead, drawn by the row
 * itself, so a null band or an Into draws nothing here.  The line starts at the row's indent level, so
 * it reads as landing at the target's nesting (the dropped sibling's level).
 *
 * Drawn behind the row's content, which is text over a transparent fill, so the line shows through it.
 *
 * @param RowDropBand? band The row's current drop band, or null when it is not the target.
 * @param Int depth The row's depth, which sets where the line starts.
 * @param Color accentColor The line color.
 * @return Modifier This modifier drawing the line behind the row.
 */
internal fun Modifier.outlinerDropLine(band: RowDropBand?, depth: Int, accentColor: Color): Modifier =
	this.drawBehind {
		// Drawn inside the row's band inset, so widen back out to the row's own frame: the line sits on the
		// row's edge, not the band's.
		inset(-OUTLINER_ROW_BAND_INSET.toPx()) {
			// Inset the line by half its stroke so the full 2.5dp stays inside the row: drawn at the very edge
			// (y == 0) its top half would clip against the row bound, leaving the first row's line a sliver.
			val strokeWidth = 2.5.dp.toPx()
			val edgeY =
				when (band) {
					RowDropBand.Before -> strokeWidth / 2f
					RowDropBand.After -> size.height - strokeWidth / 2f
					RowDropBand.Into, null -> return@drawBehind
				}
			val startX = OUTLINER_CONTENT_START.toPx() + OUTLINER_INDENT_PER_DEPTH.toPx() * depth
			drawLine(
				color = accentColor,
				start = Offset(startX, edgeY),
				end = Offset(size.width, edgeY),
				strokeWidth = strokeWidth,
			)
		}
	}

/**
 * Applies the drop at the end of a drag: reads the drag state, resolves the move through :edit's
 * [resolveOutlinerDrop] (the same band rules the row indicator drew, so what the user saw is what
 * happens), dispatches it as one undo step, and expands the destination on a nest-inside drop so the
 * user sees the moved item land.  Drops with no valid target, onto itself, or across the org /
 * armature boundary do nothing; cycles are refused inside the move.
 *
 * @param RowDragController<SelectionTarget> controller The drag state (read for the dragged + target + band, then ended).
 * @param List rows The visible rows, to resolve a node id to its selection target.
 * @param PuppetModel puppet The model, to resolve the target's parent and siblings.
 * @param EditorSession? session The session the move is dispatched through (null = no document, no-op).
 * @param Function expand Opens the given node id (the destination is expanded on a nest-inside drop so the
 *   user sees the moved item land, rather than fearing it vanished into a collapsed branch).
 */
internal fun performOutlinerDrop(
	controller: RowDragController<SelectionTarget>,
	rows: List<OutlinerRow>,
	puppet: PuppetModel,
	session: EditorSession?,
	expand: (String) -> Unit,
) {
	val targetId = controller.dropTargetKey
	val fraction = controller.dropTargetFraction ?: 0.5f
	val dragged = controller.draggedPayload
	controller.end()
	if (targetId == null || session == null || dragged == null) {
		return
	}
	val targetNode = rows.firstOrNull { row -> row.node.id == targetId }?.node ?: return
	val target = targetNode.target ?: return
	val drop = puppet.resolveOutlinerDrop(dragged, target, fraction) ?: return
	session.applyOutlinerDrop(drop)
	if (drop.expandTarget) {
		expand(targetNode.id)
	}
}