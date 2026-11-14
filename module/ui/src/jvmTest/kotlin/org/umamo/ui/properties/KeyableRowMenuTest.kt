package org.umamo.ui.properties

import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.chooseEveryRow
import org.umamo.ui.action.commandMenuOf
import org.umamo.ui.action.defaultKeymap
import org.umamo.ui.action.formatAccelerator
import org.umamo.ui.action.menuOutline
import org.umamo.ui.action.recordingRegistryOf
import org.umamo.ui.kit.menu.MenuItem
import org.umamo.ui.workspace.commands.commandFixtureSession
import org.umamo.ui.workspace.commands.registerAll
import org.umamo.ui.workspace.commands.shellCommandTables
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A keyable property row's context menu: the key pair, Insert Keyframe and Delete Keyframe, as the registry lists
 * it.  The rows gray in Edit mode, which edits the rest state and keys nothing, by the key commands' own gate.
 */
class KeyableRowMenuTest {
	/**
	 * A registry holding the shell's command tables over [session].
	 *
	 * @param EditorSession? session The open document's session, or null for no document.
	 * @return CommandRegistry The registry.
	 */
	private fun registryOver(session: EditorSession?): CommandRegistry = CommandRegistry().also { registry -> registry.registerAll(shellCommandTables(session)) }

	@Test
	fun theMenuIsTheKeyPair() {
		assertEquals(
			listOf("Insert Keyframe", "Delete Keyframe"),
			menuOutline(commandMenuOf(KEYABLE_ROW_MENU, registryOver(commandFixtureSession(EditorMode.Object)))),
		)

		val dispatched = ArrayList<String>()
		chooseEveryRow(commandMenuOf(KEYABLE_ROW_MENU, recordingRegistryOf(shellCommandTables(), dispatched)))

		assertEquals(listOf("keyform.insert", "keyform.delete"), dispatched)
	}

	@Test
	fun inEditModeTheRowsAreGrayed() {
		assertEquals(
			listOf("Insert Keyframe (off)", "Delete Keyframe (off)"),
			menuOutline(commandMenuOf(KEYABLE_ROW_MENU, registryOver(commandFixtureSession(EditorMode.Edit)))),
		)
	}

	/** Each row shows the chord its command is bound to, which is how a rigger finds out the shortcut exists. */
	@Test
	fun eachRowShowsItsCommandsChord() {
		val keymap = defaultKeymap()
		val rows = commandMenuOf(KEYABLE_ROW_MENU, registryOver(commandFixtureSession(EditorMode.Object)), keymap).map { row -> row as MenuItem.Action }

		assertEquals(
			listOf("keyform.insert", "keyform.delete").map { commandId -> keymap.chordFor(commandId)?.let { chord -> formatAccelerator(chord) } },
			rows.map { row -> row.shortcut },
		)
		assertEquals(emptyList(), rows.filter { row -> row.shortcut == null }.map { row -> row.label }, "the default keymap binds both")
	}
}