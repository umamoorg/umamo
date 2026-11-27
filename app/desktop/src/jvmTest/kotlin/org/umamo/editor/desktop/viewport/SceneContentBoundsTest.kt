package org.umamo.editor.desktop.viewport

import org.umamo.render.ContentBounds
import org.umamo.render.DecodedImage
import org.umamo.render.PuppetTextures
import org.umamo.ui.viewport.UvSceneContent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins what an area's fit frames: the renderer's bounds for a puppet area (read only then), a page's or a
 * layer's own rectangle for a UV-editor area, and the unit square whenever the surface is missing, so an
 * untextured drawable still fits sanely and the grid still frames.
 */
class SceneContentBoundsTest {
	private fun pagesOf(vararg sizes: Pair<Int, Int>): PuppetTextures =
		PuppetTextures(
			sizes.map { (width, height) -> DecodedImage(ByteArray(width * height * 4), width, height) },
			emptyMap(),
			premultipliedAlpha = false,
		)

	private fun notForAUvArea(): ContentBounds = error("the puppet bounds must not be read for a UV area")

	private fun assertBounds(minX: Float, minY: Float, width: Float, height: Float, actual: ContentBounds) {
		assertEquals(listOf(minX, minY, width, height), listOf(actual.minX, actual.minY, actual.width, actual.height))
	}

	@Test
	fun aPuppetAreaFitsTheRendererBounds() {
		val puppet = ContentBounds(-5f, -7f, 10f, 14f)
		val fitted = sceneContentBounds(RenderScene.Puppet2D, null, { puppet }, pagesOf(16 to 16))
		assertBounds(-5f, -7f, 10f, 14f, fitted)
	}

	@Test
	fun aPageFitsItsOwnRectangle() {
		val fitted = sceneContentBounds(RenderScene.UvScene, UvSceneContent.AtlasPage(1), ::notForAUvArea, pagesOf(16 to 16, 32 to 8))
		assertBounds(0f, 0f, 32f, 8f, fitted)
	}

	@Test
	fun aLayerFitsItsRaster() {
		val image = DecodedImage(ByteArray(8 * 4 * 4), 8, 4)
		val fitted = sceneContentBounds(RenderScene.UvScene, UvSceneContent.SourceLayer("layer", image), ::notForAUvArea, pagesOf())
		assertBounds(0f, 0f, 8f, 4f, fitted)
	}

	@Test
	fun aMissingSurfaceFallsBackToTheUnitSquare() {
		val pages = pagesOf(16 to 16)
		assertBounds(0f, 0f, 1f, 1f, sceneContentBounds(RenderScene.UvScene, null, ::notForAUvArea, pages))
		assertBounds(0f, 0f, 1f, 1f, sceneContentBounds(RenderScene.UvScene, UvSceneContent.AtlasPage(null), ::notForAUvArea, pages))
		assertBounds(0f, 0f, 1f, 1f, sceneContentBounds(RenderScene.UvScene, UvSceneContent.AtlasPage(3), ::notForAUvArea, pages))
		assertBounds(0f, 0f, 1f, 1f, sceneContentBounds(RenderScene.UvScene, UvSceneContent.SourceLayer("gone", null), ::notForAUvArea, pages))
	}
}