package org.umamo.ui.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the area-tree data model: recursive sealed-tree serialization round-trips (the trickiest
 * serialization risk), and the reducer's structural edits (split keeps id, switch keeps id, close
 * collapses, sole-leaf close is refused).
 */
class WorkspaceModelTest {
	/**
	 * The seeded default layout encodes to JSON and decodes back equal - proves the polymorphic
	 * SplitNode/LeafArea discriminator + SpaceKind-by-key serialization round-trip.
	 */
	@Test
	fun defaultLayoutRoundTrips() {
		val layout = defaultLayout()
		val decoded = decodeLayout(encodeLayout(layout))
		assertEquals(layout, decoded)
	}

	/**
	 * A deliberately deep (3-level) nested tree round-trips structurally, exercising recursion on both
	 * split children.
	 */
	@Test
	fun deepNestedTreeRoundTrips() {
		val deep =
			InterfaceLayout(
				activeWorkspaceId = "w",
				workspaces =
					listOf(
						Workspace(
							id = "w",
							root =
								SplitNode(
									SplitOrientation.Horizontal,
									0.4f,
									LeafArea("a", SpaceKind.Outliner),
									SplitNode(
										SplitOrientation.Vertical,
										0.7f,
										LeafArea("b", SpaceKind.Viewport2D),
										SplitNode(
											SplitOrientation.Horizontal,
											0.25f,
											LeafArea("c", SpaceKind.Parameters),
											LeafArea("d", SpaceKind.Properties),
										),
									),
								),
						),
					),
			)
		assertEquals(deep, decodeLayout(encodeLayout(deep)))
	}

	/**
	 * An unknown space key decodes to the neutral Outliner fallback rather than failing the layout.
	 */
	@Test
	fun unknownSpaceKeyFallsBackToOutliner() {
		val json = """{"activeWorkspaceId":"w","workspaces":[{"id":"w","root":{"type":"leaf","id":"a","space":"hologram"}}]}"""
		val decoded = decodeLayout(LayoutJson.parseToJsonElement(json))!!
		val leaf = decoded.workspaces.single().root as LeafArea
		assertEquals(SpaceKind.Outliner, leaf.space)
	}

	/**
	 * An empty workspace list decodes to null - the "seed defaults" signal.
	 */
	@Test
	fun emptyLayoutDecodesToNull() {
		val json = """{"activeWorkspaceId":"x","workspaces":[]}"""
		assertNull(decodeLayout(LayoutJson.parseToJsonElement(json)))
	}

	/**
	 * Splitting a leaf keeps that leaf's id on one side and mints a different id for the new sibling,
	 * which inherits the original space.
	 */
	@Test
	fun splitKeepsIdAndInheritsSpace() {
		val root = LeafArea("a", SpaceKind.Viewport2D)
		val result = reduce(root, AreaCommand.SplitArea("a", SplitOrientation.Vertical)) as SplitNode

		val original = result.first as LeafArea
		val created = result.second as LeafArea
		assertEquals("a", original.id, "the original leaf keeps its id")
		assertNotEquals("a", created.id, "the new sibling gets a fresh id")
		assertEquals(SpaceKind.Viewport2D, created.space, "the new sibling inherits the space")
		assertEquals(SplitOrientation.Vertical, result.orientation)
	}

	/**
	 * Splitting with an explicit ratio places the divider at that fraction (a corner-drag split lands the
	 * divider under the cursor rather than at the 50/50 default).
	 */
	@Test
	fun splitHonoursExplicitRatio() {
		val root = LeafArea("a", SpaceKind.Viewport2D)
		val result = reduce(root, AreaCommand.SplitArea("a", SplitOrientation.Horizontal, 0.3f)) as SplitNode
		assertEquals(0.3f, result.ratio)
		assertEquals("a", (result.first as LeafArea).id, "the original keeps its id on the first side")
	}

	/**
	 * Switching a leaf's space keeps its id.
	 */
	@Test
	fun switchSpaceKeepsId() {
		val root = LeafArea("a", SpaceKind.Viewport2D)
		val result = reduce(root, AreaCommand.SwitchSpace("a", SpaceKind.Properties)) as LeafArea
		assertEquals("a", result.id)
		assertEquals(SpaceKind.Properties, result.space)
	}

	/**
	 * Closing a leaf collapses its parent split into the surviving sibling, preserving nesting elsewhere.
	 */
	@Test
	fun closeCollapsesParentSplit() {
		val root =
			SplitNode(
				SplitOrientation.Horizontal,
				0.5f,
				LeafArea("a", SpaceKind.Outliner),
				SplitNode(
					SplitOrientation.Vertical,
					0.5f,
					LeafArea("b", SpaceKind.Viewport2D),
					LeafArea("c", SpaceKind.Parameters),
				),
			)
		// Close "b": its parent (the inner vertical split) collapses to "c".
		val result = reduce(root, AreaCommand.CloseArea("b")) as SplitNode
		assertEquals(LeafArea("a", SpaceKind.Outliner), result.first)
		assertEquals(LeafArea("c", SpaceKind.Parameters), result.second)
	}

	/**
	 * Closing the sole leaf of a workspace is refused (a workspace must keep at least one area).
	 */
	@Test
	fun closingSoleLeafIsNoOp() {
		val root = LeafArea("a", SpaceKind.Viewport2D)
		assertEquals(root, reduce(root, AreaCommand.CloseArea("a")))
	}

	/**
	 * Joining two direct-sibling leaves collapses their parent split to the survivor, keeping its id and
	 * space and discarding the consumed sibling.
	 */
	@Test
	fun joinDirectSiblingsCollapsesToSurvivor() {
		val root =
			SplitNode(
				SplitOrientation.Horizontal,
				0.5f,
				LeafArea("a", SpaceKind.Viewport2D),
				LeafArea("b", SpaceKind.Properties),
			)
		// "a" survives and consumes its sibling "b": the split collapses to just "a".
		assertEquals(LeafArea("a", SpaceKind.Viewport2D), reduce(root, AreaCommand.JoinAreas("a", "b")))
	}

	/**
	 * The survivor - not the first/second position - decides who remains: joining with "b" as survivor
	 * keeps "b".
	 */
	@Test
	fun joinHonoursSurvivorRegardlessOfPosition() {
		val root =
			SplitNode(
				SplitOrientation.Horizontal,
				0.5f,
				LeafArea("a", SpaceKind.Viewport2D),
				LeafArea("b", SpaceKind.Properties),
			)
		assertEquals(LeafArea("b", SpaceKind.Properties), reduce(root, AreaCommand.JoinAreas("b", "a")))
	}

	/**
	 * Joining a nested pair collapses only their split and preserves unrelated nesting around it.
	 */
	@Test
	fun joinPreservesUnrelatedNesting() {
		val root =
			SplitNode(
				SplitOrientation.Horizontal,
				0.5f,
				LeafArea("a", SpaceKind.Outliner),
				SplitNode(
					SplitOrientation.Vertical,
					0.5f,
					LeafArea("b", SpaceKind.Viewport2D),
					LeafArea("c", SpaceKind.Parameters),
				),
			)
		// Join "b"+"c": the inner split collapses to "b"; the outer split and "a" are untouched.
		val result = reduce(root, AreaCommand.JoinAreas("b", "c")) as SplitNode
		assertEquals(LeafArea("a", SpaceKind.Outliner), result.first)
		assertEquals(LeafArea("b", SpaceKind.Viewport2D), result.second)
	}

	/**
	 * Joining two leaves that are not direct siblings (different parents) is a no-op.
	 */
	@Test
	fun joinNonSiblingsIsNoOp() {
		val root =
			SplitNode(
				SplitOrientation.Horizontal,
				0.5f,
				LeafArea("a", SpaceKind.Outliner),
				SplitNode(
					SplitOrientation.Vertical,
					0.5f,
					LeafArea("b", SpaceKind.Viewport2D),
					LeafArea("c", SpaceKind.Parameters),
				),
			)
		// "a" and "b" are not direct siblings (a's sibling is the inner split, not b).
		assertEquals(root, reduce(root, AreaCommand.JoinAreas("a", "b")))
	}

	/**
	 * Joining a leaf with itself is a no-op (no self-consume).
	 */
	@Test
	fun joinSameIdIsNoOp() {
		val root = SplitNode(SplitOrientation.Horizontal, 0.5f, LeafArea("a", SpaceKind.Outliner), LeafArea("b", SpaceKind.Properties))
		assertEquals(root, reduce(root, AreaCommand.JoinAreas("a", "a")))
	}

	/**
	 * Joining against an unknown id leaves the tree unchanged.
	 */
	@Test
	fun joinUnknownIdIsNoOp() {
		val root = SplitNode(SplitOrientation.Horizontal, 0.5f, LeafArea("a", SpaceKind.Outliner), LeafArea("b", SpaceKind.Properties))
		assertEquals(root, reduce(root, AreaCommand.JoinAreas("a", "zzz")))
	}

	/**
	 * Docking a leaf to the bottom edge lifts it out (its column heals to the sibling) and re-inserts it as
	 * a full-width bottom strip, with the remainder leading and the area count preserved.
	 */
	@Test
	fun dockToBottomRelocatesAsFullStrip() {
		val root =
			SplitNode(
				SplitOrientation.Horizontal,
				0.5f,
				LeafArea("v", SpaceKind.Viewport2D),
				SplitNode(
					SplitOrientation.Vertical,
					0.5f,
					LeafArea("o", SpaceKind.Outliner),
					LeafArea("p", SpaceKind.Properties),
				),
			)
		// Dock "p" to the bottom: "p"'s column collapses to "o", and "p" becomes a full-width bottom row.
		val result = reduce(root, AreaCommand.DockArea("p", DockEdge.Bottom, 0.3f)) as SplitNode
		assertEquals(SplitOrientation.Vertical, result.orientation)
		assertEquals(0.7f, result.ratio, 0.0001f, "the remainder leads and takes 1 - strip fraction")
		assertEquals(LeafArea("p", SpaceKind.Properties), result.second, "the docked leaf keeps its id and space")
		val topRow = result.first as SplitNode
		assertEquals(SplitOrientation.Horizontal, topRow.orientation)
		assertEquals(LeafArea("v", SpaceKind.Viewport2D), topRow.first)
		assertEquals(LeafArea("o", SpaceKind.Outliner), topRow.second, "the source column healed to its sibling")
	}

	/**
	 * Docking to the top edge places the docked leaf as the leading child (so the strip leads), mirroring
	 * the bottom case.
	 */
	@Test
	fun dockToTopPlacesStripFirst() {
		val root =
			SplitNode(
				SplitOrientation.Horizontal,
				0.5f,
				LeafArea("v", SpaceKind.Viewport2D),
				LeafArea("p", SpaceKind.Properties),
			)
		val result = reduce(root, AreaCommand.DockArea("p", DockEdge.Top, 0.3f)) as SplitNode
		assertEquals(SplitOrientation.Vertical, result.orientation)
		assertEquals(0.3f, result.ratio, 0.0001f, "the strip leads and takes the strip fraction")
		assertEquals(LeafArea("p", SpaceKind.Properties), result.first)
		assertEquals(LeafArea("v", SpaceKind.Viewport2D), result.second, "the remainder is the lone sibling")
	}

	/**
	 * Docking the workspace's sole area is a no-op (nothing to dock against).
	 */
	@Test
	fun dockingSoleLeafIsNoOp() {
		val root = LeafArea("a", SpaceKind.Viewport2D)
		assertEquals(root, reduce(root, AreaCommand.DockArea("a", DockEdge.Right, 0.3f)))
	}

	/**
	 * A command targeting a missing id leaves the tree unchanged.
	 */
	@Test
	fun unknownTargetIsNoOp() {
		val root = SplitNode(SplitOrientation.Horizontal, 0.5f, LeafArea("a", SpaceKind.Outliner), LeafArea("b", SpaceKind.Properties))
		assertEquals(root, reduce(root, AreaCommand.SwitchSpace("zzz", SpaceKind.Parameters)))
	}

	/**
	 * withActiveRoot swaps only the active workspace's tree and leaves the others intact.
	 */
	@Test
	fun withActiveRootReplacesOnlyActive() {
		val layout = defaultLayout()
		val replacement = LeafArea("new", SpaceKind.History)
		val updated = layout.withActiveRoot(replacement)

		assertEquals(replacement, updated.activeWorkspace()!!.root)
		val texture = updated.workspaces.first { workspace -> workspace.id == "texture" }
		assertTrue(texture.root is SplitNode, "the inactive workspace is untouched")
	}
}
