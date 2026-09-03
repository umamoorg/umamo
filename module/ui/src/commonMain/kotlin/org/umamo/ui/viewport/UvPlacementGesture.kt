package org.umamo.ui.viewport

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import org.umamo.edit.IndividualOriginScope
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.MeshTransforms
import org.umamo.edit.ModalCaptureSource
import org.umamo.edit.ModalTransformCapture
import org.umamo.edit.RotationAngleTracker
import org.umamo.edit.Selection
import org.umamo.edit.TransformAxisConstraint
import org.umamo.edit.TransformPivotMode
import org.umamo.edit.buildModalTransformCapture
import org.umamo.edit.composedWith
import org.umamo.edit.localAxisScaleAboutAffine
import org.umamo.edit.placementDragTileIds
import org.umamo.edit.rotationAboutAffine
import org.umamo.edit.scaleAboutAffine
import org.umamo.edit.translationAffine
import org.umamo.format.art.AlphaContour
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.analyzeAlpha
import org.umamo.format.atlas.AtlasPackReserve
import org.umamo.render.DecodedImage
import org.umamo.render.PageOccupancy
import org.umamo.render.PixelRect
import org.umamo.render.PlacementFootprint
import org.umamo.render.SampledRegion
import org.umamo.render.SourceArtRasters
import org.umamo.render.TileMeshMask
import org.umamo.render.TileOpaqueMask
import org.umamo.render.meshMaskOf
import org.umamo.render.meshReserveByTile
import org.umamo.render.placementFootprint
import org.umamo.render.sampledRegionHitsMask
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.composeAffine
import org.umamo.runtime.model.placementAffine
import org.umamo.ui.graphics.RgbaAlphaType
import org.umamo.ui.graphics.rgbaToImageBitmap
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/*
 * The placement gesture's pointer-side bookkeeping: what a UV editor's Object overlay freezes when a
 * placement operator latches, how one pointer frame's parameters turn into a placement per tile, and
 * the readout the HUD shows.  Everything here is plain data and pure functions; the commit is the
 * session's (setAtlasPlacements) and the algebra is :edit's (composedWith and the affine builders).
 *
 * Two frames meet here.  The overlay measures in DISPLAY space - texels with y up, what the wireframes
 * and the camera speak - and a placement lives in PAGE space, y down.  The two differ by the flip
 * F(x, y) = (x, h - y), its own inverse, so a display affine G becomes the page affine F . G . F and a
 * page affine comes back the same way.  Every conversion goes through the two helpers below; nothing
 * else reasons about the sign of y.
 *
 * A collision is a mesh sampling paint (AtlasPlacementDrag.kt), exact on both sides: each tile's
 * sampled region is the rasterized coverage of its triangles, each mover's painted region is its opaque
 * mask, the bystanders' paint is the shown page's own alpha with the movers' old spots read as empty,
 * and every pointer frame tests the movers' triangles against that page and the bystanders' triangles
 * against the movers' masks.
 */

/**
 * What a UV editor showing an ATLAS PAGE hands its Object overlay so a placement gesture can run there;
 * absent over a source-layer view, where a placement has no page to move on.
 *
 * @property Int              pageWidth  The shown page's width in texels.
 * @property Int              pageHeight The shown page's height in texels.
 * @property SourceArtRasters artRasters The document's source-art pixels (the movers' crops and masks).
 * @property DecodedImage?    pageImage  The shown page's decoded pixels - the bystanders' paint - or
 *   null before the page has loaded, in which case only movers test against each other.
 */
internal class UvPlacementSurface(
	val pageWidth: Int,
	val pageHeight: Int,
	val artRasters: SourceArtRasters,
	val pageImage: DecodedImage?,
)

/**
 * The drag's live readout for the HUD badge.
 *
 * @property MeshOperatorKind operatorKind The running operator.
 * @property Int   deltaX       The snapped horizontal move in page pixels (Grab).
 * @property Int   deltaY       The snapped vertical move in page pixels, positive down (Grab).
 * @property Float angleDegrees The page-space rotation applied so far (Rotate).
 * @property Float factorX      The factor along the tile's x axis (Scale).
 * @property Float factorY      The factor along the tile's y axis (Scale).
 * @property Int   overlapCount How many moving tiles currently collide with another tile.
 * @property Boolean offPage    Whether any moving tile's opaque pixels spill past the page edge.
 */
internal class PlacementDragStatus(
	val operatorKind: MeshOperatorKind,
	val deltaX: Int,
	val deltaY: Int,
	val angleDegrees: Float,
	val factorX: Float,
	val factorY: Float,
	val overlapCount: Int,
	val offPage: Boolean,
)

/**
 * One tile a placement gesture moves, frozen at latch.
 *
 * @property AtlasTileId       tileId        The tile.
 * @property AtlasPlacement    placement     Where it sat when the gesture began.
 * @property LayerBounds       trim          Its opaque bounds, raster-local (what the composer draws).
 * @property AtlasPackReserve? reserve       Its mesh reach, raster-local (the pivot's center and the spot it vacates), or null when none is measurable.
 * @property TileMeshMask?     meshMask      The coverage of its triangles - its sampled region - or null when none is measurable.
 * @property TileOpaqueMask?   mask          Its opaque texels - its painted region - or null when the art could not be read.
 * @property List<AlphaContour> contours     The outlines of its opaque region, raster-local, for the painter chrome.
 * @property Float             pivotDisplayX The pivot it turns and scales about, display x.
 * @property Float             pivotDisplayY The pivot's display y.
 * @property ImageBitmap?      crop          Its trim's pixels for the drag preview, or null when they could not be wrapped.
 */
internal class PlacementMover(
	val tileId: AtlasTileId,
	val placement: AtlasPlacement,
	val trim: LayerBounds,
	val reserve: AtlasPackReserve?,
	val meshMask: TileMeshMask?,
	val mask: TileOpaqueMask?,
	val contours: List<AlphaContour>,
	val pivotDisplayX: Float,
	val pivotDisplayY: Float,
	val crop: ImageBitmap?,
)

/**
 * One placed tile on the page that is NOT moving: what the movers may collide with.
 *
 * @property AtlasTileId        tileId        The tile.
 * @property AtlasPlacement     placement     Where it sits.
 * @property SampledRegion?     sampled       The coverage of its triangles on the page, or null when it has no measurable mesh.
 * @property PlacementFootprint paintedBounds The bounds its paint can occupy (its whole tile through
 *   the placement, grown by the extrusion band), for attributing a painted pixel the page reports.
 */
internal class PlacementBystander(
	val tileId: AtlasTileId,
	val placement: AtlasPlacement,
	val sampled: SampledRegion?,
	val paintedBounds: PlacementFootprint,
)

/**
 * The parameters one pointer frame yields, in display units, exactly as the shared operator math
 * derives them for the mesh overlays.
 *
 * @property Float deltaDisplayX   The Grab translation's display x.
 * @property Float deltaDisplayY   The Grab translation's display y (y up).
 * @property Float factorX         The Scale factor along display x.
 * @property Float factorY         The Scale factor along display y.
 * @property Float rotationRadians The Rotate angle in the display frame's sense.
 */
internal class PlacementGestureParameters(
	val deltaDisplayX: Float,
	val deltaDisplayY: Float,
	val factorX: Float,
	val factorY: Float,
	val rotationRadians: Float,
)

/**
 * One pointer frame's evaluation over every mover.
 *
 * @property Map placementByTile     Each mover's placement under the gesture.
 * @property Map displayAffineByTile The display-space affine each mover's islands follow.
 * @property Set overlappingTileIds  Every tile (mover or bystander) in a collision.
 * @property Set samplingTileIds     The colliding tiles whose MESH reads another tile's paint.
 * @property Set paintingTileIds     The colliding tiles whose PAINT lies under another tile's mesh.
 * @property Set offPageTileIds      Every mover whose opaque pixels spill past the page edge.
 * @property PlacementDragStatus status The HUD readout.
 */
internal class PlacementDragResult(
	val placementByTile: Map<AtlasTileId, AtlasPlacement>,
	val displayAffineByTile: Map<AtlasTileId, FloatArray>,
	val overlappingTileIds: Set<AtlasTileId>,
	val samplingTileIds: Set<AtlasTileId>,
	val paintingTileIds: Set<AtlasTileId>,
	val offPageTileIds: Set<AtlasTileId>,
	val status: PlacementDragStatus,
)

/**
 * The captured state of an in-flight placement gesture.
 *
 * @property ModalTransformCapture transform The shared capture over the moving islands: its anchor is
 *   the HUD pivot and the shared-pivot modes' center, its rotation tracker keeps Rotate continuous.
 * @property List movers                    The tiles the gesture moves.
 * @property List bystanders                The page's other placed tiles, for the collision test.
 * @property PageOccupancy? occupancy       The shown page's paint with the movers' old spots read as
 *   empty, or null when the page was not available (movers then test only each other).
 * @property Map  tileByDrawable            Each moving drawable's tile.
 * @property Map  frozenPositionsByDrawable Each moving drawable's display positions at latch.
 * @property Int  pageWidth                 The page width in pixels.
 * @property Int  pageHeight                The page height in pixels.
 * @property Int  extrude                   The composer's edge extrusion, the band that counts as paint around a tile.
 */
internal class PlacementGesture(
	val transform: ModalTransformCapture,
	val movers: List<PlacementMover>,
	val bystanders: List<PlacementBystander>,
	val occupancy: PageOccupancy?,
	val tileByDrawable: Map<DrawableId, AtlasTileId>,
	val frozenPositionsByDrawable: Map<DrawableId, FloatArray>,
	val pageWidth: Int,
	val pageHeight: Int,
	val extrude: Int,
) {
	/** The latest pointer frame's evaluation, or null before the first drive. */
	var result by mutableStateOf<PlacementDragResult?>(null)
}

/** Why a placement gesture could not be built, or the gesture. */
internal sealed interface PlacementGestureBuild {
	/** Nothing selected is packed onto the shown page. */
	data object NotOnPage : PlacementGestureBuild

	/** The atlas cannot be recomposed from its source art, so no placement may move. */
	data object NotDerivable : PlacementGestureBuild

	/**
	 * The gesture is ready to drive.
	 *
	 * @property PlacementGesture gesture The frozen capture.
	 */
	class Ready(val gesture: PlacementGesture) : PlacementGestureBuild
}

/**
 * The page-space form of a display-space affine (and back - the flip is its own inverse).
 *
 * @param FloatArray affine The affine in one frame.
 * @param Int pageHeight The page height, the flip's line.
 * @return FloatArray The same transform in the other frame.
 */
internal fun flipAffineFrame(affine: FloatArray, pageHeight: Int): FloatArray {
	val flip = floatArrayOf(1f, 0f, 0f, 0f, -1f, pageHeight.toFloat())
	return composeAffine(flip, composeAffine(affine, flip))
}

/**
 * Where a tile's pixels land in DISPLAY space under [placement]: the placement's tile-to-page mapping
 * followed by the flip.  A point mapping, so the flip applies once, after the placement - unlike a
 * transform, which [flipAffineFrame] conjugates (flip, transform, flip).  Mixing the two draws the
 * art mirrored below the page.
 *
 * @param AtlasPlacement placement The tile's placement.
 * @param Int pageHeight The page height, the flip's line.
 * @return FloatArray The tile-pixel to display-texel affine.
 */
internal fun tileToDisplayAffine(placement: AtlasPlacement, pageHeight: Int): FloatArray =
	composeAffine(floatArrayOf(1f, 0f, 0f, 0f, -1f, pageHeight.toFloat()), placementAffine(placement))

/**
 * The parameters one pointer frame yields for [operatorKind], through the same helpers the mesh
 * overlays' operator math uses.
 *
 * @param MeshOperatorKind operatorKind The running operator.
 * @param TransformGestureFrame frame The pointer frame.
 * @param RotationAngleTracker rotationTracker The gesture's angle accumulator.
 * @return PlacementGestureParameters The display-space parameters.
 */
internal fun placementGestureParameters(
	operatorKind: MeshOperatorKind,
	frame: TransformGestureFrame,
	rotationTracker: RotationAngleTracker,
): PlacementGestureParameters =
	when (operatorKind) {
		MeshOperatorKind.Grab -> {
			val (deltaX, deltaY) = gestureTranslation(frame)
			PlacementGestureParameters(deltaX, deltaY, 1f, 1f, 0f)
		}

		MeshOperatorKind.Scale -> {
			val (factorX, factorY) = gestureScaleFactors(frame)
			PlacementGestureParameters(0f, 0f, factorX, factorY, 0f)
		}

		MeshOperatorKind.Rotate -> PlacementGestureParameters(0f, 0f, 1f, 1f, gestureRotationRadians(frame, rotationTracker))
		MeshOperatorKind.VertexSlide -> PlacementGestureParameters(0f, 0f, 1f, 1f, 0f)
	}

/**
 * Evaluates one pointer frame: each mover's placement under the gesture, the display affine its
 * islands follow, and the collisions the HUD and the chrome warn about.
 *
 * Grab snaps to whole page pixels (an integer placement keeps the packer's exact blit).  Rotate and a
 * uniform Scale turn about each mover's pivot as similarities, which every placement can express.  An
 * axis-locked Scale runs along the tile's OWN axes (the only expressible form for a rotated tile; the
 * page's axes for an unrotated one), so its display affine is derived from the placement it produced
 * rather than assumed.  A product no placement can hold leaves that tile where it is.
 *
 * A collision is a mesh sampling paint, exact on both sides: a mover's triangles against the page's
 * paint (with the movers' old spots read as empty) and against the other movers' masks, and every
 * bystander's triangles against each mover's mask, the composer's extrusion band counted as paint.  A
 * painted pixel the page reports is attributed to the bystanders whose paint can reach it.  Off-page
 * is the mover's opaque bounds leaving the page; a mesh reaching past the page over transparent
 * pixels is not a warning.
 *
 * @param MeshOperatorKind operatorKind The running operator.
 * @param PlacementGestureParameters parameters This frame's parameters.
 * @param TransformAxisConstraint? axisConstraint The axis lock, if any.
 * @param List movers The moving tiles.
 * @param List bystanders The page's other placed tiles.
 * @param PageOccupancy? occupancy The page's paint, or null to skip the page test.
 * @param Int pageWidth The page width in pixels.
 * @param Int pageHeight The page height in pixels.
 * @param Int extrude The composer's edge extrusion.
 * @return PlacementDragResult The evaluation.
 */
internal fun evaluatePlacementDrag(
	operatorKind: MeshOperatorKind,
	parameters: PlacementGestureParameters,
	axisConstraint: TransformAxisConstraint?,
	movers: List<PlacementMover>,
	bystanders: List<PlacementBystander>,
	occupancy: PageOccupancy?,
	pageWidth: Int,
	pageHeight: Int,
	extrude: Int,
): PlacementDragResult {
	val snappedDeltaX = parameters.deltaDisplayX.roundToInt()
	val snappedDeltaY = (-parameters.deltaDisplayY).roundToInt()
	val placementByTile = LinkedHashMap<AtlasTileId, AtlasPlacement>()
	val displayAffineByTile = LinkedHashMap<AtlasTileId, FloatArray>()
	for (mover in movers) {
		val pageAffine =
			when (operatorKind) {
				MeshOperatorKind.Grab -> translationAffine(snappedDeltaX.toFloat(), snappedDeltaY.toFloat())
				MeshOperatorKind.Rotate ->
					flipAffineFrame(rotationAboutAffine(mover.pivotDisplayX, mover.pivotDisplayY, parameters.rotationRadians), pageHeight)
				MeshOperatorKind.Scale ->
					if (axisConstraint == null) {
						flipAffineFrame(scaleAboutAffine(mover.pivotDisplayX, mover.pivotDisplayY, parameters.factorX, parameters.factorY), pageHeight)
					} else {
						localAxisScaleAboutAffine(
							mover.placement,
							mover.pivotDisplayX,
							pageHeight - mover.pivotDisplayY,
							parameters.factorX,
							parameters.factorY,
						)
					}
				MeshOperatorKind.VertexSlide -> translationAffine(0f, 0f)
			}
		val next = mover.placement.composedWith(pageAffine)
		if (next == null) {
			placementByTile[mover.tileId] = mover.placement
			displayAffineByTile[mover.tileId] = translationAffine(0f, 0f)
		} else {
			placementByTile[mover.tileId] = next
			displayAffineByTile[mover.tileId] = flipAffineFrame(pageAffine, pageHeight)
		}
	}

	val overlapping = HashSet<AtlasTileId>()
	val sampling = HashSet<AtlasTileId>()
	val painting = HashSet<AtlasTileId>()
	val offPage = HashSet<AtlasTileId>()
	for ((moverIndex, mover) in movers.withIndex()) {
		val next = placementByTile.getValue(mover.tileId)
		if (placementFootprint(next, mover.trim, reserve = null).exceeds(pageWidth, pageHeight)) {
			offPage.add(mover.tileId)
		}
		val sampled = mover.meshMask?.let { coverage -> SampledRegion(next, coverage) }
		// The mover's mesh over the page's paint: the pixel found is attributed to whichever bystanders
		// can have painted it.
		if (sampled != null && occupancy != null) {
			val hit = occupancy.firstPaintedPixelIn(sampled)
			if (hit != null) {
				overlapping.add(mover.tileId)
				sampling.add(mover.tileId)
				for (bystander in bystanders) {
					if (bystander.paintedBounds.overlaps(PlacementFootprint(hit.left.toFloat(), hit.top.toFloat(), hit.right.toFloat(), hit.bottom.toFloat()))) {
						overlapping.add(bystander.tileId)
						painting.add(bystander.tileId)
					}
				}
			}
		}
		// The bystanders' meshes over the mover's paint.
		val mask = mover.mask
		if (mask != null) {
			for (bystander in bystanders) {
				val bystanderSampled = bystander.sampled ?: continue
				if (sampledRegionHitsMask(bystanderSampled, next, mask, extrude)) {
					overlapping.add(mover.tileId)
					painting.add(mover.tileId)
					overlapping.add(bystander.tileId)
					sampling.add(bystander.tileId)
				}
			}
		}
		// The other movers, both ways, each at its own new placement.
		for (otherIndex in movers.indices) {
			if (otherIndex == moverIndex) {
				continue
			}
			val other = movers[otherIndex]
			val otherMask = other.mask ?: continue
			if (sampled != null && sampledRegionHitsMask(sampled, placementByTile.getValue(other.tileId), otherMask, extrude)) {
				overlapping.add(mover.tileId)
				sampling.add(mover.tileId)
				overlapping.add(other.tileId)
				painting.add(other.tileId)
			}
		}
	}
	// The page-space angle is the display angle's mirror: the flip reverses a rotation's sense.
	val status =
		PlacementDragStatus(
			operatorKind = operatorKind,
			deltaX = snappedDeltaX,
			deltaY = snappedDeltaY,
			angleDegrees = (-parameters.rotationRadians * 180.0 / PI).toFloat(),
			factorX = parameters.factorX,
			factorY = parameters.factorY,
			overlapCount = movers.count { mover -> mover.tileId in overlapping },
			offPage = offPage.isNotEmpty(),
		)
	return PlacementDragResult(placementByTile, displayAffineByTile, overlapping, sampling, painting, offPage, status)
}

/**
 * The page pixels a mover's paint occupied before the gesture - its trim through its old placement,
 * grown by the extrusion band and rounded out - which the page occupancy reads as empty.
 *
 * @param PlacementMover mover The mover.
 * @param Int extrude The composer's edge extrusion.
 * @return PixelRect The old spot.
 */
private fun vacatedRect(mover: PlacementMover, extrude: Int): PixelRect {
	val bounds = placementFootprint(mover.placement, mover.trim, reserve = null).expanded(extrude.toFloat())
	return PixelRect(floor(bounds.left).toInt(), floor(bounds.top).toInt(), ceil(bounds.right).toInt(), ceil(bounds.bottom).toInt())
}

/**
 * Builds the gesture a placement operator latched over: the tiles the selection moves that sit on
 * the shown page, their crops, masks, and sampled regions, the page's bystanders and its paint, and
 * the shared capture over the moving islands.
 *
 * Only the MOVERS' art is decoded (their crops, masks, and contours need it); the bystanders' paint is
 * the shown page itself, whose composed alpha is what is painted there, and every tile's sampled
 * region comes from the model's own meshes, so no other tile is decoded.  A mover whose art will not
 * decode or disagrees with its tile refuses the gesture; a bystander in that state surfaces at the
 * commit as the resolver's own fault log.
 *
 * Decodes rasters, wraps bitmaps, and scans the page, so callers run it off the UI thread.
 *
 * @param PuppetModel model The session's committed model.
 * @param UvPlacementSurface surface The shown page and the source-art store.
 * @param Selection selection The session's object selection.
 * @param List shownGeometries The shown islands' display geometry (the page's every visible island).
 * @param TransformPivotMode pivotMode The session's pivot mode.
 * @param DrawableId? activeDrawableId The active drawable, the Active Element pivot.
 * @param Pair? cursorDisplay The UV cursor in display space, the Cursor pivot, or null when unplaced.
 * @param MeshOperatorKind operatorKind The latched operator.
 * @return PlacementGestureBuild The gesture, or why there is none.
 */
internal fun buildPlacementGesture(
	model: PuppetModel,
	surface: UvPlacementSurface,
	selection: Selection,
	shownGeometries: List<GizmoMeshGeometry>,
	pivotMode: TransformPivotMode,
	activeDrawableId: DrawableId?,
	cursorDisplay: Pair<Float, Float>?,
	operatorKind: MeshOperatorKind,
): PlacementGestureBuild {
	val tileByDrawable = HashMap<DrawableId, AtlasTileId>()
	for (drawable in model.drawables) {
		val tileId = drawable.atlasTileId ?: continue
		tileByDrawable[drawable.id] = tileId
	}
	val shownTileIds = shownGeometries.mapNotNullTo(HashSet()) { geometry -> tileByDrawable[geometry.drawableId] }
	val candidateTileIds = model.placementDragTileIds(selection).filter { tileId -> tileId in shownTileIds }
	if (candidateTileIds.isEmpty()) {
		return PlacementGestureBuild.NotOnPage
	}
	// The shown page's MODEL index is the movers' page: they were chosen from islands shown on it.  A
	// page whose model size disagrees with the shown surface means the two numberings diverged.
	val pageIndex = model.atlas.tileById.getValue(candidateTileIds.first()).placement?.pageIndex ?: return PlacementGestureBuild.NotOnPage
	val page = model.atlas.pages.getOrNull(pageIndex) ?: return PlacementGestureBuild.NotDerivable
	if (page.width != surface.pageWidth || page.height != surface.pageHeight) {
		return PlacementGestureBuild.NotDerivable
	}
	val reserveByTile = meshReserveByTile(model)
	val extrude = model.atlas.composition.extrude

	val movers = ArrayList<PlacementMover>()
	for (tileId in candidateTileIds) {
		val tile = model.atlas.tileById.getValue(tileId)
		val placement = tile.placement ?: continue
		if (placement.pageIndex != pageIndex) {
			continue
		}
		// decodeRaster rather than the cached rasterFor: this runs off the UI thread the cache is
		// confined to, and a mover's art is a handful of tiles.
		val raster = surface.artRasters.decodeRaster(tileId) ?: return PlacementGestureBuild.NotDerivable
		if (raster.width != tile.width || raster.height != tile.height) {
			return PlacementGestureBuild.NotDerivable
		}
		// A placed tile with nothing opaque has nothing on the page to move.
		val analysis = analyzeAlpha(raster.width, raster.height, raster.rgba, model.atlas.composition.alphaThreshold) ?: continue
		val trim = analysis.opaqueBounds
		val reserve = reserveByTile[tileId]
		val footprint = placementFootprint(placement, trim, reserve)
		movers.add(
			PlacementMover(
				tileId = tileId,
				placement = placement,
				trim = trim,
				reserve = reserve,
				meshMask = meshMaskOf(model, tileId),
				mask = TileOpaqueMask.of(raster, trim, model.atlas.composition.alphaThreshold),
				contours = analysis.contours,
				pivotDisplayX = (footprint.left + footprint.right) / 2f,
				pivotDisplayY = surface.pageHeight - (footprint.top + footprint.bottom) / 2f,
				crop = cropBitmap(raster, trim),
			),
		)
	}
	if (movers.isEmpty()) {
		return PlacementGestureBuild.NotOnPage
	}
	val moverIds = movers.mapTo(HashSet()) { mover -> mover.tileId }
	val bystanders =
		model.atlas.tiles.mapNotNull { tile ->
			val placement = tile.placement ?: return@mapNotNull null
			if (placement.pageIndex != pageIndex || tile.id in moverIds) {
				return@mapNotNull null
			}
			// The whole tile bounds its paint: known without a decode, and never narrower than the trim.
			val extent = LayerBounds(0, 0, tile.width, tile.height)
			PlacementBystander(
				tileId = tile.id,
				placement = placement,
				sampled = meshMaskOf(model, tile.id)?.let { coverage -> SampledRegion(placement, coverage) },
				paintedBounds = placementFootprint(placement, extent, reserve = null).expanded(extrude.toFloat()),
			)
		}
	val occupancy = surface.pageImage?.let { image -> PageOccupancy.of(image, movers.map { mover -> vacatedRect(mover, extrude) }) }

	// The shared capture over the moving islands - every shown drawable over a mover, selected or not,
	// because a tile carries all of them.  Whole-mesh sources: a tile moves rigidly.
	val movingGeometries = shownGeometries.filter { geometry -> tileByDrawable[geometry.drawableId] in moverIds }
	val sources =
		movingGeometries.map { geometry ->
			ModalCaptureSource(geometry.drawableId, geometry.positions.copyOf(), geometry.indices, allVertexIndices(geometry.positions))
		}
	val activeAnchor =
		activeDrawableId
			?.let { activeId -> movingGeometries.firstOrNull { geometry -> geometry.drawableId == activeId } }
			?.let { geometry -> MeshTransforms.medianPivot(geometry.positions, allVertexIndices(geometry.positions)) }
	val transform =
		buildModalTransformCapture(
			sources = sources,
			pivotMode = pivotMode,
			individualOriginScope = IndividualOriginScope.WholeMesh,
			operatorKind = operatorKind,
			activeAnchor = activeAnchor,
			cursorAnchor = cursorDisplay,
		) ?: return PlacementGestureBuild.NotOnPage
	// Individual Origins turns each TILE about its own center (the capture's per-drawable groups would
	// turn two drawables sharing one tile about different pivots); every other mode shares the anchor.
	val pivoted =
		if (pivotMode == TransformPivotMode.IndividualOrigins) {
			movers
		} else {
			movers.map { mover ->
				PlacementMover(mover.tileId, mover.placement, mover.trim, mover.reserve, mover.meshMask, mover.mask, mover.contours, transform.anchor.first, transform.anchor.second, mover.crop)
			}
		}
	return PlacementGestureBuild.Ready(
		PlacementGesture(
			transform = transform,
			movers = pivoted,
			bystanders = bystanders,
			occupancy = occupancy,
			tileByDrawable = movingGeometries.associate { geometry -> geometry.drawableId to tileByDrawable.getValue(geometry.drawableId) },
			frozenPositionsByDrawable = movingGeometries.associate { geometry -> geometry.drawableId to geometry.positions.copyOf() },
			pageWidth = surface.pageWidth,
			pageHeight = surface.pageHeight,
			extrude = extrude,
		),
	)
}

/**
 * Every vertex index of an interleaved positions array.
 *
 * @param FloatArray positions The interleaved (x, y) positions.
 * @return Set<Int> The indices 0 until the vertex count.
 */
private fun allVertexIndices(positions: FloatArray): Set<Int> = (0 until positions.size / 2).toSet()

/**
 * The trim's pixels of a decoded tile as a Compose bitmap, straight alpha preserved.
 *
 * @param DecodedImage raster The tile's decoded art.
 * @param LayerBounds trim The sub-rectangle to crop, raster-local.
 * @return ImageBitmap? The crop, or null when the platform could not wrap it.
 */
private fun cropBitmap(raster: DecodedImage, trim: LayerBounds): ImageBitmap? {
	if (trim.width <= 0 || trim.height <= 0) {
		return null
	}
	val bytes = ByteArray(trim.width * trim.height * 4)
	for (rowIndex in 0 until trim.height) {
		val sourceOffset = ((trim.top + rowIndex) * raster.width + trim.left) * 4
		raster.rgba.copyInto(bytes, rowIndex * trim.width * 4, sourceOffset, sourceOffset + trim.width * 4)
	}
	return runCatching { rgbaToImageBitmap(bytes, trim.width, trim.height, RgbaAlphaType.Straight) }.getOrNull()
}