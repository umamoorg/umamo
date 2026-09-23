package org.umamo.render

import org.junit.Assume
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.interop.cmo3.cmo3AtlasPages
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A page Umamo composes from a CMO3's tiles and placements shows what the editor's own page shows.
 *
 * The derivation is what every atlas change leaves the document drawing - a reload, a replace, an add,
 * a repack all re-derive the WHOLE page from each tile's raster - and what a CMO3 export then writes
 * back.  So an unedited atlas, derived, must come back as the page the file holds: nothing painted
 * where the editor's page is transparent (meshes reach past their art, and anything painted there
 * shows as a fringe), and every edge resampled the way the editor resamples it, bilinearly against
 * the transparency beyond the art, so its coverage matches to a few steps of alpha.
 *
 * Gated on `cmo3.sample` (Erica by default: one 8192 page, 176 tiles whose rasters are cropped tight to
 * their art, 84 of them at fractional positions); skips by assumption without it.
 */
class DerivedAtlasPageFidelityTest {
	private companion object {
		/** Below this alpha a file pixel counts as empty page. */
		const val EMPTY_ALPHA = 16

		/** At or above this alpha a derived pixel counts as painted. */
		const val PAINTED_ALPHA = 64

		/** The most a derived pixel's alpha may differ from the file's: resampling rounds, it never moves an edge. */
		const val ALPHA_TOLERANCE = 8

		/** At or above this alpha on both pages a pixel's color is compared. */
		const val COLOR_COMPARED_ALPHA = 128

		/** How far a compared pixel's color may sit from the file's before it counts as different. */
		const val COLOR_TOLERANCE = 24

		/**
		 * The share of compared pixels whose color may differ past [COLOR_TOLERANCE].  The editor's own
		 * translucent edge texels run slightly darker than the art's color, where the derivation keeps the
		 * art's color; a handful of edge pixels differ that way and nothing else does.
		 */
		const val MAXIMUM_COLOR_MISMATCH_FRACTION = 1e-4f
	}

	private val sample: File? = System.getProperty("cmo3.sample")?.let(::File)?.takeIf { file -> file.isFile }

	@Test
	fun anUneditedAtlasDerivesBackToTheFilesOwnPages() {
		val file = sample
		Assume.assumeTrue("[derived-page] no cmo3.sample", file != null)
		val cmo3 = Cmo3.read(file!!)
		val root = cmo3.root as CModelSource
		val imported = Cmo3Import.importModelSource(root)
		val puppet = imported.puppet
		Assume.assumeTrue("[derived-page] ${file.name} draws from its source layers, not pages", puppet.atlas.storedUvsAddressPages)
		val tileResources = imported.atlasIngest.imageResourceByTile
		val rasters = SourceArtRasters.fromPng { tileId -> tileResources[tileId]?.let(cmo3::extractLayerPng) }
		val derived = assertNotNull(deriveAtlasTextures(puppet, rasters, premultipliedAlpha = false), "the atlas derives")
		val filePages = cmo3AtlasPages(root, cmo3::extractLayerPng)

		var comparedPages = 0
		val failures = ArrayList<String>()
		for (pageIndex in puppet.atlas.pages.indices) {
			// The file's page for this model page: the one a drawable over a tile placed here samples.
			val drawable =
				puppet.drawables.firstOrNull { candidate ->
					candidate.atlasTileId?.let { tileId -> puppet.atlas.tileById[tileId]?.placement?.pageIndex } == pageIndex &&
						filePages.atlasIndexByDrawableId.containsKey(candidate.id.raw)
				} ?: continue
			val filePage: RasterImage = PngCodec.read(filePages.pageBytes[filePages.atlasIndexByDrawableId.getValue(drawable.id.raw)])
			val derivedPage = derived.atlases[pageIndex]
			if (filePage.width != derivedPage.width || filePage.height != derivedPage.height) {
				failures.add("page $pageIndex: the file's is ${filePage.width}x${filePage.height}, derived ${derivedPage.width}x${derivedPage.height}")
				continue
			}
			comparedPages++
			var paintedOverEmpty = 0
			var maximumAlphaDifference = 0
			var colorCompared = 0
			var colorMismatches = 0
			for (pixelIndex in 0 until filePage.width * filePage.height) {
				val byteOffset = pixelIndex * 4
				val fileAlpha = filePage.rgba[byteOffset + 3].toInt() and 0xFF
				val derivedAlpha = derivedPage.rgba[byteOffset + 3].toInt() and 0xFF
				if (fileAlpha < EMPTY_ALPHA && derivedAlpha >= PAINTED_ALPHA) {
					paintedOverEmpty++
				}
				maximumAlphaDifference = maxOf(maximumAlphaDifference, abs(fileAlpha - derivedAlpha))
				if (fileAlpha >= COLOR_COMPARED_ALPHA && derivedAlpha >= COLOR_COMPARED_ALPHA) {
					colorCompared++
					val colorDifference =
						(0 until 3).maxOf { channel -> abs((filePage.rgba[byteOffset + channel].toInt() and 0xFF) - (derivedPage.rgba[byteOffset + channel].toInt() and 0xFF)) }
					if (colorDifference > COLOR_TOLERANCE) {
						colorMismatches++
					}
				}
			}
			val colorMismatchFraction = if (colorCompared == 0) 0f else colorMismatches.toFloat() / colorCompared
			println(
				"[derived-page] ${file.name} page $pageIndex: $paintedOverEmpty pixels painted over empty page, alpha within $maximumAlphaDifference, " +
					"$colorMismatches of $colorCompared compared pixels off by more than $COLOR_TOLERANCE in color",
			)
			if (paintedOverEmpty > 0) {
				failures.add("page $pageIndex: $paintedOverEmpty pixels painted where the file's page is empty")
			}
			if (maximumAlphaDifference > ALPHA_TOLERANCE) {
				failures.add("page $pageIndex: a pixel's alpha differs from the file's by $maximumAlphaDifference")
			}
			if (colorMismatchFraction > MAXIMUM_COLOR_MISMATCH_FRACTION) {
				failures.add("page $pageIndex: $colorMismatchFraction of compared pixels differ in color from the file's")
			}
		}
		assertTrue(comparedPages > 0, "no page was compared; the gate checked nothing")
		assertTrue(failures.isEmpty(), "the derived pages differ from the file's own:\n" + failures.joinToString("\n"))
	}
}