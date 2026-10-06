package org.umamo.ui.viewport.uv

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.umamo.edit.ActiveOperator
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.NoticePlacement
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.edit.setAtlasPlacements
import org.umamo.format.art.LayerBounds
import org.umamo.render.ViewportCamera
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.applyUvAffine
import org.umamo.ui.model.SessionAtlasPages
import org.umamo.ui.viewport.gizmo.GizmoMeshGeometry
import org.umamo.ui.viewport.gizmo.TransformGestureFrame

/**
 * One tile crop drawn at a committed placement while the page resolver is still composing the pixels
 * for that commit.
 *
 * @property ImageBitmap    crop      The trim's pixels.
 * @property LayerBounds    trim      The trim the crop covers, raster-local.
 * @property AtlasPlacement placement Where the tile was committed to.
 */
internal class GhostCrop(
	val crop: ImageBitmap,
	val trim: LayerBounds,
	val placement: AtlasPlacement,
)

/**
 * A committed placement move's crops, drawn at their new spots until the resolver publishes pages for
 * the committed atlas - so the islands never sit over the OLD pixels for the derivation's duration.
 *
 * @property PuppetAtlas atlas      The atlas instance the commit published (the resolver keys by identity).
 * @property Int         pageHeight The page height, for the display flip.
 * @property List        crops      The crops and where they were committed.
 */
internal class PlacementGhost(
	val atlas: PuppetAtlas,
	val pageHeight: Int,
	val crops: List<GhostCrop>,
)

/**
 * The ghost to draw now: a committed move's crops stay up while the committed atlas is the one they were
 * published for and the page resolver has not yet bound pages to it.  Both tests are by IDENTITY - the
 * resolver keys its pages by the atlas instance, and an undo restores the very instance a snapshot holds.
 *
 * @param PlacementGhost? ghost The last published ghost, or null.
 * @param PuppetAtlas committedAtlas The session's committed atlas.
 * @param PuppetAtlas? bindingAtlas The atlas the resolver's current pages belong to, or null without one.
 * @return PlacementGhost? The ghost to draw, or null when there is none or its pages have landed.
 */
internal fun activePlacementGhost(ghost: PlacementGhost?, committedAtlas: PuppetAtlas, bindingAtlas: PuppetAtlas?): PlacementGhost? =
	ghost?.takeIf { pending -> pending.atlas === committedAtlas && bindingAtlas !== pending.atlas }

/**
 * The commit side of the UV Object overlay's placement gesture: a UV operator latched in its area in Object
 * mode (G / S / R) moves the selected drawables' ART on the shown atlas page - each tile's pixels and every
 * drawable over it together, so the vertex-to-art mapping never changes.  The capture freezes the movers
 * (their placements, trims, mesh reserves, and crops), the page's bystanders, and the moving islands'
 * display positions; each drive evaluates the placements through the shared operator parameters
 * (UvPlacementGesture.kt) and publishes the host's readout; confirm commits ONE undo step through
 * setAtlasPlacements, leaves the crops drawn at their new spots until the session's page resolver
 * recomposes the pixels, and registers the gesture on the operation settings strip.  Nothing is pushed to
 * the puppet renderer: a placement move is invisible in the 2D viewport by construction.
 *
 * The capture builds off the UI thread (it decodes rasters and wraps crops), so [begin] suspends; a latch
 * that clears or changes while it builds begins nothing.
 *
 * @param String areaId The UV editor area the overlay covers; only an operator latched here drives.
 * @param EditorSession session The session owning the object selection, the model, and the UV latch.
 * @param State<SessionAtlasPages?> atlasPages The session's page resolver, or null without one (no ghost is
 *   ever published then, since no pages would come to retire it).
 * @param State<MutableState<PlacementDragStatus?>> dragStatus The host's drag readout for this area.
 */
internal class UvPlacementModalTransform(
	areaId: String,
	session: EditorSession,
	private val atlasPages: State<SessionAtlasPages?>,
	private val dragStatus: State<MutableState<PlacementDragStatus?>>,
) : UvModalTransform<PlacementGesture>(areaId, session) {
	/** The last committed move's crops, drawn while [activePlacementGhost] says their pages are still pending. */
	var ghost by mutableStateOf<PlacementGhost?>(null)
		private set

	/** Takes the ghost down once its pages have landed (or the atlas it was published for is gone). */
	fun dismissGhost() {
		ghost = null
	}

	/**
	 * Starts the placement gesture as a UV operator latches in this area.  Over a source layer there is no
	 * page to move on, so the latch drops with a notice.  Otherwise the capture builds off-thread from the
	 * values read before it starts; a latch that cleared or changed while it built (Escape, a mode switch)
	 * begins nothing, and a build that found nothing movable drops the latch with its own notice.  A latch
	 * cleared and made again with the same operator in the same area before the overlay recomposes still
	 * begins: the latch effect keys on the operator by value and so does not restart for it, which leaves
	 * this build as the only one that can begin it.
	 *
	 * @param ActiveOperator operator The latched operator.
	 * @param UvPlacementSurface? surface The shown page and the source-art store, or null over a source layer.
	 * @param List<GizmoMeshGeometry> shownGeometries The shown islands' display geometry.
	 * @param Selection selection The object selection.
	 * @param UvEditFrame frame The shown surface's frame (the UV cursor's display position).
	 */
	suspend fun begin(
		operator: ActiveOperator,
		surface: UvPlacementSurface?,
		shownGeometries: List<GizmoMeshGeometry>,
		selection: Selection,
		frame: UvEditFrame,
	) {
		if (surface == null) {
			session.emitNotice("notice.uv.placement.pageViewOnly", NoticePlacement.NearCursor)
			session.clearUvOperator()
			return
		}
		val model = session.model.value
		val pivotMode = session.pivotMode.value
		val activeDrawableId = (selection.active as? SelectionTarget.Drawable)?.id
		val cursorDisplay = uvCursorDisplay(session, frame)
		val build =
			withContext(Dispatchers.Default) {
				buildPlacementGesture(model, surface, selection, shownGeometries, pivotMode, activeDrawableId, cursorDisplay, operator.kind)
			}
		// By value, not identity: see the docblock for the same-operator re-latch this lets begin.
		if (session.activeUvOperator.value != operator) {
			return
		}
		when (build) {
			PlacementGestureBuild.NotOnPage -> {
				session.emitNotice("notice.uv.placement.notOnPage", NoticePlacement.NearCursor)
				session.clearUvOperator()
			}

			PlacementGestureBuild.NotDerivable -> {
				session.emitNotice("notice.uv.placement.notDerivable", NoticePlacement.NearCursor)
				session.clearUvOperator()
			}

			// The gesture measures from wherever the pointer is as the capture lands.
			is PlacementGestureBuild.Ready -> gesture.begin(build.gesture, gesture.lastPointer)
		}
	}

	/**
	 * Tears the gesture down and clears the host's readout.  Clearing it whether or not a gesture was live
	 * is safe: only this area's overlay writes this area's readout, and only while it holds a capture.
	 *
	 * @return Boolean True when this area owned a gesture.
	 */
	override fun end(): Boolean {
		val ended = super.end()
		dragStatus.value.value = null
		return ended
	}

	/**
	 * Confirms the in-flight placement gesture: commits every mover whose placement changed as ONE undo
	 * step under the operator's own label, publishes the landing, registers the gesture on the operation
	 * settings strip (an adjustment re-evaluates the same frozen gesture over that step), then clears the
	 * operator.  No preview was ever pushed to the renderer, so there is nothing to resync.
	 */
	override fun confirm() {
		val gestureData = gesture.capture
		val result = gestureData?.result
		if (gestureData != null && result != null) {
			val changed = changedPlacements(gestureData.movers, result)
			if (changed.isNotEmpty()) {
				session.setAtlasPlacements(changed, gestureData.transform.operatorKind)
				publishLanding(gestureData, result)
				registerPlacementAdjustment(session, areaId, gestureData, result) { landed -> publishLanding(gestureData, landed) }
			}
		}
		session.clearUvOperator()
	}

	/**
	 * Drives one pointer frame: the shared operator parameters over the capture's anchor, evaluated into a
	 * placement per mover and a display affine per moving island, with the readout published to the host.
	 *
	 * @param MeshOperatorKind operator The latched operator.
	 * @param Offset virtualPointer The wrap-continuous pointer.
	 * @param ViewportCamera camera The area camera.
	 * @param IntSize size The area size in pixels.
	 * @return Boolean False before the capture has landed (it builds off-thread).
	 */
	override fun drive(operator: MeshOperatorKind, virtualPointer: Offset, camera: ViewportCamera, size: IntSize): Boolean {
		val start = gesture.gestureStart ?: return false
		val gestureData = gesture.capture ?: return false
		val constraint = session.axisConstraint.value
		val pointerFrame = TransformGestureFrame(gestureData.transform.anchor, start, virtualPointer, constraint, camera, size)
		val parameters = placementGestureParameters(operator, pointerFrame, gestureData.transform.rotationTracker)
		val result =
			evaluatePlacementDrag(
				operatorKind = operator,
				parameters = parameters,
				movers = gestureData.movers,
				bystanders = gestureData.bystanders,
				occupancy = gestureData.occupancy,
				pageWidth = gestureData.pageWidth,
				pageHeight = gestureData.pageHeight,
				extrude = gestureData.extrude,
			)
		gestureData.result = result
		val preview = LinkedHashMap<DrawableId, FloatArray>()
		for ((drawableId, frozen) in gestureData.frozenPositionsByDrawable) {
			val tileId = gestureData.tileByDrawable[drawableId] ?: continue
			val affine = result.displayAffineByTile[tileId] ?: continue
			preview[drawableId] = applyUvAffine(frozen, affine)
		}
		gesture.preview = preview
		dragStatus.value.value = result.status
		return true
	}

	/**
	 * What a landed evaluation shows: the crops as ghosts at their committed spots until the resolver's
	 * pages land, and a notice when the result collides or spills.  Shared by the confirm and by every
	 * adjustment from the operation settings strip.
	 *
	 * @param PlacementGesture gestureData The frozen gesture.
	 * @param PlacementDragResult result The evaluation that landed.
	 */
	private fun publishLanding(gestureData: PlacementGesture, result: PlacementDragResult) {
		val crops =
			gestureData.movers.mapNotNull { mover ->
				val crop = mover.crop ?: return@mapNotNull null
				GhostCrop(crop, mover.trim, result.placementByTile.getValue(mover.tileId))
			}
		if (atlasPages.value != null && crops.isNotEmpty()) {
			ghost = PlacementGhost(session.model.value.atlas, gestureData.pageHeight, crops)
		}
		if (result.overlappingTileIds.isNotEmpty()) {
			session.emitNotice("notice.uv.placement.overlap", NoticePlacement.NearCursor)
		} else if (result.offPageTileIds.isNotEmpty()) {
			session.emitNotice("notice.uv.placement.offPage", NoticePlacement.NearCursor)
		}
	}
}