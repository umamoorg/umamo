package org.umamo.render

import org.umamo.format.png.PngCodec

/**
 * Builds the [PuppetTextures] for a MOC3-imported puppet from its sidecar atlas pages.  A `.moc3`
 * embeds no pixels: the pages are external PNG files listed in model3.json's `FileReferences.Textures`
 * (in order - a drawable's `textureIndex` indexes that list), so the caller reads those files and
 * hands their bytes here in the same order.
 *
 * Unlike the CMO3 extractor (which skips an undecodable page per drawable), a broken page fails the
 * whole build: a MOC3 import with a missing atlas page is a broken puppet, and the loader surfaces it
 * as an import error rather than silently rendering fallback colors.
 *
 * MOC3: アトラスは model3.json の Textures が指す外部 PNG。1 ページでも壊れていれば null（インポート失敗）。
 *
 * @param List<ByteArray>  pageBytes              The PNG bytes per manifest texture, in Textures order.
 * @param Map<String, Int> atlasIndexByDrawableId Drawable id (`ArtMesh…`) → page index (the moc's
 *                                                per-mesh textureIndex).
 * @return PuppetTextures? The decoded pages + index, or null when any page fails to decode or any
 *                         index falls outside the page list.
 */
fun buildPuppetTextures(pageBytes: List<ByteArray>, atlasIndexByDrawableId: Map<String, Int>): PuppetTextures? {
	// An index outside the decoded page list must never reach the renderer: PuppetRenderer resolves
	// pages by direct list indexing (a CMO3-era invariant its extractor upholds by construction), so
	// broken atlas wiring fails the build here rather than crashing the render thread later.
	if (atlasIndexByDrawableId.values.any { pageIndex -> pageIndex !in pageBytes.indices }) {
		return null
	}
	val atlases =
		pageBytes.map { bytes ->
			val image =
				try {
					PngCodec.read(bytes)
				} catch (_: Exception) {
					return null
				}
			DecodedImage(image.rgba, image.width, image.height)
		}
	// Cubism texture files are straight-alpha PNGs (premultiplication is a runtime load option, not a
	// property of the files), matching the straight-alpha stream PngCodec yields.
	return PuppetTextures(atlases, atlasIndexByDrawableId, premultipliedAlpha = false)
}
