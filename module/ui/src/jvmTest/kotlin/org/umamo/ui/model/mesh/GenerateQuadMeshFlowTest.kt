package org.umamo.ui.model.mesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.umamo.edit.EditorSession
import org.umamo.edit.OperatorParameter
import org.umamo.edit.SelectionOps
import org.umamo.edit.SelectionTarget
import org.umamo.edit.intValue
import org.umamo.edit.withParameter
import org.umamo.format.FileKind
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerRaster
import org.umamo.geometry.predicate.orient2d
import org.umamo.interop.art.BirthMeshMode
import org.umamo.interop.art.SourceArtImport
import org.umamo.interop.art.SourceArtImportOptions
import org.umamo.interop.art.mesh.ArtMeshPreset
import org.umamo.render.SourceArtRasters
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.AtlasComposition
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.Glue
import org.umamo.runtime.model.GluePair
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.applyAffine
import org.umamo.runtime.model.isUntouchedBirthQuad
import org.umamo.runtime.model.storedToArtAffineForTile
import org.umamo.ui.document.ArtDocument
import org.umamo.ui.document.DocumentLoad
import org.umamo.ui.document.InMemoryArt
import org.umamo.ui.document.InMemoryLayer
import org.umamo.ui.document.PuppetDocument
import org.umamo.ui.document.buildArtDocument
import org.umamo.ui.model.artwork.ReloadArtworkRequest
import org.umamo.ui.model.artwork.ReloadArtworkResult
import org.umamo.ui.model.artwork.ReloadEntry
import org.umamo.ui.model.artwork.runReloadArtwork
import org.umamo.ui.model.mesh.GenerateMeshFlowFixtures.ORIGIN_X
import org.umamo.ui.model.mesh.GenerateMeshFlowFixtures.ORIGIN_Y
import org.umamo.ui.model.mesh.GenerateMeshFlowFixtures.artXsOf
import org.umamo.ui.model.mesh.GenerateMeshFlowFixtures.bar
import org.umamo.ui.model.mesh.GenerateMeshFlowFixtures.disc
import org.umamo.ui.model.mesh.GenerateMeshFlowFixtures.drawableA
import org.umamo.ui.model.mesh.GenerateMeshFlowFixtures.drawableB
import org.umamo.ui.model.mesh.GenerateMeshFlowFixtures.host
import org.umamo.ui.model.mesh.GenerateMeshFlowFixtures.joinChildren
import org.umamo.ui.model.mesh.GenerateMeshFlowFixtures.meshOf
import org.umamo.ui.model.mesh.GenerateMeshFlowFixtures.model
import org.umamo.ui.model.mesh.GenerateMeshFlowFixtures.placedModel
import org.umamo.ui.model.mesh.GenerateMeshFlowFixtures.placement
import org.umamo.ui.model.mesh.GenerateMeshFlowFixtures.quad
import org.umamo.ui.model.mesh.GenerateMeshFlowFixtures.raster
import org.umamo.ui.model.mesh.GenerateMeshFlowFixtures.rasters
import org.umamo.ui.model.mesh.GenerateMeshFlowFixtures.tileA
import org.umamo.ui.model.mesh.GenerateMeshFlowFixtures.tileB
import org.umamo.ui.model.mesh.GenerateMeshFlowFixtures.unplacedAtlas
import org.umamo.ui.model.mesh.GenerateMeshFlowFixtures.vertexCountOf
import org.umamo.ui.model.repack.AtlasRepackHost
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Generate Quad Mesh end to end over synthetic art: a generated mesh goes back to the import's own quad
 * (which a reload then treats as untouched), twins stay alike, glued points stay in the quad, split
 * pieces keep to their own regions, the Margin and Alpha Threshold rows, a run a row could rescue, the
 * atlas room rules, one run at a time across both commands, and a mirrored mesh's winding.
 */
class GenerateQuadMeshFlowTest {
	private val standard = MeshGenerationOptions.of(ArtMeshPreset.Standard)
	private val quadMesh = QuadMeshOptions()

	@Test
	fun aGeneratedMeshGoesBackToTheImportsQuad() =
		runBlocking {
			val model = model(listOf(quad(drawableA, tileA, 10f, 10f, 54f, 54f)), unplacedAtlas(listOf(tileA)))
			val session = EditorSession(model)
			val host = host(session, rasters(tileA to disc()), this)
			assertTrue(runGenerateMesh(host, listOf(drawableA), standard, null))
			val generated = session.model.value
			assertTrue(vertexCountOf(generated, drawableA) > 4)
			val stepsBefore = session.historyView.value.steps.size

			assertTrue(runGenerateMesh(host, listOf(drawableA), quadMesh, null))

			// The disc's trim runs 12..52; the quad reaches the 2 px margin past it.
			val mesh = meshOf(session.model.value, drawableA)
			val expected = floatArrayOf(10f, 10f, 54f, 10f, 54f, 54f, 10f, 54f)
			assertContentEquals(intArrayOf(0, 1, 2, 0, 2, 3), mesh.indices)

			for (component in expected.indices) {
				assertEquals(expected[component] + if (component % 2 == 0) ORIGIN_X else ORIGIN_Y, mesh.positions[component], 1e-3f, "corner component $component")
			}

			val artUvs = applyAffine(mesh.uvs, assertNotNull(session.model.value.atlas.storedToArtAffineForTile(tileA)))
			assertTrue(mesh.isUntouchedBirthQuad(artUvs, 64, 64, ORIGIN_X, ORIGIN_Y), "a reload takes it for the import's own quad")
			assertEquals(stepsBefore + 1, session.historyView.value.steps.size)
			assertEquals("change.mesh.generateQuad", session.historyView.value.steps.last().labelKey)
			assertEquals("notice.mesh.generated", session.notice.value?.messageKey)
			assertEquals(listOf("1"), session.notice.value?.arguments)
			session.undo()
			assertSame(generated, session.model.value, "one undo brings the generated mesh back")
		}

	@Test
	fun twinsStayAlike() =
		runBlocking {
			val model = model(listOf(quad(drawableA, tileA, 10f, 10f, 54f, 54f), quad(drawableB, tileA, 10f, 10f, 54f, 54f, originX = 300f)), unplacedAtlas(listOf(tileA)))
			val session = EditorSession(model)
			val host = host(session, rasters(tileA to disc()), this)
			runGenerateMesh(host, listOf(drawableA), standard, null)

			assertTrue(runGenerateMesh(host, listOf(drawableA), quadMesh, null))

			val first = meshOf(session.model.value, drawableA)
			val second = meshOf(session.model.value, drawableB)
			assertEquals(4, second.vertexCount, "the twin got its quad too")
			assertContentEquals(first.uvs, second.uvs)
			assertContentEquals(first.indices, second.indices)
		}

	@Test
	fun aGluedPointOutsideTheArtStaysInsideTheQuad() =
		runBlocking {
			// Vertex 0 of a's mesh sits at art (4, 4), outside the disc's trim and past the margin: the quad
			// grows to hold it, and it stays a vertex with its glue.
			val glue = Glue(drawableA, drawableB, listOf(GluePair(0, 0, 0.5f, 0.5f)))
			val model = model(listOf(quad(drawableA, tileA, 4f, 4f, 60f, 60f), quad(drawableB, tileB, 10f, 10f, 54f, 54f)), unplacedAtlas(listOf(tileA, tileB))).copy(glues = listOf(glue))
			val session = EditorSession(model)

			assertTrue(runGenerateMesh(host(session, rasters(tileA to disc(), tileB to disc()), this), listOf(drawableA), quadMesh, null))

			val mesh = meshOf(session.model.value, drawableA)
			val pair = session.model.value.glues.single().pairs.single()
			assertEquals(5, mesh.vertexCount, "four corners and the glued point")
			assertEquals(4f + ORIGIN_X, mesh.positions[2 * pair.indexA], 1e-3f, "the glue names the kept point")
			assertEquals(4f + ORIGIN_Y, mesh.positions[2 * pair.indexA + 1], 1e-3f)
			assertEquals(listOf(2f, 54f), artXsOf(session.model.value, drawableA).filterIndexed { vertex, _ -> vertex != pair.indexA }.distinct().sorted(), "the quad reaches 2 px past the glued point")
		}

	@Test
	fun splitPiecesKeepToTheirOwnRegions() =
		runBlocking {
			// The bar runs x 4..59 over a tile split at x = 32.  Each piece's quad covers the art its old mesh
			// covered - its region dilated by a pixel - and the margin past that.
			val split = model(listOf(quad(drawableA, tileA, 0f, 0f, 32f, 32f, tileHeight = 32f), quad(drawableB, tileA, 32f, 0f, 64f, 32f, tileHeight = 32f)), unplacedAtlas(listOf(tileA), height = 32))
			val session = EditorSession(split)

			assertTrue(runGenerateMesh(host(session, rasters(tileA to bar()), this), listOf(drawableA), quadMesh, null))

			assertEquals(listOf(2f, 35f), artXsOf(session.model.value, drawableA).distinct().sorted(), "the left piece stays on its half")
			assertEquals(listOf(29f, 62f), artXsOf(session.model.value, drawableB).distinct().sorted(), "the right piece stays on its half")
		}

	@Test
	fun aMarginAdjustmentRequadsFromTheBaseAndIsRemembered() =
		runBlocking {
			val model = model(listOf(quad(drawableA, tileA, 10f, 10f, 54f, 54f)), unplacedAtlas(listOf(tileA)))
			val session = EditorSession(model)
			var remembered: MeshRecipe? = null
			val stepsBefore = session.historyView.value.steps.size
			runGenerateMesh(host(session, rasters(tileA to disc()), this) { recipe -> remembered = recipe }, listOf(drawableA), quadMesh, null)
			val record = assertNotNull(session.adjustableOperation.value)

			session.adjustLastOperation(record.parameters.withMargin(5), adjustedKey = QuadMeshParameterKeys.MARGIN)
			joinChildren()

			assertEquals(listOf(7f, 57f), artXsOf(session.model.value, drawableA).distinct().sorted())
			assertEquals(QuadMeshOptions(margin = 5, alphaThreshold = 1), remembered)
			assertEquals(stepsBefore + 1, session.historyView.value.steps.size, "the adjustment landed over the run's own step")
			session.undo()
			assertSame(model, session.model.value)
		}

	@Test
	fun theThresholdRowStartsAtTheAtlasThreshold() =
		runBlocking {
			// A core at alpha 255 inside a faint field at alpha 30: under the atlas's threshold of 40 the field
			// is no art, so the quad fits the core.
			val faintField = raster(64, 64, alpha = 30) { column, row -> column in 4..59 && row in 4..59 }
			val core = raster(64, 64) { column, row -> column in 20..43 && row in 20..43 }
			val art = rasters(tileA to org.umamo.render.DecodedImage(ByteArray(64 * 64 * 4) { component -> maxOf(faintField.rgba[component].toInt() and 0xFF, core.rgba[component].toInt() and 0xFF).toByte() }, 64, 64))
			val atlas = unplacedAtlas(listOf(tileA)).copy(composition = AtlasComposition(alphaThreshold = 40))
			val session = EditorSession(model(listOf(quad(drawableA, tileA, 10f, 10f, 54f, 54f)), atlas))

			assertTrue(runGenerateMesh(host(session, art, this), listOf(drawableA), quadMesh, null))

			val row = assertNotNull(session.adjustableOperation.value).parameters.first { parameter -> parameter.key == MeshGenerationParameterKeys.ALPHA_THRESHOLD } as OperatorParameter.IntParameter
			assertEquals(40, row.value)
			assertEquals(40, row.min)
			assertEquals(listOf(18f, 46f), artXsOf(session.model.value, drawableA).distinct().sorted(), "the faint field is no art")
		}

	@Test
	fun aQuadPlanBuildsNoGuides() {
		val folded = model(listOf(foldedQuad()), unplacedAtlas(listOf(tileA)))
		val decode = RunTileDecoder { _ -> disc() }

		assertTrue(planMeshGeneration(folded, listOf(drawableA), decode).guidesByDrawable.isNotEmpty(), "the folded rig gives a guide")
		assertTrue(planMeshGeneration(folded, listOf(drawableA), decode, guided = false).guidesByDrawable.isEmpty(), "a quad plan builds none")
	}

	@Test
	fun artUnderTheThresholdOpensTheStripOnAnUnchangedStep() =
		runBlocking {
			val model = model(listOf(quad(drawableA, tileA, 10f, 10f, 54f, 54f)), unplacedAtlas(listOf(tileA)))
			val session = EditorSession(model)
			val stepsBefore = session.historyView.value.steps.size

			assertFalse(runGenerateMesh(host(session, rasters(tileA to disc(alpha = 10)), this), listOf(drawableA), QuadMeshOptions(alphaThreshold = 20), null))

			assertSame(model, session.model.value)
			assertEquals("notice.mesh.noneGenerated", session.notice.value?.messageKey)
			assertEquals(stepsBefore + 1, session.historyView.value.steps.size)
			assertEquals("change.mesh.generateQuad", session.historyView.value.steps.last().labelKey)
			val record = assertNotNull(session.adjustableOperation.value, "the strip opens")
			val threshold = record.parameters.first { row -> row.key == MeshGenerationParameterKeys.ALPHA_THRESHOLD } as OperatorParameter.IntParameter

			session.adjustLastOperation(record.parameters.withParameter(MeshGenerationParameterKeys.ALPHA_THRESHOLD, threshold.copy(value = 8)), adjustedKey = MeshGenerationParameterKeys.ALPHA_THRESHOLD)
			joinChildren()

			assertEquals(listOf(10f, 54f), artXsOf(session.model.value, drawableA).distinct().sorted(), "the lowered threshold finds the art")
			assertEquals(stepsBefore + 1, session.historyView.value.steps.size)
		}

	@Test
	fun aPinnedTileWithoutRoomNamesTheMargin() =
		runBlocking {
			// Tile B's disc starts 3 px right of tile A's; a 10 px margin would sample it, and A is pinned.
			val model = placedModel(listOf(drawableA to 2f), aPlacement = placement(40f, 40f), bPlacement = placement(83f, 40f), pinA = true)
			val session = EditorSession(model)

			assertFalse(runGenerateMesh(host(session, rasters(tileA to disc(), tileB to disc()), this), listOf(drawableA), QuadMeshOptions(margin = 10), null))

			assertSame(model, session.model.value)
			assertEquals("notice.mesh.pinnedWithoutRoom", session.notice.value?.messageKey)
			assertEquals(listOf("0", "1"), session.notice.value?.arguments)
			assertEquals(10, assertNotNull(session.adjustableOperation.value).parameters.intValue(QuadMeshParameterKeys.MARGIN, 0), "the strip opens on the Margin to lower")
		}

	@Test
	fun aQuadInsideTheOldReachKeepsTheAtlas() =
		runBlocking {
			// The old mesh covers the whole raster; the quad's 10..54 sits well inside it.
			val model = placedModel(listOf(drawableA to 0f), aPlacement = placement(40f, 40f), bPlacement = placement(110f, 40f))
			val session = EditorSession(model)

			assertTrue(runGenerateMesh(host(session, rasters(tileA to disc(), tileB to disc()), this), listOf(drawableA), quadMesh, null))

			assertEquals(4, vertexCountOf(session.model.value, drawableA))
			assertSame(model.atlas, session.model.value.atlas)
		}

	@Test
	fun oneRunGoesAtATimeAcrossBothCommands() =
		runBlocking {
			val model = model(listOf(quad(drawableA, tileA, 10f, 10f, 54f, 54f)), unplacedAtlas(listOf(tileA)))
			val session = EditorSession(model)
			val entered = CountDownLatch(1)
			val release = CountDownLatch(1)
			val decodes = AtomicInteger()
			val blocking =
				SourceArtRasters { _ ->
					decodes.incrementAndGet()
					entered.countDown()
					release.await()
					disc()
				}
			val sessionOptions = MeshGenerationSessionOptions()
			val runState = MeshGenerationRunState()
			val fromArt = generateMeshLauncher(session, blocking, null, false, this, sessionOptions, runState) { null }
			val quadLauncher = generateQuadMeshLauncher(session, blocking, null, false, this, sessionOptions, runState)

			quadLauncher(null)
			assertEquals("notice.mesh.noTargets", session.notice.value?.messageKey)

			session.setSelection(SelectionOps.replace(SelectionTarget.Drawable(drawableA)))
			fromArt(null)
			withContext(Dispatchers.IO) { entered.await() }
			quadLauncher(null)
			release.countDown()
			joinChildren()

			assertEquals(1, decodes.get(), "the quad press while the first run went started nothing")
			assertEquals("change.mesh.generateFromArt", session.historyView.value.steps.last().labelKey)
		}

	@Test
	fun aMirroredMeshKeepsItsWinding() =
		runBlocking {
			// The old quad samples its art mirrored left to right, so its triangles wind the other way in the
			// art than on the canvas; the new quad keeps the canvas winding.
			val mirrored = quad(drawableA, tileA, 10f, 10f, 54f, 54f)
			val oldMesh = assertNotNull(mirrored.mesh)
			val mirroredUvs = FloatArray(8) { component -> if (component % 2 == 0) 1f - oldMesh.uvs[component] else oldMesh.uvs[component] }
			val model = model(listOf(mirrored.copy(mesh = DrawableMesh.withLocalEqualToCanvas(oldMesh.positions, mirroredUvs, oldMesh.indices))), unplacedAtlas(listOf(tileA)))
			val session = EditorSession(model)

			assertTrue(runGenerateMesh(host(session, rasters(tileA to disc()), this), listOf(drawableA), quadMesh, null))

			val mesh = meshOf(session.model.value, drawableA)

			for (triangle in 0 until mesh.triangleCount) {
				assertTrue(canvasOrientation(mesh, triangle) > 0.0, "triangle $triangle keeps the old canvas winding")
			}
		}

	@Test
	fun aQuadFromTheCommandIsResizedByTheNextReload() =
		runBlocking {
			// Artwork born with a generated mesh, given its quad back, and then repainted larger: the reload
			// takes the quad for an untouched one and fits it to the new art.
			val automatic = SourceArtImportOptions(alphaThreshold = 1, birthMeshMargin = 2, birthMesh = BirthMeshMode.Automatic(ArtMeshPreset.Standard.settings))
			val layer = InMemoryLayer("lyid:1", "Disc", 0, LayerBounds(10, 10, 40, 40), discRaster(radius = 10.0))
			val document = assertIs<ArtDocument>(assertIs<DocumentLoad.Loaded>(buildArtDocument(InMemoryArt(listOf(layer)), FileKind.Psd, "a.psd", "/art/a.psd", automatic)).document)
			val session = EditorSession(document.puppet, document.liveParams.values)
			val drawableId = session.model.value.drawables.single().id
			assertTrue(vertexCountOf(session.model.value, drawableId) > 4, "born a generated mesh")
			val meshHost = GenerateMeshHost(session, document.artRasters, sessionAtlasPages = null, premultipliedAlpha = document.textures.premultipliedAlpha, scope = this, rememberOptions = {})
			assertTrue(runGenerateMesh(meshHost, listOf(drawableId), quadMesh, null))
			assertEquals(4, vertexCountOf(session.model.value, drawableId))

			val grown = InMemoryLayer("lyid:1", "Disc", 0, LayerBounds(10, 10, 40, 40), discRaster(radius = 18.0))
			val request = ReloadArtworkRequest(listOf(ReloadEntry(ArtSourceId("art-0"), InMemoryArt(listOf(grown)), contentHash = "v2")), automatic)
			assertEquals(ReloadArtworkResult.Applied, runReloadArtwork(repackHost(session, document, this), request, areaId = null))

			val refit = meshOf(session.model.value, drawableId)
			val expected = assertNotNull(SourceArtImport.birthMeshFor(grown, alphaThreshold = 1, margin = 2))
			assertContentEquals(expected.positions, refit.positions, "the quad fits the grown art")
			assertContentEquals(expected.indices, refit.indices)
		}

	/**
	 * The rows with the Margin row's value replaced.
	 *
	 * @param Int margin The margin.
	 * @return List<OperatorParameter> The rows.
	 */
	private fun List<OperatorParameter>.withMargin(margin: Int): List<OperatorParameter> =
		withParameter(QuadMeshParameterKeys.MARGIN, (first { row -> row.key == QuadMeshParameterKeys.MARGIN } as OperatorParameter.IntParameter).copy(value = margin))

	/**
	 * A drawable over the disc art meshed by a 3 x 3 grid from 12 to 52, whose canvas is the art moved by
	 * the origin with its middle row lifted 15 px: a bend a guide would hold.
	 *
	 * @return Drawable The drawable.
	 */
	private fun foldedQuad(): Drawable {
		val art = FloatArray(18) { component -> 12f + 20f * if (component % 2 == 0) (component / 2) % 3 else (component / 2) / 3 }
		val canvas = FloatArray(art.size) { component -> art[component] + if (component % 2 == 0) ORIGIN_X else ORIGIN_Y - if (component / 2 / 3 == 1) 15f else 0f }
		val indices = intArrayOf(0, 1, 4, 0, 4, 3, 1, 2, 5, 1, 5, 4, 3, 4, 7, 3, 7, 6, 4, 5, 8, 4, 8, 7)

		return Drawable(
			id = drawableA,
			name = drawableA.raw,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh.withLocalEqualToCanvas(canvas, FloatArray(art.size) { component -> art[component] / 64f }, indices),
			geometryGrid = null,
			atlasTileId = tileA,
		)
	}

	/**
	 * Twice one triangle's signed area on its canvas positions.
	 *
	 * @param DrawableMesh mesh     The mesh.
	 * @param Int          triangle The triangle.
	 * @return Double The orientation: positive counterclockwise in raw coordinates.
	 */
	private fun canvasOrientation(mesh: DrawableMesh, triangle: Int): Double {
		val corners = IntArray(3) { corner -> mesh.indices[3 * triangle + corner] }

		return orient2d(
			mesh.positions[2 * corners[0]].toDouble(),
			mesh.positions[2 * corners[0] + 1].toDouble(),
			mesh.positions[2 * corners[1]].toDouble(),
			mesh.positions[2 * corners[1] + 1].toDouble(),
			mesh.positions[2 * corners[2]].toDouble(),
			mesh.positions[2 * corners[2] + 1].toDouble(),
		)
	}

	/**
	 * A 40 px raster holding an opaque disc at its center.
	 *
	 * @param Double radius The disc's radius.
	 * @return LayerRaster The raster.
	 */
	private fun discRaster(radius: Double): LayerRaster =
		LayerRaster(40, 40, ByteArray(40 * 40 * 4) { component -> if (component % 4 == 3 && hypot((component / 4) % 40 + 0.5 - 20.0, (component / 4) / 40 + 0.5 - 20.0) <= radius) 0xFF.toByte() else 0 })

	/**
	 * The host a reload runs over, refusing nothing.
	 *
	 * @param EditorSession  session  The session.
	 * @param PuppetDocument document The document.
	 * @param CoroutineScope scope    Where adjustments run.
	 * @return AtlasRepackHost The host.
	 */
	private fun repackHost(session: EditorSession, document: PuppetDocument, scope: CoroutineScope): AtlasRepackHost =
		AtlasRepackHost(
			session = session,
			artRasters = document.artRasters,
			sessionAtlasPages = null,
			premultipliedAlpha = document.textures.premultipliedAlpha,
			scope = scope,
			report = { report -> error("nothing here may refuse: ${report.refusals.joinToString { refusal -> "${refusal.tileName}: ${refusal.reason}" }}") },
			rememberOptions = { _, _ -> },
		)
}