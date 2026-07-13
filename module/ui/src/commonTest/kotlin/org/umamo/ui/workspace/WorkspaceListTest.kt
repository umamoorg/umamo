package org.umamo.ui.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Verifies the workspace-list operations behind the tab strip's create / duplicate / delete / reorder /
 * navigate features: the pure [InterfaceLayout] transforms (shift, insert, remove, reorder), the
 * fresh-id clone and unique-name helpers, and the drag-reorder index math.  These are the off-by-one and
 * edge-case prone bits (wraparound, refusing the last workspace, active reassignment), kept pure so they
 * are tested without a composition.
 */
class WorkspaceListTest {
	/** A workspace with a single viewport leaf, for terse fixtures. */
	private fun workspace(id: String, name: String? = null): Workspace =
		Workspace(id = id, root = LeafArea("leaf-$id", SpaceKind.Viewport2D), name = name)

	/** A three-workspace layout (a, b, c) with [activeId] active. */
	private fun threeWorkspaces(activeId: String): InterfaceLayout =
		InterfaceLayout(
			activeWorkspaceId = activeId,
			workspaces = listOf(workspace("a"), workspace("b"), workspace("c")),
		)

	@Test
	fun shiftActiveWrapsBothWays() {
		val layout = threeWorkspaces("a")
		assertEquals("b", layout.withActiveShiftedBy(1).activeWorkspaceId)
		// Previous from the first tab wraps to the last.
		assertEquals("c", layout.withActiveShiftedBy(-1).activeWorkspaceId)
		// Next from the last tab wraps to the first.
		assertEquals("a", threeWorkspaces("c").withActiveShiftedBy(1).activeWorkspaceId)
	}

	@Test
	fun shiftActiveOnEmptyIsNoOp() {
		val empty = InterfaceLayout(activeWorkspaceId = "x", workspaces = emptyList())
		assertSame(empty, empty.withActiveShiftedBy(1))
	}

	@Test
	fun insertPlacesAndActivates() {
		val layout = threeWorkspaces("a")
		val inserted = layout.withWorkspaceInserted(workspace("z"), atIndex = 1, activate = true)
		assertEquals(listOf("a", "z", "b", "c"), inserted.workspaces.map { workspace -> workspace.id })
		assertEquals("z", inserted.activeWorkspaceId)
	}

	@Test
	fun insertWithoutActivateKeepsActive() {
		val layout = threeWorkspaces("a")
		val inserted = layout.withWorkspaceInserted(workspace("z"), atIndex = 99, activate = false)
		// Index is clamped to the end, and the active selection is untouched.
		assertEquals(listOf("a", "b", "c", "z"), inserted.workspaces.map { workspace -> workspace.id })
		assertEquals("a", inserted.activeWorkspaceId)
	}

	@Test
	fun removeActiveReassignsToSlot() {
		// Removing the active middle tab moves active to the tab that slides into its slot.
		val removed = threeWorkspaces("b").withWorkspaceRemoved("b")
		assertEquals(listOf("a", "c"), removed.workspaces.map { workspace -> workspace.id })
		assertEquals("c", removed.activeWorkspaceId)
	}

	@Test
	fun removeActiveLastReassignsToNewLast() {
		val removed = threeWorkspaces("c").withWorkspaceRemoved("c")
		assertEquals("b", removed.activeWorkspaceId)
	}

	@Test
	fun removeNonActiveKeepsActive() {
		val removed = threeWorkspaces("a").withWorkspaceRemoved("c")
		assertEquals("a", removed.activeWorkspaceId)
		assertEquals(listOf("a", "b"), removed.workspaces.map { workspace -> workspace.id })
	}

	@Test
	fun removeLastWorkspaceIsRefused() {
		val single = InterfaceLayout(activeWorkspaceId = "a", workspaces = listOf(workspace("a")))
		assertSame(single, single.withWorkspaceRemoved("a"))
	}

	@Test
	fun reorderMovesItemAndKeepsActive() {
		val layout = threeWorkspaces("a")
		assertEquals(listOf("b", "a", "c"), layout.withWorkspacesReordered(0, 1).workspaces.map { workspace -> workspace.id })
		assertEquals(listOf("b", "c", "a"), layout.withWorkspacesReordered(0, 2).workspaces.map { workspace -> workspace.id })
		assertEquals(listOf("c", "a", "b"), layout.withWorkspacesReordered(2, 0).workspaces.map { workspace -> workspace.id })
		// Reordering never changes which workspace is active.
		assertEquals("a", layout.withWorkspacesReordered(0, 2).activeWorkspaceId)
	}

	@Test
	fun reorderOutOfRangeIsNoOp() {
		val layout = threeWorkspaces("a")
		assertSame(layout, layout.withWorkspacesReordered(5, 0))
	}

	@Test
	fun renameSetsOnlyTargetName() {
		val renamed = threeWorkspaces("a").withWorkspaceRenamed("b", "Faces")
		assertEquals("Faces", renamed.workspaces.first { workspace -> workspace.id == "b" }.name)
		assertNull(renamed.workspaces.first { workspace -> workspace.id == "a" }.name, "other names are untouched")
	}

	@Test
	fun renameUnknownIdIsNoOp() {
		val layout = threeWorkspaces("a")
		assertEquals(layout, layout.withWorkspaceRenamed("zzz", "Nope"))
	}

	@Test
	fun cloneAreaTreeMintsFreshLeafIdsAndPreservesShape() {
		val original =
			SplitNode(
				orientation = SplitOrientation.Horizontal,
				ratio = 0.4f,
				first = LeafArea("a", SpaceKind.UvEditor),
				second = LeafArea("b", SpaceKind.Viewport2D),
			)
		val clone = cloneAreaTree(original) as SplitNode

		assertEquals(original.orientation, clone.orientation)
		assertEquals(original.ratio, clone.ratio)
		val originalFirst = original.first as LeafArea
		val cloneFirst = clone.first as LeafArea
		assertEquals(originalFirst.space, cloneFirst.space, "the space kind is preserved")
		assertNotEquals(originalFirst.id, cloneFirst.id, "the leaf id is freshly minted, never shared")
		assertNotEquals((original.second as LeafArea).id, (clone.second as LeafArea).id)
	}

	@Test
	fun uniqueWorkspaceNameAppendsLowestFreeSuffix() {
		val existing = listOf(workspace("a", name = "Workspace"), workspace("b", name = "Workspace 2"))
		assertEquals("Workspace 3", uniqueWorkspaceName("Workspace", existing))
		assertEquals("Texture", uniqueWorkspaceName("Texture", existing), "a free name is returned unchanged")
	}

	@Test
	fun reorderTargetIndexCountsNeighborsLeftOfProjectedCenter() {
		// Three tabs centered at 50, 150, 250 (width 100). Drag the first far enough right to pass tab b.
		val centers = listOf(50f, 150f, 250f)
		assertEquals(0, reorderTargetIndex(fromIndex = 0, dragDeltaX = 0f, centers = centers))
		assertEquals(1, reorderTargetIndex(fromIndex = 0, dragDeltaX = 120f, centers = centers))
		assertEquals(2, reorderTargetIndex(fromIndex = 0, dragDeltaX = 220f, centers = centers))
		// Drag the last tab left past both neighbors.
		assertEquals(0, reorderTargetIndex(fromIndex = 2, dragDeltaX = -220f, centers = centers))
	}

	@Test
	fun reorderTargetIndexWithUnmeasuredCenterIsNoOp() {
		val centers = listOf<Float?>(null, null)
		assertEquals(0, reorderTargetIndex(fromIndex = 0, dragDeltaX = 500f, centers = centers))
	}
}
