package org.umamo.ui.viewport.uv

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.MeshSelectionOps
import org.umamo.edit.OperatorParameter
import org.umamo.edit.PROPORTIONAL_RADIUS_STEP_FACTOR
import org.umamo.edit.ProportionalEditState
import org.umamo.edit.ProportionalFalloff
import org.umamo.edit.TransformParameterKeys
import org.umamo.edit.withParameter
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.DrawableLayerBinding
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.viewport.gizmo.LEFT_AREA
import org.umamo.ui.viewport.gizmo.RIGHT_AREA
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the UV Edit overlay's modal transform ([UvEditModalTransform]) over a real session, without Compose:
 * what a latch captures and when it drops, what a drive previews, what a confirm commits and registers,
 * that the proportional radius is the one of the surface a gesture began on - the shown surface's at the
 * latch, kept through a switch mid-gesture and by a confirmed step's strip adjustment - and that an abandon
 * clears only this area's latch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UvEditModalTransformTest {
	/**
	 * The transform under test, the holders the overlay would hand it, and every model it pushed.
	 *
	 * @property EditorSession session The session.
	 * @property MutableState shownFrame The shown surface's frame, which the overlay's latch effect hands begin.
	 * @property MutableState radiusHolder The shown surface's radius state, as the overlay's live holder.
	 * @property UvEditModalTransform transform The transform.
	 * @property MutableList pushed Every preview model pushed.
	 */
	private class Rig(
		val session: EditorSession,
		val shownFrame: MutableState<UvEditFrame>,
		val radiusHolder: MutableState<MutableState<Float?>>,
		val transform: UvEditModalTransform,
		val pushed: MutableList<PuppetModel>,
	)

	/**
	 * A transform for the left area over [session], showing the rig's page with an unseeded radius.
	 *
	 * @param EditorSession session The session.
	 * @return Rig The transform and its holders.
	 */
	private fun rigOver(session: EditorSession): Rig {
		val pushed = ArrayList<PuppetModel>()
		val shownFrame = mutableStateOf(uvRigPageFrame())
		val radiusHolder = mutableStateOf<MutableState<Float?>>(mutableStateOf(null))
		val transform = UvEditModalTransform(LEFT_AREA, session, radiusHolder) { model -> pushed.add(model) }
		return Rig(session, shownFrame, radiusHolder, transform, pushed)
	}

	/**
	 * Latches [kind] in the left area and begins the gesture the way the overlay's effect does.
	 *
	 * @param MeshOperatorKind kind The operator.
	 */
	private fun Rig.latch(kind: MeshOperatorKind) {
		session.beginUvOperator(kind, LEFT_AREA)
		assertEquals(kind, session.activeUvOperator.value?.kind, "the session latched the operator")
		transform.gesture.lastPointer = UV_RIG_GESTURE_START
		val frame = shownFrame.value
		transform.begin(kind, uvRigGeometries(session.model.value, frame), session.meshSelection.value, frame)
	}

	/**
	 * One drive at [pointer].
	 *
	 * @param Offset pointer The virtual pointer.
	 * @return Boolean Whether a preview was driven.
	 */
	private fun Rig.driveTo(pointer: Offset): Boolean = transform.drivePreview(pointer, UV_RIG_PAGE_CAMERA, UV_RIG_AREA_SIZE)

	/**
	 * The shown surface switches to the rig's source layer, as the host hands over the layer's frame and its
	 * own radius state.
	 *
	 * @return MutableState<Float?> The layer's radius state.
	 */
	private fun Rig.showLayer(): MutableState<Float?> {
		val layerRadius = mutableStateOf<Float?>(null)
		shownFrame.value = uvRigLayerFrame()
		radiusHolder.value = layerRadius
		return layerRadius
	}

	/** A latch whose selection emptied before the capture drops the operator and captures nothing. */
	@Test
	fun aLatchOverNothingDrops() {
		val rig = rigOver(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
		rig.session.beginUvOperator(MeshOperatorKind.Grab, LEFT_AREA)
		val frame = rig.shownFrame.value

		rig.transform.begin(MeshOperatorKind.Grab, uvRigGeometries(rig.session.model.value, frame), MeshSelectionOps.clear(rig.session.meshSelection.value), frame)

		assertNull(rig.session.activeUvOperator.value)
		assertNull(rig.transform.gesture.capture)
		assertFalse(rig.transform.end(), "there was no gesture to tear down")
	}

	/** A Grab previews without committing, and the confirm commits the preview as one registered step. */
	@Test
	fun aGrabPreviewsThenCommitsOneStep() {
		val rig = rigOver(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
		val session = rig.session
		val stepsBefore = session.historyView.value.steps.size
		rig.latch(MeshOperatorKind.Grab)

		assertTrue(rig.driveTo(UV_RIG_TEN_TEXELS_RIGHT))
		assertEquals(110f / 256, rig.pushed.single().drawables.first { drawable -> drawable.id == UV_RIG_QUAD }.mesh!!.uvs[0], "the preview moved vertex 0")
		assertEquals(UV_RIG_QUAD_UVS, uvRigUvsOf(session, UV_RIG_QUAD), "a preview commits nothing")

		rig.transform.confirm()

		assertEquals(listOf(110f / 256) + UV_RIG_QUAD_UVS.drop(1), uvRigUvsOf(session, UV_RIG_QUAD))
		assertEquals(stepsBefore + 1, session.historyView.value.steps.size)
		assertEquals(LEFT_AREA, assertNotNull(session.adjustableOperation.value).areaId)
		assertNull(session.activeUvOperator.value)
		assertTrue(rig.transform.end(), "the gesture is torn down by the caller's effect")
		assertFalse(rig.transform.end(), "and only once")
	}

	/**
	 * Over a layer placed rotated and scaled - this placement's display round trip drifts every one of the
	 * quad's u coordinates by an ulp or two in floats - the confirm writes only the moved vertex: every other
	 * stored coordinate stays bit-identical.
	 */
	@Test
	fun aConfirmLeavesUnmovedCoordinatesBitIdentical() {
		val session = uvEditSession(elements = listOf(MeshElement.Vertex(0)))
		val binding =
			DrawableLayerBinding(
				layerKey = UV_RIG_LAYER_KEY,
				placement = AtlasPlacement(pageIndex = 0, positionX = 140f, positionY = 90f, scaleX = 0.7f, scaleY = 0.7f, rotationDegrees = 17f),
				pageWidth = UV_RIG_PAGE_SIDE,
				pageHeight = UV_RIG_PAGE_SIDE,
			)
		val frame = assertNotNull(sourceLayerEditFrame(binding, UV_RIG_LAYER_SIDE, UV_RIG_LAYER_SIDE))
		val rig = rigOver(session)
		rig.shownFrame.value = frame
		val camera = uvRigCameraOver(uvRigGeometries(session.model.value, frame))
		session.beginUvOperator(MeshOperatorKind.Grab, LEFT_AREA)
		rig.transform.gesture.lastPointer = UV_RIG_GESTURE_START
		rig.transform.begin(MeshOperatorKind.Grab, uvRigGeometries(session.model.value, frame), session.meshSelection.value, frame)

		rig.transform.drivePreview(UV_RIG_TEN_TEXELS_RIGHT, camera, UV_RIG_AREA_SIZE)
		rig.transform.confirm()

		val committed = uvRigUvsOf(session, UV_RIG_QUAD)
		assertNotEquals(UV_RIG_QUAD_UVS.take(2), committed.take(2), "vertex 0 moved")
		assertEquals(UV_RIG_QUAD_UVS.drop(2), committed.drop(2), "every other coordinate bit-identical")
	}

	/** A confirm before any drive has no preview to commit: no step, no registration, and the latch clears. */
	@Test
	fun aConfirmBeforeAnyDriveCommitsNothing() {
		val rig = rigOver(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
		val stepsBefore = rig.session.historyView.value.steps.size
		rig.latch(MeshOperatorKind.Grab)

		rig.transform.confirm()

		assertEquals(stepsBefore, rig.session.historyView.value.steps.size)
		assertNull(rig.session.adjustableOperation.value)
		assertNull(rig.session.activeUvOperator.value)
	}

	/** The first latch seeds the shown surface's radius from its size, even with proportional editing off. */
	@Test
	fun theFirstLatchSeedsTheShownSurfacesRadius() {
		val rig = rigOver(uvEditSession(elements = listOf(MeshElement.Vertex(0))))

		rig.latch(MeshOperatorKind.Grab)

		assertEquals(UV_RIG_PAGE_SIDE / 8f, rig.radiusHolder.value.value)
	}

	/** The wheel grows the texel radius one step, leaves the session's world radius alone, and re-drives at once. */
	@Test
	fun theWheelResizesTheRadiusAndReDrives() {
		val session = uvEditSession(elements = listOf(MeshElement.Vertex(0)))
		session.setProportionalEdit(ProportionalEditState(ProportionalFalloff.Linear, 10f))
		val rig = rigOver(session)
		rig.latch(MeshOperatorKind.Grab)
		rig.driveTo(UV_RIG_TEN_TEXELS_RIGHT)

		rig.transform.onScroll(-1f, UV_RIG_PAGE_CAMERA, UV_RIG_AREA_SIZE)

		assertEquals(UV_RIG_PAGE_SIDE / 8f * PROPORTIONAL_RADIUS_STEP_FACTOR, assertNotNull(rig.radiusHolder.value.value), 1e-4f)
		assertEquals(10f, assertNotNull(session.proportionalEdit.value).radiusWorld)
		assertEquals(2, rig.pushed.size, "the scroll drove a second preview")
	}

	/**
	 * A surface switch mid-gesture leaves the wheel on the radius the gesture began with: the capture is still
	 * measured in the page's texels, so the layer's radius would be a different length in them.
	 */
	@Test
	fun theWheelKeepsTheGesturesRadiusThroughASwitch() {
		val session = uvEditSession(elements = listOf(MeshElement.Vertex(0)))
		session.setProportionalEdit(ProportionalEditState(ProportionalFalloff.Linear, 10f))
		val rig = rigOver(session)
		rig.latch(MeshOperatorKind.Grab)
		val pageRadius = rig.radiusHolder.value
		val layerRadius = rig.showLayer()

		rig.transform.onScroll(-1f, UV_RIG_PAGE_CAMERA, UV_RIG_AREA_SIZE)

		assertEquals(UV_RIG_PAGE_SIDE / 8f * PROPORTIONAL_RADIUS_STEP_FACTOR, assertNotNull(pageRadius.value), 1e-4f, "the page's radius grew")
		assertNull(layerRadius.value, "the layer's radius is untouched, not even seeded")
	}

	/** A mid-gesture switch keeps the proportional weights on the gesture's radius too. */
	@Test
	fun aMidGestureProportionalChangeKeepsTheGesturesRadiusThroughASwitch() {
		val rig = rigOver(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
		rig.latch(MeshOperatorKind.Grab)
		val layerRadius = rig.showLayer()
		layerRadius.value = 1f

		rig.transform.reapplyProportional(ProportionalEditState(ProportionalFalloff.Linear, 10f))

		val entry = assertNotNull(rig.transform.gesture.capture).transform.entries.single()
		assertTrue(entry.influence.isNotEmpty(), "vertex 1 lies twenty texels away, inside the page's thirty-two, not the layer's one")
	}

	/** A gesture begun after the host hands over another surface's radius seeds and resizes that one. */
	@Test
	fun aGestureAfterASwitchTakesTheShownSurfacesRadius() {
		val session = uvEditSession(elements = listOf(MeshElement.Vertex(0)))
		session.setProportionalEdit(ProportionalEditState(ProportionalFalloff.Linear, 10f))
		val rig = rigOver(session)
		rig.latch(MeshOperatorKind.Grab)
		rig.transform.cancel()
		rig.transform.end()
		val pageRadius = rig.radiusHolder.value
		val layerRadius = rig.showLayer()
		rig.latch(MeshOperatorKind.Grab)

		rig.transform.onScroll(-1f, UV_RIG_PAGE_CAMERA, UV_RIG_AREA_SIZE)

		assertEquals(UV_RIG_LAYER_SIDE / 8f * PROPORTIONAL_RADIUS_STEP_FACTOR, assertNotNull(layerRadius.value), 1e-4f, "seeded from the layer, then grown")
		assertEquals(UV_RIG_PAGE_SIDE / 8f, pageRadius.value, "the page's radius is untouched")
	}

	/** A switch between the latch and the confirm leaves the strip row and its write-back on the gesture's surface. */
	@Test
	fun aSwitchMidGestureLeavesTheStripOnTheGesturesSurface() {
		val session = uvEditSession(elements = listOf(MeshElement.Vertex(0)))
		session.setProportionalEdit(ProportionalEditState(ProportionalFalloff.Linear, 10f))
		val rig = rigOver(session)
		rig.latch(MeshOperatorKind.Grab)
		val pageRadius = rig.radiusHolder.value
		rig.driveTo(UV_RIG_TEN_TEXELS_RIGHT)
		val layerRadius = rig.showLayer()
		rig.transform.confirm()
		val record = assertNotNull(session.adjustableOperation.value)
		val sizeRow = record.parameters.first { parameter -> parameter.key == TransformParameterKeys.PROPORTIONAL_SIZE } as OperatorParameter.FloatParameter
		assertEquals(UV_RIG_PAGE_SIDE / 8f, sizeRow.value, "the row holds the page's radius")

		session.adjustLastOperation(record.parameters.withParameter(TransformParameterKeys.PROPORTIONAL_SIZE, sizeRow.copy(value = 12f)))

		assertEquals(12f, pageRadius.value, "the step's own surface")
		assertNull(layerRadius.value, "not the surface shown now")
	}

	/** A confirmed step's strip adjustment writes back to the radius of the surface the gesture ran on. */
	@Test
	fun anAdjustmentWritesTheRadiusTheConfirmCaptured() {
		val session = uvEditSession(elements = listOf(MeshElement.Vertex(0)))
		session.setProportionalEdit(ProportionalEditState(ProportionalFalloff.Linear, 10f))
		val rig = rigOver(session)
		rig.latch(MeshOperatorKind.Grab)
		rig.driveTo(UV_RIG_TEN_TEXELS_RIGHT)
		rig.transform.confirm()
		val pageRadius = rig.radiusHolder.value
		val layerRadius = rig.showLayer()
		val record = assertNotNull(session.adjustableOperation.value)
		val sizeRow = record.parameters.first { parameter -> parameter.key == TransformParameterKeys.PROPORTIONAL_SIZE } as OperatorParameter.FloatParameter

		session.adjustLastOperation(record.parameters.withParameter(TransformParameterKeys.PROPORTIONAL_SIZE, sizeRow.copy(value = 12f)))

		assertEquals(12f, pageRadius.value, "the step's own surface")
		assertNull(layerRadius.value, "not the surface shown now")
		assertEquals(10f, assertNotNull(session.proportionalEdit.value).radiusWorld, "nor the session's world radius")
	}

	/** Proportional turned on mid-gesture weights the neighbors within the shown surface's radius. */
	@Test
	fun aMidGestureProportionalChangeWeightsTheNeighbors() {
		val rig = rigOver(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
		rig.latch(MeshOperatorKind.Grab)
		val entry = assertNotNull(rig.transform.gesture.capture).transform.entries.single()
		assertTrue(entry.influence.isEmpty(), "the latch took no weights with proportional off")

		rig.transform.reapplyProportional(ProportionalEditState(ProportionalFalloff.Linear, 10f))

		assertTrue(entry.influence.isNotEmpty(), "vertex 1 lies twenty texels away, inside the page's thirty-two")
	}

	/** Another area's latch drives nothing here. */
	@Test
	fun anotherAreasLatchDrivesNothing() {
		val rig = rigOver(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
		rig.session.beginUvOperator(MeshOperatorKind.Grab, RIGHT_AREA)

		assertFalse(rig.driveTo(UV_RIG_TEN_TEXELS_RIGHT))
		assertTrue(rig.pushed.isEmpty())
	}

	/** Abandoning a live gesture clears this area's latch and asks for the resync. */
	@Test
	fun abandoningALiveGestureClearsItsLatch() {
		val rig = rigOver(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
		rig.latch(MeshOperatorKind.Grab)
		rig.driveTo(UV_RIG_TEN_TEXELS_RIGHT)

		assertTrue(rig.transform.abandon(), "a gesture was in flight")

		assertNull(rig.session.activeUvOperator.value)
		assertNull(rig.transform.gesture.capture)
		assertEquals(UV_RIG_QUAD_UVS, uvRigUvsOf(rig.session, UV_RIG_QUAD), "nothing committed")
		assertFalse(rig.transform.abandon(), "and only once")
	}

	/** Abandoning never clears a latch another area holds. */
	@Test
	fun abandoningLeavesAnotherAreasLatch() {
		val rig = rigOver(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
		rig.session.beginUvOperator(MeshOperatorKind.Grab, RIGHT_AREA)
		val latched = rig.session.activeUvOperator.value

		assertFalse(rig.transform.abandon())

		assertSame(latched, rig.session.activeUvOperator.value)
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

	/** With the worker attached, a drive leaves the caller at once and lands as the worker publishes. */
	@Test
	fun aDriveLandsWhenTheWorkerPublishes() =
		runTest {
			val rig = rigOver(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
			attachWorker(rig)
			rig.latch(MeshOperatorKind.Grab)

			assertTrue(rig.driveTo(UV_RIG_TEN_TEXELS_RIGHT), "the drive was submitted")
			assertTrue(rig.pushed.isEmpty(), "nothing lands before the worker publishes")
			assertNull(rig.transform.gesture.preview)

			runCurrent()

			assertEquals(110f / 256, rig.pushed.single().drawables.first { drawable -> drawable.id == UV_RIG_QUAD }.mesh!!.uvs[0])
		}

	/** A confirm while a drive is pending commits where the pointer is now, and the pending one lands nothing after. */
	@Test
	fun aConfirmWhilePendingCommitsTheLatestPointer() =
		runTest {
			val rig = rigOver(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
			attachWorker(rig)
			rig.latch(MeshOperatorKind.Grab)
			rig.driveTo(Offset(220f, 150f))
			runCurrent()
			assertEquals(105f / 256, rig.pushed.single().drawables.first { drawable -> drawable.id == UV_RIG_QUAD }.mesh!!.uvs[0], "the first drive landed")

			rig.driveTo(UV_RIG_TEN_TEXELS_RIGHT)
			rig.transform.confirm()

			assertEquals(listOf(110f / 256) + UV_RIG_QUAD_UVS.drop(1), uvRigUvsOf(rig.session, UV_RIG_QUAD))
			val pushes = rig.pushed.size
			runCurrent()
			assertEquals(pushes, rig.pushed.size, "the pending drive lands nothing after the confirm")
		}

	/** The wheel resizes the texel radius at once, on the caller, and the re-driven preview lands as the worker publishes. */
	@Test
	fun theRadiusChangesAtOnceAndThePreviewLater() =
		runTest {
			val session = uvEditSession(elements = listOf(MeshElement.Vertex(0)))
			session.setProportionalEdit(ProportionalEditState(ProportionalFalloff.Linear, 10f))
			val rig = rigOver(session)
			attachWorker(rig)
			rig.latch(MeshOperatorKind.Grab)
			rig.driveTo(UV_RIG_TEN_TEXELS_RIGHT)
			runCurrent()

			rig.transform.onScroll(-1f, UV_RIG_PAGE_CAMERA, UV_RIG_AREA_SIZE)

			assertEquals(UV_RIG_PAGE_SIDE / 8f * PROPORTIONAL_RADIUS_STEP_FACTOR, assertNotNull(rig.radiusHolder.value.value), 1e-4f)
			assertEquals(1, rig.pushed.size, "the re-drive waits for the worker")
			runCurrent()
			assertEquals(2, rig.pushed.size, "and lands as it publishes")
		}
}