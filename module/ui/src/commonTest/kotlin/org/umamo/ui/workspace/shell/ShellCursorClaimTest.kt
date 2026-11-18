package org.umamo.ui.workspace.shell

import androidx.compose.ui.input.pointer.PointerIcon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The shell root's one window-wide cursor claim: which running mode the pointer shows, and which claims
 * have to beat the cursors their descendants ask for.
 *
 * The resolver is the whole of the decision on purpose - the root then carries ONE permanent hover icon
 * rather than mounting a modifier when a mode starts, because a hover icon that appears mid-gesture is
 * not consulted until the pointer next moves, and text entry starts with a click the hand rests on.
 */
class ShellCursorClaimTest {
	/** With nothing running the shell claims nothing, so every panel, splitter, and gizmo still decides. */
	@Test
	fun withNoModeRunningTheShellClaimsNothing() {
		val claim = shellCursorClaim(relationPickArmed = false, textEntryActive = false, busy = false)
		assertEquals(ShellCursorClaim.None, claim)
		assertFalse(claim.overridesDescendants, "an empty claim must leave the descendants' cursors alone")
	}

	/** An armed relation pick hides the OS pointer, because the shell draws the eyedropper itself. */
	@Test
	fun anArmedRelationPickHidesThePointer() {
		assertEquals(ShellCursorClaim.Hidden, shellCursorClaim(relationPickArmed = true, textEntryActive = false, busy = false))
	}

	/** Live text entry shows the editor's I-beam, which is what makes the mode visible window-wide. */
	@Test
	fun liveTextEntryShowsTheTextCursor() {
		assertEquals(ShellCursorClaim.TextEdit, shellCursorClaim(relationPickArmed = false, textEntryActive = true, busy = false))
	}

	/** A tracked operation past its grace shows the wait pointer. */
	@Test
	fun aBusyWindowShowsTheWaitPointer() {
		assertEquals(ShellCursorClaim.Busy, shellCursorClaim(relationPickArmed = false, textEntryActive = false, busy = true))
	}

	/**
	 * A pick outranks text entry: the shell draws the eyedropper at the pointer, so an I-beam under it
	 * would read as two cursors at once.
	 */
	@Test
	fun aPickOutranksTextEntry() {
		assertEquals(ShellCursorClaim.Hidden, shellCursorClaim(relationPickArmed = true, textEntryActive = true, busy = false))
	}

	/** Both modes outrank busy: they are what the rigger is in the middle of, and busy is background work. */
	@Test
	fun eachModeOutranksBusy() {
		assertEquals(ShellCursorClaim.Hidden, shellCursorClaim(relationPickArmed = true, textEntryActive = false, busy = true))
		assertEquals(ShellCursorClaim.TextEdit, shellCursorClaim(relationPickArmed = false, textEntryActive = true, busy = true))
	}

	/** Every mode claim overrides its descendants - a mode a splitter edge could break is not a mode. */
	@Test
	fun everyModeClaimOverridesItsDescendants() {
		listOf(ShellCursorClaim.Hidden, ShellCursorClaim.TextEdit).forEach { claim ->
			assertTrue(claim.overridesDescendants, "$claim must beat the cursors its descendants ask for")
		}
	}

	/** Busy yields: the work runs in the background, so a control that still works keeps its own cursor. */
	@Test
	fun theBusyClaimYieldsToItsDescendants() {
		assertFalse(ShellCursorClaim.Busy.overridesDescendants)
	}

	/**
	 * A yielding claim hands its own icon down as the resting pointer, or a region's hand-written plain
	 * pointer would beat it; every other claim hands down the plain pointer, which an override shows over.
	 */
	@Test
	fun onlyTheBusyClaimChangesTheRestingPointer() {
		assertEquals(ShellCursorClaim.Busy.pointerIcon(), ShellCursorClaim.Busy.restingPointerIcon())
		listOf(ShellCursorClaim.None, ShellCursorClaim.Hidden, ShellCursorClaim.TextEdit).forEach { claim ->
			assertEquals(PointerIcon.Default, claim.restingPointerIcon(), "$claim must leave the plain pointer at rest")
		}
	}
}