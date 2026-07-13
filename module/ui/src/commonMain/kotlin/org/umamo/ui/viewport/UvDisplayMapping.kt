package org.umamo.ui.viewport

import org.umamo.render.PuppetTextures
import org.umamo.runtime.model.Drawable

/*
 * The UV editor's display mapping: the space its camera, hit tests, and wireframe operate in is
 * TEXEL SPACE WITH Y UP - displayX = u * pageWidth, displayY = (1 - v) * pageHeight.  Stored UVs
 * put v = 0 at the TOP row of the atlas image (the decoder emits top row first and the sampler
 * addresses it directly), while ViewportCamera's world space grows y UPWARD - so the v axis must
 * flip or the wireframe renders vertically mirrored against the drawn page.  Texel units (rather
 * than normalized UV) make 1:1 zoom mean one texel per screen pixel and give non-square pages
 * their true aspect.  The mapping is affine and involutive per axis, so the shared transform
 * operators, proportional falloffs, and element queries work over display arrays unchanged; edits
 * convert back per touched vertex on commit.
 *
 * UV エディタの表示座標系。テクセル単位・Y 上向き（v=0 は画像の最上段なので反転する）。
 */

/**
 * Maps a normalized atlas u to the display (texel) x.
 *
 * @param Float u The normalized atlas u coordinate.
 * @param Int pageWidth The atlas page width in texels.
 * @return Float The display-space x.
 */
internal fun uvToDisplayX(u: Float, pageWidth: Int): Float = u * pageWidth

/**
 * Maps a normalized atlas v to the display (texel) y, flipping into the camera's Y-up convention.
 *
 * @param Float v The normalized atlas v coordinate (0 at the image's top row).
 * @param Int pageHeight The atlas page height in texels.
 * @return Float The display-space y.
 */
internal fun uvToDisplayY(v: Float, pageHeight: Int): Float = (1f - v) * pageHeight

/**
 * Maps a display (texel) x back to the normalized atlas u - the exact inverse of [uvToDisplayX].
 *
 * @param Float displayX The display-space x.
 * @param Int pageWidth The atlas page width in texels.
 * @return Float The normalized atlas u coordinate.
 */
internal fun displayToUvU(displayX: Float, pageWidth: Int): Float = displayX / pageWidth

/**
 * Maps a display (texel) y back to the normalized atlas v - the exact inverse of [uvToDisplayY].
 *
 * @param Float displayY The display-space y.
 * @param Int pageHeight The atlas page height in texels.
 * @return Float The normalized atlas v coordinate.
 */
internal fun displayToUvV(displayY: Float, pageHeight: Int): Float = 1f - displayY / pageHeight

/**
 * Maps an interleaved (u, v) array into display space (see the file header for the convention).
 *
 * @param FloatArray uvs The interleaved normalized atlas coordinates.
 * @param Int pageWidth The atlas page width in texels.
 * @param Int pageHeight The atlas page height in texels.
 * @return FloatArray A new interleaved display-space array.
 */
internal fun uvToDisplay(uvs: FloatArray, pageWidth: Int, pageHeight: Int): FloatArray {
	val display = FloatArray(uvs.size)
	val vertexCount = uvs.size / 2
	for (vertexIndex in 0 until vertexCount) {
		display[vertexIndex * 2] = uvToDisplayX(uvs[vertexIndex * 2], pageWidth)
		display[vertexIndex * 2 + 1] = uvToDisplayY(uvs[vertexIndex * 2 + 1], pageHeight)
	}
	return display
}

/**
 * Maps an interleaved display-space array back to normalized (u, v) - the inverse of [uvToDisplay],
 * the commit-side conversion after a display-space transform.
 *
 * @param FloatArray display The interleaved display-space coordinates.
 * @param Int pageWidth The atlas page width in texels.
 * @param Int pageHeight The atlas page height in texels.
 * @return FloatArray A new interleaved normalized uv array.
 */
internal fun displayToUv(display: FloatArray, pageWidth: Int, pageHeight: Int): FloatArray {
	val uvs = FloatArray(display.size)
	val vertexCount = display.size / 2
	for (vertexIndex in 0 until vertexCount) {
		uvs[vertexIndex * 2] = displayToUvU(display[vertexIndex * 2], pageWidth)
		uvs[vertexIndex * 2 + 1] = displayToUvV(display[vertexIndex * 2 + 1], pageHeight)
	}
	return uvs
}

/**
 * Resolves which atlas page a drawable samples: the mapping is keyed by the source format's ids, so
 * a session-created duplicate resolves through textureSourceId - the same resolution the GPU upload
 * and the thumbnailer use.
 *
 * @param Drawable drawable The drawable whose page to resolve.
 * @param PuppetTextures textures The document's decoded atlas pages.
 * @return Int? The page index into [PuppetTextures.atlases], or null when the drawable is untextured.
 */
internal fun atlasPageIndexFor(drawable: Drawable, textures: PuppetTextures): Int? =
	textures.atlasIndexByDrawableId[(drawable.textureSourceId ?: drawable.id).raw]
