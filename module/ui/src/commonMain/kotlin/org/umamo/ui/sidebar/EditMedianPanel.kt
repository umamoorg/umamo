package org.umamo.ui.sidebar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.kit.Text
import org.umamo.ui.properties.MutedNote
import org.umamo.ui.properties.WorldPositionFields
import org.umamo.ui.properties.rememberFieldScrubPreview
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.transform.CoveredVertices
import org.umamo.ui.transform.captureEditModeMeshes
import org.umamo.ui.transform.coveredMedianIn
import org.umamo.ui.transform.coveredVerticesOf
import org.umamo.ui.viewport.LocalLiveMeshPreview
import org.umamo.ui.viewport.viewport2d.CoveredMedianMove
import org.umamo.ui.viewport.viewport2d.coveredMedianMove
import org.umamo.ui.viewport.viewport2d.moveCoveredMedianAdjustable
import org.umamo.ui.viewport.viewport2d.previewCoveredMedianMove

/**
 * The move the panel's writes drive, captured at the first write and kept for the covered set it was built from:
 * every frame of a scrub drives the same capture, and a commit changes the model, which brings a fresh set.
 *
 * @param List<CoveredVertices> covered The covered vertices the move captures.
 */
private class MedianWrites(private val covered: List<CoveredVertices>) {
	private var captured: CoveredMedianMove? = null

	/**
	 * The move, captured on first use.
	 *
	 * @return CoveredMedianMove? The move, or null when nothing is covered.
	 */
	fun move(): CoveredMedianMove? = captured ?: coveredMedianMove(covered).also { move -> captured = move }
}

/**
 * The Item tab's Transform panel in Edit mode: where the covered vertices sit, "Vertex:" for one and "Median:" for
 * several, their mean across every mesh in the edit, measured from the world axes.  A typed or scrubbed value
 * moves them all together as a Grab would, one undo step the operation strip can adjust, and while a modal
 * transform runs, here or in another viewport, the numbers follow it.  With nothing covered it says so.
 *
 * The geometry is captured once per commit, and only while the panel is composed.
 *
 * @param SidebarContext context The sidebar's context.
 */
@Composable
internal fun EditMedianPanel(context: SidebarContext) {
	val session = context.session ?: return
	val puppet = context.puppet
	val selection = context.meshSelection
	val capture = remember(puppet, selection.drawableIds) { captureEditModeMeshes(puppet, selection.drawableIds) }
	val covered =
		remember(capture, selection) {
			coveredVerticesOf(
				selection,
				capture.map { captured -> captured.geometry },
				capture.associate { captured -> captured.geometry.drawableId to captured.mesh.indices },
			)
		}
	// A snapshot read, so a running transform recomposes the panel with each frame it publishes.
	val live = LocalLiveMeshPreview.current?.restByDrawable
	val median = remember(covered, live) { coveredMedianIn(covered, live.orEmpty()) }

	if (median == null) {
		MutedNote(Res.string.sidebar_item_nothing_selected)

		return
	}

	val writes = remember(covered) { MedianWrites(covered) }
	val scrub = rememberFieldScrubPreview(enabled = true)
	val areaId = context.areaId
	Text(
		text = stringResource(if (median.vertexCount == 1) Res.string.sidebar_edit_vertex else Res.string.sidebar_edit_median),
		style = LocalUmamoTypography.current.labelMedium,
		color = LocalUmamoColors.current.text,
	)
	WorldPositionFields(
		puppet = puppet,
		worldX = median.worldX,
		worldZ = median.worldZ,
		xDescription = Res.string.sidebar_median_x_description,
		zDescription = Res.string.sidebar_median_z_description,
		// A typed value, a chevron step, or a scrub's release puts the median's axis coordinate there.
		onCommit = { axis, worldValue ->
			scrub.commit { writes.move()?.let { move -> session.moveCoveredMedianAdjustable(move, axis, worldValue, areaId) } }
		},
		onPreview = { axis, worldValue ->
			scrub.preview(writes.move()?.let { move -> session.previewCoveredMedianMove(move, axis, worldValue) })
		},
		onScrubCancel = { scrub.end() },
	)
}