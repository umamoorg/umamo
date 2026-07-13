package org.umamo.ui.action

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The palette ranking: label prefix > label word start > label substring > id-only substring, ties
 * alphabetical, non-matches dropped. The "ac" case pins the property that matters most for usability:
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
}
