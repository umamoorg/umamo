package org.umamo.ui.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Verifies the layout save pacing: debounced writes persist and deduplicate by instance, a held
 * splitter drag blocks every debounced write, the drag's release commits exactly once, and a
 * zero-movement drag writes nothing.  Instances are distinguished by identity (data-class copies are
 * value-equal but distinct), matching the controller's fresh-instance-per-edit publishing.
 */
class LayoutSavePacerTest {
	/** A minimal single-workspace layout; copies of it model successive published edits. */
	private fun layout(): InterfaceLayout =
		InterfaceLayout(
			activeWorkspaceId = "workspace",
			workspaces = listOf(Workspace(id = "workspace", root = LeafArea("area", SpaceKind.Viewport2D))),
		)

	@Test
	fun debouncedSavePersistsOnceAndDeduplicates() {
		val saved = ArrayList<InterfaceLayout>()
		val initial = layout()
		val pacer = LayoutSavePacer(initial) { persisted -> saved.add(persisted) }
		val edited = initial.copy()
		pacer.saveDebounced(edited)
		pacer.saveDebounced(edited)
		assertEquals(1, saved.size)
		assertSame(edited, saved.single())
	}

	@Test
	fun heldDragBlocksDebouncedWritesAndCommitsOnRelease() {
		val saved = ArrayList<InterfaceLayout>()
		val initial = layout()
		val pacer = LayoutSavePacer(initial) { persisted -> saved.add(persisted) }
		pacer.setDragActive(true, initial)
		val midDrag = initial.copy()
		// A mid-drag pause longer than the debounce would deliver this; the held drag suppresses it.
		pacer.saveDebounced(midDrag)
		assertEquals(0, saved.size)
		val released = midDrag.copy()
		pacer.setDragActive(false, released)
		assertEquals(1, saved.size)
		assertSame(released, saved.single())
		// The still-pending debounce of the committed instance is a duplicate - suppressed.
		pacer.saveDebounced(released)
		assertEquals(1, saved.size)
	}

	@Test
	fun zeroMovementDragWritesNothing() {
		val saved = ArrayList<InterfaceLayout>()
		val initial = layout()
		val pacer = LayoutSavePacer(initial) { persisted -> saved.add(persisted) }
		pacer.setDragActive(true, initial)
		pacer.setDragActive(false, initial)
		assertEquals(0, saved.size)
	}

	@Test
	fun overlappingDragsCommitOnlyWhenTheLastReleases() {
		val saved = ArrayList<InterfaceLayout>()
		val initial = layout()
		val pacer = LayoutSavePacer(initial) { persisted -> saved.add(persisted) }
		// Two fingers on two splitters (the touch-tablet shape): the first release must NOT resume
		// writes while the second drag is still held.
		pacer.setDragActive(true, initial)
		pacer.setDragActive(true, initial)
		val firstReleased = initial.copy()
		pacer.setDragActive(false, firstReleased)
		assertEquals(0, saved.size)
		pacer.saveDebounced(firstReleased)
		assertEquals(0, saved.size)
		val secondReleased = firstReleased.copy()
		pacer.setDragActive(false, secondReleased)
		assertEquals(1, saved.size)
		assertSame(secondReleased, saved.single())
	}

	@Test
	fun savesResumeAfterCommit() {
		val saved = ArrayList<InterfaceLayout>()
		val initial = layout()
		val pacer = LayoutSavePacer(initial) { persisted -> saved.add(persisted) }
		val committed = initial.copy()
		pacer.setDragActive(true, initial)
		pacer.setDragActive(false, committed)
		val laterEdit = committed.copy()
		pacer.saveDebounced(laterEdit)
		assertEquals(2, saved.size)
		assertSame(laterEdit, saved.last())
	}
}