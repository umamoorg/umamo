package org.umamo.editor.desktop.viewport

import org.umamo.render.ContentBounds
import org.umamo.render.DecodedImage
import org.umamo.render.PuppetTextures
import org.umamo.ui.viewport.UvSceneContent

/**
 * The content rectangle an area's camera fits: the puppet's rest-pose bounds for a 2D area, or the
 * shown surface's rectangle for a UV-editor area (the atlas page, or the source layer's raster), which
 * the registry widens to the shown meshes.  The puppet bounds come through a function because they are
 * the renderer's lazily computed extent, evaluated only when a puppet area is the one being fitted.
 *
 * @param RenderScene     scene        The area's kind.
 * @param UvSceneContent? uvContent    What a UV-editor area shows; null for a puppet area.
 * @param Function        puppetBounds Supplies the puppet's rest-pose bounds; read only for a puppet area.
 * @param PuppetTextures  pages        The atlas pages applied, which a page rectangle is sized from.
 * @return ContentBounds The rectangle to fit.
 */
internal fun sceneContentBounds(
	scene: RenderScene,
	uvContent: UvSceneContent?,
	puppetBounds: () -> ContentBounds,
	pages: PuppetTextures,
): ContentBounds =
	when (scene) {
		RenderScene.Puppet2D -> puppetBounds()
		RenderScene.UvScene ->
			when (uvContent) {
				is UvSceneContent.AtlasPage -> pageContentBounds(pages, uvContent.pageIndex)
				is UvSceneContent.SourceLayer -> imageContentBounds(uvContent.image)
				null -> pageContentBounds(pages, null)
			}
	}

/**
 * The source-layer rectangle (0, 0, width, height) for the UV-editor fit, or a unit square when there
 * is no layer, matching [pageContentBounds]' fallback so both UV scenes frame the same way.
 *
 * @param DecodedImage image The layer raster, or null for none.
 * @return ContentBounds The layer rectangle, in texel/display units.
 */
internal fun imageContentBounds(image: DecodedImage?): ContentBounds =
	if (image != null) {
		ContentBounds(0f, 0f, image.width.toFloat(), image.height.toFloat())
	} else {
		ContentBounds(0f, 0f, 1f, 1f)
	}

/**
 * The atlas page rectangle (0, 0, pageWidth, pageHeight) for the UV-editor fit, or a unit square when
 * the page is missing (an untextured active drawable) so the fit stays sane and the grid still frames.
 *
 * @param PuppetTextures pages     The atlas pages applied.
 * @param Int            pageIndex The atlas page, or null for none.
 * @return ContentBounds The page rectangle, in texel/display units.
 */
internal fun pageContentBounds(pages: PuppetTextures, pageIndex: Int?): ContentBounds {
	val page = pageIndex?.let { pages.atlases.getOrNull(it) }
	return if (page != null) {
		ContentBounds(0f, 0f, page.width.toFloat(), page.height.toFloat())
	} else {
		ContentBounds(0f, 0f, 1f, 1f)
	}
}