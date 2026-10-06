package org.umamo.ui.viewport.viewport2d

import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshChange
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.MeshSelectMode
import org.umamo.edit.MeshSelectionOps
import org.umamo.edit.PROPORTIONAL_RADIUS_STEP_FACTOR
import org.umamo.edit.ProportionalEditState
import org.umamo.edit.ProportionalFalloff
import org.umamo.edit.TransformParameterKeys
import org.umamo.edit.floatValue
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the Edit overlay's modal transform ([EditModalTransform]) over a real session, without Compose:
 * what a latch captures and when it drops, what a drive previews, what a confirm commits and registers,
 * and when the wheel and a mid-gesture proportional change apply.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditModalTransformTest {
	/** Where the pointer rests as the gesture latches: the gesture measures from here. */
	private val gestureStart = Offset(200f, 150f)

	/** The transform under test, plus every model it pushed. */
	private class Rig(val session: EditorSession, val transform: EditModalTransform, val pushed: MutableList<PuppetModel>)

	/**
	 * A transform for the left area over [session], recording its pushes.
	 *
	 * @param EditorSession session The session.
	 * @return Rig The transform and its pushes.
	 */
	private fun rigOver(session: EditorSession): Rig {
		val pushed = ArrayList<PuppetModel>()
		return Rig(session, EditModalTransform(LEFT_AREA_ID, session, { model -> pushed.add(model) }), pushed)
	}

	/**
	 * Latches [kind] in the left area and begins the gesture the way the overlay's effect does.
	 *
	 * @param MeshOperatorKind kind The operator.
	 * @param Offset pointer Where the pointer rests at the latch.
	 * @param Boolean suppressProportional True for a latch that takes no weights.
	 */
	private fun Rig.latch(kind: MeshOperatorKind, pointer: Offset = gestureStart, suppressProportional: Boolean = false) {
		session.beginMeshOperator(kind, LEFT_AREA_ID, suppressProportional)
		assertEquals(kind, session.activeMeshOperator.value?.kind, "the session latched the operator")
		transform.gesture.lastPointer = pointer
		transform.begin(kind, geometriesOf(session), session.meshSelection.value)
	}

	/**
	 * The session meshes' live geometry, as the overlay composes it.
	 *
	 * @param EditorSession session The session.
	 * @return List The geometry.
	 */
	private fun geometriesOf(session: EditorSession): List<EditMeshGeometry> =
		editMeshGeometries(session.model.value, session.meshSelection.value.drawableIds)

	/**
	 * One drive at [pointer].
	 *
	 * @param Offset pointer The virtual pointer.
	 * @return Boolean Whether a preview was driven.
	 */
	private fun Rig.driveTo(pointer: Offset): Boolean = transform.drivePreview(pointer, RIG_CAMERA, RIG_AREA_SIZE)

	/** A latch whose selection emptied before the capture drops the operator and captures nothing. */
	@Test
	fun aLatchOverNothingDrops() {
		val rig = rigOver(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
		rig.session.beginMeshOperator(MeshOperatorKind.Grab, LEFT_AREA_ID)

		rig.transform.begin(MeshOperatorKind.Grab, geometriesOf(rig.session), MeshSelectionOps.clear(rig.session.meshSelection.value))

		assertNull(rig.session.activeMeshOperator.value)
		assertNull(rig.transform.gesture.capture)
		assertFalse(rig.transform.end(), "there was no gesture to tear down")
	}

	/** A Grab previews without committing, and the confirm commits the preview as one registered step. */
	@Test
	fun aGrabPreviewsThenCommitsOneStep() {
		val rig = rigOver(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
		val session = rig.session
		val stepsBefore = session.historyView.value.steps.size
		rig.latch(MeshOperatorKind.Grab)

		assertTrue(rig.driveTo(Offset(240f, 150f)))
		assertEquals(10f, rig.pushed.single().drawables.first { drawable -> drawable.id == RIG_QUAD }.mesh!!.positions[0], "the preview moved vertex 0")
		assertEquals(0f, rigPositionsOf(session, RIG_QUAD)[0], "a preview commits nothing")

		rig.transform.confirm()

		assertEquals(listOf(10f, 0f, 20f, 0f, 20f, 20f, 0f, 20f), rigPositionsOf(session, RIG_QUAD))
		assertEquals(stepsBefore + 1, session.historyView.value.steps.size)
		assertEquals(LEFT_AREA_ID, assertNotNull(session.adjustableOperation.value).areaId)
		assertNull(session.activeMeshOperator.value)
		assertTrue(rig.transform.end(), "the gesture is torn down by the caller's effect")
		assertFalse(rig.transform.end(), "and only once")
	}

	/** A confirm before any drive has no preview to commit: no step, no registration, and the latch clears. */
	@Test
	fun aConfirmBeforeAnyDriveCommitsNothing() {
		val rig = rigOver(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
		val stepsBefore = rig.session.historyView.value.steps.size
		rig.latch(MeshOperatorKind.Grab)

		rig.transform.confirm()

		assertEquals(stepsBefore, rig.session.historyView.value.steps.size)
		assertNull(rig.session.adjustableOperation.value)
		assertNull(rig.session.activeMeshOperator.value)
	}

	/** A Vertex Slide over an active edge has no vertex to slide: it begins, then drops the latch. */
	@Test
	fun aSlideWithNoActiveVertexBeginsThenDrops() {
		val session = gizmoEditSession()
		session.setMeshSelectMode(MeshSelectMode.Edge)
		session.setMeshSelection(MeshSelectionOps.add(session.meshSelection.value, RIG_QUAD, MeshElement.Edge(0, 1)))
		val rig = rigOver(session)

		rig.latch(MeshOperatorKind.VertexSlide)

		assertNull(session.activeMeshOperator.value)
		assertTrue(rig.transform.end(), "the gesture began first, so its teardown resyncs the renderer")
	}

	/** A slide confirm registers the Factor row at the landed factor. */
	@Test
	fun aSlideRegistersItsFactor() {
		val rig = rigOver(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
		rig.latch(MeshOperatorKind.VertexSlide, rigScreenOf(0f, 0f))

		rig.driveTo(Offset(200f, 112f))
		rig.transform.confirm()

		assertEquals(10f, rigPositionsOf(rig.session, RIG_QUAD)[0])
		val record = assertNotNull(rig.session.adjustableOperation.value)
		assertEquals(0.5f, record.parameters.floatValue(TransformParameterKeys.SLIDE_FACTOR, -1f), 1e-4f)
	}

	/** The wheel grows the radius one step and re-drives the preview at once. */
	@Test
	fun theWheelResizesTheRadiusAndReDrives() {
		val session = gizmoEditSession(elements = listOf(MeshElement.Vertex(0)))
		session.setProportionalEdit(ProportionalEditState(ProportionalFalloff.Linear, 10f))
		val rig = rigOver(session)
		rig.latch(MeshOperatorKind.Grab)
		rig.driveTo(Offset(240f, 150f))

		rig.transform.onScroll(-1f, RIG_CAMERA, RIG_AREA_SIZE)

		assertEquals(10f * PROPORTIONAL_RADIUS_STEP_FACTOR, assertNotNull(session.proportionalEdit.value).radiusWorld, 1e-4f)
		assertEquals(2, rig.pushed.size, "the scroll drove a second preview")
	}

	/** The wheel leaves the radius alone for a slide and for a latch that takes no weights. */
	@Test
	fun theWheelIgnoresASlideAndASuppressedLatch() {
		for (suppressed in listOf(false, true)) {
			val session = gizmoEditSession(elements = listOf(MeshElement.Vertex(0)))
			session.setProportionalEdit(ProportionalEditState(ProportionalFalloff.Linear, 10f))
			val rig = rigOver(session)
			if (suppressed) {
				rig.latch(MeshOperatorKind.Grab, suppressProportional = true)
			} else {
				rig.latch(MeshOperatorKind.VertexSlide, rigScreenOf(0f, 0f))
			}

			rig.transform.onScroll(-1f, RIG_CAMERA, RIG_AREA_SIZE)

			assertEquals(10f, assertNotNull(session.proportionalEdit.value).radiusWorld, "suppressed = $suppressed")
			assertTrue(rig.pushed.isEmpty(), "suppressed = $suppressed")
		}
	}

	/** Proportional turned on mid-gesture weights a Grab's neighbors, and never a slide's or a suppressed latch's. */
	@Test
	fun aMidGestureProportionalChangeWeightsOnlyAWeightedGesture() {
		val halo = ProportionalEditState(ProportionalFalloff.Linear, 30f)
		for ((kind, suppressed, weighted) in listOf(
			Triple(MeshOperatorKind.Grab, false, true),
			Triple(MeshOperatorKind.VertexSlide, false, false),
			Triple(MeshOperatorKind.Grab, true, false),
		)) {
			val rig = rigOver(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
			rig.latch(kind, rigScreenOf(0f, 0f), suppressed)
			val entry = assertNotNull(rig.transform.gesture.capture).transform.entries.single()
			assertTrue(entry.influence.isEmpty(), "the latch took no weights with proportional off")

			rig.transform.reapplyProportional(halo)

			assertEquals(weighted, entry.influence.isNotEmpty(), "$kind, suppressed = $suppressed")
		}
	}

	/** Another area's latch drives nothing here. */
	@Test
	fun anotherAreasLatchDrivesNothing() {
		val rig = rigOver(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
		rig.session.beginMeshOperator(MeshOperatorKind.Grab, "right")

		assertFalse(rig.driveTo(Offset(240f, 150f)))
		assertTrue(rig.pushed.isEmpty())
	}

	/** Abandoning a live gesture clears this area's latch and asks for the resync. */
	@Test
	fun abandoningALiveGestureClearsItsLatch() {
		val rig = rigOver(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
		rig.latch(MeshOperatorKind.Grab)
		rig.driveTo(Offset(240f, 150f))

		assertTrue(rig.transform.abandon(), "a gesture was in flight")

		assertNull(rig.session.activeMeshOperator.value)
		assertNull(rig.transform.gesture.capture)
		assertEquals(0f, rigPositionsOf(rig.session, RIG_QUAD)[0], "nothing committed")
		assertFalse(rig.transform.abandon(), "and only once")
	}

	/** Abandoning never clears a latch another area holds. */
	@Test
	fun abandoningLeavesAnotherAreasLatch() {
		val rig = rigOver(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
		rig.session.beginMeshOperator(MeshOperatorKind.Grab, "right")

		assertFalse(rig.transform.abandon())

		assertEquals("right", rig.session.activeMeshOperator.value?.areaId)
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

	/**
	 * A drawable's positions in the most recent model the rig pushed.
	 *
	 * @param Rig rig The rig.
	 * @param DrawableId drawableId The drawable to read.
	 * @return FloatArray The pushed positions.
	 */
	private fun pushedPositionsOf(rig: Rig, drawableId: DrawableId = RIG_QUAD): FloatArray =
		rig.pushed.last().drawables.first { drawable -> drawable.id == drawableId }.mesh!!.positions

	/** With the worker attached, a drive leaves the caller at once and lands as the worker publishes. */
	@Test
	fun aDriveLandsWhenTheWorkerPublishes() =
		runTest {
			val rig = rigOver(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
			attachWorker(rig)
			rig.latch(MeshOperatorKind.Grab)

			assertTrue(rig.driveTo(Offset(240f, 150f)), "the drive was submitted")
			assertTrue(rig.pushed.isEmpty(), "nothing lands before the worker publishes")
			assertNull(rig.transform.gesture.preview)

			runCurrent()

			assertEquals(10f, pushedPositionsOf(rig)[0], "the drive landed")
			assertNotNull(rig.transform.gesture.preview)
		}

	/** A confirm while a drive is pending commits where the pointer is now, and the pending one lands nothing after. */
	@Test
	fun aConfirmWhilePendingCommitsTheLatestPointer() =
		runTest {
			val rig = rigOver(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
			attachWorker(rig)
			rig.latch(MeshOperatorKind.Grab)
			rig.driveTo(Offset(220f, 150f))
			runCurrent()
			assertEquals(5f, pushedPositionsOf(rig)[0], "the first drive landed")

			rig.driveTo(Offset(240f, 150f))
			rig.transform.confirm()

			assertEquals(10f, rigPositionsOf(rig.session, RIG_QUAD)[0], "the confirm committed the latest pointer")
			val pushes = rig.pushed.size
			runCurrent()
			assertEquals(pushes, rig.pushed.size, "the pending drive lands nothing after the confirm")
		}

	/** A slide confirm while a drive is pending registers the landing of the latest pointer. */
	@Test
	fun aSlideConfirmWhilePendingRegistersTheLatestLanding() =
		runTest {
			val rig = rigOver(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
			attachWorker(rig)
			rig.latch(MeshOperatorKind.VertexSlide, rigScreenOf(0f, 0f))
			rig.driveTo(Offset(180f, 111f))
			runCurrent()

			rig.driveTo(Offset(200f, 112f))
			rig.transform.confirm()

			assertEquals(10f, rigPositionsOf(rig.session, RIG_QUAD)[0])
			val record = assertNotNull(rig.session.adjustableOperation.value)
			assertEquals(0.5f, record.parameters.floatValue(TransformParameterKeys.SLIDE_FACTOR, -1f), 1e-4f)
		}

	/**
	 * Three pointer events a quarter turn apart, none computed before the next arrives, still add up to
	 * three quarter turns: the parameters resolve per event, so the angle accumulator sees every step.
	 */
	@Test
	fun rotateUnderConflationKeepsEveryTrackerStep() =
		runTest {
			val rig = rigOver(gizmoEditSession(elements = listOf(MeshElement.Vertex(0), MeshElement.Vertex(2))))
			attachWorker(rig)
			// The two vertices' median is the quad's center, which the rig shows at (200, 150).  The angle
			// accumulator measures from the first drive, so the first one lands where the gesture starts.
			rig.latch(MeshOperatorKind.Rotate, Offset(240f, 150f))

			rig.driveTo(Offset(240f, 150f))
			rig.driveTo(Offset(200f, 190f))
			rig.driveTo(Offset(160f, 150f))
			rig.driveTo(Offset(200f, 110f))
			rig.transform.confirm()

			val record = assertNotNull(rig.session.adjustableOperation.value)
			assertEquals(270f, abs(record.parameters.floatValue(TransformParameterKeys.ANGLE, 0f)), 1e-2f)
		}

	/** A halo change after a drive was submitted leaves that drive on the halo it was made with. */
	@Test
	fun aHaloChangeAfterSubmitDoesNotReachThePendingDrive() =
		runTest {
			val session = gizmoEditSession(elements = listOf(MeshElement.Vertex(0)))
			session.setProportionalEdit(ProportionalEditState(ProportionalFalloff.Linear, 30f))
			val rig = rigOver(session)
			attachWorker(rig)
			rig.latch(MeshOperatorKind.Grab)

			rig.driveTo(Offset(240f, 150f))
			rig.transform.reapplyProportional(null)
			runCurrent()

			val published = pushedPositionsOf(rig)
			assertEquals(10f, published[0])
			assertNotEquals(20f, published[2], "the halo the drive was made with moved vertex 1")
		}

	/** The wheel resizes the radius at once and re-drives through the worker. */
	@Test
	fun aScrollReDrivesThroughTheWorker() =
		runTest {
			val session = gizmoEditSession(elements = listOf(MeshElement.Vertex(0)))
			session.setProportionalEdit(ProportionalEditState(ProportionalFalloff.Linear, 10f))
			val rig = rigOver(session)
			attachWorker(rig)
			rig.latch(MeshOperatorKind.Grab)
			rig.driveTo(Offset(240f, 150f))
			runCurrent()

			rig.transform.onScroll(-1f, RIG_CAMERA, RIG_AREA_SIZE)

			assertEquals(10f * PROPORTIONAL_RADIUS_STEP_FACTOR, assertNotNull(session.proportionalEdit.value).radiusWorld, 1e-4f)
			assertEquals(1, rig.pushed.size, "the re-drive waits for the worker")
			runCurrent()
			assertEquals(2, rig.pushed.size, "and lands as it publishes")
		}

	/** A commit landing while a drive is in flight shows in the preview that drive publishes. */
	@Test
	fun aCommitWhileHeldShowsInThePublishedPreview() =
		runTest {
			val rig = rigOver(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
			attachWorker(rig)
			rig.latch(MeshOperatorKind.Grab)
			rig.driveTo(Offset(240f, 150f))

			rig.session.commitMeshPositions(
				MeshChange.TransformVertices(mapOf(RIG_OTHER to listOf(0)), MeshOperatorKind.Grab),
				mapOf(RIG_OTHER to floatArrayOf(45f, 0f, 60f, 0f, 40f, 20f)),
			)
			runCurrent()

			assertEquals(10f, pushedPositionsOf(rig)[0], "the drive landed")
			assertEquals(45f, pushedPositionsOf(rig, RIG_OTHER)[0], "over the model the commit made")
		}

	private companion object {
		const val LEFT_AREA_ID = "left"
	}
}