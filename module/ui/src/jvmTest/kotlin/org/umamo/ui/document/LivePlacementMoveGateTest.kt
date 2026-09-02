package org.umamo.ui.document

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.umamo.edit.EditorSession
import org.umamo.edit.setAtlasPlacements
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.interop.cmo3.cmo3AtlasPages
import org.umamo.render.DecodedImage
import org.umamo.render.derivedTileTrim
import org.umamo.render.encodeAtlasPng
import org.umamo.render.placementFootprint
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.applyUvAffine
import org.umamo.runtime.model.storedToArtAffineForTile
import org.umamo.ui.model.AtlasRepackReport
import org.umamo.ui.model.SessionAtlasPages
import org.umamo.ui.model.runAtlasRepack
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The live placement-move gate, driven the way the gizmo drives it: a repack (so the atlas is a
 * generated one whose numbering the resolver and the export share), then one tile moved by whole
 * pixels and another turned and reduced through the session's placement commit, the page resolver
 * recomposing, and the export writing what the resolver shows.  Proves the pieces agree: the moved
 * tile's pixels sit at the new spot verbatim, its vertices still sample the same art pixels, and the
 * file reads back with the placements, coordinates, and pages the session holds.
 *
 * Skips without the corpus sample.
 */
class LivePlacementMoveGateTest {
	private val sample: File? = System.getProperty("cmo3.sample")?.let(::File)?.takeIf { it.isFile }

	private fun pixelBytes(image: DecodedImage, x: Int, y: Int): ByteArray {
		val offset = (y * image.width + x) * 4
		return image.rgba.copyOfRange(offset, offset + 4)
	}

	private fun assertPlacementClose(expected: AtlasPlacement, actual: AtlasPlacement?, name: String) {
		assertNotNull(actual, "'$name' reimports placed")
		assertEquals(expected.pageIndex, actual.pageIndex, "'$name' page")
		assertEquals(expected.positionX, actual.positionX, 1e-2f, "'$name' position x")
		assertEquals(expected.positionY, actual.positionY, 1e-2f, "'$name' position y")
		assertEquals(expected.scaleX, actual.scaleX, 1e-3f, "'$name' scale x")
		assertEquals(expected.scaleY, actual.scaleY, 1e-3f, "'$name' scale y")
		assertEquals(expected.rotationDegrees, actual.rotationDegrees, 1e-2f, "'$name' rotation")
	}

	@Test
	fun aLivePlacementMoveExportsTheMovedArrangement() =
		runBlocking {
			val file = sample
			if (file == null) {
				println("cmo3.sample not present; skipping the live placement move gate")
				return@runBlocking
			}
			val load = loadDocument(file.readBytes(), file.name, file.path)
			val document = assertIs<Cmo3Document>(assertIs<DocumentLoad.Loaded>(load).document)
			val session = EditorSession(document.puppet, document.liveParams.values)
			val sessionAtlasPages = SessionAtlasPages(session, document.puppet.atlas, document.textures, document.artRasters)
			val follower = launch { sessionAtlasPages.follow() }
			var refusal: AtlasRepackReport? = null
			runAtlasRepack(session, document.artRasters, sessionAtlasPages, document.textures.premultipliedAlpha) { report ->
				refusal = report
			}
			assertNull(refusal?.refusals?.joinToString { "${it.tileName}: ${it.reason}" }, "the repack refused")
			val repacked = session.model.value
			withTimeout(30_000) {
				while (sessionAtlasPages.binding.value.atlas !== repacked.atlas) {
					yield()
				}
			}

			// Two bound, placed tiles with art: one to move by whole pixels, one to turn and reduce.
			val boundTileIds = repacked.drawables.mapNotNullTo(HashSet()) { drawable -> drawable.atlasTileId }
			val candidates =
				repacked.atlas.tiles.filter { tile ->
					tile.placement != null && tile.id in boundTileIds && document.artRasters.rasterFor(tile.id) != null
				}
			assertTrue(candidates.size >= 2, "the sample packs at least two bound tiles with art")
			val mover = candidates[0]
			val turner = candidates[1]
			val moverPlacement = assertNotNull(mover.placement)
			val moverRaster = assertNotNull(document.artRasters.rasterFor(mover.id))
			val moverTrim = assertNotNull(derivedTileTrim(moverRaster))
			val page = repacked.atlas.pages[moverPlacement.pageIndex]
			val footprint = placementFootprint(moverPlacement, moverTrim, reserve = null)
			val deltaX = if (footprint.right + 9 <= page.width) 7 else -7
			val deltaY = if (footprint.bottom + 7 <= page.height) 5 else -5
			val movedPlacement = moverPlacement.copy(positionX = moverPlacement.positionX + deltaX, positionY = moverPlacement.positionY + deltaY)
			val turnedPlacement = assertNotNull(turner.placement).copy(rotationDegrees = 30f, scaleX = 0.75f, scaleY = 0.75f)
			val moverDrawable = repacked.drawables.first { drawable -> drawable.atlasTileId == mover.id && (drawable.mesh?.uvs?.size ?: 0) >= 2 }
			val artUvsBefore =
				applyUvAffine(assertNotNull(moverDrawable.mesh).uvs, assertNotNull(repacked.atlas.storedToArtAffineForTile(mover.id)))

			session.setAtlasPlacements(mapOf(mover.id to movedPlacement, turner.id to turnedPlacement))

			val moved = session.model.value
			assertNotSame(repacked.atlas, moved.atlas, "the move committed a new atlas")
			assertEquals(movedPlacement, moved.atlas.tileById.getValue(mover.id).placement)
			assertEquals(turnedPlacement, moved.atlas.tileById.getValue(turner.id).placement)
			val artUvsAfter =
				applyUvAffine(
					assertNotNull(moved.drawables.first { drawable -> drawable.id == moverDrawable.id }.mesh).uvs,
					assertNotNull(moved.atlas.storedToArtAffineForTile(mover.id)),
				)
			for (componentIndex in artUvsBefore.indices) {
				assertTrue(
					abs(artUvsBefore[componentIndex] - artUvsAfter[componentIndex]) < 1e-4f,
					"'${moverDrawable.name}' samples the same art pixel after the move (component $componentIndex)",
				)
			}

			withTimeout(60_000) {
				while (sessionAtlasPages.binding.value.atlas !== moved.atlas) {
					yield()
				}
			}
			val effective = sessionAtlasPages.binding.value.textures
			val composedPage = effective.atlases[movedPlacement.pageIndex]
			assertContentEquals(
				pixelBytes(moverRaster, moverTrim.left + 1, moverTrim.top + 1),
				pixelBytes(composedPage, movedPlacement.positionX.toInt() + moverTrim.left + 1, movedPlacement.positionY.toInt() + moverTrim.top + 1),
				"the moved tile's pixels sit at the new spot verbatim",
			)

			val prepared =
				prepareCmo3Export(
					document = document,
					edited = exportedModelFor(document, session),
					effectiveTextures = effective,
					modelName = "gate",
					nowMillis = 0L,
					obfuscateKey = 0,
				)
			follower.cancel()
			println("export report: ${prepared.report}")
			val reread = Cmo3.read(Cmo3.write(prepared.model))
			val reimported = Cmo3Import.fromModelSource(reread.root as CModelSource)
			assertEquals(moved.atlas.pages, reimported.atlas.pages, "page inventory")
			assertPlacementClose(movedPlacement, reimported.atlas.tileById[mover.id]?.placement, mover.name)
			assertPlacementClose(turnedPlacement, reimported.atlas.tileById[turner.id]?.placement, turner.name)
			val editedUvs = moved.drawables.associate { drawable -> drawable.id to drawable.mesh?.uvs }
			var uvMismatches = 0
			for (drawable in reimported.drawables) {
				val expected = editedUvs[drawable.id] ?: continue
				if (!expected.contentEquals(drawable.mesh?.uvs)) {
					uvMismatches++
				}
			}
			assertEquals(0, uvMismatches, "drawables whose exported uvs differ from the session's")
			val exportedPages = cmo3AtlasPages(reread.root as CModelSource) { resource -> reread.extractLayerPng(resource) }.pageBytes
			val expectedPages = effective.atlases.map { pageImage -> encodeAtlasPng(pageImage) }
			assertEquals(expectedPages.size, exportedPages.size, "exported page count")
			for ((pageIndex, bytes) in exportedPages.withIndex()) {
				assertTrue(expectedPages.any { expected -> expected.contentEquals(bytes) }, "exported page $pageIndex is not one of the resolver's pages")
			}
		}
}