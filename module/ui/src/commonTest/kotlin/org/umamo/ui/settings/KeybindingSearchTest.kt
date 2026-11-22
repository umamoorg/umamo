package org.umamo.ui.settings

import org.umamo.ui.action.KeyChord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which rows of a keybindings category card a search leaves standing, for typed text and for a recorded chord. */
class KeybindingSearchTest {
	private val rows =
		listOf(
			KeybindingSearchRow("snap.pie", "Snap Pie", "Shift+S"),
			KeybindingSearchRow("snap.cursorToGrid", "Cursor to Grid", "Unbound"),
			KeybindingSearchRow("snap.selectionToCursor", "Selection to Cursor", "Ctrl+Alt+C"),
		)

	/**
	 * Resolves the card for a typed query.
	 *
	 * @param String query The search text.
	 * @return List The visible row indices, or null when the card hides.
	 */
	private fun textMatch(query: String): List<Int>? = keybindingCardVisibility("Snap", rows, KeybindingSearch.Text(query), firingCommandId = null).takeIf { visibility -> visibility.shown }?.visibleRowIndices

	@Test
	fun aBlankQueryShowsEveryRow() {
		assertEquals(listOf(0, 1, 2), textMatch(""))
		assertEquals(listOf(0, 1, 2), textMatch("   "))
		assertFalse(KeybindingSearch.Text(" ").isActive())
	}

	@Test
	fun aQueryMatchesANameOrAShownShortcut() {
		assertEquals(listOf(2), textMatch("selection"))
		assertEquals(listOf(2), textMatch("alt+c"), "the chip's text, ignoring case")
		assertEquals(listOf(1), textMatch("unbound"), "an unbound chip reads Unbound")
		assertEquals(listOf(1, 2), textMatch("cursor"), "every row it matches")
	}

	@Test
	fun aCategoryTitleMatchShowsTheWholeCard() {
		assertEquals(listOf(0, 1, 2), textMatch("sna"))
	}

	@Test
	fun aQueryMatchingNothingHidesTheCard() {
		assertEquals(null, textMatch("zzz"))
	}

	@Test
	fun aCardWithNoRowsNeverShows() {
		assertFalse(keybindingCardVisibility("Snap", emptyList(), KeybindingSearch.Text("snap"), firingCommandId = null).shown)
		assertFalse(keybindingCardVisibility("Snap", emptyList(), KeybindingSearch.Text(""), firingCommandId = null).shown)
	}

	/** A recorded chord keeps only the command it runs, and a title match plays no part. */
	@Test
	fun aRecordedChordKeepsOnlyTheCommandItRuns() {
		val chord = KeybindingSearch.Chord(KeyChord("KeyS", shift = true))

		assertTrue(chord.isActive())
		keybindingCardVisibility("Snap", rows, chord, firingCommandId = "snap.pie").let { visibility ->
			assertTrue(visibility.shown)
			assertEquals(listOf(0), visibility.visibleRowIndices)
		}
		assertFalse(keybindingCardVisibility("Snap", rows, chord, firingCommandId = "file.save").shown, "it runs a command on another card")
		assertFalse(keybindingCardVisibility("Snap", rows, chord, firingCommandId = null).shown, "it runs nothing")
	}
}