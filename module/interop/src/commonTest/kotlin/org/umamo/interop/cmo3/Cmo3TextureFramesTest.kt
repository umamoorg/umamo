package org.umamo.interop.cmo3

import org.umamo.format.cmo3.model.custom.CImageResource
import org.umamo.format.cmo3.model.custom.CModelImage
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CCachedImage
import org.umamo.format.cmo3.model.gen.CCachedImageManager
import org.umamo.format.cmo3.model.gen.CDrawableSourceSet
import org.umamo.format.cmo3.model.gen.CModelImageGroup
import org.umamo.format.cmo3.model.gen.CTextureAtlas
import org.umamo.format.cmo3.model.gen.CTextureInputExtension
import org.umamo.format.cmo3.model.gen.CTextureInput_TextureAtlasRegion
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.cmo3.model.gen.GTexture2D
import org.umamo.format.cmo3.model.identity.Id
import org.umamo.format.cmo3.model.type.CAffine
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The CMO3 texture-frame rules over a hand-built graph: one model image with its raster and a reduced
 * cache copy, one atlas page, and a drawable over each kind of image.
 */
class Cmo3TextureFramesTest {
	/** The raster-to-cache scale every texture here carries (an art size over its 64-aligned padding). */
	private val cacheScaleU = 0.75f
	private val cacheScaleV = 0.5f

	private val page = CImageResource()
	private val raster = CImageResource()
	private val copy = CImageResource()
	private val modelImage =
		CModelImage().apply {
			_filteredImage = raster
			cachedImageManager =
				CCachedImageManager().apply {
					cachedImages =
						listOf(
							CCachedImage().apply { _cachedImageResource = raster },
							CCachedImage().apply {
								_cachedImageResource = copy
								reductionRatio = 2
							},
						)
				}
		}

	/**
	 * A drawable over [resource], packed (with an atlas-region input) or not.
	 *
	 * @param String         drawableId The drawable's id string.
	 * @param CImageResource resource   The image its texture names.
	 * @param Boolean        packed     Whether it carries an atlas-region input.
	 * @param Boolean        scaled     Whether its texture carries the cache scale rather than the identity.
	 * @return CArtMeshSource The drawable.
	 */
	private fun drawable(drawableId: String, resource: CImageResource, packed: Boolean, scaled: Boolean = true): CArtMeshSource =
		CArtMeshSource().apply {
			id = Id("ArtMesh").apply { idstr = drawableId }
			texture =
				GTexture2D().apply {
					srcImageResource = resource
					transformImageResource01toLogical01 =
						CAffine().apply {
							if (scaled) {
								m00 = cacheScaleU
								m11 = cacheScaleV
							}
						}
				}
			_extensions =
				listOf(
					CTextureInputExtension().apply {
						_textureInputs = if (packed) listOf(CTextureInput_TextureAtlasRegion()) else emptyList()
					},
				)
		}

	private val onPage = drawable("ArtMeshPage", page, packed = true, scaled = false)
	private val onRaster = drawable("ArtMeshRaster", raster, packed = true)
	private val onCopy = drawable("ArtMeshCopy", copy, packed = true)
	private val unpacked = drawable("ArtMeshUnpacked", raster, packed = false)

	/**
	 * The graph holding every drawable, the page, and the model image.
	 *
	 * @return CModelSource The root.
	 */
	private fun modelSource(): CModelSource =
		CModelSource().apply {
			textureManager =
				CTextureManager().apply {
					_textureAtlases = listOf(CTextureAtlas().apply { cachedAtlasImage = page })
					_modelImageGroups = listOf(CModelImageGroup().apply { _modelImages = listOf(modelImage) })
				}
			drawableSourceSet = CDrawableSourceSet().apply { _sources = listOf(onPage, onRaster, onCopy, unpacked) }
		}

	/**
	 * Asserts two coordinate arrays agree to within float rounding.
	 *
	 * @param FloatArray expected The expected coordinates.
	 * @param FloatArray actual   The coordinates produced.
	 * @param String     message  What is being compared.
	 */
	private fun assertClose(expected: FloatArray, actual: FloatArray, message: String) {
		assertEquals(expected.size, actual.size, message)
		for (componentIndex in expected.indices) {
			assertTrue(abs(expected[componentIndex] - actual[componentIndex]) < 1e-6f, "$message: component $componentIndex is ${actual[componentIndex]}, not ${expected[componentIndex]}")
		}
	}

	@Test
	fun eachDrawableStoresTheFrameOfWhatItsTextureNames() {
		val frames = Cmo3TextureFrames(modelSource())

		assertEquals(Cmo3StoredFrame.Page, frames.storedFrameOf(onPage), "a packed drawable over a page stores the page's frame")
		assertEquals(Cmo3StoredFrame.Raster, frames.storedFrameOf(onRaster), "a packed drawable over its raster stores the raster's frame")
		assertEquals(Cmo3StoredFrame.Cache, frames.storedFrameOf(onCopy), "a drawable over a reduced copy stores the cache frame")
		assertEquals(Cmo3StoredFrame.Cache, frames.storedFrameOf(unpacked), "an unpacked drawable stores the cache frame")
		assertTrue(frames.samplesReducedCopy(onCopy))
		assertFalse(frames.samplesReducedCopy(onRaster))
		assertFalse(frames.samplesReducedCopy(unpacked))
	}

	@Test
	fun aReducedCopyIsShownFromItsModelImagesRaster() {
		val frames = Cmo3TextureFrames(modelSource())

		assertSame(raster, frames.renderedImageOf(onCopy), "the copy gives way to the raster it was reduced from")
		assertSame(page, frames.renderedImageOf(onPage))
		assertSame(raster, frames.renderedImageOf(onRaster))
		assertTrue(frames.isShownFrom(onCopy, modelImage))
		assertTrue(frames.isShownFrom(onRaster, modelImage))
		assertFalse(frames.isShownFrom(onPage, modelImage))
	}

	@Test
	fun cacheFrameCoordinatesInvertIntoTheRasterAndBack() {
		val frames = Cmo3TextureFrames(modelSource())
		val stored = floatArrayOf(cacheScaleU, cacheScaleV, 0.3f, 0.1f)

		val model = frames.modelUvsOf(onCopy, stored)
		assertClose(floatArrayOf(1f, 1f, 0.3f / cacheScaleU, 0.1f / cacheScaleV), model, "the cache frame's art edge is the raster's")
		assertClose(stored, frames.storedUvsOf(onCopy, model), "the forward affine restores the stored coordinates")
		assertSame(stored, frames.modelUvsOf(onRaster, stored), "a drawable storing its image's own frame is taken verbatim")
		assertContentEquals(stored, frames.storedUvsOf(onRaster, stored), "and written back verbatim")
	}

	@Test
	fun aPlacedTilesCoordinatesGoOntoItsPageAndBackOff() {
		val frames = Cmo3TextureFrames(modelSource())
		// The tile's model-to-art affine: its art occupies a quarter by a half of the page, from (0.25, 0.25).
		val storedToArt = floatArrayOf(4f, 0f, -1f, 0f, 2f, -0.5f)
		val stored = floatArrayOf(cacheScaleU * 0.5f, cacheScaleV * 0.5f, 0f, 0f)

		// Over a copy: out of the cache frame to the art's center (0.5, 0.5), then onto the page.
		val model = frames.modelUvsOf(onCopy, stored, storedToArt)
		assertClose(floatArrayOf(0.375f, 0.5f, 0.25f, 0.25f), model, "the art's center and corner land on the page")
		assertClose(stored, frames.storedUvsOf(onCopy, model, storedToArt), "and come back off it into the cache frame")
		// Over the raster: no cache step, the same placement.
		val rasterModel = frames.modelUvsOf(onRaster, floatArrayOf(0.5f, 0.5f), storedToArt)
		assertClose(floatArrayOf(0.375f, 0.5f), rasterModel, "a raster pair lands on the page")
		assertClose(floatArrayOf(0.5f, 0.5f), frames.storedUvsOf(onRaster, rasterModel, storedToArt), "and comes back in the raster's frame")
		// Over the page: already there, whatever the placement.
		assertSame(stored, frames.modelUvsOf(onPage, stored, storedToArt), "page coordinates are taken verbatim")
		assertContentEquals(stored, frames.storedUvsOf(onPage, stored, storedToArt), "and stored verbatim")
		// An unplaced tile's identity affine changes nothing past the cache step.
		assertClose(floatArrayOf(0.5f, 0.5f, 0f, 0f), frames.modelUvsOf(onCopy, stored, floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f)), "without a placement the raster frame is the model's")
	}

	@Test
	fun anUnchangedPairKeepsItsStoredBits() {
		val frames = Cmo3TextureFrames(modelSource())
		val stored = floatArrayOf(0.3f, 0.7f, 0.1f, 0.2f)
		val baseline = frames.modelUvsOf(onCopy, stored)
		val edited = baseline.copyOf().also { uvs -> uvs[2] = 0.5f }

		val written = frames.storedUvsOf(onCopy, edited, storedUvs = stored, baselineUvs = baseline)

		assertEquals(stored[0].toRawBits(), written[0].toRawBits(), "the untouched pair keeps its stored u")
		assertEquals(stored[1].toRawBits(), written[1].toRawBits(), "the untouched pair keeps its stored v")
		assertClose(floatArrayOf(0.5f * cacheScaleU, baseline[3] * cacheScaleV), written.copyOfRange(2, 4), "the edited pair goes through the affine")
	}

	@Test
	fun theCopySetIsTakenAsReadAndOnlyARetargetedTextureLeavesTheCacheFrame() {
		val frames = Cmo3TextureFrames(modelSource())

		// A rewrite rebuilds the image's cache without the copy; the drawable still samples it.
		modelImage.cachedImageManager = CCachedImageManager().apply { cachedImages = listOf(CCachedImage().apply { _cachedImageResource = raster }) }
		assertEquals(Cmo3StoredFrame.Cache, frames.storedFrameOf(onCopy), "a cache rebuild does not change what the drawable's coordinates mean")

		// Retargeting the texture onto the raster does.
		(onCopy.texture as GTexture2D).srcImageResource = raster
		assertEquals(Cmo3StoredFrame.Raster, frames.storedFrameOf(onCopy), "a packed drawable moved onto its raster stores the raster's frame")
		assertSame(raster, frames.renderedImageOf(onCopy))
	}

	@Test
	fun anAtlasPageIsNeverAReducedCopy() {
		modelImage.cachedImageManager = CCachedImageManager().apply { cachedImages = listOf(CCachedImage().apply { _cachedImageResource = page }) }
		val frames = Cmo3TextureFrames(modelSource())

		assertFalse(frames.isReducedCopy(page))
		assertEquals(Cmo3StoredFrame.Page, frames.storedFrameOf(onPage))
		assertSame(page, frames.renderedImageOf(onPage))
	}
}