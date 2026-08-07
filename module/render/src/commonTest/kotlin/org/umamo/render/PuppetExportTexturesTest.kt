package org.umamo.render

import org.umamo.format.png.PngCodec
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The export-side texture helpers, on hand-built inputs.
 *
 * Both live here rather than in the app layer because they read [PuppetTextures] and touch nothing
 * else - which is also what makes them reachable from commonTest at all.
 */
class PuppetExportTexturesTest {
	/**
	 * A drawable with the given id and page binding.
	 *
	 * @param String id   The drawable id.
	 * @param Int    page Its atlas page, or -1 for the unbound sentinel.
	 * @return Drawable The drawable.
	 */
	private fun drawable(id: String, page: Int = -1): Drawable =
		Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = null,
			geometryGrid = null,
			texturePage = page,
		)

	/**
	 * A model over the given drawables.
	 *
	 * @param Drawable drawables The drawables.
	 * @return PuppetModel The model.
	 */
	private fun modelOf(vararg drawables: Drawable): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = drawables.toList(),
			rootChildren = drawables.map { drawable -> OrgChild.Drawable(drawable.id) },
			rootPartId = null,
			canvasWidth = 0f,
			canvasHeight = 0f,
			worldOriginX = 0f,
			worldOriginY = 0f,
		)

	/**
	 * A texture set binding the given drawable ids to pages.
	 *
	 * @param Pair bindings Drawable id to page index.
	 * @return PuppetTextures The set.
	 */
	private fun texturesBinding(vararg bindings: Pair<String, Int>): PuppetTextures =
		PuppetTextures(emptyList(), bindings.toMap(), premultipliedAlpha = false)

	@Test
	fun aCmo3OriginDrawableGetsItsPageFromTheDecodedSet() {
		// The case the helper exists for: a CMO3 has no page index of its own, so every drawable arrives
		// on the -1 sentinel that the moc lowering would otherwise clamp to page 0 - pointing a
		// multi-page rig at one atlas.
		val bound = withTexturePagesFrom(modelOf(drawable("A"), drawable("B")), texturesBinding("A" to 0, "B" to 2))
		assertEquals(listOf(0, 2), bound.drawables.map { it.texturePage })
	}

	@Test
	fun anUnmappedDrawableKeepsWhateverPageItHad() {
		val bound = withTexturePagesFrom(modelOf(drawable("A", page = 3), drawable("B")), texturesBinding("A" to 3))
		assertEquals(listOf(3, -1), bound.drawables.map { it.texturePage }, "B has no binding to take")
	}

	@Test
	fun aModelWithNothingToMoveIsReturnedUnchanged() {
		// Identity, not equality: the caller re-exports this straight into the lowering, and an
		// unnecessary copy would defeat every downstream identity check on the model.
		val model = modelOf(drawable("A", page = 1))
		assertSame(model, withTexturePagesFrom(model, texturesBinding("A" to 1)))
	}

	@Test
	fun anEncodedAtlasPageRoundTripsThroughThePngCodec() {
		val page = DecodedImage(ByteArray(5 * 7 * 4) { index -> index.toByte() }, width = 5, height = 7)
		val decoded = PngCodec.read(encodeAtlasPng(page))
		assertEquals(5, decoded.width)
		assertEquals(7, decoded.height)
		assertEquals(page.rgba.toList(), decoded.rgba.toList(), "the pixels must survive the re-encode")
	}
}
