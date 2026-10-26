package org.umamo.ui.document

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.umamo.edit.EditorSession
import org.umamo.edit.OperatorParameter
import org.umamo.format.FileKind
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.alphaChannel
import org.umamo.interop.art.ArtSourceDescriptor
import org.umamo.interop.art.BirthMeshMode
import org.umamo.interop.art.BirthMesher
import org.umamo.interop.art.BirthOutcome
import org.umamo.interop.art.SourceArtImport
import org.umamo.interop.art.SourceArtImportOptions
import org.umamo.interop.art.mesh.ArtMeshPreset
import org.umamo.interop.art.mesh.meshCoversOpaquePixels
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.applyUvAffine
import org.umamo.runtime.model.storedToArtAffineForTile
import org.umamo.ui.model.artwork.AddArtworkRequest
import org.umamo.ui.model.artwork.ImportParameterKeys
import org.umamo.ui.model.artwork.ReloadArtworkRequest
import org.umamo.ui.model.artwork.ReloadArtworkResult
import org.umamo.ui.model.artwork.ReloadEntry
import org.umamo.ui.model.artwork.resolveBirths
import org.umamo.ui.model.artwork.runAddArtwork
import org.umamo.ui.model.artwork.runReloadArtwork
import org.umamo.ui.model.repack.AtlasRepackHost
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Automatic birth mode driven the way the shell drives it, over in-memory art: an add births every
 * layer a mesh that covers its art through the pack, an adjustment that moves the art places the same
 * meshes again from the request's cache and keeps the mode, a raised Alpha Threshold row meshes the art
 * tighter, a reload births the layers a file gained, and the parallel births equal the sequential ones.
 */
class AutomaticBirthFlowTest {
	private val automatic = SourceArtImportOptions(alphaThreshold = 1, birthMeshMargin = 2, birthMesh = BirthMeshMode.Automatic(ArtMeshPreset.Standard.settings))
	private val quad = SourceArtImportOptions(alphaThreshold = 1, birthMeshMargin = 2)

	/**
	 * A 40 x 40 raster: an opaque disc of radius 10 at its center inside a faint ring, alpha 40, out to radius 16.
	 *
	 * @return LayerRaster The raster.
	 */
	private fun discWithGlow(): LayerRaster {
		val rgba = ByteArray(40 * 40 * 4)

		for (pixel in 0 until 40 * 40) {
			val distance = hypot(pixel % 40 + 0.5 - 20.0, pixel / 40 + 0.5 - 20.0)

			if (distance <= 16.0) {
				rgba[pixel * 4] = 9
				rgba[pixel * 4 + 3] = if (distance <= 10.0) 0xFF.toByte() else 40
			}
		}

		return LayerRaster(40, 40, rgba)
	}

	/**
	 * The host the shell builds over an open document's session, refusing nothing.
	 *
	 * @param EditorSession  session  The session.
	 * @param PuppetDocument document The document.
	 * @param CoroutineScope scope    Where adjustments run.
	 * @return AtlasRepackHost The host.
	 */
	private fun hostOver(session: EditorSession, document: PuppetDocument, scope: CoroutineScope): AtlasRepackHost =
		AtlasRepackHost(
			session = session,
			artRasters = document.artRasters,
			sessionAtlasPages = null,
			premultipliedAlpha = document.textures.premultipliedAlpha,
			scope = scope,
			report = { report -> error("nothing here may refuse: ${report.refusals.joinToString { "${it.tileName}: ${it.reason}" }}") },
			rememberOptions = { _, _ -> },
		)

	/**
	 * Whether a drawable's mesh, read through its tile's stored-to-art map, covers every pixel of [raster] at
	 * or above [alphaThreshold].
	 *
	 * @param PuppetModel model          The model.
	 * @param Drawable    drawable       The drawable.
	 * @param LayerRaster raster         Its tile's art.
	 * @param Int         alphaThreshold The least alpha counted as art.
	 * @return Boolean True when it covers the art.
	 */
	private fun coversItsArt(model: PuppetModel, drawable: Drawable, raster: LayerRaster, alphaThreshold: Int): Boolean {
		val mesh = assertNotNull(drawable.mesh)
		val artUvs = applyUvAffine(mesh.uvs, assertNotNull(model.atlas.storedToArtAffineForTile(assertNotNull(drawable.atlasTileId))))
		val artPoints = FloatArray(artUvs.size) { component -> artUvs[component] * if (component % 2 == 0) raster.width else raster.height }

		return meshCoversOpaquePixels(raster.alphaChannel(), alphaThreshold, artPoints, mesh.indices)
	}

	@Test
	fun anAddBornsEveryLayerAMeshThatCoversItsArtThroughThePack() =
		runBlocking {
			val document = newBlankDocument()
			val session = EditorSession(document.puppet, document.liveParams.values)
			val disc = discWithGlow()
			val art = InMemoryArt(listOf(InMemoryLayer("lyid:1", "Disc", 0, LayerBounds(10, 10, 40, 40), disc)))

			assertTrue(runAddArtwork(hostOver(session, document, this), AddArtworkRequest(art, ArtSourceDescriptor("disc.psd", "/art/disc.psd", "psd"), automatic), areaId = null))

			val added = session.model.value.drawables.single()
			assertTrue(assertNotNull(added.mesh).vertexCount > 4, "a generated mesh, not a quad")
			assertNotNull(session.model.value.atlas.tileById.getValue(assertNotNull(added.atlasTileId)).placement, "packed")
			assertTrue(coversItsArt(session.model.value, added, disc, alphaThreshold = 1), "every pixel the quad would draw, the faint glow included")
		}

	@Test
	fun anOffsetAdjustmentPlacesTheSameMeshesAgainAndKeepsTheMode() =
		runBlocking {
			val document = newBlankDocument()
			val session = EditorSession(document.puppet, document.liveParams.values)
			val host = hostOver(session, document, this)
			val hostArt = InMemoryArt(listOf(InMemoryLayer("lyid:1", "Host", 0, LayerBounds(4, 4, 8, 8), solidRaster(8, 8, 1))), widthPx = 128, heightPx = 128)
			assertTrue(runAddArtwork(host, AddArtworkRequest(hostArt, ArtSourceDescriptor("host.psd", "/art/host.psd", "psd"), quad), areaId = null))
			val request = AddArtworkRequest(InMemoryArt(listOf(InMemoryLayer("lyid:9", "Disc", 0, LayerBounds(0, 0, 40, 40), discWithGlow())), widthPx = 48, heightPx = 48), ArtSourceDescriptor("disc.png", "/art/disc.png", "png"), automatic)
			assertTrue(runAddArtwork(host, request, areaId = null))
			val placed = session.model.value.drawables.first { drawable -> drawable.name == "Disc" }
			val before = assertNotNull(placed.mesh)
			assertTrue(before.vertexCount > 4)
			assertEquals(1, request.births.size, "the birth is kept for the request's adjustments")

			val record = assertNotNull(session.adjustableOperation.value)
			session.adjustLastOperation(record.parameters.map { parameter -> if (parameter.key == ImportParameterKeys.OFFSET_X && parameter is OperatorParameter.IntParameter) parameter.copy(value = parameter.value + 5) else parameter })
			withTimeout(120_000) {
				while (session.model.value.drawables.first { drawable -> drawable.name == "Disc" }.mesh?.positions?.contentEquals(before.positions) != false) {
					yield()
				}
			}

			val after = assertNotNull(session.model.value.drawables.first { drawable -> drawable.name == "Disc" }.mesh)
			assertEquals(1, request.births.size, "nothing was meshed again")
			assertContentEquals(before.indices, after.indices, "the same mesh, still generated")
			assertContentEquals(FloatArray(before.positions.size) { component -> before.positions[component] + if (component % 2 == 0) 5f else 0f }, after.positions, "placed again 5 px to the right")
		}

	@Test
	fun aRaisedAlphaThresholdRowMeshesTheArtTighter() =
		runBlocking {
			val document = newBlankDocument()
			val session = EditorSession(document.puppet, document.liveParams.values)
			val art = InMemoryArt(listOf(InMemoryLayer("lyid:1", "Disc", 0, LayerBounds(10, 10, 40, 40), discWithGlow())))
			assertTrue(runAddArtwork(hostOver(session, document, this), AddArtworkRequest(art, ArtSourceDescriptor("disc.psd", "/art/disc.psd", "psd"), automatic), areaId = null))
			val wide = assertNotNull(session.model.value.drawables.single().mesh)

			val record = assertNotNull(session.adjustableOperation.value)
			session.adjustLastOperation(record.parameters.map { parameter -> if (parameter.key == ImportParameterKeys.ALPHA_THRESHOLD && parameter is OperatorParameter.IntParameter) parameter.copy(value = 100) else parameter })
			withTimeout(120_000) {
				while (session.model.value.drawables.single().mesh === wide) {
					yield()
				}
			}

			val tight = assertNotNull(session.model.value.drawables.single().mesh)
			assertTrue(widthOf(tight.positions) < widthOf(wide.positions) - 4f, "the glow is no art at 100: ${widthOf(wide.positions)} -> ${widthOf(tight.positions)}")
		}

	@Test
	fun aReloadBornsTheLayersAFileGained() =
		runBlocking {
			val first = InMemoryLayer("lyid:1", "First", 0, LayerBounds(10, 10, 8, 8), solidRaster(8, 8, 1))
			val load = buildArtDocument(InMemoryArt(listOf(first)), FileKind.Psd, "a.psd", "/art/a.psd", automatic)
			val document = assertIs<ArtDocument>(assertIs<DocumentLoad.Loaded>(load).document)
			val session = EditorSession(document.puppet, document.liveParams.values)
			val gained = InMemoryLayer("lyid:2", "Gained", -1, LayerBounds(20, 20, 40, 40), discWithGlow())
			val request = ReloadArtworkRequest(listOf(ReloadEntry(ArtSourceId("art-0"), InMemoryArt(listOf(first, gained)), contentHash = "v2")), automatic)

			assertEquals(ReloadArtworkResult.Applied, runReloadArtwork(hostOver(session, document, this), request, areaId = null))

			val born = session.model.value.drawables.first { drawable -> drawable.name == "Gained" }
			assertTrue(assertNotNull(born.mesh).vertexCount > 4, "the gained layer is born like an import's")
			assertEquals(1, request.births.size)
		}

	@Test
	fun parallelAndSequentialBirthsAgree() =
		runBlocking {
			val layers = (0 until 6).map { index -> InMemoryLayer("lyid:$index", "Layer $index", index, LayerBounds(index * 4, index * 3, 40, 40), discWithGlow()) }
			val named = SourceArtImport.fromSourceArt(InMemoryArt(layers), ArtSourceDescriptor("many.psd", null, "psd"), automatic)
			val sequential = BirthMesher.resolveSequentially(named.pendingBirths)
			val parallel = withContext(Dispatchers.Default) { resolveBirths(named.pendingBirths, cache = null, progress = null) }

			assertEquals(sequential.outcomes.keys, parallel.outcomes.keys)

			for ((drawableId, outcome) in sequential.outcomes) {
				val one = assertIs<BirthOutcome.Born>(outcome).mesh
				val other = assertIs<BirthOutcome.Born>(parallel.outcomes.getValue(drawableId)).mesh
				assertContentEquals(one.positions, other.positions, "$drawableId positions")
				assertContentEquals(one.indices, other.indices, "$drawableId triangles")
			}
		}

	/**
	 * A mesh's horizontal extent.
	 *
	 * @param FloatArray positions The positions.
	 * @return Float The extent.
	 */
	private fun widthOf(positions: FloatArray): Float {
		val xs = positions.filterIndexed { component, _ -> component % 2 == 0 }

		return xs.max() - xs.min()
	}
}