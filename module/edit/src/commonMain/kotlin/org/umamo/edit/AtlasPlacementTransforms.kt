package org.umamo.edit

import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.composeAffine
import org.umamo.runtime.model.placementAffine
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/*
 * The placement gizmo's model math: how a gesture affine measured on the page rewrites one tile's
 * translation / rotation / scale, the page-space affines the three operators build, and which tiles
 * a selection moves.  Pure over :runtime, so the overlay that drives the gesture, the commit, and the
 * tests all read one algebra - a placement written by one rule and previewed by another is exactly the
 * drift the gizmo must not have.
 *
 * Every affine here is the runtime's 2x3 row-major form (m00, m01, m02, m10, m11, m12) over PAGE
 * pixels, y down, the frame AtlasPlacement is expressed in.
 */

/**
 * How far from orthogonal a composed affine's axes may be (normalized) before it stops being a
 * translation / rotation / scale and becomes a shear no placement can express.
 */
private const val PLACEMENT_SHEAR_TOLERANCE = 1e-4f

/**
 * This placement with [pageAffine] applied on top - the placement whose forward transform is
 * `pageAffine . placementAffine(this)` - or null when that product is not a placement.
 *
 * A placement is translation / rotation / scale and nothing else, so only a similarity (translate,
 * rotate, uniform scale) or a scale along the tile's own axes composes onto one exactly; a non-uniform
 * scale along the page's axes shears a rotated tile, which no placement can carry.  Refusing beats
 * writing the closest placement, which would visibly re-point every mesh bound to the tile.
 *
 * The scale signs follow the receiver's, so a mirrored import stays mirrored through a move rather
 * than flipping into a half-turn with positive scales (both describe the same pixels, but the export
 * should write the file's own convention back).  The rotation comes back normalized to (-180, 180].
 *
 * @param FloatArray pageAffine The gesture's transform in page pixels, applied after the placement.
 * @return AtlasPlacement? The composed placement, or null when the product is not TRS-expressible or
 *   degenerate.
 */
fun AtlasPlacement.composedWith(pageAffine: FloatArray): AtlasPlacement? {
	val composed = composeAffine(pageAffine, placementAffine(this))
	val columnX0 = composed[0]
	val columnX1 = composed[3]
	val columnY0 = composed[1]
	val columnY1 = composed[4]
	val lengthX = hypot(columnX0, columnX1)
	val lengthY = hypot(columnY0, columnY1)
	if (lengthX == 0f || lengthY == 0f) {
		return null
	}
	// The two columns are the rotated, scaled unit axes; a placement keeps them perpendicular.
	val shear = abs(columnX0 * columnY0 + columnX1 * columnY1) / (lengthX * lengthY)
	if (shear > PLACEMENT_SHEAR_TOLERANCE) {
		return null
	}
	// The rotation is read off the x axis, negated back when that axis carries a mirror so the
	// receiver's sign convention survives; the y scale is then the y axis projected onto the rotated
	// y direction, which carries its own sign.
	val signX = if (scaleX < 0f) -1f else 1f
	val radians = atan2(columnX1 * signX, columnX0 * signX)
	val cosine = cos(radians)
	val sine = sin(radians)
	val scaleYSigned = columnY1 * cosine - columnY0 * sine
	return AtlasPlacement(
		pageIndex = pageIndex,
		positionX = composed[2],
		positionY = composed[5],
		scaleX = lengthX * signX,
		scaleY = scaleYSigned,
		rotationDegrees = (radians * 180.0 / PI).toFloat(),
	)
}

/**
 * The page-space translation by ([deltaX], [deltaY]).
 *
 * @param Float deltaX The horizontal move in page pixels.
 * @param Float deltaY The vertical move in page pixels (positive is down).
 * @return FloatArray The affine.
 */
fun translationAffine(deltaX: Float, deltaY: Float): FloatArray = floatArrayOf(1f, 0f, deltaX, 0f, 1f, deltaY)

/**
 * The page-space rotation by [radians] about ([pivotX], [pivotY]), in the same sense
 * [AtlasPlacement.rotationDegrees] uses - composing this onto a placement adds the angle to it.
 *
 * @param Float pivotX The pivot's x in page pixels.
 * @param Float pivotY The pivot's y in page pixels.
 * @param Float radians The angle.
 * @return FloatArray The affine.
 */
fun rotationAboutAffine(pivotX: Float, pivotY: Float, radians: Float): FloatArray {
	val cosine = cos(radians)
	val sine = sin(radians)
	return floatArrayOf(
		cosine,
		-sine,
		pivotX - cosine * pivotX + sine * pivotY,
		sine,
		cosine,
		pivotY - sine * pivotX - cosine * pivotY,
	)
}

/**
 * The page-space scale by ([factorX], [factorY]) about ([pivotX], [pivotY]), along the PAGE axes.
 * Only a uniform factor composes onto a rotated placement; the axis-locked form for a rotated tile is
 * [localAxisScaleAboutAffine].
 *
 * @param Float pivotX  The pivot's x in page pixels.
 * @param Float pivotY  The pivot's y in page pixels.
 * @param Float factorX The horizontal factor.
 * @param Float factorY The vertical factor.
 * @return FloatArray The affine.
 */
fun scaleAboutAffine(pivotX: Float, pivotY: Float, factorX: Float, factorY: Float): FloatArray =
	floatArrayOf(factorX, 0f, pivotX - factorX * pivotX, 0f, factorY, pivotY - factorY * pivotY)

/**
 * The page-space scale by ([factorX], [factorY]) about ([pivotX], [pivotY]) along [placement]'s OWN
 * axes: `T(P) . R(rotation) . diag(factorX, factorY) . R(-rotation) . T(-P)`.  This is the one
 * axis-locked scale a rotated placement can express - it multiplies the placement's own scale pair and
 * leaves its rotation alone - and for an unrotated placement it is the page-axis scale exactly.
 *
 * @param AtlasPlacement placement The placement whose axes the factors follow.
 * @param Float pivotX  The pivot's x in page pixels.
 * @param Float pivotY  The pivot's y in page pixels.
 * @param Float factorX The factor along the tile's x axis.
 * @param Float factorY The factor along the tile's y axis.
 * @return FloatArray The affine.
 */
fun localAxisScaleAboutAffine(
	placement: AtlasPlacement,
	pivotX: Float,
	pivotY: Float,
	factorX: Float,
	factorY: Float,
): FloatArray {
	val radians = (placement.rotationDegrees.toDouble() * PI / 180.0).toFloat()
	val intoTileAxes = rotationAboutAffine(pivotX, pivotY, -radians)
	val scaled = scaleAboutAffine(pivotX, pivotY, factorX, factorY)
	val outOfTileAxes = rotationAboutAffine(pivotX, pivotY, radians)
	return composeAffine(outOfTileAxes, composeAffine(scaled, intoTileAxes))
}

/**
 * The placed tiles under [selection]: every selected drawable's tile that is packed onto a page,
 * pinned or not, in document order.  A drawable over unpacked art has nothing on a page, and a part
 * or deformer in the selection contributes nothing.  The pin commands' domain - Unpin must find the
 * pinned tiles - where a gesture reads the narrower [placementDragTileIds].
 *
 * @param Selection selection The session's object selection.
 * @return Set<AtlasTileId> The placed tiles the selection covers, empty when there are none.
 */
fun PuppetModel.placementSelectedTileIds(selection: Selection): Set<AtlasTileId> {
	val selectedIds = selection.targets.mapNotNullTo(HashSet()) { target -> (target as? SelectionTarget.Drawable)?.id }
	if (selectedIds.isEmpty()) {
		return emptySet()
	}
	val tileIds = LinkedHashSet<AtlasTileId>()
	for (drawable in drawables) {
		if (drawable.id !in selectedIds) {
			continue
		}
		val tileId = drawable.atlasTileId ?: continue
		if (atlas.tileById[tileId]?.placement == null) {
			continue
		}
		tileIds.add(tileId)
	}
	return tileIds
}

/**
 * The tiles a placement gesture over [selection] moves: the placed tiles under it
 * ([placementSelectedTileIds]) minus the pinned ones.  A pin means "keep this where it is", and a
 * hand move is no exception - a pinned tile stays put until it is unpinned, and a gesture over a
 * mixed selection moves the rest around it.
 *
 * @param Selection selection The session's object selection.
 * @return Set<AtlasTileId> The movable placed tiles the selection covers, empty when there are none.
 */
fun PuppetModel.placementDragTileIds(selection: Selection): Set<AtlasTileId> =
	placementSelectedTileIds(selection).filterTo(LinkedHashSet()) { tileId -> atlas.tileById[tileId]?.pinned != true }