package org.umamo.ui.workspace

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
		val claim = shellCursorClaim(relationPickArmed = false, textEntryActive = false)
		assertEquals(ShellCursorClaim.None, claim)
		assertFalse(claim.overridesDescendants, "an empty claim must leave the descendants' cursors alone")
	}

	/** An armed relation pick hides the OS pointer, because the shell draws the eyedropper itself. */
	@Test
	fun anArmedRelationPickHidesThePointer() {
		assertEquals(ShellCursorClaim.Hidden, shellCursorClaim(relationPickArmed = true, textEntryActive = false))
	}

	/** Live text entry shows the editor's I-beam, which is what makes the mode visible window-wide. */
	@Test
	fun liveTextEntryShowsTheTextCursor() {
		assertEquals(ShellCursorClaim.TextEdit, shellCursorClaim(relationPickArmed = false, textEntryActive = true))
	}

	/**
	 * A pick outranks text entry: the shell draws the eyedropper at the pointer, so an I-beam under it
	 * would read as two cursors at once.
	 */
	@Test
	fun aPickOutranksTextEntry() {
		assertEquals(ShellCursorClaim.Hidden, shellCursorClaim(relationPickArmed = true, textEntryActive = true))
	}

	/** Every real claim overrides its descendants - a mode a splitter edge could break is not a mode. */
	@Test
	fun everyRealClaimOverridesItsDescendants() {
		val claimed = ShellCursorClaim.entries.filter { claim -> claim != ShellCursorClaim.None }
		claimed.forEach { claim ->
			assertTrue(claim.overridesDescendants, "$claim must beat the cursors its descendants ask for")
		}
	}
}