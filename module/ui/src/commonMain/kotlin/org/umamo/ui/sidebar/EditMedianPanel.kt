package org.umamo.ui.sidebar

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.originRelativeX
import org.umamo.runtime.model.originRelativeZ
import org.umamo.runtime.model.worldXFromOriginRelative
import org.umamo.runtime.model.worldZFromOriginRelative
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.field.FieldStack
import org.umamo.ui.kit.field.NumberField
import org.umamo.ui.kit.field.PropertyFieldRow
import org.umamo.ui.properties.UNBOUNDED_RANGE
import org.umamo.ui.properties.rememberFieldScrubPreview
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.transform.CoveredVertices
import org.umamo.ui.transform.DrawableWorldGeometry
import org.umamo.ui.transform.captureDrawableWorlds
import org.umamo.ui.transform.coveredMedian
import org.umamo.ui.transform.coveredMedianIn
import org.umamo.ui.transform.coveredVerticesOf
import org.umamo.ui.viewport.LocalLiveMeshPreview
import org.umamo.ui.viewport.viewport2d.CoveredMedianMove
import org.umamo.ui.viewport.viewport2d.MedianAxis
import org.umamo.ui.viewport.viewport2d.coveredMedianMove
import org.umamo.ui.viewport.viewport2d.moveCoveredMedianAdjustable
import org.umamo.ui.viewport.viewport2d.previewCoveredMedianMove

/**
 * The meshes in the edit as the panel captured them: their world geometry at the neutral pose Edit mode is
 * pinned to, and the triangle indices that geometry belongs to.
 *
 * @property List<DrawableWorldGeometry> geometries Each mesh's captured geometry, skipping one with no mapping.
 * @property Map<DrawableId, IntArray> triangleIndicesById Each captured mesh's triangle indices.
 */
private class EditMeshCapture(
	val geometries: List<DrawableWorldGeometry>,
	val triangleIndicesById: Map<DrawableId, IntArray>,
)

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
	val capture = remember(puppet, selection.drawableIds) { captureEditMeshes(puppet, selection.drawableIds) }
	val covered = remember(capture, selection) { coveredVerticesOf(selection, capture.geometries, capture.triangleIndicesById) }
	// A snapshot read, so a running transform recomposes the panel with each frame it publishes.
	val live = LocalLiveMeshPreview.current?.restByDrawable
	val median = remember(covered, live) { live?.let { restByDrawable -> coveredMedianIn(covered, restByDrawable) } ?: coveredMedian(covered) }

	if (median == null) {
		PanelHint(Res.string.sidebar_item_nothing_selected)

		return
	}

	val writes = remember(covered) { MedianWrites(covered) }
	val scrub = rememberFieldScrubPreview(enabled = true)
	val areaId = context.areaId

	/**
	 * Shows one frame of a scrub that would put the median's [axis] coordinate on [worldValue].
	 *
	 * @param MedianAxis axis The axis scrubbed.
	 * @param Float worldValue The world coordinate.
	 */
	fun previewAt(axis: MedianAxis, worldValue: Float) {
		scrub.preview(writes.move()?.let { move -> session.previewCoveredMedianMove(move, axis, worldValue) })
	}

	/**
	 * Puts the median's [axis] coordinate on [worldValue]: a typed value, a chevron step, or a scrub's release.
	 *
	 * @param MedianAxis axis The axis typed on.
	 * @param Float worldValue The world coordinate.
	 */
	fun commitAt(axis: MedianAxis, worldValue: Float) {
		scrub.commit { writes.move()?.let { move -> session.moveCoveredMedianAdjustable(move, axis, worldValue, areaId) } }
	}

	Text(
		text = stringResource(if (median.vertexCount == 1) Res.string.sidebar_edit_vertex else Res.string.sidebar_edit_median),
		style = LocalUmamoTypography.current.labelMedium,
		color = LocalUmamoColors.current.text,
	)
	FieldStack(
		listOf(
			{ position ->
				PropertyFieldRow(
					label = stringResource(Res.string.properties_field_position_x),
					description = stringResource(Res.string.sidebar_median_x_description),
				) {
					NumberField(
						value = puppet.originRelativeX(median.worldX),
						onValueChange = { newX -> commitAt(MedianAxis.X, puppet.worldXFromOriginRelative(newX)) },
						onPreview = { newX -> previewAt(MedianAxis.X, puppet.worldXFromOriginRelative(newX)) },
						onScrubCancel = { scrub.end() },
						modifier = Modifier.fillMaxWidth(),
						range = UNBOUNDED_RANGE,
						decimals = 1,
						stackPosition = position,
					)
				}
			},
			{ position ->
				PropertyFieldRow(
					label = stringResource(Res.string.properties_field_position_z),
					description = stringResource(Res.string.sidebar_median_z_description),
				) {
					NumberField(
						value = puppet.originRelativeZ(median.worldZ),
						onValueChange = { newZ -> commitAt(MedianAxis.Z, puppet.worldZFromOriginRelative(newZ)) },
						onPreview = { newZ -> previewAt(MedianAxis.Z, puppet.worldZFromOriginRelative(newZ)) },
						onScrubCancel = { scrub.end() },
						modifier = Modifier.fillMaxWidth(),
						range = UNBOUNDED_RANGE,
						decimals = 1,
						stackPosition = position,
					)
				}
			},
		),
	)
}

/**
 * Captures the meshes in the edit at the neutral pose Edit mode is pinned to, through one resolver, so the
 * deformer chain bakes once for all of them.
 *
 * @param PuppetModel model The committed model.
 * @param List<DrawableId> drawableIds The meshes in the edit.
 * @return EditMeshCapture The capture.
 */
private fun captureEditMeshes(model: PuppetModel, drawableIds: List<DrawableId>): EditMeshCapture {
	val geometries = captureDrawableWorlds(model, emptyMap(), drawableIds)
	val meshById = model.drawables.associate { drawable -> drawable.id to drawable.mesh }
	val triangleIndicesById = HashMap<DrawableId, IntArray>(geometries.size)

	for (geometry in geometries) {
		meshById[geometry.drawableId]?.let { mesh -> triangleIndicesById[geometry.drawableId] = mesh.indices }
	}

	return EditMeshCapture(geometries, triangleIndicesById)
}