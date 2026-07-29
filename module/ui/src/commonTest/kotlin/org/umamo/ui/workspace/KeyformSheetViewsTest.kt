package org.umamo.ui.workspace

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Pins the shell-level sheet registry's dispatch-time resolution: the hovered sheet wins, a lone sheet
 * resolves without hover, two un-hovered sheets are ambiguous for view ops but a unique selection
 * disambiguates the selection ops, and unregistration is by identity so a replacement registration
 * cannot be torn down by the older composition's dispose.
 */
class KeyformSheetViewsTest {
	private fun surface(hasSelection: Boolean = false, boxSelectArmed: Boolean = false): KeyformSheetSurface =
		KeyformSheetSurface(
			selectedTracks = { emptyList() },
			hasSelection = { hasSelection },
			clearSelection = {},
			frameAll = {},
			armBoxSelect = {},
			boxSelectArmed = { boxSelectArmed },
			disarmBoxSelect = {},
			nudgeSelection = {},
		)

	/** Escape has to find the armed sheet wherever it is, since arming is per area but the key is global. */
	@Test
	fun theArmedSheetIsFoundWhicheverAreaHoldsIt() {
		val views = KeyformSheetViews()
		views.register("a", surface())
		val armed = surface(boxSelectArmed = true)
		views.register("b", armed)
		assertSame(armed, views.armedBoxSelect())

		val quiet = KeyformSheetViews()
		quiet.register("a", surface())
		assertNull(quiet.armedBoxSelect(), "with nothing armed, Escape falls through to the branches below it")
	}

	/** The hovered sheet area always wins, regardless of how many are open. */
	@Test
	fun hoveredAreaWins() {
		val views = KeyformSheetViews()
		val sheetA = surface()
		val sheetB = surface()
		views.register("a", sheetA)
		views.register("b", sheetB)
		assertSame(sheetB, views.resolve("b"))
		assertSame(sheetA, views.resolve("a"))
	}

	/** A lone open sheet resolves with no hover at all - the single-sheet case must keep working. */
	@Test
	fun aLoneSheetResolvesWithoutHover() {
		val views = KeyformSheetViews()
		val sheet = surface()
		views.register("a", sheet)
		assertSame(sheet, views.resolve(null))
		assertSame(sheet, views.resolveForSelection(null))
	}

	/** Two un-hovered sheets are genuinely ambiguous for a view op, so nothing resolves. */
	@Test
	fun twoUnhoveredSheetsAreAmbiguousForViewOps() {
		val views = KeyformSheetViews()
		views.register("a", surface())
		views.register("b", surface())
		assertNull(views.resolve(null))
	}

	/** A unique selection is itself a statement about which sheet is meant, so selection ops resolve it. */
	@Test
	fun aUniqueSelectionDisambiguatesSelectionOps() {
		val views = KeyformSheetViews()
		val selected = surface(hasSelection = true)
		views.register("a", surface(hasSelection = false))
		views.register("b", selected)
		assertSame(selected, views.resolveForSelection(null))
	}

	/** Unregistration is by identity: a stale dispose must not tear down a newer registration. */
	@Test
	fun unregisterIsByIdentity() {
		val views = KeyformSheetViews()
		val older = surface()
		val newer = surface()
		views.register("a", older)
		views.register("a", newer)
		views.unregister("a", older)
		assertSame(newer, views.resolve("a"), "the stale dispose left the replacement registered")
		views.unregister("a", newer)
		assertNull(views.resolve("a"))
	}
}
