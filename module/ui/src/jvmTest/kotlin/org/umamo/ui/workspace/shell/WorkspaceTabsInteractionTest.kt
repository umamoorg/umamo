package org.umamo.ui.workspace.shell

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The tab strip's press handling, driven with real pointer input over the real workspace commands: the
 * double-click that opens the inline rename editor, the reorder drag that shares its gesture, and the
 * neighbors that slide aside while the drag runs.
 *
 * The strip's gesture is a long-lived pointerInput coroutine per tab slot, so what these pin is which
 * workspace a slot's gesture believes it belongs to after the list underneath it has changed.
 */
@OptIn(ExperimentalTestApi::class)
class WorkspaceTabsInteractionTest {
	/**
	 * The names of every tab currently showing the inline rename editor.
	 *
	 * @param ComposeUiTest test The running UI test.
	 * @return List The editor's text for each open editor (empty when none is open).
	 */
	private fun openEditors(test: ComposeUiTest): List<String> =
		listOf("Alpha", "Bravo", "Charlie").filter { name ->
			test.onAllNodes(hasSetTextAction() and hasText(name)).fetchSemanticsNodes().isNotEmpty()
		}

	/**
	 * Where a tab's face is drawn: its left edge in root pixels, the drag and slide offsets included.
	 *
	 * @param ComposeUiTest test The running UI test.
	 * @param String name The tab's name.
	 * @return Float The drawn left edge.
	 */
	private fun drawnLeft(test: ComposeUiTest, name: String): Float = test.onNodeWithText(name).fetchSemanticsNode().boundsInRoot.left

	/**
	 * The horizontal drag that carries one tab's center just past another's.
	 *
	 * @param ComposeUiTest test The running UI test.
	 * @param String held The tab being dragged.
	 * @param String passed The tab whose center the drag ends just beyond.
	 * @return Float The drag in pixels.
	 */
	private fun dragPast(test: ComposeUiTest, held: String, passed: String): Float {
		val heldCenter = test.onNodeWithText(held).fetchSemanticsNode().boundsInRoot.center.x
		val passedCenter = test.onNodeWithText(passed).fetchSemanticsNode().boundsInRoot.center.x

		return passedCenter - heldCenter + if (passedCenter > heldCenter) 4f else -4f
	}

	/**
	 * Presses a tab, holds it past the reorder gate, and drags it, leaving the button down.
	 *
	 * @param ComposeUiTest test The running UI test.
	 * @param String name The tab to drag.
	 * @param Float deltaX The horizontal drag in pixels.
	 */
	private fun holdAndDrag(test: ComposeUiTest, name: String, deltaX: Float) {
		test.onNodeWithText(name).performMouseInput {
			moveTo(center)
			press()
			advanceEventTime(150L)
			moveBy(Offset(deltaX, 0f))
		}
		test.waitForIdle()
	}

	/**
	 * Releases the held button.
	 *
	 * @param ComposeUiTest test The running UI test.
	 */
	private fun releaseDrag(test: ComposeUiTest) {
		test.onRoot().performMouseInput { release() }
	}

	/** While a tab is dragged past a neighbor, that neighbor slides into the slot the held tab left. */
	@Test
	fun neighborsSlideAsideWhileTheDragRuns() =
		runComposeUiTest {
			val fixture = WorkspaceTabsFixture()
			fixture.mount(this)
			val alphaLeft = drawnLeft(this, "Alpha")
			val bravoLeft = drawnLeft(this, "Bravo")
			val charlieLeft = drawnLeft(this, "Charlie")
			val pastBravo = dragPast(this, "Alpha", "Bravo")
			holdAndDrag(this, "Alpha", pastBravo)
			assertEquals(alphaLeft + pastBravo, drawnLeft(this, "Alpha"), 1f, "the held tab follows the pointer")
			assertEquals(alphaLeft, drawnLeft(this, "Bravo"), 1f, "Bravo slides into the slot Alpha left")
			assertEquals(charlieLeft, drawnLeft(this, "Charlie"), 1f, "Charlie is not passed, so it stays")
			assertEquals(listOf("Alpha", "Bravo", "Charlie"), fixture.names, "nothing reorders before the release")
			releaseDrag(this)
			waitForIdle()
			assertEquals(listOf("Bravo", "Alpha", "Charlie"), fixture.names, "the release lands in the gap")
			assertEquals(alphaLeft, drawnLeft(this, "Bravo"), 1f)
			assertEquals(alphaLeft + (charlieLeft - bravoLeft), drawnLeft(this, "Alpha"), 1f, "Alpha rests in Bravo's old place")
			assertEquals(charlieLeft, drawnLeft(this, "Charlie"), 1f)
		}

	/** Dragging back over the held tab's own slot closes the gap again, and the release changes nothing. */
	@Test
	fun draggingBackClosesTheGap() =
		runComposeUiTest {
			val fixture = WorkspaceTabsFixture()
			fixture.mount(this)
			val alphaLeft = drawnLeft(this, "Alpha")
			val bravoLeft = drawnLeft(this, "Bravo")
			val pastBravo = dragPast(this, "Alpha", "Bravo")
			holdAndDrag(this, "Alpha", pastBravo)
			assertEquals(alphaLeft, drawnLeft(this, "Bravo"), 1f, "the drag passed Bravo")
			onRoot().performMouseInput { moveBy(Offset(-pastBravo, 0f)) }
			waitForIdle()
			assertEquals(bravoLeft, drawnLeft(this, "Bravo"), 1f, "Bravo slides back once the drag returns")
			releaseDrag(this)
			waitForIdle()
			assertEquals(listOf("Alpha", "Bravo", "Charlie"), fixture.names)
			assertEquals(alphaLeft, drawnLeft(this, "Alpha"), 1f)
		}

	/**
	 * The tabs' names in the order the strip composes them.
	 *
	 * @param ComposeUiTest test The running UI test.
	 * @return List The names, in semantics-tree order.
	 */
	private fun composedOrder(test: ComposeUiTest): List<String> =
		test
			.onAllNodes(hasText("Alpha") or hasText("Bravo") or hasText("Charlie"))
			.fetchSemanticsNodes()
			.map { node -> node.config[SemanticsProperties.Text].joinToString { text -> text.text } }

	/**
	 * The frame that composes the new order after a drop draws every tab at its new slot.  Before it, the old
	 * layout and its slide offsets already draw the tabs there, so the check waits for the new composition.  A
	 * slide animator kept across the reorder would then draw the tab now in a passed slot a tab-width off.
	 *
	 * The drag carries the last tab to the front.  The semantics tree lists the raised tab last, so with the
	 * held tab already last, the old composition never reads as the new order.
	 */
	@Test
	fun theFrameAfterTheDropDrawsEveryTabAtItsNewSlot() =
		runComposeUiTest {
			val fixture = WorkspaceTabsFixture()
			fixture.mount(this)
			val alphaBounds = onNodeWithText("Alpha").fetchSemanticsNode().boundsInRoot
			val bravoLeft = drawnLeft(this, "Bravo")
			// A face sits inset in its slot by the same margin on every tab, so a slot is its face plus that inset.
			val slotInset = (bravoLeft - alphaBounds.left) - alphaBounds.width
			val alphaSlotWidth = bravoLeft - alphaBounds.left
			val charlieSlotWidth = onNodeWithText("Charlie").fetchSemanticsNode().boundsInRoot.width + slotInset
			val newOrder = listOf("Charlie", "Alpha", "Bravo")
			// Past both neighbors, so two slots change occupant.
			holdAndDrag(this, "Charlie", dragPast(this, "Charlie", "Alpha"))
			assertEquals(alphaBounds.left + charlieSlotWidth, drawnLeft(this, "Alpha"), 1f, "the drag passed Alpha")
			mainClock.autoAdvance = false
			releaseDrag(this)
			var frames = 0

			while (composedOrder(this) != newOrder && frames < 5) {
				mainClock.advanceTimeByFrame()
				frames++
			}

			assertEquals(newOrder, composedOrder(this), "the strip composed the new order")
			assertEquals(newOrder, fixture.names)
			assertEquals(alphaBounds.left, drawnLeft(this, "Charlie"), 1f, "Charlie at slot 0")
			assertEquals(alphaBounds.left + charlieSlotWidth, drawnLeft(this, "Alpha"), 1f, "Alpha at slot 1")
			assertEquals(alphaBounds.left + charlieSlotWidth + alphaSlotWidth, drawnLeft(this, "Bravo"), 1f, "Bravo at slot 2")
			mainClock.autoAdvance = true
		}

	/**
	 * After a reorder by drag, a double-click on the slot the held tab left renames the workspace now in it.  The
	 * slide preview reads the drag state the gesture writes, so it must leave the gesture's identity keys alone.
	 */
	@Test
	fun doubleClickAfterADragReorderRenamesTheTabNowInThatSlot() =
		runComposeUiTest {
			val fixture = WorkspaceTabsFixture()
			fixture.mount(this)
			holdAndDrag(this, "Alpha", dragPast(this, "Alpha", "Bravo"))
			releaseDrag(this)
			waitForIdle()
			assertEquals(listOf("Bravo", "Alpha", "Charlie"), fixture.names, "the drag reordered the tabs")
			mainClock.advanceTimeBy(1_000L)
			onNodeWithText("Bravo").performMouseInput {
				advanceEventTime(1_000L)
				click()
				advanceEventTime(100L)
				click()
			}
			waitForIdle()
			assertEquals(listOf("Bravo"), openEditors(this), "the editor opens on the tab that was double-clicked")
			assertEquals("bravo", fixture.controller.renamingWorkspaceId)
		}

	/**
	 * The held tab stops at the strip's ends however far the pointer goes, and stays there until the pointer
	 * comes back to it.
	 */
	@Test
	fun theHeldTabStopsAtTheStripEnds() =
		runComposeUiTest {
			val fixture = WorkspaceTabsFixture()
			fixture.mount(this)
			val alphaLeft = drawnLeft(this, "Alpha")
			val charlieRight = onNodeWithText("Charlie").fetchSemanticsNode().boundsInRoot.right
			holdAndDrag(this, "Alpha", -200f)
			assertEquals(alphaLeft, drawnLeft(this, "Alpha"), 1f, "the strip's left end")
			onRoot().performMouseInput { moveBy(Offset(1_000f, 0f)) }
			waitForIdle()
			assertEquals(charlieRight, onNodeWithText("Alpha").fetchSemanticsNode().boundsInRoot.right, 1f, "the strip's right end")
			// Still far past the end, so the tab stays put rather than starting back from where it stopped.
			onRoot().performMouseInput { moveBy(Offset(-50f, 0f)) }
			waitForIdle()
			assertEquals(charlieRight, onNodeWithText("Alpha").fetchSemanticsNode().boundsInRoot.right, 1f, "the pointer is still past the end")
			releaseDrag(this)
			waitForIdle()
			assertEquals(listOf("Bravo", "Charlie", "Alpha"), fixture.names, "the release lands at the end")
		}

	/** Escape puts the held tab and its neighbors back, and the release that follows reorders nothing. */
	@Test
	fun escapeCancelsADragInFlight() =
		runComposeUiTest {
			val fixture = WorkspaceTabsFixture()
			fixture.mount(this)
			val alphaLeft = drawnLeft(this, "Alpha")
			val bravoLeft = drawnLeft(this, "Bravo")
			holdAndDrag(this, "Alpha", dragPast(this, "Alpha", "Bravo"))
			assertEquals(alphaLeft, drawnLeft(this, "Bravo"), 1f, "the drag passed Bravo")
			assertNotNull(fixture.rowDragCancel.cancel, "the drag parks its cancel on the shell's seam")
			fixture.pressKey(this, Key.Escape)
			assertEquals(alphaLeft, drawnLeft(this, "Alpha"), 1f, "the held tab is back in its slot")
			assertEquals(bravoLeft, drawnLeft(this, "Bravo"), 1f, "Bravo slides back")
			assertNull(fixture.rowDragCancel.cancel, "the seam is released with the drag")
			onRoot().performMouseInput { moveBy(Offset(60f, 0f)) }
			waitForIdle()
			assertEquals(alphaLeft, drawnLeft(this, "Alpha"), 1f, "nothing follows the pointer after the cancel")
			releaseDrag(this)
			waitForIdle()
			assertEquals(listOf("Alpha", "Bravo", "Charlie"), fixture.names, "the release reorders nothing")
		}

	/**
	 * A drag whose gesture is cancelled under it, here by a workspace added mid-drag, leaves every tab at rest
	 * and reorders nothing.
	 */
	@Test
	fun aDragCancelledMidwayLeavesEveryTabAtRest() =
		runComposeUiTest {
			val fixture = WorkspaceTabsFixture()
			fixture.mount(this)
			val alphaLeft = drawnLeft(this, "Alpha")
			val bravoLeft = drawnLeft(this, "Bravo")
			val charlieLeft = drawnLeft(this, "Charlie")
			val pastBravo = dragPast(this, "Alpha", "Bravo")
			holdAndDrag(this, "Alpha", pastBravo)
			assertEquals(alphaLeft, drawnLeft(this, "Bravo"), 1f, "the drag passed Bravo")
			runOnIdle { fixture.commands.invoke("workspace.new") }
			waitForIdle()
			assertEquals(alphaLeft, drawnLeft(this, "Alpha"), 1f, "the held tab returns to its slot")
			assertEquals(bravoLeft, drawnLeft(this, "Bravo"), 1f, "Bravo slides back")
			assertEquals(charlieLeft, drawnLeft(this, "Charlie"), 1f)
			releaseDrag(this)
			waitForIdle()
			assertEquals(listOf("Alpha", "Bravo", "Charlie"), fixture.names.take(3), "the cancelled drag reordered nothing")
			assertEquals(4, fixture.names.size)
		}

	/** Two quick presses on a tab open that tab's rename editor. */
	@Test
	fun doubleClickOpensTheClickedTabsEditor() =
		runComposeUiTest {
			val fixture = WorkspaceTabsFixture()
			fixture.mount(this)
			onNodeWithText("Bravo").performMouseInput {
				click()
				advanceEventTime(100L)
				click()
			}
			waitForIdle()
			assertEquals(listOf("Bravo"), openEditors(this))
			assertEquals("bravo", fixture.controller.renamingWorkspaceId, "the double-click ran Rename Workspace on that tab")
		}

	/**
	 * After the order changes underneath a slot, a double-click there renames the workspace the slot now
	 * shows.  The slot's gesture outlives the change (same index, same tab count), so a gesture that captured
	 * its workspace when it started would open the editor on the tab that used to sit there.
	 */
	@Test
	fun doubleClickAfterAReorderRenamesTheTabNowInThatSlot() =
		runComposeUiTest {
			val fixture = WorkspaceTabsFixture()
			fixture.mount(this)
			// A first click starts slot 0's gesture while it still holds Alpha.
			onNodeWithText("Alpha").performMouseInput { click() }
			waitForIdle()
			mainClock.advanceTimeBy(1_000L)
			runOnIdle { fixture.controller.reorder(1, 0) }
			waitForIdle()
			assertEquals(listOf("Bravo", "Alpha", "Charlie"), fixture.names, "the order changed under the slot")
			onNodeWithText("Bravo").performMouseInput {
				click()
				advanceEventTime(100L)
				click()
			}
			waitForIdle()
			assertEquals(listOf("Bravo"), openEditors(this), "the editor opens on the tab that was double-clicked")
		}

	/** A press that became a reorder drag never pairs with the click after it as a double-click. */
	@Test
	fun aClickRightAfterADragIsNotADoubleClick() =
		runComposeUiTest {
			val fixture = WorkspaceTabsFixture()
			fixture.mount(this)
			onNodeWithText("Alpha").performMouseInput {
				press()
				// Past the reorder hold gate, then a nudge too small to change the order.
				advanceEventTime(150L)
				moveBy(Offset(2f, 0f))
				release()
				advanceEventTime(50L)
				click()
			}
			waitForIdle()
			assertEquals(emptyList(), openEditors(this), "no rename editor opens")
			assertEquals(1, onAllNodesWithText("Alpha").fetchSemanticsNodes().size)
		}
}