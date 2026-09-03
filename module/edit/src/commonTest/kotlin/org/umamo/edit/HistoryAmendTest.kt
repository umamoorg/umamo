package org.umamo.edit

import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins [History.amendTop], the primitive behind adjusting the last operation: it rewrites the live
 * step in place without growing the stack or moving the cursor, refuses while a redo branch exists,
 * and works at the oldest entry under a cap of one.
 */
class HistoryAmendTest {
	private val part = Part(PartId("a"), "A", children = emptyList())

	private fun model(name: String): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = listOf(part.copy(name = name)),
			deformers = emptyList(),
			drawables = emptyList(),
			rootChildren = listOf(OrgChild.Part(PartId("a"))),
			rootPartId = null,
		)

	private fun snapshot(name: String): EditorSnapshot = EditorSnapshot(model(name), Selection(), emptyMap())

	private fun rename(name: String): PartChange.Rename = PartChange.Rename(PartId("a"), name)

	private fun liveName(history: History): String = history.current.model.parts.first().name

	@Test
	fun amendingReplacesTheLiveStepWithoutGrowingTheStack() {
		val history = History(snapshot("seed"))
		history.push(snapshot("first"), rename("first"))
		history.push(snapshot("second"), rename("second"))
		val adjusted = rename("second-adjusted")

		assertTrue(history.amendTop(snapshot("second-adjusted"), adjusted))

		assertEquals(3, history.steps.size, "the stack did not grow")
		assertEquals(2, history.cursorIndex, "the cursor stayed on the live step")
		assertEquals("second-adjusted", liveName(history))
		assertSame(adjusted, history.currentChange, "the entry carries the amended change")
		history.undo()
		assertEquals("first", liveName(history), "undo steps to the base the operation ran from")
		history.redo()
		assertEquals("second-adjusted", liveName(history), "redo returns to the amended result")
	}

	@Test
	fun anAmendRefusesWhileARedoBranchExists() {
		val history = History(snapshot("seed"))
		history.push(snapshot("first"), rename("first"))
		history.push(snapshot("second"), rename("second"))
		history.undo()

		assertFalse(history.amendTop(snapshot("first-adjusted"), rename("first-adjusted")))

		assertEquals("first", liveName(history), "the refused amend changed nothing")
		history.redo()
		assertEquals("second", liveName(history), "the redo branch survived")
	}

	@Test
	fun anAmendAtTheOldestEntryWorksUnderACapOfOne() {
		val history = History(snapshot("seed"), initialLimit = 1)
		history.push(snapshot("first"), rename("first"))
		assertEquals(1, history.steps.size, "the cap dropped the seed")
		assertEquals(0, history.cursorIndex)

		assertTrue(history.amendTop(snapshot("first-adjusted"), rename("first-adjusted")))

		assertEquals("first-adjusted", liveName(history))
		assertEquals(1, history.steps.size)
		assertFalse(history.canUndo, "no base survives in the stack - the record holds it instead")
	}
}