package org.umamo.ui.viewport.viewport2d

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.EditorSession
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.edit.structure.selectableOf
import org.umamo.render.ViewportCamera
import org.umamo.render.pick.anchorsInBox
import org.umamo.render.pick.anchorsInCircle
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.viewport.ObjectPickCandidate
import org.umamo.ui.viewport.PuppetViewportService
import org.umamo.ui.viewport.asObjectPickCandidates
import org.umamo.ui.viewport.gizmo.HIT_RADIUS_PX
import org.umamo.ui.viewport.gizmo.MarqueeSelectController
import org.umamo.ui.viewport.gizmo.ObjectPickController
import org.umamo.ui.viewport.gizmo.objectMarquee
import org.umamo.ui.viewport.gizmo.resolveObjectBoxSelection
import org.umamo.ui.viewport.gizmo.screenToWorld
import org.umamo.ui.viewport.gizmo.selectableDeformerTargets
import kotlin.math.max
import kotlin.math.min

/**
 * The world point each selectable object is boxed and brushed by: the ONE source the Object overlay's
 * box and circle selection read.  That is each drawable's world centroid and each drawn warp lattice's
 * center (see [viewportSelectionAnchors]), snapshotted at the press that starts a select gesture (the pose
 * is fixed for the whole drag, so one snapshot serves every move) and tested against the region each
 * frame.  Objects that gain a viewport anchor later (a part, a rotation's pivot) join here.
 *
 * One instance per area: the marquee, the pick controller, and the armed box all refresh and read this
 * same holder, so a snapshot any of them takes is the one the others test against.
 *
 * @param Function takeSnapshot Reads the current anchors.
 */
internal class ObjectSelectionAnchors(
	private val takeSnapshot: () -> Map<SelectionTarget, FloatArray>,
) {
	/** Each object's world anchor as of the last [refresh]. */
	var anchors by mutableStateOf<Map<SelectionTarget, FloatArray>>(emptyMap())
		private set

	/** Takes a fresh snapshot, at the press that starts a select gesture. */
	fun refresh() {
		anchors = takeSnapshot()
	}
}

/**
 * The anchors box and circle select test in [areaId]: the centers of the warp lattices the area draws, and each
 * drawable's world centroid.
 *
 * @param PuppetViewportService service The render service.
 * @param String areaId The area.
 * @return Map<SelectionTarget, FloatArray> The anchors, the warps first.
 */
internal fun viewportSelectionAnchors(service: PuppetViewportService, areaId: String): Map<SelectionTarget, FloatArray> {
	val anchors = LinkedHashMap<SelectionTarget, FloatArray>()

	for ((deformerId, center) in service.latticeWorldCenters(areaId)) {
		anchors[SelectionTarget.Deformer(deformerId)] = center
	}

	for ((drawableId, centroid) in service.drawableWorldCentroids()) {
		anchors[SelectionTarget.Drawable(drawableId)] = centroid
	}

	return anchors
}

/**
 * How near a lattice's grid line a click must land to take its warp, in screen pixels: narrower than a control
 * point's [HIT_RADIUS_PX], so the art between a dense lattice's lines stays clickable.
 */
internal const val LATTICE_LINE_HIT_RADIUS_PX = 4f

/**
 * What a plain click at [position] in [areaId] takes: the first selectable warp whose drawn lattice is under it (a
 * control point within [HIT_RADIUS_PX], else a grid line within [LATTICE_LINE_HIT_RADIUS_PX]), and failing that, the
 * front-most drawable's art.  A locked warp is passed over, so the click reaches what is under its lattice.
 *
 * @param PuppetViewportService service The render service (the lattice and raster picks).
 * @param String areaId The clicked area.
 * @param Offset position The click in area-local pixels.
 * @param PuppetModel model The model the selectable flags live in.
 * @return SelectionTarget? The target, or null on empty canvas.
 */
internal fun viewportTopmostTarget(service: PuppetViewportService, areaId: String, position: Offset, model: PuppetModel): SelectionTarget? {
	val lattices = service.latticePicksAt(areaId, position.x, position.y, HIT_RADIUS_PX, LATTICE_LINE_HIT_RADIUS_PX)

	return selectableDeformerTargets(lattices, model).firstOrNull() ?: service.pickAt(areaId, position.x, position.y)?.let { drawableId -> SelectionTarget.Drawable(drawableId) }
}

/**
 * Everything an Alt click at [position] in [areaId] lists: the warps whose drawn lattice is under it, best first,
 * then every drawable whose art is, front to back.
 *
 * @param PuppetViewportService service The render service.
 * @param String areaId The clicked area.
 * @param Offset position The click in area-local pixels.
 * @return List<ObjectPickCandidate> The stack, unfiltered.
 */
internal fun viewportPickStack(service: PuppetViewportService, areaId: String, position: Offset): List<ObjectPickCandidate> {
	val lattices =
		service.latticePicksAt(areaId, position.x, position.y, HIT_RADIUS_PX, LATTICE_LINE_HIT_RADIUS_PX).map { deformerId ->
			ObjectPickCandidate(SelectionTarget.Deformer(deformerId))
		}

	return lattices + service.pickAllAt(areaId, position.x, position.y).asObjectPickCandidates()
}

/**
 * The drawable ids a selection holds, for the live GPU tint preview.
 *
 * @return Set<DrawableId> The selected drawables.
 */
internal fun Selection.selectedDrawableIds(): Set<DrawableId> = targets.mapNotNull { (it as? SelectionTarget.Drawable)?.id }.toSet()

/**
 * One Circle-select brush stamp: encloses the objects whose world anchor is within the brush, filters
 * out the unselectable ones, and adds them to (or removes them from) the working selection.  The radius is
 * in screen pixels, so it converts to world units by the zoom; the center unprojects to world space to
 * match the anchors.  A stamp that encloses nothing returns [working] itself.
 *
 * @param Selection working The stroke's working selection.
 * @param Boolean erasing True when the stroke removes what it paints.
 * @param Offset screenPos The brush center in area-local pixels.
 * @param Float radiusPx The brush radius in screen pixels.
 * @param ViewportCamera activeCamera The area camera.
 * @param IntSize size The area size in pixels.
 * @param Map<SelectionTarget, FloatArray> anchors Each object's world anchor.
 * @param PuppetModel model The model the selectable filter reads.
 * @return Selection The painted working selection.
 */
internal fun circleStampSelection(
	working: Selection,
	erasing: Boolean,
	screenPos: Offset,
	radiusPx: Float,
	activeCamera: ViewportCamera,
	size: IntSize,
	anchors: Map<SelectionTarget, FloatArray>,
	model: PuppetModel,
): Selection {
	val (worldX, worldZ) = screenToWorld(screenPos.x, screenPos.y, activeCamera, size)
	val worldRadius = radiusPx / activeCamera.zoom
	val enclosed = anchorsInCircle(anchors, worldX, worldZ, worldRadius).filter { target -> model.selectableOf(target) }

	if (enclosed.isEmpty()) {
		return working
	}

	return if (erasing) {
		val remaining = working.targets - enclosed.toSet()
		Selection(remaining, working.active?.takeIf { it in remaining } ?: remaining.lastOrNull())
	} else {
		Selection(working.targets + enclosed, enclosed.last())
	}
}

/**
 * A finished box drag's selection: every selectable object whose world anchor the box encloses
 * (Shift adds to the current selection).  Shared by the armed (Blender B) and un-armed paths.
 *
 * @param Selection current The selection the box applies to.
 * @param Offset start The box's press corner in area-local pixels.
 * @param Offset end The box's release corner.
 * @param Boolean additive True to add to [current] rather than replace it.
 * @param ViewportCamera activeCamera The area camera.
 * @param IntSize size The area size in pixels.
 * @param Map<SelectionTarget, FloatArray> anchors Each object's world anchor.
 * @param PuppetModel model The model the selectable filter reads.
 * @return Selection The new selection.
 */
internal fun boxSelection(
	current: Selection,
	start: Offset,
	end: Offset,
	additive: Boolean,
	activeCamera: ViewportCamera,
	size: IntSize,
	anchors: Map<SelectionTarget, FloatArray>,
	model: PuppetModel,
): Selection {
	val (worldStartX, worldStartZ) = screenToWorld(start.x, start.y, activeCamera, size)
	val (worldEndX, worldEndZ) = screenToWorld(end.x, end.y, activeCamera, size)
	val enclosed =
		anchorsInBox(anchors, min(worldStartX, worldEndX), min(worldStartZ, worldEndZ), max(worldStartX, worldEndX), max(worldStartZ, worldEndZ))
			.filter { target -> model.selectableOf(target) }

	return resolveObjectBoxSelection(current, enclosed, additive)
}

/**
 * This viewport's object marquee (see objectMarquee): a stamp paints objects by their anchors, a box encloses
 * them by their anchors, a stroke refreshes the anchors as it begins, and the live stroke tints on the GPU.  The
 * overlay holds one per area, so every callback reads the session and [anchors] when it runs.
 *
 * @param EditorSession session The session owning the object selection and the armed tool.
 * @param ObjectSelectionAnchors anchors The area's anchor holder.
 * @return MarqueeSelectController<Selection> The marquee.
 */
internal fun viewportObjectMarquee(session: EditorSession, anchors: ObjectSelectionAnchors): MarqueeSelectController<Selection> =
	objectMarquee(
		session = session,
		stampStroke = { working, erasing, center, radiusPx, stampCamera, stampSize ->
			circleStampSelection(working, erasing, center, radiusPx, stampCamera, stampSize, anchors.anchors, session.model.value)
		},
		applyBox = { start, end, additive, boxCamera, boxSize ->
			session.setSelection(boxSelection(session.selection.value, start, end, additive, boxCamera, boxSize, anchors.anchors, session.model.value))
		},
		onStrokeBegin = { anchors.refresh() },
		previewStroke = { stroke -> session.setPreviewSelection(stroke?.selectedDrawableIds()) },
	)

/**
 * The idle click-pick / box flow over whole objects (press rubber-bands, sub-threshold release picks,
 * Alt resolves the overlap stack, Shift+RightClick places the 2D cursor), bound to this viewport's lattice
 * and raster pickers ([viewportTopmostTarget], [viewportPickStack]) and its anchor
 * snapshot; see ObjectPickController.
 *
 * @param String areaId The overlay's area (the pick target).
 * @param EditorSession session The session owning the selection and the cursor.
 * @param PuppetViewportService service The render service (the lattice and raster picks).
 * @param MarqueeSelectController<Selection> marquee The area's marquee.
 * @param ObjectSelectionAnchors anchors The area's anchor holder.
 * @param Function onOverlapRequest Opens the overlap picker for an Alt click with 2+ candidates.
 * @return ObjectPickController The controller.
 */
internal fun viewportObjectPick(
	areaId: String,
	session: EditorSession,
	service: PuppetViewportService,
	marquee: MarqueeSelectController<Selection>,
	anchors: ObjectSelectionAnchors,
	onOverlapRequest: (Offset, List<ObjectPickCandidate>) -> Unit,
): ObjectPickController =
	ObjectPickController(
		session = session,
		marquee = marquee,
		pickTopmost = { position -> viewportTopmostTarget(service, areaId, position, session.model.value) },
		pickStack = { position -> viewportPickStack(service, areaId, position) },
		onOverlapRequest = onOverlapRequest,
		placeCursor = session::setCursor2d,
		onBoxBegin = { anchors.refresh() },
	)