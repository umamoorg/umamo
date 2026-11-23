package org.umamo.ui.kit.container

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Covers the search rule a searchable stack of section cards shares: the match predicate and per-row visibility. */
class SectionSearchTest {
	/** The match predicate is case-insensitive, and a blank query matches everything. */
	@Test
	fun matchesQueryIsCaseInsensitiveAndBlankMatchesAll() {
		val haystack = listOf("Canvas", "Size: %1\$s × %2\$s")
		assertTrue(matchesQuery(haystack, ""))
		assertTrue(matchesQuery(haystack, "   "))
		assertTrue(matchesQuery(haystack, "can"))
		assertTrue(matchesQuery(haystack, "SIZE"))
		assertFalse(matchesQuery(haystack, "runtime"))
		assertFalse(matchesQuery(emptyList(), "x"))
	}

	/**
	 * Per-row visibility: a blank query or a section-title match shows every row; a field query keeps only
	 * the matching rows; and a stacked row (several terms) survives when any of its terms match.
	 */
	@Test
	fun sectionVisibilityHidesNonMatchingRows() {
		val rows = listOf(listOf("Opacity"), listOf("Blend Mode"), listOf("Invert Mask"))

		// Blank query: the whole section, every row.
		sectionVisibility("Compositing", rows, "").let { result ->
			assertTrue(result.shown)
			assertEquals(listOf(0, 1, 2), result.visibleRowIndices)
		}
		// A field-name query keeps only the matching row.
		sectionVisibility("Compositing", rows, "blend").let { result ->
			assertTrue(result.shown)
			assertEquals(listOf(1), result.visibleRowIndices)
		}
		// A section-title match shows every row (the whole section is relevant), even when no row matches.
		sectionVisibility("Compositing", rows, "compos").let { result ->
			assertTrue(result.shown)
			assertEquals(listOf(0, 1, 2), result.visibleRowIndices)
		}
		// No match anywhere hides the section outright.
		sectionVisibility("Compositing", rows, "zzz").let { result ->
			assertFalse(result.shown)
			assertTrue(result.visibleRowIndices.isEmpty())
		}
		// A stacked row (canvas width + height) matches on any of its terms.
		sectionVisibility("Canvas", listOf(listOf("Width", "Height")), "height").let { result ->
			assertTrue(result.shown)
			assertEquals(listOf(0), result.visibleRowIndices)
		}
	}

	/**
	 * A section that built no rows for the current context never shows - not on a blank query, and not on a
	 * title match either.  This is how Transform opts out for an item with no transform (a Part, a warp
	 * deformer) instead of drawing an empty card, so a title match must not resurrect it.
	 */
	@Test
	fun sectionVisibilityHidesASectionThatBuiltNoRows() {
		val noRows = emptyList<List<String>>()

		sectionVisibility("Transform", noRows, "").let { result ->
			assertFalse(result.shown, "an empty section is not shown on a blank query")
			assertTrue(result.visibleRowIndices.isEmpty())
		}
		sectionVisibility("Transform", noRows, "transf").let { result ->
			assertFalse(result.shown, "a title match must not resurrect an empty section")
			assertTrue(result.visibleRowIndices.isEmpty())
		}
		sectionVisibility("Transform", noRows, "zzz").let { result ->
			assertFalse(result.shown)
			assertTrue(result.visibleRowIndices.isEmpty())
		}
	}
}