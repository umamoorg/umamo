package org.umamo.ui.viewport.uv

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.umamo.edit.ActiveOperator
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.beginUvOperator
import org.umamo.render.DecodedImage
import org.umamo.render.PuppetTextures
import org.umamo.render.SourceArtRasters
import org.umamo.ui.model.SessionAtlasPages
import org.umamo.ui.viewport.gizmo.LEFT_AREA
import org.umamo.ui.viewport.gizmo.RIGHT_AREA
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the UV Object overlay's placement gesture ([UvPlacementModalTransform]) over a real session, without
 * Compose.  The capture builds off the UI thread, and the overlay's effect cancellation hides what happens
 * when the latch moves while it builds, so these cases hold the build at its first raster decode: a build
 * that lands after its latch cleared or changed begins nothing, and one that lands begins at the pointer as
 * it is then.  They also pin the landing's ghost (published only where a page resolver will retire it), the
 * readout's teardown, and the abandon.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UvPlacementModalTransformTest {
	/**
	 * The transform under test over a placed-model session in Object mode, plus the host readout it writes.
	 *
	 * @property EditorSession session The session.
	 * @property UvPlacementModalTransform transform The transform.
	 * @property MutableState dragStatus The host's readout for the left area.
	 * @property UvPlacementSceneState sceneState The host's placement scene for the left area.
	 */
	private class Rig(
		val session: EditorSession,
		val transform: UvPlacementModalTransform,
		val dragStatus: MutableState<PlacementDragStatus?>,
		val sceneState: UvPlacementSceneState,
	)

	/**
	 * A transform for the left area over a fresh placed-model session with the quad selected.
	 *
	 * @param Function atlasPagesOf Builds the page resolver over the session, or null for none.
	 * @return Rig The transform and its session.
	 */
	private fun rigOver(atlasPagesOf: (EditorSession) -> SessionAtlasPages? = { null }): Rig {
		val session = uvObjectSession(model = uvRigPlacedModel())
		val dragStatus = mutableStateOf<PlacementDragStatus?>(null)
		val sceneState = UvPlacementSceneState()
		val transform = UvPlacementModalTransform(LEFT_AREA, session, mutableStateOf(atlasPagesOf(session)), mutableStateOf(dragStatus), mutableStateOf(sceneState))
		transform.gesture.lastPointer = UV_RIG_GESTURE_START
		return Rig(session, transform, dragStatus, sceneState)
	}

	/**
	 * Latches [kind] in the left area.
	 *
	 * @param MeshOperatorKind kind The operator.
	 * @return ActiveOperator The latch.
	 */
	private fun Rig.latch(kind: MeshOperatorKind = MeshOperatorKind.Grab): ActiveOperator {
		session.beginUvOperator(kind, LEFT_AREA)
		return assertNotNull(session.activeUvOperator.value, "the session latched the placement")
	}

	/**
	 * Begins the gesture the way the overlay's effect does, over [surface].
	 *
	 * @param ActiveOperator operator The latch the effect saw.
	 * @param UvPlacementSurface surface The shown page.
	 */
	private suspend fun Rig.begin(operator: ActiveOperator, surface: UvPlacementSurface) {
		val frame = uvRigPageFrame()
		transform.begin(operator, surface, uvRigGeometries(session.model.value, frame), session.selection.value, frame, Dispatchers.Default)
	}

	/**
	 * A page whose raster decode waits at [gate] once it has signalled [entered], so a case can act while
	 * the capture is building.
	 *
	 * @param CountDownLatch entered Counted down as the build reaches its first decode.
	 * @param CountDownLatch gate Released by the case to let the build go on.
	 * @return UvPlacementSurface The surface.
	 */
	private fun gatedSurface(entered: CountDownLatch, gate: CountDownLatch): UvPlacementSurface {
		val rasters = uvRigArtRasters()
		val gated =
			SourceArtRasters { tileId ->
				entered.countDown()
				gate.await(BUILD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
				rasters.decodeRaster(tileId)
			}
		return UvPlacementSurface(UV_RIG_PAGE_SIDE, UV_RIG_PAGE_SIDE, gated, pageImage = null)
	}

	/**
	 * Begins over a gated page, runs [whileBuilding] once the build is held at its first decode, then lets
	 * the build land and waits for it.
	 *
	 * @param ActiveOperator operator The latch the effect saw.
	 * @param Function whileBuilding What happens while the capture builds.
	 */
	private fun Rig.beginWhileBuilding(operator: ActiveOperator, whileBuilding: () -> Unit) {
		val entered = CountDownLatch(1)
		val gate = CountDownLatch(1)
		runBlocking {
			val build = launch { begin(operator, gatedSurface(entered, gate)) }
			assertTrue(withContext(Dispatchers.IO) { entered.await(BUILD_TIMEOUT_SECONDS, TimeUnit.SECONDS) }, "the build reached its decode")
			whileBuilding()
			gate.countDown()
			build.join()
		}
	}

	/** A build that lands after its latch cleared begins nothing and says nothing. */
	@Test
	fun aBuildThatLandsAfterTheLatchClearedBeginsNothing() {
		val rig = rigOver()
		val operator = rig.latch()

		rig.beginWhileBuilding(operator) { rig.session.clearUvOperator() }

		assertNull(rig.transform.gesture.capture)
		assertNull(rig.session.notice.value)
	}

	/** A build that lands after the latch was replaced by another begins nothing either. */
	@Test
	fun aBuildThatLandsAfterAReLatchBeginsNothing() {
		val rig = rigOver()
		val operator = rig.latch(MeshOperatorKind.Grab)

		rig.beginWhileBuilding(operator) {
			rig.session.clearUvOperator()
			rig.session.beginUvOperator(MeshOperatorKind.Scale, LEFT_AREA)
		}

		assertNull(rig.transform.gesture.capture)
		assertEquals(MeshOperatorKind.Scale, rig.session.activeUvOperator.value?.kind, "the new latch is left to its own effect")
	}

	/**
	 * A latch cleared and made again with the same operator while the capture builds is begun by this build:
	 * the overlay's effect keys on the operator by value and does not restart for an equal one, so no other
	 * build is coming for it.
	 */
	@Test
	fun aBuildThatLandsAfterASameOperatorReLatchBegins() {
		val rig = rigOver()
		val operator = rig.latch(MeshOperatorKind.Grab)

		rig.beginWhileBuilding(operator) {
			rig.session.clearUvOperator()
			rig.session.beginUvOperator(MeshOperatorKind.Grab, LEFT_AREA)
		}

		assertNotNull(rig.transform.gesture.capture, "the re-latched Grab began")
		assertEquals(operator, rig.session.activeUvOperator.value)
	}

	/** A build that lands begins the gesture at the pointer as it is when it lands, not as it was at the latch. */
	@Test
	fun theGestureStartsAtThePointerWhereTheBuildLanded() {
		val rig = rigOver()
		val operator = rig.latch()
		val landedAt = Offset(210f, 160f)

		rig.beginWhileBuilding(operator) { rig.transform.gesture.lastPointer = landedAt }

		assertNotNull(rig.transform.gesture.capture)
		assertEquals(landedAt, rig.transform.gesture.gestureStart)
	}

	/** A confirm moves the tile and leaves its crops drawn until their pages land - only where a resolver will land them. */
	@Test
	fun aConfirmPublishesAGhostOnlyWithAResolver() {
		for (withResolver in listOf(false, true)) {
			val rig =
				rigOver { session ->
					if (withResolver) {
						val blankPage = DecodedImage(ByteArray(UV_RIG_PAGE_SIDE * UV_RIG_PAGE_SIDE * 4), UV_RIG_PAGE_SIDE, UV_RIG_PAGE_SIDE)
						SessionAtlasPages(session, session.model.value.atlas, PuppetTextures(listOf(blankPage), emptyMap(), premultipliedAlpha = false), uvRigArtRasters())
					} else {
						null
					}
				}
			val operator = rig.latch()
			runBlocking { rig.begin(operator, uvRigPlacementSurface()) }

			assertTrue(rig.transform.drivePreview(UV_RIG_TEN_TEXELS_RIGHT, UV_RIG_PAGE_CAMERA, UV_RIG_AREA_SIZE))
			rig.transform.confirm()

			assertEquals(uvRigTilePlacement(110f), uvRigPlacementOf(rig.session, UV_RIG_QUAD_TILE), "with resolver = $withResolver")
			assertNull(rig.session.activeUvOperator.value)
			val ghost = rig.sceneState.ghost
			if (withResolver) {
				assertSame(rig.session.model.value.atlas, assertNotNull(ghost).atlas, "published for the committed atlas")
				assertEquals(1, ghost.crops.size)
			} else {
				assertNull(ghost, "no resolver would ever retire it")
			}
		}
	}

	/** Each drive publishes the host's readout, and the teardown clears it. */
	@Test
	fun theTeardownClearsTheReadout() {
		val rig = rigOver()
		val operator = rig.latch()
		runBlocking { rig.begin(operator, uvRigPlacementSurface()) }
		rig.transform.drivePreview(UV_RIG_TEN_TEXELS_RIGHT, UV_RIG_PAGE_CAMERA, UV_RIG_AREA_SIZE)
		assertEquals(10, assertNotNull(rig.dragStatus.value).deltaX)

		assertTrue(rig.transform.end(), "a gesture was in flight")

		assertNull(rig.dragStatus.value)
		assertNull(rig.transform.gesture.capture)
		assertFalse(rig.transform.end(), "and only once")
	}

	/** Abandoning a live placement clears this area's latch and the readout; another area's latch stays. */
	@Test
	fun abandoningClearsOnlyThisAreasLatch() {
		val rig = rigOver()
		val operator = rig.latch()
		runBlocking { rig.begin(operator, uvRigPlacementSurface()) }
		rig.transform.drivePreview(UV_RIG_TEN_TEXELS_RIGHT, UV_RIG_PAGE_CAMERA, UV_RIG_AREA_SIZE)

		assertTrue(rig.transform.abandon())
		assertNull(rig.session.activeUvOperator.value)
		assertNull(rig.dragStatus.value)
		assertEquals(uvRigTilePlacement(100f), uvRigPlacementOf(rig.session, UV_RIG_QUAD_TILE), "nothing committed")

		rig.session.beginUvOperator(MeshOperatorKind.Grab, RIGHT_AREA)
		assertFalse(rig.transform.abandon())
		assertEquals(RIGHT_AREA, rig.session.activeUvOperator.value?.areaId)
	}

	/**
	 * The UI keeps a ghost only while the committed atlas is the very instance it was published for; when its
	 * pages land is the engine's call (PlacementGhostRuleTest).
	 */
	@Test
	fun theActiveGhostComparesTheCommittedAtlasByIdentity() {
		val atlas = uvRigPlacedModel().atlas
		val equalAtlas = atlas.copy()
		val ghost = PlacementGhost(atlas, UV_RIG_PAGE_SIDE, emptyList())

		assertEquals(atlas, equalAtlas, "the copy is equal")
		assertSame(ghost, activePlacementGhost(ghost, atlas))
		assertNull(activePlacementGhost(ghost, equalAtlas), "an equal atlas that is not the published one retires it")
		assertNull(activePlacementGhost(null, atlas))
	}

	/**
	 * Each drive publishes the drag's share of the area's scene, and the end clears it; a landing's ghost
	 * outlives the end, and only its dismissal takes it down.
	 */
	@Test
	fun theDragLivesInTheSceneUntilTheEndAndTheGhostUntilItsDismissal() {
		val rig =
			rigOver { session ->
				val blankPage = DecodedImage(ByteArray(UV_RIG_PAGE_SIDE * UV_RIG_PAGE_SIDE * 4), UV_RIG_PAGE_SIDE, UV_RIG_PAGE_SIDE)
				SessionAtlasPages(session, session.model.value.atlas, PuppetTextures(listOf(blankPage), emptyMap(), premultipliedAlpha = false), uvRigArtRasters())
			}
		val operator = rig.latch()
		runBlocking { rig.begin(operator, uvRigPlacementSurface()) }
		assertNull(rig.sceneState.drag, "nothing before the first drive")

		rig.transform.drivePreview(UV_RIG_TEN_TEXELS_RIGHT, UV_RIG_PAGE_CAMERA, UV_RIG_AREA_SIZE)
		val first = assertNotNull(rig.sceneState.drag, "a drive publishes the drag")
		assertEquals(uvRigTilePlacement(110f), first.result.placementByTile[UV_RIG_QUAD_TILE], "with its placement")
		assertTrue(first.previewPositionsById.isNotEmpty(), "and the moving islands' positions")
		rig.transform.drivePreview(UV_RIG_GESTURE_START, UV_RIG_PAGE_CAMERA, UV_RIG_AREA_SIZE)
		assertNotSame(first, rig.sceneState.drag, "every drive publishes anew")

		rig.transform.drivePreview(UV_RIG_TEN_TEXELS_RIGHT, UV_RIG_PAGE_CAMERA, UV_RIG_AREA_SIZE)
		rig.transform.confirm()
		val ghost = assertNotNull(rig.sceneState.ghost, "the landing publishes its ghost")
		assertSame(rig.session.model.value.atlas, ghost.atlas)
		assertEquals(UV_RIG_QUAD_TILE, ghost.crops.single().tileId, "naming the tile whose layer may stand in")
		rig.transform.end()
		assertNull(rig.sceneState.drag, "the end takes the drag down")
		assertSame(ghost, rig.sceneState.ghost, "and leaves the ghost")

		rig.transform.dismissGhost()
		assertNull(rig.sceneState.ghost)
	}

	private companion object {
		const val BUILD_TIMEOUT_SECONDS = 5L
	}

	/**
	 * Runs the rig's drive worker on the test's scheduler, the way the overlay's effect runs it, so a drive
	 * publishes only as the scheduler runs.
	 *
	 * @param Rig rig The rig.
	 */
	private fun TestScope.attachWorker(rig: Rig) {
		backgroundScope.launch { rig.transform.drive.run(StandardTestDispatcher(testScheduler)) }
		runCurrent()
	}

	/** With the worker attached, a drive leaves the caller at once and its readout and drag land as the worker publishes. */
	@Test
	fun aDriveLandsWhenTheWorkerPublishes() =
		runTest {
			val rig = rigOver()
			val operator = rig.latch()
			rig.begin(operator, uvRigPlacementSurface())
			attachWorker(rig)

			assertTrue(rig.transform.drivePreview(UV_RIG_TEN_TEXELS_RIGHT, UV_RIG_PAGE_CAMERA, UV_RIG_AREA_SIZE), "the drive was submitted")
			assertNull(rig.dragStatus.value, "nothing lands before the worker publishes")
			assertNull(rig.sceneState.drag)

			runCurrent()

			assertEquals(10, assertNotNull(rig.dragStatus.value).deltaX)
			assertEquals(uvRigTilePlacement(110f), assertNotNull(rig.sceneState.drag).result.placementByTile[UV_RIG_QUAD_TILE])
		}

	/** A confirm while a drive is pending commits where the pointer is now. */
	@Test
	fun aConfirmWhilePendingCommitsTheLatestPointer() =
		runTest {
			val rig = rigOver()
			val operator = rig.latch()
			rig.begin(operator, uvRigPlacementSurface())
			attachWorker(rig)
			rig.transform.drivePreview(Offset(220f, 150f), UV_RIG_PAGE_CAMERA, UV_RIG_AREA_SIZE)
			runCurrent()
			assertEquals(5, assertNotNull(rig.dragStatus.value).deltaX, "the first drive landed")

			rig.transform.drivePreview(UV_RIG_TEN_TEXELS_RIGHT, UV_RIG_PAGE_CAMERA, UV_RIG_AREA_SIZE)
			rig.transform.confirm()

			assertEquals(uvRigTilePlacement(110f), uvRigPlacementOf(rig.session, UV_RIG_QUAD_TILE))
			runCurrent()
			assertEquals(10, assertNotNull(rig.dragStatus.value).deltaX, "nothing older lands after the confirm")
		}

	/** A gesture that ends while a drive is pending leaves the readout and the drag down when it lands. */
	@Test
	fun anEndWhilePendingLeavesTheReadoutAndTheDragDown() =
		runTest {
			val rig = rigOver()
			val operator = rig.latch()
			rig.begin(operator, uvRigPlacementSurface())
			attachWorker(rig)
			rig.transform.drivePreview(UV_RIG_TEN_TEXELS_RIGHT, UV_RIG_PAGE_CAMERA, UV_RIG_AREA_SIZE)

			assertTrue(rig.transform.end(), "a gesture was in flight")
			runCurrent()

			assertNull(rig.dragStatus.value)
			assertNull(rig.sceneState.drag)
		}
}