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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the pies' entries: the key-type pie offers Lattice, Opacity, Colors, and All in slot order, and every pie's
 * chip is enabled exactly when its command is registered and available - so Colors greys out on a target without the
 * colors, and a pie whose commands no registry carries cannot be picked.
 */
class ViewportPieMenusTest {
	/**
	 * A registry holding every command table over [session], as the shell registers them.
	 *
	 * @param EditorSession session The session the tables close over.
	 * @return CommandRegistry The registry.
	 */
	private fun registryOver(session: EditorSession): CommandRegistry = CommandRegistry().apply { everyCommandTable(session).forEach { command -> register(command) } }

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
	fun aPieWhoseCommandsNoRegistryCarriesCannotBePicked() {
		for (kind in PieMenuKind.entries) {
			assertTrue(pieMenuEntriesFor(kind, CommandRegistry()).none { entry -> entry.enabled }, "$kind")
		}

		val merge = pieMenuEntriesFor(PieMenuKind.MergeTarget, registryOver(commandFixtureSession(EditorMode.Edit)))
		assertTrue(merge.all { entry -> entry.enabled }, "the merge choices are available in Edit mode")
	}
}