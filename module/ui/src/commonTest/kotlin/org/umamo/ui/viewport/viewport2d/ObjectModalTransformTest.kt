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
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.transform.beginObjectOperator
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the Object overlay's modal transform ([ObjectModalTransform]) over a real session, without
 * Compose: a Grab previews the whole drawable and commits it as one registered TransformDrawables step,
 * a selection with nothing projectable drops the latch, and a cancel commits nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObjectModalTransformTest {
	/** Where the pointer rests as the gesture latches: the gesture measures from here. */
	private val gestureStart = Offset(200f, 150f)

	/** The transform under test, plus every model it pushed. */
	private class Rig(val session: EditorSession, val transform: ObjectModalTransform, val pushed: MutableList<PuppetModel>)

	/**
	 * Latches [kind] in the left area over [session] and begins the gesture the way the overlay's effect does.
	 *
	 * @param EditorSession session The session, in Object mode with a selection.
	 * @param MeshOperatorKind kind The operator.
	 * @return Rig The transform and its pushes.
	 */
	private fun latched(session: EditorSession, kind: MeshOperatorKind = MeshOperatorKind.Grab): Rig {
		val pushed = ArrayList<PuppetModel>()
		val transform = ObjectModalTransform(LEFT_AREA_ID, session, { model -> pushed.add(model) })
		session.beginObjectOperator(kind, LEFT_AREA_ID)
		assertEquals(kind, session.activeObjectOperator.value?.kind, "the session latched the operator")
		transform.gesture.lastPointer = gestureStart
		transform.begin(kind)
		return Rig(session, transform, pushed)
	}

	/** A Grab previews the whole drawable, and the confirm commits it as one registered step. */
	@Test
	fun aGrabPreviewsThenCommitsOneStep() {
		val session = gizmoObjectSession()
		val stepsBefore = session.historyView.value.steps.size
		val rig = latched(session)

		assertTrue(rig.transform.drivePreview(Offset(240f, 150f), RIG_CAMERA, RIG_AREA_SIZE))
		assertEquals(0f, rigPositionsOf(session, RIG_QUAD)[0], "a preview commits nothing")
		rig.transform.confirm()

		assertEquals(listOf(10f, 0f, 30f, 0f, 30f, 20f, 10f, 20f), rigPositionsOf(session, RIG_QUAD))
		assertEquals(stepsBefore + 1, session.historyView.value.steps.size)
		val record = assertNotNull(session.adjustableOperation.value)
		assertEquals(LEFT_AREA_ID, record.areaId)
		assertIs<MeshChange.TransformDrawables>(record.change)
		assertNull(session.activeObjectOperator.value)
		assertTrue(rig.transform.end())
	}

	/** A selection whose drawables cannot be projected captures nothing and drops the latch. */
	@Test
	fun aLatchOverNothingProjectableDrops() {
		val rig = latched(gizmoObjectSession(selected = listOf(RIG_HIDDEN)))

		assertNull(rig.session.activeObjectOperator.value)
		assertNull(rig.transform.gesture.capture)
		assertFalse(rig.transform.end())
	}

	/** A cancel commits nothing; the teardown is still the caller's. */
	@Test
	fun aCancelCommitsNothing() {
		val session = gizmoObjectSession()
		val stepsBefore = session.historyView.value.steps.size
		val rig = latched(session)
		rig.transform.drivePreview(Offset(240f, 150f), RIG_CAMERA, RIG_AREA_SIZE)

		rig.transform.cancel()

		assertEquals(0f, rigPositionsOf(session, RIG_QUAD)[0])
		assertEquals(stepsBefore, session.historyView.value.steps.size)
		assertNull(session.activeObjectOperator.value)
		assertTrue(rig.transform.end(), "the gesture was live, so the caller resyncs")
	}

	/** Another area's latch drives nothing here. */
	@Test
	fun anotherAreasLatchDrivesNothing() {
		val session = gizmoObjectSession()
		val pushed = ArrayList<PuppetModel>()
		val transform = ObjectModalTransform(LEFT_AREA_ID, session, { model -> pushed.add(model) })
		session.beginObjectOperator(MeshOperatorKind.Grab, "right")

		assertFalse(transform.drivePreview(Offset(240f, 150f), RIG_CAMERA, RIG_AREA_SIZE))
		assertTrue(pushed.isEmpty())
	}

	/** Abandoning a live gesture clears this area's latch and asks for the resync; another area's latch stays. */
	@Test
	fun abandoningClearsOnlyThisAreasLatch() {
		val rig = latched(gizmoObjectSession())
		rig.transform.drivePreview(Offset(240f, 150f), RIG_CAMERA, RIG_AREA_SIZE)

		assertTrue(rig.transform.abandon())
		assertNull(rig.session.activeObjectOperator.value)
		assertEquals(0f, rigPositionsOf(rig.session, RIG_QUAD)[0])

		rig.session.beginObjectOperator(MeshOperatorKind.Grab, "right")
		assertFalse(rig.transform.abandon())
		assertEquals("right", rig.session.activeObjectOperator.value?.areaId)
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
			val rig = latched(gizmoObjectSession())
			attachWorker(rig)

			assertTrue(rig.transform.drivePreview(Offset(240f, 150f), RIG_CAMERA, RIG_AREA_SIZE), "the drive was submitted")
			assertTrue(rig.pushed.isEmpty(), "nothing lands before the worker publishes")

			runCurrent()

			assertEquals(10f, rig.pushed.single().drawables.first { drawable -> drawable.id == RIG_QUAD }.mesh!!.positions[0])
		}

	/** A confirm while a drive is pending commits where the pointer is now, and the pending one lands nothing after. */
	@Test
	fun aConfirmWhilePendingCommitsTheLatestPointer() =
		runTest {
			val session = gizmoObjectSession()
			val rig = latched(session)
			attachWorker(rig)
			rig.transform.drivePreview(Offset(220f, 150f), RIG_CAMERA, RIG_AREA_SIZE)
			runCurrent()

			rig.transform.drivePreview(Offset(240f, 150f), RIG_CAMERA, RIG_AREA_SIZE)
			rig.transform.confirm()

			assertEquals(listOf(10f, 0f, 30f, 0f, 30f, 20f, 10f, 20f), rigPositionsOf(session, RIG_QUAD))
			val pushes = rig.pushed.size
			runCurrent()
			assertEquals(pushes, rig.pushed.size, "the pending drive lands nothing after the confirm")
		}

	private companion object {
		const val LEFT_AREA_ID = "left"
	}
}