package org.umamo.ui.workspace

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When a finished press ends text entry and hands the keyboard back to the shell root.
 *
 * The bug behind the rule: a header filter kept focus for as long as the document stayed open, so undo
 * and every other shortcut went quietly dead the moment someone typed in one.
 */
class TextEntryReleaseTest {
	/** With no editor live there is nothing to release, wherever the press landed. */
	@Test
	fun noEditorLiveReleasesNothing() {
		listOf(true, false).forEach { onEditor ->
			assertFalse(
				shouldReleaseTextEntry(
					textEntryActive = false,
					pressLandedOnTextEditor = onEditor,
					selfFocusedOverlayOpen = false,
				),
				"no text entry is live, so a press (onEditor=$onEditor) has nothing to end",
			)
		}
	}

	/** A press on the field itself is a caret move, not a way out of it. */
	@Test
	fun aPressOnTheEditorItselfKeepsTextEntry() {
		assertFalse(
			shouldReleaseTextEntry(
				textEntryActive = true,
				pressLandedOnTextEditor = true,
				selfFocusedOverlayOpen = false,
			),
		)
	}

	/** A press anywhere else ends text entry - Blender's rule, and the whole point of the session. */
	@Test
	fun aPressElsewhereReleases() {
		assertTrue(
			shouldReleaseTextEntry(
				textEntryActive = true,
				pressLandedOnTextEditor = false,
				selfFocusedOverlayOpen = false,
			),
		)
	}

	/**
	 * An overlay that owns its own focus keeps it.  The modal ladder has already stood the root's
	 * shortcuts down while one is open, so a release would hand the keyboard to a root that refuses to
	 * use it - and the command palette keeps its list navigation on an ancestor of its own search field.
	 */
	@Test
	fun aSelfFocusedOverlayNeverReleases() {
		assertFalse(
			shouldReleaseTextEntry(
				textEntryActive = true,
				pressLandedOnTextEditor = false,
				selfFocusedOverlayOpen = true,
			),
		)
	}
}