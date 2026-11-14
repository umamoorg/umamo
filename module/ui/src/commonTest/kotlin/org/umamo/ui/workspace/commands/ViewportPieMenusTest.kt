package org.umamo.ui.workspace.commands

import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.PieMenuKind
import org.umamo.runtime.model.RuntimeTarget
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.pie_insert_key_all
import org.umamo.ui.resources.pie_insert_key_colors
import org.umamo.ui.resources.pie_insert_key_lattice
import org.umamo.ui.resources.pie_insert_key_opacity
import org.umamo.ui.resources.pie_title_insert_key
import org.umamo.ui.resources.pie_title_merge
import org.umamo.ui.resources.pie_title_pivot
import org.umamo.ui.resources.pie_title_snap
import org.umamo.ui.resources.pie_title_uv_snap
import org.umamo.ui.theme.LocalUmamoIcons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the pies' entries: every pie is its command family as the registry lists it, so its table's order is its slot
 * order; the key-type pie offers Lattice, Opacity, Colors, and All; and a chip is enabled exactly when its command is
 * available - so Colors grays out on a target without the colors, and a pie whose family no registry carries has no
 * chip to pick.
 */
class ViewportPieMenusTest {
	/**
	 * A registry holding every command table over [session], as the shell registers them.
	 *
	 * @param EditorSession session The session the tables close over.
	 * @return CommandRegistry The registry.
	 */
	private fun registryOver(session: EditorSession): CommandRegistry = CommandRegistry().apply { everyCommandTable(session).forEach { command -> register(command) } }

	/**
	 * The ring each pie draws, slot by slot, clockwise from West.  The order is the family's, as its table
	 * registers it, and it is what the hand and the 1-8 digit picks know - so a table edit that moves a slot
	 * should be a deliberate edit to these lists.
	 */
	@Test
	fun eachPieOffersItsChoicesInSlotOrder() {
		val registry = registryOver(commandFixtureSession(EditorMode.Object))
		val rings =
			mapOf(
				PieMenuKind.PivotMode to listOf("transform.pivot.median", "transform.pivot.individual", "transform.pivot.active", "transform.pivot.cursor"),
				PieMenuKind.Snap to
					listOf(
						"snap.cursorToWorldOrigin",
						"snap.cursorToGrid",
						"snap.cursorToSelected",
						"snap.cursorToActive",
						"snap.selectionToGrid",
						"snap.selectionToCursor",
						"snap.selectionToCursorOffset",
						"snap.selectionToActive",
					),
				PieMenuKind.UvSnap to
					listOf(
						"uv.snap.selectionToPixels",
						"uv.snap.selectionToCursor",
						"uv.snap.selectionToCursorOffset",
						"uv.snap.selectionToGrid",
						"uv.snap.cursorToPixels",
						"uv.snap.cursorToSelected",
						"uv.snap.cursorToGrid",
					),
				PieMenuKind.MergeTarget to listOf("mesh.merge.atCenter", "mesh.merge.atFirst", "mesh.merge.atLast"),
				PieMenuKind.InsertKey to listOf("keyform.insert.lattice", "keyform.insert.opacity", "keyform.insert.colors", "keyform.insert.all"),
			)
		assertEquals(PieMenuKind.entries.toSet(), rings.keys, "every pie's ring is pinned here")

		for ((kind, ring) in rings) {
			assertEquals(ring, pieMenuEntriesFor(kind, registry).map { entry -> entry.commandId }, "$kind")
		}
	}

	/** A pie holds nothing of its own: ids, labels, and glyphs are its family's members', and its title the heading. */
	@Test
	fun aPieIsItsFamilyAsTheRegistryListsIt() {
		val registry = registryOver(commandFixtureSession(EditorMode.Object))

		for (kind in PieMenuKind.entries) {
			val family = pieFamilyOf(kind)
			val members = registry.inGroup(family)
			val entries = pieMenuEntriesFor(kind, registry)

			assertEquals(members.map { member -> member.id }, entries.map { entry -> entry.commandId }, "$kind")
			assertEquals(members.map { member -> member.rowLabel }, entries.map { entry -> entry.label }, "$kind")
			assertEquals(members.map { member -> member.icon }, entries.map { entry -> entry.icon }, "$kind")
			assertEquals(family.heading, pieMenuTitleFor(kind), "$kind")
			assertTrue(entries.size in 1..8, "$kind: the ring has eight slots, and ${entries.size} chips")
		}
	}

	@Test
	fun thePiesKeepTheirTitles() {
		assertEquals(Res.string.pie_title_pivot, pieMenuTitleFor(PieMenuKind.PivotMode))
		assertEquals(Res.string.pie_title_snap, pieMenuTitleFor(PieMenuKind.Snap))
		assertEquals(Res.string.pie_title_uv_snap, pieMenuTitleFor(PieMenuKind.UvSnap))
		assertEquals(Res.string.pie_title_merge, pieMenuTitleFor(PieMenuKind.MergeTarget))
		assertEquals(Res.string.pie_title_insert_key, pieMenuTitleFor(PieMenuKind.InsertKey))
	}

	@Test
	fun theChipsWearTheirCommandsGlyphs() {
		val registry = registryOver(commandFixtureSession(EditorMode.Object))

		assertEquals(
			listOf(LocalUmamoIcons.pivotMedian, LocalUmamoIcons.pivotIndividual, LocalUmamoIcons.pivotActive, LocalUmamoIcons.pivotCursor),
			pieMenuEntriesFor(PieMenuKind.PivotMode, registry).map { entry -> entry.icon },
		)
		assertEquals(
			List(4) { LocalUmamoIcons.cursor } + List(4) { LocalUmamoIcons.selection },
			pieMenuEntriesFor(PieMenuKind.Snap, registry).map { entry -> entry.icon },
			"the cursor's snaps, then the selection's",
		)
		assertEquals(
			List(4) { LocalUmamoIcons.selection } + List(3) { LocalUmamoIcons.pivotCursor },
			pieMenuEntriesFor(PieMenuKind.UvSnap, registry).map { entry -> entry.icon },
			"the selection's snaps, then the cursor's",
		)
		assertTrue(pieMenuEntriesFor(PieMenuKind.MergeTarget, registry).all { entry -> entry.icon == null }, "placeholder icons until the set is drawn")
	}

	@Test
	fun theKeyTypePieOffersItsFourChoicesInSlotOrder() {
		val entries = pieMenuEntriesFor(PieMenuKind.InsertKey, registryOver(commandFixtureSession(EditorMode.Pose)))

		assertEquals(listOf("keyform.insert.lattice", "keyform.insert.opacity", "keyform.insert.colors", "keyform.insert.all"), entries.map { entry -> entry.commandId })
		assertEquals(
			listOf(Res.string.pie_insert_key_lattice, Res.string.pie_insert_key_opacity, Res.string.pie_insert_key_colors, Res.string.pie_insert_key_all),
			entries.map { entry -> entry.label },
		)
		assertTrue(entries.all { entry -> entry.icon == null }, "placeholder icons until the set is drawn")
	}

	@Test
	fun eachChipIsEnabledExactlyWhenItsCommandIsAvailable() {
		val posed = pieMenuEntriesFor(PieMenuKind.InsertKey, registryOver(commandFixtureSession(EditorMode.Pose)))
		assertEquals(listOf(true, true, true, true), posed.map { entry -> entry.enabled })

		val withoutColors = pieMenuEntriesFor(PieMenuKind.InsertKey, registryOver(commandFixtureSession(EditorMode.Pose, RuntimeTarget.Cubism40)))
		assertEquals(listOf(true, true, false, true), withoutColors.map { entry -> entry.enabled }, "a 4.0 target has no colors to key")

		val inObjectMode = pieMenuEntriesFor(PieMenuKind.InsertKey, registryOver(commandFixtureSession(EditorMode.Object)))
		assertTrue(inObjectMode.none { entry -> entry.enabled }, "nothing is posed outside Pose mode")
	}

	@Test
	fun aPieWhoseFamilyNoRegistryCarriesHasNoChipToPick() {
		for (kind in PieMenuKind.entries) {
			assertEquals(emptyList(), pieMenuEntriesFor(kind, CommandRegistry()), "$kind")
		}

		val merge = pieMenuEntriesFor(PieMenuKind.MergeTarget, registryOver(commandFixtureSession(EditorMode.Edit)))
		assertEquals(3, merge.size)
		assertTrue(merge.all { entry -> entry.enabled }, "the merge choices are available in Edit mode")
	}
}