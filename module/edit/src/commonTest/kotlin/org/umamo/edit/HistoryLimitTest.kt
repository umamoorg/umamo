package org.umamo.edit

import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Verifies the retained-undo-step cap: that pushing past it drops the oldest steps while keeping the
 * cursor on the live one, that a degenerate cap still retains a usable stack, and that reassigning the
 * cap at runtime (what the History Steps preference does) trims immediately without ever discarding the
 * live step or a reachable redo branch.
 */
class HistoryLimitTest {
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

	/**
	 * Pushes [count] distinguishable steps, numbered from 1 so a step's name identifies its ordinal.
	 *
	 * @param History history The stack to push onto.
	 * @param Int count The number of steps to record.
	 */
	private fun pushSteps(history: History, count: Int) {
		for (stepNumber in 1..count) {
			history.push(snapshot("step$stepNumber"), PartChange.Rename(PartId("a"), "step$stepNumber"))
		}
	}

	/**
	 * The name carried by the live step, which identifies which push the cursor sits on.
	 *
	 * @param History history The stack to read.
	 * @return String The live snapshot's part name.
	 */
	private fun liveName(history: History): String = history.current.model.parts.first().name

	/**
	 * Pushing past the cap drops the oldest steps from the front, leaving the stack exactly at the cap with
	 * the cursor still on the newest push.
	 */
	@Test
	fun pushingPastTheCapDropsTheOldestSteps() {
		val history = History(snapshot("seed"), 5)
		pushSteps(history, 12)

		assertEquals(5, history.steps.size)
		assertEquals(4, history.cursorIndex)
		assertEquals("step12", liveName(history))
		// The seed and steps 1-7 are gone; the retained window starts at step 8.
		assertEquals("step8", history.steps.first().snapshot.model.parts.first().name)
	}

	/**
	 * A cap below 1 clamps to a single retained step rather than emptying the stack, so the live state is
	 * always readable and the stack never indexes out of range.
	 */
	@Test
	fun capBelowOneClampsToASingleRetainedStep() {
		val history = History(snapshot("seed"), 0)
		assertEquals(1, history.limit)

		pushSteps(history, 3)
		assertEquals(1, history.steps.size)
		assertEquals(0, history.cursorIndex)
		assertEquals("step3", liveName(history))
		assertTrue(!history.canUndo && !history.canRedo, "a one-step stack has nothing to undo or redo")

		history.limit = -10
		assertEquals(1, history.limit)
		assertEquals(1, history.steps.size)
	}

	/**
	 * Lowering the cap trims the stack there and then - the preference frees memory without waiting for the
	 * next edit - while the live step itself is untouched.
	 */
	@Test
	fun loweringTheCapTrimsImmediately() {
		val history = History(snapshot("seed"), 100)
		pushSteps(history, 20)
		val liveSnapshot = history.current

		history.limit = 4

		assertEquals(4, history.steps.size)
		assertEquals(3, history.cursorIndex)
		assertSame(liveSnapshot, history.current, "trimming must not move the live step")
		assertEquals("step17", history.steps.first().snapshot.model.parts.first().name)
	}

	/**
	 * With the cursor mid-history the trim stops at the live step: the current state and every reachable
	 * redo step survive, so the stack may legitimately still exceed the cap until later pushes shed it.
	 */
	@Test
	fun loweringTheCapNeverTrimsPastTheLiveStep() {
		val history = History(snapshot("seed"), 100)
		pushSteps(history, 20)
		// Walk back five steps, so the cursor sits at index 15 with a five-entry redo branch ahead of it.
		repeat(5) { history.undo() }
		val liveSnapshot = history.current
		assertEquals("step15", liveName(history))

		history.limit = 2

		assertSame(liveSnapshot, history.current, "the live step must survive any trim")
		assertEquals(0, history.cursorIndex, "the trim stops once the live step reaches the front")
		// Six entries remain: the live step plus the five redo steps it can still reach.
		assertEquals(6, history.steps.size)
		assertTrue(history.canRedo, "a reachable redo branch is never discarded by a trim")
		assertTrue(!history.canUndo, "everything before the live step was trimmed away")

		// The excess sheds on the next push, which discards the redo branch and applies the cap in full.
		history.push(snapshot("after"), PartChange.Rename(PartId("a"), "after"))
		assertEquals(2, history.steps.size)
		assertEquals("after", liveName(history))
	}

	/**
	 * Raising the cap keeps every retained entry and simply allows the stack to grow further.
	 */
	@Test
	fun raisingTheCapKeepsExistingSteps() {
		val history = History(snapshot("seed"), 5)
		pushSteps(history, 5)
		assertEquals(5, history.steps.size)

		history.limit = 10
		assertEquals(5, history.steps.size, "raising the cap discards nothing")
		assertEquals("step5", liveName(history))

		pushSteps(history, 5)
		assertEquals(10, history.steps.size)
	}

	/**
	 * The session's cap - the property the preference writes - trims the stack and republishes the derived
	 * state, so the history panel drops the same rows and canUndo reflects the shortened stack.
	 */
	@Test
	fun sessionCapTrimsAndRepublishesTheHistoryView() {
		val session = EditorSession(model("A"))
		for (stepNumber in 1..8) {
			session.mutate(PartChange.Rename(PartId("a"), "A$stepNumber")) { it.withPartName(PartId("a"), "A$stepNumber") }
		}
		// Seed plus eight edits.
		assertEquals(9, session.historyView.value.steps.size)
		assertTrue(session.canUndo.value)

		session.historyLimit = 3

		val view = session.historyView.value
		assertEquals(3, view.steps.size)
		assertEquals(2, view.cursor)
		assertEquals("A8", session.model.value.parts.first().name, "trimming must not change the live model")
		assertTrue(session.canUndo.value, "two older steps are still retained")

		// Down to a single step: nothing is left to undo, and the panel shows exactly the live row.
		session.historyLimit = 1
		assertEquals(1, session.historyView.value.steps.size)
		assertEquals(0, session.historyView.value.cursor)
		assertTrue(!session.canUndo.value)
		assertEquals("A8", session.model.value.parts.first().name)
	}
}