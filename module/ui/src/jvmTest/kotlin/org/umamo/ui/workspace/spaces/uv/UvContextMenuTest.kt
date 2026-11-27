package org.umamo.ui.workspace.spaces.uv

import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.chooseEveryRow
import org.umamo.ui.action.commandMenuOf
import org.umamo.ui.action.menuOutline
import org.umamo.ui.action.recordingRegistryOf
import org.umamo.ui.workspace.commands.commandFixtureSession
import org.umamo.ui.workspace.commands.registerAll
import org.umamo.ui.workspace.commands.shellCommandTables
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The UV editor's context menu: the two mirrors, each under its axis, a rule, then the art meshes.  Every row is read
 * from its command, so the mirrors gray outside Edit mode by the mirror commands' own gate.
 */
class UvContextMenuTest {
	/**
	 * A registry holding the shell's command tables over [session].
	 *
	 * @param EditorSession? session The open document's session, or null for no document.
	 * @return CommandRegistry The registry.
	 */
	private fun registryOver(session: EditorSession?): CommandRegistry = CommandRegistry().also { registry -> registry.registerAll(shellCommandTables(session)) }

	/** The fixture's document has no art tiles to mesh from, so the generate rows are grayed in every mode. */
	@Test
	fun inEditModeTheMirrorsCanBePicked() {
		assertEquals(
			listOf("Mirror X", "Mirror Y", "---", "Generate Mesh from Art (off)", "Generate Quad Mesh (off)"),
			menuOutline(commandMenuOf(UV_CONTEXT_ROWS, registryOver(commandFixtureSession(EditorMode.Edit)))),
		)
	}

	@Test
	fun outsideEditModeTheMirrorsAreGrayed() {
		assertEquals(
			listOf("Mirror X (off)", "Mirror Y (off)"),
			menuOutline(commandMenuOf(UV_CONTEXT_ROWS, registryOver(commandFixtureSession(EditorMode.Object)))).take(2),
		)
	}

	@Test
	fun theRowsDispatchTheirCommands() {
		val dispatched = ArrayList<String>()
		chooseEveryRow(commandMenuOf(UV_CONTEXT_ROWS, recordingRegistryOf(shellCommandTables(), dispatched)))

		assertEquals(listOf("uv.mirrorU", "uv.mirrorV", "mesh.generateFromArt", "mesh.generateQuad"), dispatched)
	}
}