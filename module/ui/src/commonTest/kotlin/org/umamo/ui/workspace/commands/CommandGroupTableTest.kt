package org.umamo.ui.workspace.commands

import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandFamily
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.CommandTable
import org.umamo.ui.action.isWithin
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.menu_select_kind_drawables
import org.umamo.ui.resources.menu_select_kind_parts
import org.umamo.ui.resources.menu_select_kind_rotations
import org.umamo.ui.resources.menu_select_kind_warps
import org.umamo.ui.workspace.AreaOverlayHub
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the groups the command tables place their commands in: every command sits in a table, every table and every
 * family has members, and a family lists what its table registers, in the table's order.
 *
 * A menu, a pie, or a header control lists a family straight from the registry, so a command its table forgot to
 * tag, or a table that returns a bare list, is a row that silently never appears.
 */
class CommandGroupTableTest {
	/** A registry holding every command table, as the shell, the settings-backed shell, and the app register them. */
	private fun registryOverEveryTable(): CommandRegistry = CommandRegistry().also { registry -> everyCommandTable().forEach { command -> registry.register(command) } }

	@Test
	fun everyCommandBelongsToATable() {
		val outsideEveryTable = everyCommandTable().filter { command -> command.group == null }

		assertEquals(emptyList(), outsideEveryTable.map { command -> command.id }, "a table function returns its list through commandTable")
	}

	@Test
	fun everyTableAndEveryFamilyHasMembers() {
		val registry = registryOverEveryTable()

		for (table in CommandTable.entries) {
			assertTrue(registry.inGroup(table).isNotEmpty(), "no table function places its commands in $table")
		}

		for (family in CommandFamily.entries) {
			assertTrue(registry.inGroup(family).isNotEmpty(), "no command names $family")
		}
	}

	/** Each command is in exactly one table, so the tables together are the whole registry and nothing more. */
	@Test
	fun theTablesPartitionTheRegistry() {
		val registry = registryOverEveryTable()
		val byTable = CommandTable.entries.flatMap { table -> registry.inGroup(table) }

		assertEquals(registry.all().size, byTable.size)
		assertEquals(registry.all().map { command -> command.id }.toSet(), byTable.map { command -> command.id }.toSet())
	}

	/** A table's group is what its function registers, in that order - with no need to call the function to learn it. */
	@Test
	fun aTablesGroupListsWhatItsFunctionRegisters() {
		val registry = registryOverEveryTable()

		assertEquals(
			overlayCommands(AreaOverlayHub(), CommandRouting { null }, viewportPresent = false).map { command -> command.id },
			registry.inGroup(CommandTable.Overlay).map { command -> command.id },
		)
	}

	@Test
	fun theByKindFamiliesListTheirKindsInMenuOrderUnderTheKindAlone() {
		val registry = registryOverEveryTable()
		val kindLabels =
			listOf(
				Res.string.menu_select_kind_parts,
				Res.string.menu_select_kind_drawables,
				Res.string.menu_select_kind_warps,
				Res.string.menu_select_kind_rotations,
			)
		val selectAll = registry.inGroup(CommandFamily.SelectAllOfKind)
		val invert = registry.inGroup(CommandFamily.InvertSelectionOfKind)

		assertEquals(listOf("select.all.parts", "select.all.drawables", "select.all.warps", "select.all.rotations"), selectAll.map { command -> command.id })
		assertEquals(listOf("select.invert.parts", "select.invert.drawables", "select.invert.warps", "select.invert.rotations"), invert.map { command -> command.id })
		assertEquals(kindLabels, selectAll.map { command -> command.rowLabel })
		assertEquals(kindLabels, invert.map { command -> command.rowLabel })
		assertTrue(selectAll.all { command -> command.title != command.rowLabel }, "the palette title still names what the command does")
	}

	/** A family's members are in its table too, without having said so. */
	@Test
	fun aFamilysMembersAreInItsTable() {
		val registry = registryOverEveryTable()
		val selectTable = registry.inGroup(CommandTable.Select).map { command -> command.id }

		for (family in listOf(CommandFamily.SelectAllOfKind, CommandFamily.InvertSelectionOfKind)) {
			assertTrue(family.isWithin(CommandTable.Select))
			assertTrue(selectTable.containsAll(registry.inGroup(family).map { command -> command.id }), "$family")
		}

		assertTrue("select.all" in selectTable, "beside the commands that name no family")
		assertTrue(registry.inGroup(CommandFamily.SelectAllOfKind).none { command -> command.id == "select.all" })
	}

	@Test
	fun aTablePlacesItselfOnWhatNamesNoGroupAndKeepsTheOrder() {
		val bare = Command("test.bare", title = null) {}
		val member = Command("test.member", title = null, group = CommandFamily.SelectAllOfKind, groupLabel = Res.string.menu_select_kind_parts) {}

		val table = commandTable(CommandTable.Select, member, bare)

		assertEquals(listOf("test.member", "test.bare"), table.map { command -> command.id })
		assertSame(member, table[0], "a family member is already placed, and is left as it was")
		assertEquals(CommandTable.Select, table[1].group)
		assertSame(bare.handler, table[1].handler, "placing a command changes nothing else about it")
	}

	/** A family listed from the registry holds what its own table registers, so no other table may add to it. */
	@Test
	fun aTableRefusesAMemberOfAnotherTablesFamily() {
		val stray = Command("test.stray", title = null, group = CommandFamily.SelectAllOfKind, groupLabel = Res.string.menu_select_kind_parts) {}

		assertFailsWith<IllegalArgumentException> { commandTable(CommandTable.Overlay, stray) }
	}
}