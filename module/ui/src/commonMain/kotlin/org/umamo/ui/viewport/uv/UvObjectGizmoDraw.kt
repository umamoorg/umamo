package org.umamo.ui.viewport.uv

import androidx.compose.runtime.State
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.MeshSelectMode
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.edit.TransformAxisConstraint
import org.umamo.render.ViewportCamera
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.theme.SelectionOverlayStyle
import org.umamo.ui.theme.drawRubberBand
import org.umamo.ui.viewport.ViewportOverlayColors
import org.umamo.ui.viewport.gizmo.GizmoMeshGeometry
import org.umamo.ui.viewport.gizmo.MarqueeSelectController
import org.umamo.ui.viewport.gizmo.ModalGestureState
import org.umamo.ui.viewport.gizmo.buildHighlightSets
import org.umamo.ui.viewport.gizmo.drawMeshWireframe
import org.umamo.ui.viewport.gizmo.drawOwnedModalTransformHud

/**
 * Each drawable's atlas tile, for the per-island warning and pin styling.
 *
 * @param PuppetModel model The committed model.
 * @return Map<DrawableId, AtlasTileId> The tile of every drawable bound to one.
 */
internal fun tileIdsByDrawable(model: PuppetModel): Map<DrawableId, AtlasTileId> =
	model.drawables.mapNotNull { drawable -> drawable.atlasTileId?.let { tileId -> drawable.id to tileId } }.toMap()

/**
 * The pinned tiles, whose islands draw their edges in the pinned color.
 *
 * @param PuppetModel model The committed model.
 * @return Set<AtlasTileId> The pinned tiles.
 */
internal fun pinnedTileIdsOf(model: PuppetModel): Set<AtlasTileId> = model.atlas.tiles.filter { tile -> tile.pinned }.mapTo(HashSet()) { tile -> tile.id }

/**
 * Draws every shown island in the Blender object-overlay style.  Islands paint back-to-front by the rest
 * front rank, so the front-most island's wireframe draws last - the painted stacking matches the pick order.
 * Styling is per-island palette substitution: the idle palette IS the dim style; a selected island fills
 * and outlines with the selected colors; the active island keeps the selected fill under the active-green
 * outline (faceActive is deliberately never a fill - it would blank the art).  The islands of every
 * colliding tile - the triangles ARE the sampled region, so they are the honest thing to flag - draw their
 * edges in the warning color, movers and bystanders alike; a pinned tile's islands draw theirs in the
 * pinned color, the warning winning while a collision is live (a warning is transient, a pin is state).
 * During a placement gesture a moving island draws from the live preview.
 *
 * @param List<GizmoMeshGeometry> geometries The shown islands' display geometry.
 * @param Map<DrawableId, Float> frontRankById The rest-pose front rank (larger is nearer).
 * @param Selection selection The object selection.
 * @param MeshSelectMode selectMode The mesh select mode the wireframe draws in.
 * @param Map<DrawableId, AtlasTileId> tileByDrawableId Each drawable's tile.
 * @param Set<AtlasTileId> pinnedTileIds The pinned tiles.
 * @param Set<AtlasTileId> collidingTileIds Every tile in a live placement collision.
 * @param Map<DrawableId, FloatArray>? preview The moving islands' live display positions, or null.
 * @param ViewportOverlayColors colors The settings-backed mesh palette.
 * @param ViewportCamera camera The frame camera.
 * @param IntSize size The area size in pixels.
 */
internal fun DrawScope.drawUvObjectIslands(
	geometries: List<GizmoMeshGeometry>,
	frontRankById: Map<DrawableId, Float>,
	selection: Selection,
	selectMode: MeshSelectMode,
	tileByDrawableId: Map<DrawableId, AtlasTileId>,
	pinnedTileIds: Set<AtlasTileId>,
	collidingTileIds: Set<AtlasTileId>,
	preview: Map<DrawableId, FloatArray>?,
	colors: ViewportOverlayColors,
	camera: ViewportCamera,
	size: IntSize,
) {
	val selectedIds = selection.targets.mapNotNull { target -> (target as? SelectionTarget.Drawable)?.id }.toSet()
	val activeId = (selection.active as? SelectionTarget.Drawable)?.id
	val paintOrdered = geometries.sortedBy { geometry -> frontRankById[geometry.drawableId] ?: 0f }
	for (geometry in paintOrdered) {
		val styled =
			when {
				geometry.drawableId == activeId ->
					colors.copy(faceIdle = colors.faceSelected, edgeIdle = colors.edgeActive)
				geometry.drawableId in selectedIds ->
					colors.copy(faceIdle = colors.faceSelected, edgeIdle = colors.edgeSelected)
				else -> colors
			}
		val tileId = tileByDrawableId[geometry.drawableId]
		val islandColors =
			when {
				collidingTileIds.isNotEmpty() && tileId in collidingTileIds ->
					styled.copy(edgeIdle = colors.warning, edgeSelected = colors.warning, edgeActive = colors.warning)
				tileId in pinnedTileIds ->
					styled.copy(edgeIdle = colors.pinnedPlacement, edgeSelected = colors.pinnedPlacement, edgeActive = colors.pinnedPlacement)
				else -> styled
			}
		drawMeshWireframe(
			positions = preview?.get(geometry.drawableId) ?: geometry.positions,
			indices = geometry.indices,
			edges = geometry.edges,
			highlight = buildHighlightSets(emptySet(), null, selectMode, geometry.indices),
			selectMode = selectMode,
			colors = islandColors,
			camera = camera,
			size = size,
			objectOverlay = true,
		)
	}
}

/**
 * Draws the UV Object overlay's gesture chrome: the rubber band and the modal HUD (axis line, pivot dash,
 * drawn cursor), shared chrome with the other gizmo overlays.  The pointer, the marquee corners, the
 * capture, and the axis constraint are read HERE, in the draw phase, never while the overlay composes.
 * Only the initiating area draws the HUD - the capture exists solely in the overlay whose area the
 * operator latch names.
 *
 * @param MarqueeSelectController<Selection> marquee The area's box machinery.
 * @param ModalGestureState<PlacementGesture> gesture The area's placement gesture state.
 * @param Boolean owned Whether the latched UV operator is this area's.
 * @param State axisConstraint The session's axis constraint.
 * @param ViewportCamera camera The frame camera.
 * @param IntSize size The area size in pixels.
 * @param SelectionOverlayStyle style The marching-ants style for the band.
 * @param Color lineColor The HUD's line color.
 */
internal fun DrawScope.drawUvObjectGizmoChrome(
	marquee: MarqueeSelectController<Selection>,
	gesture: ModalGestureState<PlacementGesture>,
	owned: Boolean,
	axisConstraint: State<TransformAxisConstraint?>,
	camera: ViewportCamera,
	size: IntSize,
	style: SelectionOverlayStyle,
	lineColor: Color,
) {
	drawRubberBand(marquee.boxStart, marquee.boxCurrent, style)
	drawOwnedModalTransformHud(
		owned = owned,
		pivotWorld = gesture.capture?.transform?.anchor,
		gesture = gesture,
		axisConstraint = axisConstraint,
		camera = camera,
		size = size,
		lineColor = lineColor,
	)
}