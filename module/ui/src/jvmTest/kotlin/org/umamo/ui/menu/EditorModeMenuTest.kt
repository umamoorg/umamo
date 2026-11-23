package org.umamo.ui.menu

import org.umamo.edit.EditorMode
import org.umamo.ui.action.CommandFamily
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.familyMenuOf
import org.umamo.ui.kit.menu.MenuItem
import org.umamo.ui.workspace.commands.commandFixtureSession
import org.umamo.ui.workspace.commands.everyCommandTable
import org.umamo.ui.workspace.commands.registerAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The editor-mode family's menu, which the 2D header's mode dropdown shows: a row per mode, each wearing that mode's
 * glyph and none ticked, since the family is no check list.  The rows are read through the family's menu, so what is
 * pinned is the family's own declaration and not how some site happens to build a row.
 */
class EditorModeMenuTest {
	@Test
	fun eachModesRowWearsItsGlyphAndNoTick() {
		val registry = CommandRegistry().also { registry -> registry.registerAll(everyCommandTable(commandFixtureSession(EditorMode.Edit))) }
		val modes = registry.inGroup(CommandFamily.EditorMode)
		val rows = familyMenuOf(CommandFamily.EditorMode, registry).map { row -> row as MenuItem.Action }

		assertTrue(modes.any { mode -> mode.isOn() }, "one mode is current")
		assertEquals(modes.size, rows.size, "a row per mode")

		for ((mode, row) in modes.zip(rows)) {
			assertNull(row.checked, "${mode.id} carries a tick slot")
			assertNotNull(row.icon, "${mode.id} lost its glyph")
			assertEquals(mode.icon, row.icon)
		}
	}
}