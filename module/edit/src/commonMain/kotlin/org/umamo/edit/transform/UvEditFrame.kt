package org.umamo.edit.transform

import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableLayerBinding
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.identityAffine
import org.umamo.runtime.model.invertAffine
import org.umamo.runtime.model.layerUvAffineOf
import org.umamo.runtime.model.meshOf

/**
 * The space a UV editing gesture works in: the display surface's texel size, and how a coordinate
 * over it reaches the stored texture coordinates.  Over an atlas page the display is the stored frame and
 * [storedFromFrame] is null; over a source layer the drawable's atlas placement stands between them, so an
 * edit authored on the art commits in the frame the document stores.  Only the two ends of an edit read
 * it - showing a stored coordinate and writing an authored one back; the operators between are
 * space-agnostic.
 *
 * A plain class, not a data class: it wraps an affine array whose identity-based equals would make a
 * generated structural equals quietly wrong.  Never key a remember or an effect on one - key on the
 * dimensions and the shown surface's identity, which are the values it was built from.
 *
 * @property Int displayWidth The shown surface's width in texels.
 * @property Int displayHeight The shown surface's height in texels.
 */
class UvEditFrame(
	val displayWidth: Int,
	val displayHeight: Int,
	private val storedFromFrame: FloatArray?,
	private val frameFromStored: FloatArray?,
) {
	/** True when the display surface is the stored frame itself, so no conversion applies. */
	val isStoredFrame: Boolean get() = storedFromFrame == null

	/**
	 * The stored coordinate a display point authors.
	 *
	 * @param Float displayX The display x in texels.
	 * @param Float displayY The display y in texels.
	 * @return Pair<Float, Float> The stored (u, v).
	 */
	fun storedUvAt(displayX: Float, displayY: Float): Pair<Float, Float> {
		val frameU = displayToUvU(displayX, displayWidth)
		val frameV = displayToUvV(displayY, displayHeight)
		val affine = storedFromFrame ?: return frameU to frameV

		return (affine[0] * frameU + affine[1] * frameV + affine[2]) to
			(affine[3] * frameU + affine[4] * frameV + affine[5])
	}

	/**
	 * Where a stored coordinate falls on the display surface.
	 *
	 * @param Float u The stored u.
	 * @param Float v The stored v.
	 * @return Pair<Float, Float> The display (x, y) in texels.
	 */
	fun displayAt(u: Float, v: Float): Pair<Float, Float> {
		val affine = frameFromStored
		val frameU = if (affine == null) u else affine[0] * u + affine[1] * v + affine[2]
		val frameV = if (affine == null) v else affine[3] * u + affine[4] * v + affine[5]

		return uvToDisplayX(frameU, displayWidth) to uvToDisplayY(frameV, displayHeight)
	}

	/**
	 * Where a stored coordinate falls on the shown surface in texels measured from its top-left, y down: the way an
	 * image editor, the operation strip's rows, and a tile's placement read a position.  Display space runs y up,
	 * so the vertical reading is the surface's height less the display y.
	 *
	 * @param Float u The stored u.
	 * @param Float v The stored v.
	 * @return Pair<Float, Float> The texel (x, y), y down.
	 */
	fun texelAt(u: Float, v: Float): Pair<Float, Float> {
		val (displayX, displayY) = displayAt(u, v)

		return displayX to displayYOfTexelY(displayY)
	}

	/**
	 * The display y of a y-down texel reading, or the reverse: the flip is its own inverse.
	 *
	 * @param Float texelY A vertical position in y-down texels (or in display y).
	 * @return Float The same position in display y (or in y-down texels).
	 */
	fun displayYOfTexelY(texelY: Float): Float = displayHeight - texelY

	/**
	 * The stored coordinate that puts the point stored at ([u], [v]) on [texelValue] along one y-down texel axis,
	 * the point's other texel coordinate held.  Over the stored frame the two axes are independent, so the other
	 * stored component is kept exactly as it was rather than converted out and back, which can land one float
	 * step off; over a layer's frame its mapping mixes the axes, and both come from the moved display point.
	 *
	 * @param Float u The point's stored u.
	 * @param Float v The point's stored v.
	 * @param TexelAxis axis The axis set.
	 * @param Float texelValue The point's new coordinate on that axis, in y-down texels.
	 * @return Pair<Float, Float> The moved point's stored (u, v).
	 */
	fun storedUvMovedTo(u: Float, v: Float, axis: TexelAxis, texelValue: Float): Pair<Float, Float> {
		val (displayX, displayY) = displayAt(u, v)

		return when (axis) {
			TexelAxis.X -> {
				val (movedU, movedV) = storedUvAt(texelValue, displayY)
				if (isStoredFrame) movedU to v else movedU to movedV
			}

			TexelAxis.Y -> {
				val (movedU, movedV) = storedUvAt(displayX, displayYOfTexelY(texelValue))
				if (isStoredFrame) u to movedV else movedU to movedV
			}
		}
	}

	/**
	 * Converts a whole display-space array into stored coordinates.
	 *
	 * @param FloatArray displayPositions The display coordinates, interleaved (x, y).
	 * @return FloatArray The stored (u, v) coordinates, a fresh array.
	 */
	fun storedUvs(displayPositions: FloatArray): FloatArray {
		val storedUvs = FloatArray(displayPositions.size)
		var componentIndex = 0

		while (componentIndex + 1 < displayPositions.size) {
			val (u, v) = storedUvAt(displayPositions[componentIndex], displayPositions[componentIndex + 1])
			storedUvs[componentIndex] = u
			storedUvs[componentIndex + 1] = v
			componentIndex += 2
		}

		return storedUvs
	}

	/**
	 * This frame as the normalized-coordinate pair the frame-aware session operations take.
	 *
	 * @return UvFrame? The stored-to-frame pair, or null when the stored coordinates are the frame.
	 */
	fun asUvFrame(): UvFrame? {
		val toFrame = frameFromStored ?: return null
		val fromFrame = storedFromFrame ?: return null

		return UvFrame(toFrame, fromFrame)
	}
}

/**
 * The stored uvs a texture-coordinate edit commits: the drawable's current values with ONLY the moved
 * vertices overwritten from the transformed display positions.
 *
 * Rebuilding from the stored array keeps an untouched vertex bit-identical: a display round trip is not
 * exact in floats, and the export's changed-uv test is a bitwise compare, so converting everything would
 * report meshes the gesture never touched as edited.
 *
 * @param FloatArray storedUvs The drawable's current stored coordinates.
 * @param Collection<Int> movedIndices The vertices the operation actually moved.
 * @param FloatArray displayPositions The transformed display coordinates, interleaved (x, y).
 * @param UvEditFrame frame The space the positions are in.
 * @return FloatArray The coordinates to commit, a fresh array.
 */
fun storedUvsWithMoved(
	storedUvs: FloatArray,
	movedIndices: Collection<Int>,
	displayPositions: FloatArray,
	frame: UvEditFrame,
): FloatArray {
	val committed = storedUvs.copyOf()

	for (vertexIndex in movedIndices) {
		val componentIndex = vertexIndex * 2

		if (componentIndex + 1 >= committed.size || componentIndex + 1 >= displayPositions.size) {
			continue
		}

		val (u, v) = frame.storedUvAt(displayPositions[componentIndex], displayPositions[componentIndex + 1])
		committed[componentIndex] = u
		committed[componentIndex + 1] = v
	}

	return committed
}

/**
 * The stored coordinates a texture-coordinate edit commits for one mesh, the confirm's and an adjustment
 * rerun's alike: the model's current values with only the moved vertices overwritten (see
 * [storedUvsWithMoved]), or, for a drawable the model holds no coordinates for, the whole transformed array
 * converted.
 *
 * @param PuppetModel model The model the edit lands over.
 * @param DrawableId drawableId The mesh.
 * @param Collection<Int> movedIndices The vertices the edit moved.
 * @param FloatArray displayPositions The transformed display coordinates, interleaved (x, y).
 * @param UvEditFrame frame The space the coordinates are in.
 * @return FloatArray The coordinates to commit, a fresh array.
 */
fun storedUvsForCommit(
	model: PuppetModel,
	drawableId: DrawableId,
	movedIndices: Collection<Int>,
	displayPositions: FloatArray,
	frame: UvEditFrame,
): FloatArray {
	val storedUvs = model.meshOf(drawableId)?.uvs

	return if (storedUvs == null) frame.storedUvs(displayPositions) else storedUvsWithMoved(storedUvs, movedIndices, displayPositions, frame)
}

/**
 * The frame of a UV editor showing an atlas page: the display texels ARE the stored frame.
 *
 * @param Int pageWidth The shown page's width in texels.
 * @param Int pageHeight The shown page's height in texels.
 * @return UvEditFrame The page-view frame.
 */
fun atlasPageEditFrame(pageWidth: Int, pageHeight: Int): UvEditFrame =
	UvEditFrame(pageWidth, pageHeight, storedFromFrame = null, frameFromStored = null)

/**
 * The frame of a UV editor showing a source layer, built from a drawable's recovered placement.
 *
 * Every drawable over one layer shares this frame - the layer view shows exactly the drawables bound
 * to the shown layer - so a single conversion covers a whole edit, which is what lets the shared-pivot
 * transform modes keep meaning something.
 *
 * Note the store's packed / unpacked test and the CMO3 export's are independently derived, and a
 * document that carries an atlas while sampling per-layer rasters satisfies only one of them.  That
 * divergence is harmless here: an unpacked binding's frame is the identity, so a
 * disagreement costs a conversion that would have changed nothing.
 *
 * @param DrawableLayerBinding binding The shown drawable's recovered binding.
 * @param Int layerWidth The shown layer's width in pixels.
 * @param Int layerHeight The shown layer's height in pixels.
 * @return UvEditFrame? The layer-view frame, or null when the mapping cannot be formed.
 */
fun sourceLayerEditFrame(binding: DrawableLayerBinding, layerWidth: Int, layerHeight: Int): UvEditFrame? {
	val frameFromStored = layerUvAffineOf(binding, layerWidth, layerHeight) ?: return null
	val storedFromFrame = invertAffine(frameFromStored) ?: return null

	// An unpacked drawable's art IS its stored frame, so it takes the page view's own null-conversion
	// path rather than carrying a pair of identity matrices through every commit.
	if (frameFromStored.contentEquals(identityAffine())) {
		return atlasPageEditFrame(layerWidth, layerHeight)
	}

	return UvEditFrame(layerWidth, layerHeight, storedFromFrame, frameFromStored)
}