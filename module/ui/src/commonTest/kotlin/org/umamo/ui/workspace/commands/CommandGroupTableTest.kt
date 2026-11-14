package org.umamo.ui.workspace.commands

import org.umamo.edit.EditorMode
import org.umamo.edit.MeshSelectMode
import org.umamo.edit.ProportionalFalloff
import org.umamo.edit.TransformPivotMode
import org.umamo.edit.setMeshSelectMode
import org.umamo.edit.transform.choiceKey
import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandFamily
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.CommandSection
import org.umamo.ui.action.CommandTable
import org.umamo.ui.action.isWithin
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.menu_select_kind_drawables
import org.umamo.ui.resources.menu_select_kind_parts
import org.umamo.ui.resources.menu_select_kind_rotations
import org.umamo.ui.resources.menu_select_kind_warps
import org.umamo.ui.viewport.falloffLabelResource
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

		for (section in CommandSection.entries) {
			assertTrue(registry.inGroup(section).isNotEmpty(), "no command names $section")
		}
	}

	/**
	 * A family with sections is its sections' members and nothing else, in the order its table registers them - a
	 * command naming the family itself would sit in the pie's ring and in neither part of the menu.
	 */
	@Test
	fun aSectionedFamilyIsExactlyItsSections() {
		val registry = registryOverEveryTable()
		val sectioned =
			mapOf(
				CommandFamily.Snap to listOf(CommandSection.SnapCursor, CommandSection.SnapSelection),
				CommandFamily.UvSnap to listOf(CommandSection.UvSnapSelection, CommandSection.UvSnapCursor),
			)
		assertEquals(CommandSection.entries.map { section -> section.parent }.toSet(), sectioned.keys, "every sectioned family is pinned here")

		for ((family, sections) in sectioned) {
			assertEquals(CommandSection.entries.filter { section -> section.parent == family }.toSet(), sections.toSet(), "$family")
			assertEquals(
				sections.flatMap { section -> registry.inGroup(section) }.map { command -> command.id },
				registry.inGroup(family).map { command -> command.id },
				"$family lists its sections in this order",
			)
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

	/**
	 * A family whose members are the choices of one setting has exactly one of them in effect, and it is the one
	 * the session holds - which is what a control listing the family lights, and whose glyph its chip wears.
	 */
	@Test
	fun aChoiceFamilyHasTheSessionsChoiceInEffect() {
		val session = commandFixtureSession(EditorMode.Edit)
		val registry = CommandRegistry().also { registry -> everyCommandTable(session).forEach { command -> registry.register(command) } }
		val inEffect = { family: CommandFamily -> registry.inGroup(family).filter { command -> command.isOn() }.map { command -> command.id } }

		assertEquals(listOf("mode.edit"), inEffect(CommandFamily.EditorMode))
		assertEquals(listOf("mesh.selectMode.vertex"), inEffect(CommandFamily.SelectMode))
		assertEquals(listOf("transform.pivot.median"), inEffect(CommandFamily.PivotMode))
		assertEquals(listOf("mesh.proportional.falloff.${session.proportionalSettings.value.falloff.choiceKey}"), inEffect(CommandFamily.ProportionalFalloff))

		session.setMeshSelectMode(MeshSelectMode.Face)
		session.setPivotMode(TransformPivotMode.Cursor)
		session.setProportionalFalloff(ProportionalFalloff.Sharp)

		assertEquals(listOf("mesh.selectMode.face"), inEffect(CommandFamily.SelectMode), "read live, never sampled at registration")
		assertEquals(listOf("transform.pivot.cursor"), inEffect(CommandFamily.PivotMode))
		assertEquals(listOf("mesh.proportional.falloff.sharp"), inEffect(CommandFamily.ProportionalFalloff))
	}

	/**
	 * With no document open the pivot and the mode still read as the ones a session starts on, so their header chips
	 * show the same face before the first file opens; the select modes and the curves belong to controls that are not
	 * shown then, and report nothing.
	 */
	@Test
	fun withNoDocumentThePivotAndTheModeReadAsASessionsFirst() {
		val registry = registryOverEveryTable()
		val inEffect = { family: CommandFamily -> registry.inGroup(family).filter { command -> command.isOn() }.map { command -> command.id } }

		assertEquals(listOf("transform.pivot.median"), inEffect(CommandFamily.PivotMode))
		assertEquals(listOf("mode.object"), inEffect(CommandFamily.EditorMode))
		assertEquals(emptyList(), inEffect(CommandFamily.SelectMode))
		assertEquals(emptyList(), inEffect(CommandFamily.ProportionalFalloff))
	}

	/** A button or a chip shows a member by its glyph, so a member of these families with none drawn would have no face. */
	@Test
	fun theFamiliesShownAsButtonsHaveAGlyphPerMember() {
		val registry = registryOverEveryTable()

		for (family in listOf(CommandFamily.SelectMode, CommandFamily.PivotMode, CommandFamily.ProportionalFalloff, CommandFamily.EditorMode)) {
			assertEquals(emptyList(), registry.inGroup(family).filter { command -> command.icon == null }.map { command -> command.id }, "$family")
		}
	}

	/** The falloff family holds a member per curve, in the curves' order, each under the curve's short name. */
	@Test
	fun theFalloffFamilyListsEveryCurveUnderItsShortName() {
		val curves = registryOverEveryTable().inGroup(CommandFamily.ProportionalFalloff)

		assertEquals(ProportionalFalloff.entries.map { falloff -> "mesh.proportional.falloff.${falloff.choiceKey}" }, curves.map { command -> command.id })
		assertEquals(ProportionalFalloff.entries.map { falloff -> falloffLabelResource(falloff) }, curves.map { command -> command.rowLabel })
		assertTrue(curves.all { command -> command.title != command.rowLabel }, "the palette title still says Proportional Falloff")
	}
}