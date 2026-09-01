package org.umamo.ui.viewport

import org.umamo.edit.UvFrame
import org.umamo.runtime.model.DrawableLayerBinding
import org.umamo.runtime.model.identityUvAffine
import org.umamo.runtime.model.invertUvAffine
import org.umamo.runtime.model.layerUvAffineOf

/**
 * The space a UV editing gesture works in: the display surface's texel size, and how a coordinate
 * over it reaches the stored texture coordinates.
 *
 * The UV editor authors one mapping over two surfaces.  Over an atlas page the display IS the stored
 * frame - the texel mapping in UvDisplayMapping.kt is the whole conversion - and [storedFromFrame] is
 * null, so that path carries no conversion at all.  Over a source layer the display is the artwork's own
 * pixels, and the drawable's atlas placement stands between them; [storedFromFrame] carries that step
 * so an edit authored on the art commits in the frame the document stores.
 *
 * Everything downstream of the display coordinates - the transform operators, proportional falloff,
 * box and circle queries, hit tests - is space-agnostic and needs no part of this.  Only the two ends
 * do: reading a stored coordinate to show it, and writing an authored one back.
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
 * Rebuilding from the stored array rather than converting the whole transformed one is what keeps an
 * untouched vertex bit-identical.  A display round trip is exact in the reals but not in floats, and
 * through a source layer's placement it is one conversion further; the export's changed-uv test is a
 * bitwise compare, so converting everything would report meshes as edited that the gesture never
 * touched - and warn the user about re-derived mappings that were never authored.
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
 * divergence is pre-existing and harmless here: an unpacked binding's frame is the identity, so a
 * disagreement costs a conversion that would have changed nothing.
 *
 * @param DrawableLayerBinding binding The shown drawable's recovered binding.
 * @param Int layerWidth The shown layer's width in pixels.
 * @param Int layerHeight The shown layer's height in pixels.
 * @return UvEditFrame? The layer-view frame, or null when the mapping cannot be formed.
 */
fun sourceLayerEditFrame(binding: DrawableLayerBinding, layerWidth: Int, layerHeight: Int): UvEditFrame? {
	val frameFromStored = layerUvAffineOf(binding, layerWidth, layerHeight) ?: return null
	val storedFromFrame = invertUvAffine(frameFromStored) ?: return null
	// An unpacked drawable's art IS its stored frame, so it takes the page view's own null-conversion
	// path rather than carrying a pair of identity matrices through every commit.
	if (frameFromStored.contentEquals(identityUvAffine())) {
		return atlasPageEditFrame(layerWidth, layerHeight)
	}
	return UvEditFrame(layerWidth, layerHeight, storedFromFrame, frameFromStored)
}