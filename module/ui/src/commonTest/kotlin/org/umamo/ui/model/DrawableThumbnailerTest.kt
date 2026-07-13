package org.umamo.ui.model

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import org.umamo.render.DecodedImage
import org.umamo.render.PuppetTextures
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Unit-tests [DrawableThumbnailer], the shared crop-and-downsample used by both the overlap picker and the
 * Outliner hover preview. Corpus-free: a hand-built 4x4 atlas with distinct corner pixels and a drawable
 * whose UVs cover the left half, so the cropped thumbnail is a known 2x4 1:1 copy whose pixels can be
 * asserted exactly. No GL - the bitmap is read back via toPixelMap (common Compose API). If the ui android target ever
 * enables host tests, the Bitmap-backed rgbaToImageBitmap actual will need Robolectric.
 */
class DrawableThumbnailerTest {
	/** Packs an opaque RGBA pixel into a 4x4 atlas buffer at (x, y), top row first. */
	private fun setPixel(rgba: ByteArray, width: Int, x: Int, y: Int, color: Color) {
		val offset = (y * width + x) * 4
		rgba[offset] = (color.red * 255f).toInt().toByte()
		rgba[offset + 1] = (color.green * 255f).toInt().toByte()
		rgba[offset + 2] = (color.blue * 255f).toInt().toByte()
		rgba[offset + 3] = 255.toByte()
	}

	private fun atlas4x4(): DecodedImage {
		val width = 4
		val height = 4
		val rgba = ByteArray(width * height * 4)
		// Black fill, then four distinct corners within the left-half crop (columns 0..1).
		for (offset in 3 until rgba.size step 4) {
			rgba[offset] = 255.toByte() // opaque
		}
		setPixel(rgba, width, 0, 0, Color.Red)
		setPixel(rgba, width, 1, 0, Color.Green)
		setPixel(rgba, width, 0, 3, Color.Blue)
		setPixel(rgba, width, 1, 3, Color.Yellow)
		return DecodedImage(rgba, width, height)
	}

	private fun drawable(id: String, uvs: FloatArray, positions: FloatArray = floatArrayOf()): Drawable =
		Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh(positions = positions, uvs = uvs, indices = intArrayOf()),
			keyforms = null,
		)

	/** A flat model: every [drawables] entry sits at the organisational root (no owning part). */
	private fun flatModel(drawables: List<Drawable>): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = drawables,
			rootChildren = drawables.map { OrgChild.Drawable(it.id) },
			rootPartId = null,
		)

	/** A one-part model: [part] owns every [drawables] entry (membership lives in the org tree). */
	private fun partModel(partId: String, drawables: List<Drawable>): PuppetModel {
		val part = Part(PartId(partId), partId.uppercase(), drawables.map { OrgChild.Drawable(it.id) })
		return PuppetModel(
			parameters = emptyList(),
			parts = listOf(part),
			deformers = emptyList(),
			drawables = drawables,
			rootChildren = listOf(OrgChild.Part(part.id)),
			rootPartId = null,
		)
	}

	/** A 4x4 atlas: the left two columns red, the right two green, so a left-UV crop reads red and a right-UV crop green. */
	private fun atlasLeftRedRightGreen(): DecodedImage {
		val width = 4
		val height = 4
		val rgba = ByteArray(width * height * 4)
		for (y in 0 until height) {
			for (x in 0 until width) {
				setPixel(rgba, width, x, y, if (x < 2) Color.Red else Color.Green)
			}
		}
		return DecodedImage(rgba, width, height)
	}

	@Test
	fun cropsTheUvBoundingBoxAtOneToOneWhenSmallerThanTheThumbnailBox() {
		// UVs over the left half: u in [0, 0.5], v in [0, 1] → crop columns 0..1, rows 0..3 (a 2x4 region).
		val leftHalf = floatArrayOf(0f, 0f, 0.5f, 0f, 0.5f, 1f, 0f, 1f)
		val puppet = flatModel(listOf(drawable("mesh", leftHalf)))
		val textures = PuppetTextures(listOf(atlas4x4()), mapOf("mesh" to 0), premultipliedAlpha = false)

		val thumbnail = DrawableThumbnailer(puppet, textures).thumbnailFor(DrawableId("mesh"))
		assertEquals(true, thumbnail != null, "a textured, meshed drawable yields a thumbnail")
		val pixels = thumbnail!!.toPixelMap()

		// 96px box never upscales a 2x4 region, so the crop is copied 1:1.
		assertEquals(2, pixels.width)
		assertEquals(4, pixels.height)
		// Nearest-neighbor is the identity over the left two columns, so the corners survive verbatim.
		assertEquals(Color.Red.toArgb(), pixels[0, 0].toArgb())
		assertEquals(Color.Green.toArgb(), pixels[1, 0].toArgb())
		assertEquals(Color.Blue.toArgb(), pixels[0, 3].toArgb())
		assertEquals(Color.Yellow.toArgb(), pixels[1, 3].toArgb())
	}

	@Test
	fun duplicateResolvesItsAtlasThroughItsSourceAfterUpdateModel() {
		// The atlas map knows only the source-format id "mesh"; the duplicate carries a fresh id and
		// textureSourceId = mesh, with its own UVs over the right (green) half.
		val source = drawable("mesh", floatArrayOf(0f, 0f, 0.5f, 0f, 0.5f, 1f, 0f, 1f))
		val copy =
			drawable("mesh.001", floatArrayOf(0.5f, 0f, 1f, 0f, 1f, 1f, 0.5f, 1f))
				.copy(textureSourceId = DrawableId("mesh"))
		val textures = PuppetTextures(listOf(atlasLeftRedRightGreen()), mapOf("mesh" to 0), premultipliedAlpha = false)
		val thumbnailer = DrawableThumbnailer(flatModel(listOf(source)), textures)

		// Before the refresh the id is unknown - the session has not pushed the duplicating edit yet.
		assertNull(thumbnailer.thumbnailFor(DrawableId("mesh.001")))

		thumbnailer.updateModel(flatModel(listOf(source, copy)))
		val thumbnail = thumbnailer.thumbnailFor(DrawableId("mesh.001"))
		assertNotNull(thumbnail, "a session-created duplicate previews through its source's atlas page")
		val pixels = thumbnail.toPixelMap()
		assertEquals(Color.Green.toArgb(), pixels[0, 0].toArgb(), "the crop follows the COPY's own UVs (the green half)")
	}

	@Test
	fun remeshEvictsTheStaleCropOnUpdateModel() {
		// Same id, new UV array (a remesh copy-on-writes uvs): the cached red-half crop must not survive.
		val before = drawable("mesh", floatArrayOf(0f, 0f, 0.5f, 0f, 0.5f, 1f, 0f, 1f))
		val after = drawable("mesh", floatArrayOf(0.5f, 0f, 1f, 0f, 1f, 1f, 0.5f, 1f))
		val textures = PuppetTextures(listOf(atlasLeftRedRightGreen()), mapOf("mesh" to 0), premultipliedAlpha = false)
		val thumbnailer = DrawableThumbnailer(flatModel(listOf(before)), textures)
		assertEquals(Color.Red.toArgb(), thumbnailer.thumbnailFor(DrawableId("mesh"))!!.toPixelMap()[0, 0].toArgb())

		thumbnailer.updateModel(flatModel(listOf(after)))
		assertEquals(
			Color.Green.toArgb(),
			thumbnailer.thumbnailFor(DrawableId("mesh"))!!.toPixelMap()[0, 0].toArgb(),
			"the remeshed UVs re-crop instead of serving the stale cached thumbnail",
		)
	}

	@Test
	fun returnsNullForAnUntexturedDrawable() {
		// A drawable with mesh UVs but no atlas page mapping is untextured → no preview.
		val uvs = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f)
		val puppet = flatModel(listOf(drawable("flat", uvs)))
		val textures = PuppetTextures(listOf(atlas4x4()), emptyMap(), premultipliedAlpha = false)

		assertNull(DrawableThumbnailer(puppet, textures).thumbnailFor(DrawableId("flat")))
	}

	@Test
	fun combinesPartMembersInModelSpaceLeftToRight() {
		// Two opaque, non-overlapping layers under one part: 'a' on the model-space left (red atlas region),
		// 'b' on the right (green). Their union is 20x10 model units → a 128x64 composite split down the middle.
		val left =
			drawable(
				id = "a",
				uvs = floatArrayOf(0f, 0f, 0.5f, 0f, 0.5f, 1f, 0f, 1f),
				positions = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f),
			)
		val right =
			drawable(
				id = "b",
				uvs = floatArrayOf(0.5f, 0f, 1f, 0f, 1f, 1f, 0.5f, 1f),
				positions = floatArrayOf(10f, 0f, 20f, 0f, 20f, 10f, 10f, 10f),
			)
		val puppet = partModel("p", listOf(left, right))
		val textures = PuppetTextures(listOf(atlasLeftRedRightGreen()), mapOf("a" to 0, "b" to 0), premultipliedAlpha = false)

		val composite = DrawableThumbnailer(puppet, textures).partThumbnailFor(PartId("p"))
		assertNotNull(composite, "a part with textured members yields a composite")
		val pixels = composite.toPixelMap()

		// 128px long edge over the 20x10 union → 128x64; left half is 'a' (red), right half is 'b' (green).
		assertEquals(128, pixels.width)
		assertEquals(64, pixels.height)
		assertEquals(Color.Red.toArgb(), pixels[16, 32].toArgb())
		assertEquals(Color.Green.toArgb(), pixels[112, 32].toArgb())
	}

	@Test
	fun stacksPartMembersTopToBottomByModelY() {
		// Two stacked layers under one part: 'upper' at the smaller model Y (red), 'lower' below it (green).
		// Rest-pose Y is top-down, so the smaller-Y layer must land at the TOP of the composite (no Y flip).
		val upper =
			drawable(
				id = "a",
				uvs = floatArrayOf(0f, 0f, 0.5f, 0f, 0.5f, 1f, 0f, 1f),
				positions = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f),
			)
		val lower =
			drawable(
				id = "b",
				uvs = floatArrayOf(0.5f, 0f, 1f, 0f, 1f, 1f, 0.5f, 1f),
				positions = floatArrayOf(0f, 10f, 10f, 10f, 10f, 20f, 0f, 20f),
			)
		val puppet = partModel("p", listOf(upper, lower))
		val textures = PuppetTextures(listOf(atlasLeftRedRightGreen()), mapOf("a" to 0, "b" to 0), premultipliedAlpha = false)

		val composite = DrawableThumbnailer(puppet, textures).partThumbnailFor(PartId("p"))
		assertNotNull(composite)
		val pixels = composite.toPixelMap()

		// 128px long edge over the 10x20 union → 64x128; the smaller-Y layer is the top half (red).
		assertEquals(64, pixels.width)
		assertEquals(128, pixels.height)
		assertEquals(Color.Red.toArgb(), pixels[32, 16].toArgb())
		assertEquals(Color.Green.toArgb(), pixels[32, 100].toArgb())
	}

	@Test
	fun partWithNoTexturedMembersHasNoComposite() {
		val flat = drawable("a", floatArrayOf(0f, 0f, 1f, 1f), floatArrayOf(0f, 0f, 10f, 10f))
		val puppet = partModel("empty", listOf(flat))
		// No atlas mapping → the member is untextured → no crop → null composite.
		val textures = PuppetTextures(listOf(atlasLeftRedRightGreen()), emptyMap(), premultipliedAlpha = false)

		assertNull(DrawableThumbnailer(puppet, textures).partThumbnailFor(PartId("empty")))
	}
}
