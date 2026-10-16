package org.umamo.ui.model.mesh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.OperatorParameter
import org.umamo.edit.SelectionOps
import org.umamo.edit.SelectionTarget
import org.umamo.edit.floatValue
import org.umamo.edit.intValue
import org.umamo.edit.structure.rename
import org.umamo.edit.withParameter
import org.umamo.geometry.locate.TriangleLocator
import org.umamo.interop.art.mesh.ArtMeshPreset
import org.umamo.render.DecodedImage
import org.umamo.render.SourceArtRasters
import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.Glue
import org.umamo.runtime.model.GluePair
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.applyUvAffine
import org.umamo.runtime.model.invertUvAffine
import org.umamo.runtime.model.storedToArtAffineForTile
import org.umamo.ui.document.blendedAt
import org.umamo.ui.model.repack.MAX_IMPORT_PAGE_SIZE
import org.umamo.ui.progress.ProgressLabels
import org.umamo.ui.progress.ProgressTask
import org.umamo.ui.progress.ProgressTracker
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Generate Mesh from Art end to end over synthetic art: one undo step over every drawable, twins
 * regenerated together (apart when pinned at one point through different vertices), split pieces kept
 * to their own regions, a run superseded by an edit, skips
 * counted, the atlas room rules (in place, clear of the neighbours, packed around, pinned), and the
 * strip's adjustments (a re-mesh from the base, a preset re-seed, the newest of two quick edits).
 */
class GenerateMeshFlowTest {
	private val tileA = AtlasTileId("tileA")
	private val tileB = AtlasTileId("tileB")
	private val drawableA = DrawableId("a")
	private val drawableB = DrawableId("b")
	private val standard = MeshGenerationOptions.of(ArtMeshPreset.Standard)

	@Test
	fun everyTargetLandsInOneUndoStep() =
		runBlocking {
			val model = model(listOf(quad(drawableA, tileA, 10f, 10f, 54f, 54f), quad(drawableB, tileB, 10f, 10f, 54f, 54f)), unplacedAtlas(listOf(tileA, tileB)))
			val session = EditorSession(model)
			val stepsBefore = session.historyView.value.steps.size

			assertTrue(runGenerateMesh(host(session, rasters(tileA to disc(), tileB to disc()), this), listOf(drawableA, drawableB), standard, null))

			assertEquals(stepsBefore + 1, session.historyView.value.steps.size)
			assertEquals("change.mesh.generateFromArt", session.historyView.value.steps.last().labelKey)
			assertTrue(vertexCountOf(session.model.value, drawableA) > 4)
			assertTrue(vertexCountOf(session.model.value, drawableB) > 4)
			assertNotNull(session.adjustableOperation.value, "the step is on the strip")
			assertEquals("notice.mesh.generated", session.notice.value?.messageKey)
			assertEquals(listOf("2"), session.notice.value?.arguments)
			session.undo()
			assertSame(model, session.model.value)
		}

	@Test
	fun aRunShowsItsCountedTaskWhileItMeshesAndEndsItAfter() =
		runBlocking {
			val model = model(listOf(quad(drawableA, tileA, 10f, 10f, 54f, 54f), quad(drawableB, tileB, 10f, 10f, 54f, 54f)), unplacedAtlas(listOf(tileA, tileB)))
			val session = EditorSession(model)
			val tracker = ProgressTracker()
			val seen = Collections.synchronizedList(ArrayList<ProgressTask>())
			val watcher = launch(Dispatchers.Unconfined) { tracker.tasks.collect { tasks -> seen.addAll(tasks) } }

			assertTrue(runGenerateMesh(host(session, rasters(tileA to disc(), tileB to disc()), this, progress = tracker), listOf(drawableA, drawableB), standard, null))
			watcher.cancel()

			assertTrue(seen.any { task -> task.labelKey == ProgressLabels.MESH_GENERATING && task.total == 2 }, "counted by drawable: ${seen.toList()}")
			assertTrue(tracker.tasks.value.isEmpty(), "the task ends with the run")
		}

	@Test
	fun aTwinRegeneratesWithItsSibling() =
		runBlocking {
			val model = model(listOf(quad(drawableA, tileA, 10f, 10f, 54f, 54f), quad(drawableB, tileA, 10f, 10f, 54f, 54f, originX = 300f)), unplacedAtlas(listOf(tileA)))
			val session = EditorSession(model)

			runGenerateMesh(host(session, rasters(tileA to disc()), this), listOf(drawableA), standard, null)

			val first = meshOf(session.model.value, drawableA)
			val second = meshOf(session.model.value, drawableB)
			assertTrue(second.vertexCount > 4, "the twin was regenerated too")
			assertContentEquals(first.uvs, second.uvs)
			assertContentEquals(first.indices, second.indices)
		}

	@Test
	fun twinsPinnedAtOnePointThroughDifferentVerticesRegenerateApart() =
		runBlocking {
			// Each mesh carries the quad's first corner twice, a duplicated seam vertex, and the glue names
			// one copy on either side: the two share their pin point but not their pinned vertex, so their
			// guides differ in their candidates and cannot be merged - they are meshed on their own guides.
			val glue = Glue(drawableA, drawableB, listOf(GluePair(0, 4, 0.5f, 0.5f)))
			val model = model(listOf(seamQuad(drawableA, tileA), seamQuad(drawableB, tileA, originX = 300f)), unplacedAtlas(listOf(tileA))).copy(glues = listOf(glue))
			val session = EditorSession(model)

			assertTrue(runGenerateMesh(host(session, rasters(tileA to disc()), this), listOf(drawableA), standard, null))

			assertTrue(vertexCountOf(session.model.value, drawableA) > 5, "the target was regenerated")
			assertTrue(vertexCountOf(session.model.value, drawableB) > 5, "its tile sharer was regenerated too")
			assertEquals(1, session.model.value.glues.single().pairs.size, "the glue survives")
		}

	@Test
	fun splitPiecesKeepTheirOwnRegionsWhileASoleSamplerMeshesTheWholeArt() =
		runBlocking {
			val split = model(listOf(quad(drawableA, tileA, 0f, 0f, 32f, 32f, tileHeight = 32f), quad(drawableB, tileA, 32f, 0f, 64f, 32f, tileHeight = 32f)), unplacedAtlas(listOf(tileA), height = 32))
			val splitSession = EditorSession(split)
			runGenerateMesh(host(splitSession, rasters(tileA to bar()), this), listOf(drawableA), standard, null)
			val leftXs = artXsOf(splitSession.model.value, drawableA)
			val rightXs = artXsOf(splitSession.model.value, drawableB)
			assertTrue(leftXs.max() < 41f, "the left piece stays on its half (max x ${leftXs.max()})")
			assertTrue(rightXs.min() > 23f, "the right piece stays on its half (min x ${rightXs.min()})")

			val sole = model(listOf(quad(drawableA, tileA, 0f, 0f, 32f, 32f, tileHeight = 32f)), unplacedAtlas(listOf(tileA), height = 32))
			val soleSession = EditorSession(sole)
			runGenerateMesh(host(soleSession, rasters(tileA to bar()), this), listOf(drawableA), standard, null)
			assertTrue(artXsOf(soleSession.model.value, drawableA).max() > 59f, "a sole sampler meshes all of its art")
		}

	@Test
	fun anEditDuringTheRunSupersedesIt() =
		runBlocking {
			val model = model(listOf(quad(drawableA, tileA, 10f, 10f, 54f, 54f), quad(drawableB, tileB, 10f, 10f, 54f, 54f)), unplacedAtlas(listOf(tileA, tileB)))
			val session = EditorSession(model)
			val entered = CountDownLatch(1)
			val release = CountDownLatch(1)
			val blocking =
				SourceArtRasters { _ ->
					entered.countDown()
					release.await()
					disc()
				}

			val run = async { runGenerateMesh(host(session, blocking, this@runBlocking), listOf(drawableA), standard, null) }
			withContext(Dispatchers.IO) { entered.await() }
			session.rename(SelectionTarget.Drawable(drawableB), "renamed")
			release.countDown()

			assertFalse(run.await())
			assertEquals("notice.mesh.superseded", session.notice.value?.messageKey)
			assertNotEquals("change.mesh.generateFromArt", session.historyView.value.steps.last().labelKey)
			assertEquals(4, vertexCountOf(session.model.value, drawableA))
		}

	@Test
	fun skipsAreCountedAndTheRestLand() =
		runBlocking {
			val loose = quad(DrawableId("loose"), null, 0f, 0f, 8f, 8f)
			val model = model(listOf(quad(drawableA, tileA, 10f, 10f, 54f, 54f), quad(drawableB, tileB, 10f, 10f, 54f, 54f), loose), unplacedAtlas(listOf(tileA, tileB)))
			val session = EditorSession(model)

			assertTrue(runGenerateMesh(host(session, rasters(tileA to disc(), tileB to transparent()), this), listOf(drawableA, drawableB, loose.id), standard, null))

			assertEquals("notice.mesh.generatedWithSkips", session.notice.value?.messageKey)
			assertEquals(listOf("1", "2"), session.notice.value?.arguments)
			assertEquals(4, vertexCountOf(session.model.value, drawableB))
		}

	@Test
	fun aBentRigKeepsTheOldVerticesThatHoldIt() =
		runBlocking {
			// A fine grid over the disc whose canvas folds 15 px along x = 32: at the coarse Standard spacing
			// the fold falls between new vertices unless the old ones holding it are kept.
			val model = model(listOf(foldedGrid(drawableA, tileA)), unplacedAtlas(listOf(tileA)))
			val held = largestFoldDeviation(model, freshRun(model, standardWithTolerance(0.5f)))
			val loose = largestFoldDeviation(model, freshRun(model, standardWithTolerance(64f)))

			assertTrue(held <= 0.5f + 1e-3f, "with a 0.5 px tolerance the fold is held to $held px")
			assertTrue(loose > 3f, "with a 64 px tolerance the fold is lost: $loose px")
		}

	@Test
	fun aRunARowCouldRescueOpensTheStripOnAnUnchangedStep() =
		runBlocking {
			// Art fainter than the threshold: nothing is generated, but lowering the Alpha Threshold would.
			val model = model(listOf(quad(drawableA, tileA, 10f, 10f, 54f, 54f)), unplacedAtlas(listOf(tileA)))
			val session = EditorSession(model)
			val stepsBefore = session.historyView.value.steps.size

			assertFalse(runGenerateMesh(host(session, rasters(tileA to disc(alpha = 10)), this), listOf(drawableA), standard, null))

			assertSame(model, session.model.value, "nothing changed")
			assertEquals("notice.mesh.noneGenerated", session.notice.value?.messageKey)
			assertEquals(stepsBefore + 1, session.historyView.value.steps.size, "an unchanged step carries the strip")
			assertEquals("change.mesh.generateFromArt", session.historyView.value.steps.last().labelKey)
			val record = assertNotNull(session.adjustableOperation.value, "the strip opens")

			session.adjustLastOperation(record.parameters.withParameter(MeshGenerationParameterKeys.ALPHA_THRESHOLD, (record.parameters.first { row -> row.key == MeshGenerationParameterKeys.ALPHA_THRESHOLD } as OperatorParameter.IntParameter).copy(value = 8)), adjustedKey = MeshGenerationParameterKeys.ALPHA_THRESHOLD)
			joinChildren()

			assertTrue(vertexCountOf(session.model.value, drawableA) > 4, "the lowered threshold meshes the art")
			assertEquals(stepsBefore + 1, session.historyView.value.steps.size, "the adjustment landed over the unchanged step")
			session.undo()
			assertSame(model, session.model.value, "one undo restores the rig")
		}

	@Test
	fun nothingGeneratedForAReasonNoRowChangesRecordsNothing() =
		runBlocking {
			val model = model(listOf(quad(drawableA, tileA, 10f, 10f, 54f, 54f)), unplacedAtlas(listOf(tileA)))
			val session = EditorSession(model)

			// No art at all for the tile: no row can bring it.
			assertFalse(runGenerateMesh(host(session, rasters(), this), listOf(drawableA), standard, null))

			assertSame(model, session.model.value)
			assertNull(session.adjustableOperation.value)
			assertEquals("notice.mesh.noneGenerated", session.notice.value?.messageKey)
		}

	@Test
	fun artTheBudgetCannotFitIsMeshedUnderARaisedOne() =
		runBlocking {
			// Sixteen specks, each its own outline ring of at least six vertices: far over a budget of 32.
			val specks = raster(64, 64) { column, row -> column % 16 in 6..7 && row % 16 in 6..7 }
			val model = model(listOf(quad(drawableA, tileA, 10f, 10f, 54f, 54f)), unplacedAtlas(listOf(tileA)))
			val tight = standard.copy(settings = standard.settings.copy(vertexBudget = 32))
			val session = EditorSession(model)

			assertTrue(runGenerateMesh(host(session, rasters(tileA to specks), this), listOf(drawableA), tight, null))

			assertTrue(vertexCountOf(session.model.value, drawableA) > 32, "meshed under a budget above the row's")
			assertEquals("notice.mesh.generatedRefit", session.notice.value?.messageKey)
			assertEquals(listOf("1", "1"), session.notice.value?.arguments)
			assertEquals(32, assertNotNull(session.adjustableOperation.value).parameters.intValue(MeshGenerationParameterKeys.VERTEX_BUDGET, 0), "the row keeps what the rigger asked for")
		}

	@Test
	fun growthInsideTheReservedRoomKeepsTheAtlas() =
		runBlocking {
			// The old mesh covers the whole 64 px raster, so the generated one (6 px past a 20 px disc) fits.
			val model = placedModel(listOf(drawableA to 0f), aPlacement = placement(40f, 40f), bPlacement = placement(150f, 150f))
			val session = EditorSession(model)

			runGenerateMesh(host(session, rasters(tileA to disc(), tileB to disc()), this), listOf(drawableA), standard, null)

			assertTrue(vertexCountOf(session.model.value, drawableA) > 4)
			assertSame(model.atlas, session.model.value.atlas)
		}

	@Test
	fun aTileClearOfItsNeighboursStaysWhereItIs() =
		runBlocking {
			// A birth quad reserves only 2 px past the art; the generated outline needs 6, but nothing is near.
			val model = placedModel(listOf(drawableA to 2f), aPlacement = placement(40f, 40f), bPlacement = placement(160f, 160f))
			val session = EditorSession(model)

			runGenerateMesh(host(session, rasters(tileA to disc(), tileB to disc()), this), listOf(drawableA), standard, null)

			assertTrue(vertexCountOf(session.model.value, drawableA) > 4)
			assertSame(model.atlas, session.model.value.atlas)
		}

	@Test
	fun aTileThatWouldSampleItsNeighbourIsPackedAround() =
		runBlocking {
			// Tile B's disc starts 3 px right of tile A's: the generated outline would sample it.
			val model = placedModel(listOf(drawableA to 2f), aPlacement = placement(40f, 40f), bPlacement = placement(83f, 40f))
			val session = EditorSession(model)

			runGenerateMesh(host(session, rasters(tileA to disc(), tileB to disc()), this), listOf(drawableA), standard, null)

			val after = session.model.value
			assertNotEquals(model.atlas.tileById.getValue(tileA).placement, after.atlas.tileById.getValue(tileA).placement, "tile A moved")
			assertEquals(model.atlas.tileById.getValue(tileB).placement, after.atlas.tileById.getValue(tileB).placement, "tile B held still")
			// The birth quad's canvas is its art moved by the origin, so every vertex must still map back to
			// its own art point through the NEW placement.
			val mesh = meshOf(after, drawableA)
			val artUvs = applyUvAffine(mesh.uvs, after.atlas.storedToArtAffineForTile(tileA)!!)

			for (vertex in 0 until mesh.vertexCount) {
				assertEquals(mesh.positions[2 * vertex] - ORIGIN_X, artUvs[2 * vertex] * 64f, 1e-2f, "vertex $vertex x")
				assertEquals(mesh.positions[2 * vertex + 1] - ORIGIN_Y, artUvs[2 * vertex + 1] * 64f, 1e-2f, "vertex $vertex y")
			}
		}

	@Test
	fun aGrownTilePacksAroundTilesItIsNotMoving() =
		runBlocking {
			// Beside A and B: a used tile with nothing opaque, placed (a collision image), and a used tile
			// wider than any page that the import left unplaced (a canvas-sized background).  Neither moves,
			// so neither may refuse the pack that moves A.
			val clearTile = AtlasTileId("clear")
			val wideTile = AtlasTileId("wide")
			val base = placedModel(listOf(drawableA to 2f), aPlacement = placement(40f, 40f), bPlacement = placement(83f, 40f))
			val clearPlacement = placement(160f, 160f)
			val atlas =
				base.atlas.copy(
					tiles =
						base.atlas.tiles +
							AtlasTile(clearTile, "Collision", 16, 16, placement = clearPlacement) +
							AtlasTile(wideTile, "Background", MAX_IMPORT_PAGE_SIZE + 8, 1),
				)
			val model = base.copy(atlas = atlas, drawables = base.drawables + quad(DrawableId("clear"), clearTile, 0f, 0f, 16f, 16f) + quad(DrawableId("wide"), wideTile, 0f, 0f, 64f, 1f))
			val session = EditorSession(model)
			val art = rasters(tileA to disc(), tileB to disc(), clearTile to raster(16, 16) { _, _ -> false }, wideTile to raster(MAX_IMPORT_PAGE_SIZE + 8, 1) { _, _ -> true })

			assertTrue(runGenerateMesh(host(session, art, this), listOf(drawableA), standard, null), "the pack landed: ${session.notice.value}")

			val after = session.model.value
			assertNotEquals(model.atlas.tileById.getValue(tileA).placement, after.atlas.tileById.getValue(tileA).placement, "tile A moved")
			assertEquals(model.atlas.tileById.getValue(tileB).placement, after.atlas.tileById.getValue(tileB).placement, "tile B held still")
			assertEquals(clearPlacement, after.atlas.tileById.getValue(clearTile).placement, "the transparent tile kept its spot")
			assertNull(after.atlas.tileById.getValue(wideTile).placement, "the unplaced tile stayed out of the pack")
			val movedA = assertNotNull(after.atlas.tileById.getValue(tileA).placement)
			val separated = movedA.positionX + 64f <= clearPlacement.positionX || clearPlacement.positionX + 16f <= movedA.positionX || movedA.positionY + 64f <= clearPlacement.positionY || clearPlacement.positionY + 16f <= movedA.positionY
			assertTrue(separated || movedA.pageIndex != clearPlacement.pageIndex, "A did not land on the transparent tile's spot")
		}

	@Test
	fun aPinnedTileWithoutRoomKeepsItsMesh() =
		runBlocking {
			val model = placedModel(listOf(drawableA to 2f), aPlacement = placement(40f, 40f), bPlacement = placement(83f, 40f), pinA = true)
			val session = EditorSession(model)

			assertFalse(runGenerateMesh(host(session, rasters(tileA to disc(), tileB to disc()), this), listOf(drawableA), standard, null))

			assertSame(model, session.model.value)
			assertEquals("notice.mesh.pinnedWithoutRoom", session.notice.value?.messageKey)
			assertEquals(listOf("0", "1"), session.notice.value?.arguments)
		}

	@Test
	fun anAdjustmentRemeshesFromTheBaseAndRemembersIt() =
		runBlocking {
			val model = model(listOf(quad(drawableA, tileA, 10f, 10f, 54f, 54f)), unplacedAtlas(listOf(tileA)))
			val session = EditorSession(model)
			var remembered: MeshGenerationOptions? = null
			val host = host(session, rasters(tileA to disc()), this) { options -> remembered = options }
			runGenerateMesh(host, listOf(drawableA), standard, null)
			val firstCount = vertexCountOf(session.model.value, drawableA)
			val record = assertNotNull(session.adjustableOperation.value)

			session.adjustLastOperation(record.parameters.withFloat(MeshGenerationParameterKeys.OUTLINE_SPACING, 12f))
			joinChildren()

			val expected = freshRun(model, standard.copy(settings = standard.settings.copy(outlineSpacing = 12.0)))
			assertNotEquals(firstCount, vertexCountOf(session.model.value, drawableA))
			assertContentEquals(meshOf(expected, drawableA).positions, meshOf(session.model.value, drawableA).positions)
			assertEquals(12.0, remembered?.settings?.outlineSpacing)
			session.undo()
			assertSame(model, session.model.value, "the adjusted step is still the one step")
		}

	@Test
	fun aPresetChangeReseedsTheRowsAndRemeshes() =
		runBlocking {
			val model = model(listOf(quad(drawableA, tileA, 10f, 10f, 54f, 54f)), unplacedAtlas(listOf(tileA)))
			val session = EditorSession(model)
			runGenerateMesh(host(session, rasters(tileA to disc()), this), listOf(drawableA), standard, null)
			val record = assertNotNull(session.adjustableOperation.value)
			val presetRow = record.parameters.first { row -> row.key == MeshGenerationParameterKeys.PRESET } as OperatorParameter.ChoiceParameter

			session.adjustLastOperation(record.parameters.withParameter(MeshGenerationParameterKeys.PRESET, presetRow.copy(value = ArtMeshPreset.Coarse.key)))
			joinChildren()

			val rows = assertNotNull(session.adjustableOperation.value).parameters
			assertEquals(ArtMeshPreset.Coarse.settings.outlineSpacing.toFloat(), rows.floatValue(MeshGenerationParameterKeys.OUTLINE_SPACING, 0f))
			val expected = freshRun(model, MeshGenerationOptions.of(ArtMeshPreset.Coarse))
			assertContentEquals(meshOf(expected, drawableA).positions, meshOf(session.model.value, drawableA).positions)
		}

	@Test
	fun choosingThePresetAlreadyShownAppliesItAgain() =
		runBlocking {
			val model = model(listOf(quad(drawableA, tileA, 10f, 10f, 54f, 54f)), unplacedAtlas(listOf(tileA)))
			val session = EditorSession(model)
			runGenerateMesh(host(session, rasters(tileA to disc()), this), listOf(drawableA), standard, null)
			val record = assertNotNull(session.adjustableOperation.value)
			session.adjustLastOperation(record.parameters.withFloat(MeshGenerationParameterKeys.OUTLINE_SPACING, 12f), adjustedKey = MeshGenerationParameterKeys.OUTLINE_SPACING)
			joinChildren()
			val edited = assertNotNull(session.adjustableOperation.value)
			assertEquals(12f, edited.parameters.floatValue(MeshGenerationParameterKeys.OUTLINE_SPACING, 0f))
			val presetRow = edited.parameters.first { row -> row.key == MeshGenerationParameterKeys.PRESET } as OperatorParameter.ChoiceParameter
			assertEquals(ArtMeshPreset.Standard.key, presetRow.value, "the edited row leaves the preset shown")

			// The strip hands the same choice back, naming the row.
			session.adjustLastOperation(edited.parameters.withParameter(MeshGenerationParameterKeys.PRESET, presetRow.copy(value = ArtMeshPreset.Standard.key)), adjustedKey = MeshGenerationParameterKeys.PRESET)
			joinChildren()

			val rows = assertNotNull(session.adjustableOperation.value).parameters
			assertEquals(ArtMeshPreset.Standard.settings.outlineSpacing.toFloat(), rows.floatValue(MeshGenerationParameterKeys.OUTLINE_SPACING, 0f), "the preset's spacing is back")
			val expected = freshRun(model, standard)
			assertContentEquals(meshOf(expected, drawableA).positions, meshOf(session.model.value, drawableA).positions, "and the mesh is a fresh Standard run's")
		}

	@Test
	fun theNewestOfTwoQuickAdjustmentsLands() =
		runBlocking {
			val model = model(listOf(quad(drawableA, tileA, 10f, 10f, 54f, 54f)), unplacedAtlas(listOf(tileA)))
			val session = EditorSession(model)
			runGenerateMesh(host(session, rasters(tileA to disc()), this), listOf(drawableA), standard, null)
			val record = assertNotNull(session.adjustableOperation.value)

			session.adjustLastOperation(record.parameters.withFloat(MeshGenerationParameterKeys.OUTLINE_SPACING, 10f))
			session.adjustLastOperation(record.parameters.withFloat(MeshGenerationParameterKeys.OUTLINE_SPACING, 20f))
			joinChildren()

			val expected = freshRun(model, standard.copy(settings = standard.settings.copy(outlineSpacing = 20.0)))
			assertContentEquals(meshOf(expected, drawableA).positions, meshOf(session.model.value, drawableA).positions)
		}

	@Test
	fun theTargetsAreTheObjectSelectionOrTheEditSession() {
		val model = model(listOf(quad(drawableA, tileA, 10f, 10f, 54f, 54f), quad(drawableB, tileB, 10f, 10f, 54f, 54f)), unplacedAtlas(listOf(tileA, tileB)))
		val session = EditorSession(model)
		assertTrue(generateMeshTargets(session).isEmpty(), "nothing selected, nothing to mesh")
		session.setSelection(SelectionOps.add(SelectionOps.replace(SelectionTarget.Drawable(drawableA)), SelectionTarget.Drawable(drawableB)))
		assertEquals(listOf(drawableA, drawableB), generateMeshTargets(session))
		session.setMode(EditorMode.Edit)
		assertEquals(session.meshSelection.value.drawableIds, generateMeshTargets(session))
		assertTrue(generateMeshTargets(session).isNotEmpty())
	}

	@Test
	fun theLauncherNamesAnEmptySelectionAndIgnoresASecondPressMidRun() =
		runBlocking {
			val model = model(listOf(quad(drawableA, tileA, 10f, 10f, 54f, 54f)), unplacedAtlas(listOf(tileA)))
			val session = EditorSession(model)
			val entered = CountDownLatch(1)
			val release = CountDownLatch(1)
			val blocking =
				SourceArtRasters { _ ->
					entered.countDown()
					release.await()
					disc()
				}
			val sessionOptions = MeshGenerationSessionOptions()
			val runState = MeshGenerationRunState()
			val launch = generateMeshLauncher(session, blocking, null, false, this, sessionOptions, runState) { "coarse" }

			launch(null)
			assertEquals("notice.mesh.noTargets", session.notice.value?.messageKey)

			session.setSelection(SelectionOps.replace(SelectionTarget.Drawable(drawableA)))
			val stepsBefore = session.historyView.value.steps.size
			launch(null)
			withContext(Dispatchers.IO) { entered.await() }
			launch(null)
			// The command table is rebuilt mid-run (a selection change re-registers it); the new launcher
			// shares the window's run state and still sees the run going.
			val rebuilt = generateMeshLauncher(session, blocking, null, false, this, sessionOptions, runState) { "coarse" }
			rebuilt(null)
			release.countDown()
			joinChildren()

			assertEquals(stepsBefore + 1, session.historyView.value.steps.size, "the second and third presses while the first ran were ignored")
			assertEquals("change.mesh.generateFromArt", session.historyView.value.steps.last().labelKey)
			val presetRow = session.adjustableOperation.value!!.parameters.first { row -> row.key == MeshGenerationParameterKeys.PRESET } as OperatorParameter.ChoiceParameter
			assertEquals("coarse", presetRow.value, "a first run starts from the settings' preset")
		}

	@Test
	fun aGuardTrippingInsideTheRunIsANoticeNotACrash() =
		runBlocking {
			val model = model(listOf(quad(drawableA, tileA, 10f, 10f, 54f, 54f)), unplacedAtlas(listOf(tileA)))
			val session = EditorSession(model)
			// The store's decode stands in for any check() in the pipeline: an IllegalStateException, not the
			// IllegalArgumentException a refused input throws.
			val tripping = SourceArtRasters { _ -> throw IllegalStateException("a guard tripped") }

			assertFalse(runGenerateMesh(host(session, tripping, this), listOf(drawableA), standard, null))

			assertSame(model, session.model.value)
			assertEquals("notice.mesh.noneGenerated", session.notice.value?.messageKey)
		}

	@Test
	fun aCancellationInsideTheRunPropagates() =
		runBlocking {
			val model = model(listOf(quad(drawableA, tileA, 10f, 10f, 54f, 54f)), unplacedAtlas(listOf(tileA)))
			val session = EditorSession(model)
			val cancelling = SourceArtRasters { _ -> throw CancellationException("cancelled") }
			val run = async { runGenerateMesh(host(session, cancelling, this), listOf(drawableA), standard, null) }

			assertTrue(runCatching { run.await() }.exceptionOrNull() is CancellationException, "a cancellation is not a guard's refusal")
		}

	@Test
	fun aMeshBuiltCoarserThanAskedIsNoticedAndLogged() =
		runBlocking {
			val model = model(listOf(quad(drawableA, tileA, 10f, 10f, 54f, 54f)), unplacedAtlas(listOf(tileA)))
			// Dense spacings over the whole disc, so the mesh asked for runs well past the budget floor.
			val dense = standard.copy(settings = standard.settings.copy(outlineSpacing = 4.0, interiorSpacing = 6.0))
			val asked = EditorSession(model)
			runGenerateMesh(host(asked, rasters(tileA to disc()), this), listOf(drawableA), dense, null)
			val askedCount = vertexCountOf(asked.model.value, drawableA)
			// A budget under what the rows ask for, and over the floor the rows clamp to: the mesher widens
			// the spacings a round or two and fits.
			val budget = askedCount * 2 / 3
			val tight = dense.copy(settings = dense.settings.copy(vertexBudget = budget))
			assertEquals(budget, clampedMeshSettings(tight.settings, minimumAlphaThreshold = 1).vertexBudget, "the budget survives the rows' clamp")
			val session = EditorSession(model)

			assertTrue(runGenerateMesh(host(session, rasters(tileA to disc()), this), listOf(drawableA), tight, null))

			assertTrue(vertexCountOf(session.model.value, drawableA) <= budget, "the mesh was built within the budget")
			assertEquals("notice.mesh.generatedCoarser", session.notice.value?.messageKey)
			assertEquals(listOf("1", "1"), session.notice.value?.arguments)
		}

	@Test
	fun everyTileIsDecodedOncePerRunAndOncePerAdjustment() =
		runBlocking {
			// Tile B's disc starts 3 px right of tile A's: the collision test reads B as A's neighbour, and
			// the pack that follows composes both.
			val model = placedModel(listOf(drawableA to 2f), aPlacement = placement(40f, 40f), bPlacement = placement(83f, 40f))
			val session = EditorSession(model)
			val decodesByTile = HashMap<AtlasTileId, Int>()
			val art = mapOf(tileA to disc(), tileB to disc())
			val counting =
				SourceArtRasters { tileId ->
					synchronized(decodesByTile) { decodesByTile[tileId] = (decodesByTile[tileId] ?: 0) + 1 }
					art[tileId]
				}

			runGenerateMesh(host(session, counting, this), listOf(drawableA), standard, null)

			assertEquals(mapOf(tileA to 1, tileB to 1), decodesByTile, "the run decoded each tile once")
			val record = assertNotNull(session.adjustableOperation.value)
			session.adjustLastOperation(record.parameters.withFloat(MeshGenerationParameterKeys.OUTLINE_SPACING, 12f))
			joinChildren()
			assertEquals(mapOf(tileA to 2, tileB to 2), decodesByTile, "the adjustment decoded each tile once more, holding none from the run")
		}

	/**
	 * Runs Generate Mesh from Art on drawable a of a fresh session over [model] and returns the result.
	 *
	 * @param PuppetModel           model   The model.
	 * @param MeshGenerationOptions options The options.
	 * @return PuppetModel The model after the run.
	 */
	private suspend fun freshRun(model: PuppetModel, options: MeshGenerationOptions): PuppetModel {
		val session = EditorSession(model)
		runGenerateMesh(host(session, rasters(tileA to disc(), tileB to disc()), CoroutineScope(Dispatchers.Default)), listOf(drawableA), options, null)

		return session.model.value
	}

	/** Waits for every rerun the strip launched in this test's scope. */
	private suspend fun CoroutineScope.joinChildren() {
		coroutineContext.job.children.toList().joinAll()
	}

	/**
	 * The rows with one float row's value replaced.
	 *
	 * @param String key   The row.
	 * @param Float  value The value.
	 * @return List<OperatorParameter> The rows.
	 */
	private fun List<OperatorParameter>.withFloat(key: String, value: Float): List<OperatorParameter> =
		map { row -> if (row.key == key) (row as OperatorParameter.FloatParameter).copy(value = value) else row }

	/**
	 * A host over a session and some art, the strip's reruns in [scope].
	 *
	 * @param EditorSession    session  The session.
	 * @param SourceArtRasters rasters  The art.
	 * @param CoroutineScope   scope    Where reruns run.
	 * @param ProgressTracker? progress Where the run shows while it works, or null.
	 * @param Function         remember Receives the landed options.
	 * @return GenerateMeshHost The host.
	 */
	private fun host(session: EditorSession, rasters: SourceArtRasters, scope: CoroutineScope, progress: ProgressTracker? = null, remember: (MeshGenerationOptions) -> Unit = {}): GenerateMeshHost =
		GenerateMeshHost(session, rasters, sessionAtlasPages = null, premultipliedAlpha = false, scope = scope, progress = progress, rememberOptions = remember)

	/**
	 * A raster store over fixed art.
	 *
	 * @param Array<Pair<AtlasTileId, DecodedImage>> art The art by tile.
	 * @return SourceArtRasters The store.
	 */
	private fun rasters(vararg art: Pair<AtlasTileId, DecodedImage>): SourceArtRasters {
		val byTile = art.toMap()

		return SourceArtRasters { tileId -> byTile[tileId] }
	}

	/**
	 * A 64 px raster holding a disc of radius 20 in its middle (its trim runs 12..52).
	 *
	 * @param Int alpha The disc's alpha; opaque by default.
	 * @return DecodedImage The art.
	 */
	private fun disc(alpha: Int = 255): DecodedImage =
		raster(64, 64, alpha) { column, row ->
			val offsetX = column + 0.5 - 32.0
			val offsetY = row + 0.5 - 32.0
			offsetX * offsetX + offsetY * offsetY <= 400.0
		}

	/**
	 * A 64 x 32 raster holding an opaque bar from x 4 to 59 and y 4 to 27.
	 *
	 * @return DecodedImage The art.
	 */
	private fun bar(): DecodedImage = raster(64, 32) { column, row -> column in 4..59 && row in 4..27 }

	/**
	 * A fully transparent 64 px raster.
	 *
	 * @return DecodedImage The art.
	 */
	private fun transparent(): DecodedImage = raster(64, 64) { _, _ -> false }

	/**
	 * A raster painted where a predicate says so.
	 *
	 * @param Int                   width  The width.
	 * @param Int                   height The height.
	 * @param Int                   alpha  The alpha of the pixels the predicate marks.
	 * @param (Int, Int) -> Boolean opaque Whether a pixel is opaque.
	 * @return DecodedImage The raster.
	 */
	private fun raster(width: Int, height: Int, alpha: Int = 255, opaque: (Int, Int) -> Boolean): DecodedImage {
		val rgba = ByteArray(width * height * 4)

		for (row in 0 until height) {
			for (column in 0 until width) {
				if (opaque(column, row)) {
					rgba[(row * width + column) * 4 + 3] = alpha.toByte()
				}
			}
		}

		return DecodedImage(rgba, width, height)
	}

	/**
	 * An atlas of unplaced tiles.
	 *
	 * @param List<AtlasTileId> tiles  The tiles.
	 * @param Int               height The tiles' height.
	 * @return PuppetAtlas The atlas.
	 */
	private fun unplacedAtlas(tiles: List<AtlasTileId>, height: Int = 64): PuppetAtlas = PuppetAtlas(tiles = tiles.map { tileId -> AtlasTile(tileId, tileId.raw, 64, height) })

	/**
	 * An upright placement on page 0.
	 *
	 * @param Float x The left edge, page pixels.
	 * @param Float y The top edge.
	 * @return AtlasPlacement The placement.
	 */
	private fun placement(x: Float, y: Float): AtlasPlacement = AtlasPlacement(0, x, y, 1f, 1f, 0f)

	/**
	 * Drawable a over tile A and an unused tile B, both placed on a 256 px page, a's quad spanning the
	 * disc's trim widened by a margin (0 widens it to the whole raster).
	 *
	 * @param List<Pair<DrawableId, Float>> quads      The drawable and its quad's margin past the trim.
	 * @param AtlasPlacement                aPlacement Tile A's placement.
	 * @param AtlasPlacement                bPlacement Tile B's placement.
	 * @param Boolean                       pinA       Whether tile A is pinned.
	 * @return PuppetModel The model.
	 */
	private fun placedModel(quads: List<Pair<DrawableId, Float>>, aPlacement: AtlasPlacement, bPlacement: AtlasPlacement, pinA: Boolean = false): PuppetModel {
		val atlas =
			PuppetAtlas(
				pages = listOf(AtlasPage(256, 256)),
				tiles = listOf(AtlasTile(tileA, "A", 64, 64, placement = aPlacement, pinned = pinA), AtlasTile(tileB, "B", 64, 64, placement = bPlacement)),
			)
		val artToStored = invertUvAffine(atlas.storedToArtAffineForTile(tileA)!!)!!
		val drawables =
			quads.map { (drawableId, margin) ->
				val low = if (margin == 0f) 0f else 12f - margin
				val high = if (margin == 0f) 64f else 52f + margin
				val unplaced = quad(drawableId, tileA, low, low, high, high)
				val mesh = unplaced.mesh!!

				unplaced.copy(mesh = DrawableMesh.withLocalEqualToCanvas(mesh.positions, applyUvAffine(mesh.uvs, artToStored), mesh.indices))
			}

		return model(drawables, atlas)
	}

	/**
	 * A quad drawable over an art rectangle of a tile (UVs in the art frame), its canvas the art moved by
	 * the origin, as a birth quad is.
	 *
	 * @param DrawableId   id      The drawable.
	 * @param AtlasTileId? tileId  The tile, or null.
	 * @param Float        left    The left edge, art pixels.
	 * @param Float        top     The top edge.
	 * @param Float        right   The right edge.
	 * @param Float        bottom  The bottom edge.
	 * @param Float        originX    The canvas x of the art's left edge.
	 * @param Float        tileHeight The tile's height in pixels (every tile here is 64 wide).
	 * @return Drawable The drawable.
	 */
	private fun quad(id: DrawableId, tileId: AtlasTileId?, left: Float, top: Float, right: Float, bottom: Float, originX: Float = ORIGIN_X, tileHeight: Float = 64f): Drawable {
		val art = floatArrayOf(left, top, right, top, right, bottom, left, bottom)
		val canvas = FloatArray(art.size) { component -> art[component] + if (component % 2 == 0) originX else ORIGIN_Y }
		val uvs = FloatArray(art.size) { component -> art[component] / if (component % 2 == 0) 64f else tileHeight }

		return Drawable(
			id = id,
			name = id.raw,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh.withLocalEqualToCanvas(canvas, uvs, intArrayOf(0, 1, 2, 0, 2, 3)),
			geometryGrid = null,
			atlasTileId = tileId,
		)
	}

	/**
	 * A quad over the disc art whose first corner is a vertex twice, its two triangles each using one
	 * copy - a duplicated seam vertex, as a split mesh welded back together carries.
	 *
	 * @param DrawableId  id      The drawable.
	 * @param AtlasTileId tileId  The tile.
	 * @param Float       originX The canvas x the art's left lands on.
	 * @return Drawable The drawable.
	 */
	private fun seamQuad(id: DrawableId, tileId: AtlasTileId, originX: Float = ORIGIN_X): Drawable {
		val art = floatArrayOf(10f, 10f, 54f, 10f, 54f, 54f, 10f, 54f, 10f, 10f)
		val canvas = FloatArray(art.size) { component -> art[component] + if (component % 2 == 0) originX else ORIGIN_Y }
		val uvs = FloatArray(art.size) { component -> art[component] / 64f }

		return quad(id, tileId, 10f, 10f, 54f, 54f, originX).copy(mesh = DrawableMesh.withLocalEqualToCanvas(canvas, uvs, intArrayOf(0, 1, 2, 4, 2, 3)))
	}

	/**
	 * Standard with its deformation tolerance set.
	 *
	 * @param Float tolerance The tolerance, art pixels.
	 * @return MeshGenerationOptions The options.
	 */
	private fun standardWithTolerance(tolerance: Float): MeshGenerationOptions =
		MeshGenerationOptions(ArtMeshPreset.Standard, ArtMeshPreset.Standard.settings.copy(deformationTolerance = tolerance.toDouble()))

	/**
	 * A drawable over the disc art meshed by a 9 x 9 grid from 12 to 52, five pixels apart, whose canvas
	 * is the art moved by the origin and folded up by 15 px along x = 32, back to flat by x = 27 and 37.
	 *
	 * @param DrawableId  id     The drawable.
	 * @param AtlasTileId tileId The tile.
	 * @return Drawable The drawable.
	 */
	private fun foldedGrid(id: DrawableId, tileId: AtlasTileId): Drawable {
		val art = FloatArray(2 * 81) { component -> 12f + 5f * if (component % 2 == 0) (component / 2) % 9 else (component / 2) / 9 }
		val canvas = FloatArray(art.size) { component -> if (component % 2 == 0) art[component] + ORIGIN_X else art[component] + ORIGIN_Y + foldAt(art[component - 1]) }
		val indices = ArrayList<Int>()

		for (row in 0 until 8) {
			for (column in 0 until 8) {
				val topLeft = row * 9 + column
				indices.addAll(listOf(topLeft, topLeft + 1, topLeft + 10, topLeft, topLeft + 10, topLeft + 9))
			}
		}

		return Drawable(
			id = id,
			name = id.raw,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh.withLocalEqualToCanvas(canvas, FloatArray(art.size) { component -> art[component] / 64f }, indices.toIntArray()),
			geometryGrid = null,
			atlasTileId = tileId,
		)
	}

	/**
	 * The fold's lift at an art x.
	 *
	 * @param Float x The art x.
	 * @return Float The lift, canvas pixels.
	 */
	private fun foldAt(x: Float): Float = 15f * maxOf(0f, 1f - abs(x - 32f) / 5f)

	/**
	 * The largest distance, over the old grid vertices the new mesh covers, between the old canvas
	 * position and the new mesh's interpolation of its own canvas there.
	 *
	 * @param PuppetModel before The model with the folded grid.
	 * @param PuppetModel after  The model after the run.
	 * @return Float The largest distance, canvas pixels.
	 */
	private fun largestFoldDeviation(before: PuppetModel, after: PuppetModel): Float {
		val old = meshOf(before, drawableA)
		val new = meshOf(after, drawableA)
		val newArt = FloatArray(new.uvs.size) { component -> new.uvs[component] * 64f }
		val locator = TriangleLocator(newArt, new.indices)
		var largest = 0f

		for (vertex in 0 until old.vertexCount) {
			val point = blendedAt(locator, new.indices, new.positions, old.uvs[2 * vertex] * 64.0, old.uvs[2 * vertex + 1] * 64.0) ?: continue
			largest = maxOf(largest, abs(point.first.toFloat() - old.positions[2 * vertex]), abs(point.second.toFloat() - old.positions[2 * vertex + 1]))
		}

		return largest
	}

	/**
	 * A model of drawables over an atlas.
	 *
	 * @param List<Drawable> drawables The drawables.
	 * @param PuppetAtlas    atlas     The atlas.
	 * @return PuppetModel The model.
	 */
	private fun model(drawables: List<Drawable>, atlas: PuppetAtlas): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = drawables,
			rootChildren = drawables.map { drawable -> OrgChild.Drawable(drawable.id) },
			rootPartId = null,
			atlas = atlas,
		)

	/**
	 * A drawable's mesh.
	 *
	 * @param PuppetModel model      The model.
	 * @param DrawableId  drawableId The drawable.
	 * @return DrawableMesh The mesh.
	 */
	private fun meshOf(model: PuppetModel, drawableId: DrawableId): DrawableMesh = model.drawables.first { drawable -> drawable.id == drawableId }.mesh!!

	/**
	 * A drawable's vertex count.
	 *
	 * @param PuppetModel model      The model.
	 * @param DrawableId  drawableId The drawable.
	 * @return Int The count.
	 */
	private fun vertexCountOf(model: PuppetModel, drawableId: DrawableId): Int = meshOf(model, drawableId).vertexCount

	/**
	 * A drawable's vertices' x in its tile's art pixels.
	 *
	 * @param PuppetModel model      The model.
	 * @param DrawableId  drawableId The drawable.
	 * @return List<Float> The x of every vertex.
	 */
	private fun artXsOf(model: PuppetModel, drawableId: DrawableId): List<Float> {
		val drawable = model.drawables.first { candidate -> candidate.id == drawableId }
		val artUvs = applyUvAffine(drawable.mesh!!.uvs, model.atlas.storedToArtAffineForTile(drawable.atlasTileId!!)!!)

		return (0 until artUvs.size / 2).map { vertex -> artUvs[2 * vertex] * 64f }
	}

	private companion object {
		/** The canvas x of a quad's art origin. */
		const val ORIGIN_X: Float = 200f

		/** The canvas y of a quad's art origin. */
		const val ORIGIN_Y: Float = 100f
	}
}