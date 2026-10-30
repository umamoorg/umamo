package org.umamo.ui.sidebar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.kit.Text
import org.umamo.ui.model.LocalPuppetRenderSync
import org.umamo.ui.model.PuppetRenderSync
import org.umamo.ui.properties.MutedNote
import org.umamo.ui.properties.WorldPositionFields
import org.umamo.ui.properties.rememberFieldScrubPreview
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.transform.CoveredVertices
import org.umamo.ui.transform.ElementEditStage
import org.umamo.ui.transform.coveredMedian
import org.umamo.ui.transform.coveredVerticesOf
import org.umamo.ui.transform.rememberElementEditStage
import org.umamo.ui.viewport.LiveMeshPreview
import org.umamo.ui.viewport.LocalLiveMeshPreview
import org.umamo.ui.viewport.viewport2d.CoveredMedianMove
import org.umamo.ui.viewport.viewport2d.MeshDriveResult
import org.umamo.ui.viewport.viewport2d.coveredMedianMove
import org.umamo.ui.viewport.viewport2d.editOwnerGeometries
import org.umamo.ui.viewport.viewport2d.moveCoveredMedianAdjustable
import org.umamo.ui.viewport.viewport2d.previewCoveredMedianMove

/**
 * The move the panel's writes drive, captured at the first write and kept for the covered set it was built from:
 * every frame of a scrub drives the same capture, and a commit changes the model, which brings a fresh set.
 *
 * @param List<CoveredVertices> covered The covered vertices the move captures.
 * @param ElementEditStage stage Where they were captured.
 */
private class MedianWrites(
	private val covered: List<CoveredVertices>,
	private val stage: ElementEditStage,
) {
	private var captured: CoveredMedianMove? = null

	/**
	 * The move, captured on first use.
	 *
	 * @return CoveredMedianMove? The move, or null when nothing is covered.
	 */
	fun move(): CoveredMedianMove? = captured ?: coveredMedianMove(covered, stage).also { move -> captured = move }
}

/**
 * A median scrub's moved lattices, carried to the 2D viewport while the scrub is in flight: at rest their moved
 * points go to the cage alone, since shaping a rest lattice holds the art still; at the pose their pending forms go to
 * the cage and the renderer, since the art follows.  The scrub's end puts both back on what is committed.
 *
 * @param LiveMeshPreview? livePreview The window's live mesh preview, or null outside a shell.
 * @param Function renderSync The render-sync handle, read when used, or null outside a renderer.
 */
private class LatticeScrubPreview(
	private val livePreview: LiveMeshPreview?,
	private val renderSync: () -> PuppetRenderSync?,
) {
	private var previewing = false
	private var posed = false

	/**
	 * Shows [result]'s lattices as the scrub's current frame.
	 *
	 * @param MeshDriveResult result The scrub's drive, which moved at least one lattice.
	 */
	fun preview(result: MeshDriveResult) {
		val preview = livePreview ?: return
		previewing = true
		preview.showLattices(result.latticeWorld, result.latticeForms)

		if (result.latticeForms.isNotEmpty()) {
			posed = true
			renderSync()?.previewLattices(result.latticeForms)
		}
	}

	/** Ends the preview, returning the cage (and, after a posed scrub, the renderer) to what is committed. */
	fun end() {
		if (!previewing) {
			return
		}

		previewing = false
		livePreview?.clearLattices()

		if (posed) {
			posed = false
			renderSync()?.resync()
		}
	}
}

/**
 * The Item tab's Transform panel in Edit and Pose mode: where the covered vertices sit, "Vertex:" for one and
 * "Median:" for several, their mean across every mesh or lattice in the edit, measured from the world axes.  A typed
 * or scrubbed value moves them all together as a Grab would, one undo step the operation strip can adjust, and while
 * a modal transform runs, here or in another viewport, the numbers follow it.  With nothing covered it says so.
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
	val stage = rememberElementEditStage(session)
	val capture = remember(puppet, selection.owners, stage) { editOwnerGeometries(puppet, selection.owners, stage) }
	val covered =
		remember(capture, selection) {
			coveredVerticesOf(
				selection,
				capture.map { geometry -> geometry.worldGeometry },
				capture.associate { geometry -> geometry.owner to geometry.topology },
			)
		}
	// Snapshot reads, so a running transform recomposes the panel with each frame it publishes.
	val livePreview = LocalLiveMeshPreview.current
	val live = livePreview?.restByDrawable
	val liveLattices = livePreview?.latticeWorldByDeformer
	val median = remember(covered, live, liveLattices) { coveredMedian(covered, live.orEmpty(), liveLattices.orEmpty()) }
	val renderSync = rememberUpdatedState(LocalPuppetRenderSync.current)
	val latticeScrub = remember(livePreview) { LatticeScrubPreview(livePreview) { renderSync.value } }
	DisposableEffect(latticeScrub) {
		onDispose {
			latticeScrub.end()
		}
	}

	if (median == null) {
		MutedNote(Res.string.sidebar_item_nothing_selected)

		return
	}

	val writes = remember(covered, stage) { MedianWrites(covered, stage) }
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
			latticeScrub.end()
		},
		// A lattice's frame moves the cage (and, at the pose, the art through the pending forms); a mesh's rides the
		// renderer's model preview.
		onPreview = { axis, worldValue ->
			val previewed = writes.move()?.let { move -> session.previewCoveredMedianMove(move, axis, worldValue) }

			if (previewed != null && previewed.latticeWorld.isNotEmpty()) {
				latticeScrub.preview(previewed)
			} else {
				latticeScrub.end()
				scrub.preview(previewed?.folded)
			}
		},
		onScrubCancel = {
			scrub.end()
			latticeScrub.end()
		},
	)
}