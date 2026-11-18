package org.umamo.ui.action

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The palette ranking: label prefix > label word start > label substring > id-only substring, ties
 * alphabetical, non-matches dropped.  The "ac" case pins the property that matters most for usability:
 * "Actual Size" must outrank the workspace commands that merely contain "ac".
 */
class CommandSearchTest {
	private data class Entry(val id: String, val label: String)

	private fun rank(entries: List<Entry>, query: String): List<String> =
		rankCommandMatches(entries, query, labelOf = { entry -> entry.label }, idOf = { entry -> entry.id })
			.map { entry -> entry.label }

	@Test
	fun labelPrefixOutranksSubstringMatches() {
		val entries =
			listOf(
				Entry("workspace.prev", "Previous Workspace"),
				Entry("workspace.next", "Next Workspace"),
				Entry("view.zoomActualSize", "Actual Size"),
			)
		assertEquals(
			listOf("Actual Size", "Next Workspace", "Previous Workspace"),
			rank(entries, "ac"),
			"the prefix match leads; the substring matches follow alphabetically",
		)
	}

	@Test
	fun wordStartOutranksMidWordSubstring() {
		val entries =
			listOf(
				Entry("a", "Reincarnate"), // "in" mid-word
				Entry("b", "Zoom In"), // "in" starts the second word
				Entry("c", "Invert"), // "in" starts the label
			)
		assertEquals(listOf("Invert", "Zoom In", "Reincarnate"), rank(entries, "in"))
	}

	@Test
	fun idOnlyMatchesRankLastAndPowerUserIdSearchWorks() {
		val entries =
			listOf(
				Entry("view.zoomIn", "Zoom In"),
				Entry("view.fit", "Fit View"),
			)
		assertEquals(listOf("Fit View", "Zoom In"), rank(entries, "view."), "both match only by id; alphabetical")
		assertEquals(listOf("Zoom In"), rank(entries, "zoomin"), "camelCase id substring hits")
	}

	@Test
	fun matchingIsCaseInsensitive() {
		val entries = listOf(Entry("view.zoomActualSize", "Actual Size"))
		assertEquals(listOf("Actual Size"), rank(entries, "ACTUAL"))
		assertEquals(listOf("Actual Size"), rank(entries, "actual size"))
	}

	@Test
	fun nonMatchesAreDropped() {
		val entries = listOf(Entry("view.fit", "Fit View"), Entry("edit.undo", "Undo"))
		assertEquals(listOf("Undo"), rank(entries, "und"))
		assertEquals(emptyList(), rank(entries, "xyzzy"))
	}

	@Test
	fun scoreTiersAreStable() {
		assertEquals(0, commandMatchScore("Actual Size", "view.zoomActualSize", "ac"))
		assertEquals(1, commandMatchScore("Zoom In", "view.zoomIn", "in"))
		assertEquals(2, commandMatchScore("Previous Workspace", "workspace.prev", "ac"))
		assertEquals(3, commandMatchScore("Fit View", "view.fit", "view."))
		assertNull(commandMatchScore("Fit View", "view.fit", "xyzzy"))
	}

	/**
	 * The configurator's ranking adds two tiers to the palette's: the group a command is listed under, and
	 * the accelerator it is bound to - so a search for a group name or for a chord finds the command.
	 */
	@Test
	fun theConfiguratorTiersReachTheGroupAndTheChord() {
		// A query that reaches only the group, and one that reaches only the accelerator.
		assertEquals(4, shortcutMatchScore("Fit View", "view.fit", "Viewport", "", "viewport"))
		assertEquals(5, shortcutMatchScore("Fit View", "view.fit", "View", "Ctrl+Shift+=", "ctrl"))
		assertNull(shortcutMatchScore("Fit View", "view.fit", "View", "Ctrl+Shift+=", "xyzzy"))
	}

	/**
	 * The configurator ranks the same way the palette does, on the tiers it shares with it.
	 */
	@Test
	fun theConfiguratorRanksLikeThePalette() {
		val entries =
			listOf(
				ConfigRow("view.zoomActualSize", "Actual Size", "View", ""),
				ConfigRow("workspace.prev", "Previous Workspace", "Workspace", ""),
			)

		// "workspace" is in the group of the second row only, so it reaches the group tier.
		assertEquals(listOf("Previous Workspace"), rankShortcut(entries, "workspace"))
		assertEquals(emptyList(), rankShortcut(entries, "xyzzy"))
	}

	/**
	 * The configurator's ranking drops the non-matches and keeps the order.
	 */
	private fun rankShortcut(entries: List<ConfigRow>, query: String): List<String> =
		rankShortcutMatches(
			entries,
			query,
			labelOf = { entry -> entry.label },
			idOf = { entry -> entry.id },
			groupOf = { entry -> entry.group },
			shortcutOf = { entry -> entry.shortcut },
		).map { entry -> entry.label }

	/**
	 * A row the configurator ranks: a command, its label, its group, and the accelerator it is bound to.
	 *
	 * @property String id The command's dotted id.
	 * @property String label The command's name.
	 * @property String group The group the command is listed under.
	 * @property String shortcut The accelerator the command is bound to.
	 */
	private data class ConfigRow(
		val id: String,
		val label: String,
		val group: String,
		val shortcut: String,
	)
}