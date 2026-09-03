package org.umamo.edit

import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the session half of adjusting the last operation: a registration captures the base and the
 * committed change, an adjustment re-runs from that base and rewrites the operation's own step, any
 * other push or a restore clears the record, a stale amend after a clear is refused, an amended step
 * dirties a saved document, and a registration against the wrong model or without a push is refused.
 */
class AdjustableOperationTest {
	private val partId = PartId("a")
	private val widthKey = "width"

	private fun model(name: String): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = listOf(Part(partId, name, children = emptyList())),
			deformers = emptyList(),
			drawables = emptyList(),
			rootChildren = listOf(OrgChild.Part(partId)),
			rootPartId = null,
		)

	private fun PuppetModel.renamed(name: String): PuppetModel = copy(parts = parts.map { part -> part.copy(name = name) })

	private fun liveName(session: EditorSession): String = session.model.value.parts.first().name

	private fun parameters(width: Int): List<OperatorParameter> = listOf(OperatorParameter.IntParameter(widthKey, "op.width", width, 0, 100))

	/**
	 * Runs the test's stand-in operation: renames the part to "w<width>" as one commit and registers it
	 * as adjustable, its rerun renaming from the BASE to the width it is given.
	 *
	 * @param EditorSession session The session to run in.
	 * @param Int width The operation's one setting.
	 * @return AdjustableOperation The live record.
	 */
	private fun runOperation(session: EditorSession, width: Int): AdjustableOperation {
		session.mutate(PartChange.Rename(partId, "w$width")) { current -> current.renamed("w$width") }
		val committed = session.model.value
		return assertNotNull(
			session.registerAdjustableOperation(committed, areaId = "area-1", parameters = parameters(width)) { record ->
				val newWidth = record.parameters.intValue(widthKey, -1)
				session.amendLastCommit(record, record.baseSnapshot.model.renamed("w$newWidth"))
			},
			"a registration right after the commit succeeds",
		)
	}

	@Test
	fun aRegistrationCapturesTheBaseAndTheCommittedChange() {
		val session = EditorSession(model("seed"))

		val record = runOperation(session, 1)

		assertEquals("seed", record.baseSnapshot.model.parts.first().name, "the base is the state the operation ran from")
		assertEquals("change.part.rename", record.change.labelKey)
		assertEquals("area-1", record.areaId)
		assertSame(record, session.adjustableOperation.value)
	}

	@Test
	fun anAdjustmentRerunsFromTheBaseAndRewritesTheStepInPlace() {
		val session = EditorSession(model("seed"))
		runOperation(session, 1)
		val stepsBefore = session.historyView.value.steps.size

		session.adjustLastOperation(parameters(7))

		assertEquals("w7", liveName(session), "the rerun landed")
		assertEquals(stepsBefore, session.historyView.value.steps.size, "the stack did not grow")
		assertFalse(session.canRedo.value)
		assertEquals(parameters(7), assertNotNull(session.adjustableOperation.value).parameters, "the record shows the new settings")
		session.undo()
		assertEquals("seed", liveName(session), "one undo returns to the base")
		assertFalse(session.canUndo.value)
		session.redo()
		assertEquals("w7", liveName(session), "redo returns the adjusted result")
	}

	@Test
	fun anyOtherPushClearsTheRecord() {
		val session = EditorSession(model("seed"))
		runOperation(session, 1)

		session.mutate(PartChange.Rename(partId, "other")) { current -> current.renamed("other") }

		assertNull(session.adjustableOperation.value, "another commit ends the adjustable operation")
	}

	@Test
	fun aRestoreClearsTheRecordAndAStaleAmendIsRefused() {
		val session = EditorSession(model("seed"))
		val record = runOperation(session, 1)

		session.undo()

		assertNull(session.adjustableOperation.value, "undo ends the adjustable operation")
		assertFalse(session.amendLastCommit(record, model("stale")), "a rerun completing after the clear is dropped")
		assertEquals("seed", liveName(session), "nothing was published")
		session.adjustLastOperation(parameters(9))
		assertEquals("seed", liveName(session), "adjusting with no record is a no-op")
	}

	@Test
	fun anAmendedStepDirtiesASavedDocument() {
		val session = EditorSession(model("seed"))
		runOperation(session, 1)
		session.markSaved()
		assertFalse(session.dirty.value)

		session.adjustLastOperation(parameters(2))

		assertTrue(session.dirty.value, "the adjusted result is unsaved content")
	}

	@Test
	fun aRegistrationIsRefusedWithoutACommitOrAgainstAnotherModel() {
		val fresh = EditorSession(model("seed"))
		assertNull(fresh.registerAdjustableOperation(fresh.model.value, null, parameters(1)) {}, "nothing was pushed")

		val session = EditorSession(model("seed"))
		session.mutate(PartChange.Rename(partId, "w1")) { current -> current.renamed("w1") }
		assertNull(session.registerAdjustableOperation(model("elsewhere"), null, parameters(1)) {}, "the live model is not the one claimed")
		assertNotNull(session.registerAdjustableOperation(session.model.value, null, parameters(1)) {}, "the right model registers")
		assertNull(session.registerAdjustableOperation(session.model.value, null, parameters(1)) {}, "a second registration for the same push is refused")
	}
}