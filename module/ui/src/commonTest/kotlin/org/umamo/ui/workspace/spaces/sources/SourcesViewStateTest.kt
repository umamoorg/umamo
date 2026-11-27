package org.umamo.ui.workspace.spaces.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins what a fold press does to the table's open rows.  During a search every row shows open whatever
 * its fold says, so a press then has nothing to fold, and must not write a fold the rigger cannot see
 * change.
 */
class SourcesViewStateTest {
	private val file = "source:art-0"
	private val layer = "layer:art-0/lyid:1"

	/** A press opens a closed row and closes an open one. */
	@Test
	fun aFoldPressFlipsTheRow() {
		val viewState = SourcesViewState()
		assertFalse(viewState.isOpen(layer), "a layer starts closed")

		viewState.toggleFold(layer)
		assertTrue(viewState.isOpen(layer))

		viewState.toggleFold(layer)
		assertFalse(viewState.isOpen(layer))
	}

	/** A file and the unbound group start open, so their first press closes them. */
	@Test
	fun aFilesFirstPressClosesIt() {
		val viewState = SourcesViewState()

		viewState.toggleFold(file)
		viewState.toggleFold(SOURCES_UNBOUND_GROUP_ID)

		assertFalse(viewState.isOpen(file))
		assertFalse(viewState.isOpen(SOURCES_UNBOUND_GROUP_ID))
	}

	/** During a search a press writes no fold, so the row is as it was once the search ends. */
	@Test
	fun aFoldPressDuringASearchWritesNothing() {
		val viewState = SourcesViewState()
		viewState.query = "hair"

		viewState.toggleFold(layer)
		viewState.toggleFold(file)

		assertTrue(viewState.expanded.isEmpty())
		viewState.query = ""
		assertFalse(viewState.isOpen(layer))
		assertTrue(viewState.isOpen(file))
	}

	/** Opening a closed row records its fold. */
	@Test
	fun openingAClosedRowRecordsIt() {
		val viewState = SourcesViewState()

		viewState.open(layer)

		assertTrue(viewState.isOpen(layer))
		assertEquals(mapOf(layer to true), viewState.expanded.toMap())
	}

	/**
	 * Opening a row that is open already writes nothing, whether it is open by default or by a fold: a key
	 * added for a row that looks the same would have everything keyed on the folds built again.
	 */
	@Test
	fun openingAnOpenRowWritesNothing() {
		val viewState = SourcesViewState()

		viewState.open(file)
		assertTrue(viewState.expanded.isEmpty(), "a file is open by default")

		viewState.open(layer)
		viewState.open(layer)
		assertEquals(mapOf(layer to true), viewState.expanded.toMap())
	}

	/** Opening reopens a row a press closed, a file included. */
	@Test
	fun openingReopensAClosedFile() {
		val viewState = SourcesViewState()
		viewState.toggleFold(file)

		viewState.open(file)

		assertTrue(viewState.isOpen(file))
	}

	/** A search does not hold an opening back: the row a drop lands in has to be open once the search is gone. */
	@Test
	fun openingWritesDuringASearchToo() {
		val viewState = SourcesViewState()
		viewState.query = "hair"

		viewState.open(layer)

		viewState.query = ""
		assertTrue(viewState.isOpen(layer))
	}

	/** Blanks are no search, so a press folds as usual. */
	@Test
	fun aBlankQueryIsNoSearch() {
		val viewState = SourcesViewState()
		viewState.query = "   "

		assertFalse(viewState.searching)
		viewState.toggleFold(layer)
		assertTrue(viewState.isOpen(layer))
	}
}