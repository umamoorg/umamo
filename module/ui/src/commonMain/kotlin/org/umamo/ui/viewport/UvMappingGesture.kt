package org.umamo.ui.viewport

import org.umamo.edit.IndividualOriginScope
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.MeshTransforms
import org.umamo.edit.ModalCaptureSource
import org.umamo.edit.ModalTransformCapture
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.edit.TransformGestureParameters
import org.umamo.edit.TransformPivotMode
import org.umamo.edit.buildModalTransformCapture
import org.umamo.runtime.model.DrawableId

/*
 * The UV editor's texture-coordinate gesture as data, shared by its two authors: the Edit overlay's
 * element transform (the selected vertices, with proportional halos) and the Object overlay's mapping
 * move over a SOURCE LAYER (every vertex of each selected island as one object).  Both freeze
 * display-space coordinates into the shared ModalTransformCapture, drive them through applyOperator, and
 * commit back through the frame; what differs is only which vertices the capture covers and how
 * Individual Origins partitions them, which is why the builder below is the Object overlay's alone
 * while the class serves both.
 */

/**
 * The captured state of an in-flight UV transform: the shared [ModalTransformCapture] (its entries hold each
 * mesh's frozen display-space coordinates as their positions, plus the pivot groups, proportional halos, and
 * moved sets) together with the frame those coordinates were mapped in.  The texture-space sibling of the
 * Edit gesture, minus every deformer concern: UVs live in one flat display space, so there is no deformer
 * space mapping, no movement transfer, and no world/local split - the operator transforms the display
 * coordinates directly and the result converts back through the frame to the stored coordinates.
 *
 * The frame freezes here so the display-to-uv conversion at drive and commit always matches the space the
 * originals were mapped in (the shown surface can hop mid-gesture if the active drawable changes from
 * another area).
 *
 * @property ModalTransformCapture transform The shared gesture capture (entries, groups, anchor, halos, kind).
 * @property UvEditFrame frame The space the gesture is authored in (its texel size and the conversion
 *   back to the stored coordinates).
 */
internal class UvGesture(
	val transform: ModalTransformCapture,
	val frame: UvEditFrame,
)

/**
 * Freezes the Object-mode mapping move over a source layer: every selected island shown on the layer,
 * every vertex of it covered, so the gesture moves each island as one object over the fixed art.
 *
 * The art is the frame here - a layer's pixels cannot move within their own layer - so the object an
 * Object-mode gesture moves is the mesh's mapping, the mirror image of the page view where the art
 * moves and the mapping holds.  Individual Origins turns each island about its own centroid (the
 * viewport Object gizmo's scope, not the Edit overlay's connectivity islands); the Active Element
 * anchor is the active island's own centroid; the Cursor anchor is the UV cursor in display space.
 * Proportional editing never applies: there are no unselected vertices to weight.
 *
 * @param List<GizmoMeshGeometry> shownGeometries The islands drawn over the layer (every visible one).
 * @param Selection selection The session's object selection: the islands it names move.
 * @param TransformPivotMode pivotMode The session's pivot mode.
 * @param Pair<Float, Float>? cursorDisplay The UV cursor in display space, or null when unplaced.
 * @param UvEditFrame frame The layer's frame, frozen onto the gesture for the commit.
 * @param MeshOperatorKind operatorKind The latched operator.
 * @return UvGesture? The frozen gesture, or null when nothing selected is shown on the layer.
 */
internal fun buildUvMappingGesture(
	shownGeometries: List<GizmoMeshGeometry>,
	selection: Selection,
	pivotMode: TransformPivotMode,
	cursorDisplay: Pair<Float, Float>?,
	frame: UvEditFrame,
	operatorKind: MeshOperatorKind,
): UvGesture? {
	val selectedIds = selection.targets.mapNotNullTo(HashSet()) { target -> (target as? SelectionTarget.Drawable)?.id }
	if (selectedIds.isEmpty()) {
		return null
	}
	val sources =
		shownGeometries.mapNotNull { geometry ->
			if (geometry.drawableId !in selectedIds || geometry.positions.size < 2) {
				return@mapNotNull null
			}
			// Frozen (copied) here: the builder shares the array by reference for the whole drag.
			ModalCaptureSource(geometry.drawableId, geometry.positions.copyOf(), geometry.indices, allVertexIndices(geometry.positions))
		}
	val activeId = (selection.active as? SelectionTarget.Drawable)?.id
	val activeAnchor =
		sources
			.firstOrNull { source -> source.drawableId == activeId }
			?.let { source -> MeshTransforms.medianPivot(source.positions, source.coveredIndices) }
	val transform =
		buildModalTransformCapture(
			sources = sources,
			pivotMode = pivotMode,
			individualOriginScope = IndividualOriginScope.WholeMesh,
			operatorKind = operatorKind,
			activeAnchor = activeAnchor,
			cursorAnchor = cursorDisplay,
		) ?: return null
	return UvGesture(transform, frame)
}

/**
 * One pointer frame of the mapping move: every captured island's display coordinates under
 * [parameters], with no proportional halo (Object mode moves whole islands).
 *
 * @param ModalTransformCapture transform The frozen capture.
 * @param MeshOperatorKind operator The active operator.
 * @param TransformGestureParameters parameters The delta / factors / angle this frame resolved.
 * @return Map<DrawableId, FloatArray> Each moving island's transformed display coordinates, in capture order.
 */
internal fun mappingPreview(
	transform: ModalTransformCapture,
	operator: MeshOperatorKind,
	parameters: TransformGestureParameters,
): Map<DrawableId, FloatArray> {
	val preview = LinkedHashMap<DrawableId, FloatArray>(transform.entries.size)
	for (entry in transform.entries) {
		preview[entry.drawableId] = applyOperator(operator, entry.positions, entry.groups, parameters, emptyMap())
	}
	return preview
}