package org.umamo.ui.workspace.commands

import org.umamo.edit.EditorMode
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.MeshSelectMode
import org.umamo.edit.ProportionalFalloff
import org.umamo.edit.TransformPivotMode
import org.umamo.edit.setMeshSelectMode
import org.umamo.edit.transform.beginBoxSelect
import org.umamo.edit.transform.beginCircleSelect
import org.umamo.edit.transform.beginObjectOperator
import org.umamo.edit.transform.choiceKey
import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandFamily
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.CommandSection
import org.umamo.ui.action.CommandTable
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.menu_select_kind_drawables
import org.umamo.ui.resources.menu_select_kind_parts
import org.umamo.ui.resources.menu_select_kind_rotations
import org.umamo.ui.resources.menu_select_kind_warps
import org.umamo.ui.resources.menu_uv_mirror_x
import org.umamo.ui.resources.menu_uv_mirror_y
import org.umamo.ui.viewport.falloffLabelResource
import org.umamo.ui.workspace.AreaOverlayHub
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the groups the command tables place their commands in: every command sits in a table, every table, family,
 * and section has members, and a family's own commands come from one table function, in its order.
 *
 * A menu, a pie, or a header control lists a family straight from the registry, so a command its table forgot to
 * tag, or a table that returns a bare list, is a row that silently never appears.
 */
class CommandGroupTableTest {
	/** A registry holding every command table, as the shell, the settings-backed shell, and the app register them. */
	private fun registryOverEveryTable(): CommandRegistry = CommandRegistry().also { registry -> everyCommandTable().forEach { command -> registry.register(command) } }

	@Test
	fun everyCommandBelongsToATable() {
		val outsideEveryTable = everyCommandTable().filter { command -> command.table == null }

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
	 * A family with sections is its sections' members and nothing else, section by section in the order they are
	 * declared - a command naming the family itself would sit in the pie's ring and in no block of the menu.
	 */
	@Test
	fun aSectionedFamilyIsExactlyItsSections() {
		val registry = registryOverEveryTable()
		val sectioned =
			mapOf(
				CommandFamily.File to listOf(CommandSection.FileDocument, CommandSection.FileSession),
				CommandFamily.FileImport to listOf(CommandSection.ImportArtwork, CommandSection.ImportPuppet),
				CommandFamily.FileExport to listOf(CommandSection.ExportPuppet, CommandSection.ExportImage),
				CommandFamily.Edit to listOf(CommandSection.EditHistory, CommandSection.EditSettings),
				CommandFamily.Select to listOf(CommandSection.SelectEverything, CommandSection.SelectByKind),
				CommandFamily.Workspace to
					listOf(CommandSection.WorkspaceSwitch, CommandSection.WorkspaceManage, CommandSection.WorkspaceTab, CommandSection.WorkspaceFiles),
				CommandFamily.Help to
					listOf(CommandSection.HelpLinks, CommandSection.HelpSetup, CommandSection.HelpUpdates, CommandSection.HelpLogs, CommandSection.HelpAbout),
				CommandFamily.ViewportTool to listOf(CommandSection.ToolSelect, CommandSection.ToolTransform, CommandSection.ToolBrush),
				CommandFamily.Snap to listOf(CommandSection.SnapCursor, CommandSection.SnapSelection),
				CommandFamily.UvSnap to listOf(CommandSection.UvSnapSelection, CommandSection.UvSnapCursor),
				CommandFamily.Relation to listOf(CommandSection.RelationSelect, CommandSection.RelationBind, CommandSection.RelationRemove, CommandSection.RelationCopy),
			)
		assertEquals(CommandSection.entries.map { section -> section.parent }.toSet(), sectioned.keys, "every sectioned family is pinned here")

		for ((family, sections) in sectioned) {
			assertEquals(CommandSection.entries.filter { section -> section.parent == family }, sections, "$family declares its sections in this order")
			assertEquals(emptyList(), registry.membersOf(family).map { command -> command.id }, "$family has a command in none of its sections")
			assertEquals(
				sections.flatMap { section -> registry.inGroup(section) }.map { command -> command.id },
				registry.inGroup(family).map { command -> command.id },
				"$family lists its sections in this order",
			)
		}
	}

	/**
	 * A family's own commands, or one section's, come whole from one table function.  Registration order holds only
	 * inside one: two functions feeding one block would list it in whichever order they happened to register, and
	 * again differently after a document swap.  A block that draws from two is two sections.
	 */
	@Test
	fun eachFamilysOwnCommandsComeFromOneTableFunction() {
		val functions = everyCommandTableFunction()
		val listings = CommandFamily.entries + CommandSection.entries

		for (listing in listings) {
			val feeding = functions.filter { function -> function.any { command -> command.group == listing } }

			assertTrue(feeding.size <= 1, "$listing is fed by ${feeding.size} table functions: ${feeding.map { function -> function.first().id }}")
		}
	}

	/**
	 * The sections that join the block before them, with no rule: each is the second table function feeding one
	 * block, which is the only reason a block is cut into sections a menu does not show.
	 */
	@Test
	fun theSectionsThatJoinTheBlockBeforeThemAreExactlyThese() {
		assertEquals(
			listOf(CommandSection.ImportPuppet, CommandSection.ExportImage, CommandSection.HelpUpdates, CommandSection.HelpLogs, CommandSection.ToolTransform, CommandSection.ToolBrush),
			CommandSection.entries.filter { section -> !section.ruled },
		)
	}

	/** The families that sit inside another are its submenus, and each sits in the block it is shown in. */
	@Test
	fun theSubmenusSitInTheirBlocks() {
		assertEquals(
			mapOf(
				CommandFamily.FileImport to CommandSection.FileDocument,
				CommandFamily.FileExport to CommandSection.FileDocument,
				CommandFamily.SelectAllOfKind to CommandSection.SelectByKind,
				CommandFamily.InvertSelectionOfKind to CommandSection.SelectByKind,
			),
			CommandFamily.entries.filter { family -> family.parent != null }.associateWith { family -> family.parent },
		)
	}

	/** The Select menu: the selection as a whole, then a block holding the two by-kind submenus. */
	@Test
	fun theSelectFamilyHoldsItsRowsAndItsByKindSubmenus() {
		val registry = registryOverEveryTable()

		assertEquals(listOf("select.all", "select.invert"), registry.membersOf(CommandSection.SelectEverything).map { command -> command.id })
		assertEquals(emptyList(), registry.membersOf(CommandSection.SelectByKind), "the second block is submenus alone")
		assertEquals(
			listOf(CommandFamily.SelectAllOfKind, CommandFamily.InvertSelectionOfKind),
			CommandFamily.entries.filter { family -> family.parent == CommandSection.SelectByKind },
			"the submenus, in the order they are declared",
		)
		assertEquals(
			listOf("select.all", "select.invert") +
				registry.inGroup(CommandFamily.SelectAllOfKind).map { command -> command.id } +
				registry.inGroup(CommandFamily.InvertSelectionOfKind).map { command -> command.id },
			registry.inGroup(CommandFamily.Select).map { command -> command.id },
		)
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

	/**
	 * The two pairs a context menu lists inline.  The mirrors go by their axis, which the UV editor's menu shows; the
	 * key pair goes by its titles, since a keyable row's menu has no heading over it to say what is keyed.
	 */
	@Test
	fun theMirrorAndKeyPairsListTheirTwoCommandsUnderTheLabelsTheirMenusShow() {
		val registry = registryOverEveryTable()
		val mirrors = registry.inGroup(CommandFamily.UvMirror)
		val keys = registry.inGroup(CommandFamily.KeyformKey)

		assertEquals(listOf("uv.mirrorU", "uv.mirrorV"), mirrors.map { command -> command.id })
		assertEquals(listOf(Res.string.menu_uv_mirror_x, Res.string.menu_uv_mirror_y), mirrors.map { command -> command.rowLabel })
		assertTrue(mirrors.all { command -> command.title != command.rowLabel }, "the palette title still says which UVs and which way")
		assertEquals(listOf("keyform.insert", "keyform.delete"), keys.map { command -> command.id })
		assertEquals(keys.map { command -> command.title }, keys.map { command -> command.rowLabel })
	}

	/** A family's members are in their table too, which is a separate fact from the family: neither implies the other. */
	@Test
	fun aFamilysMembersAreStillInTheirTable() {
		val registry = registryOverEveryTable()
		val selectTable = registry.inGroup(CommandTable.Select).map { command -> command.id }

		for (family in listOf(CommandFamily.SelectAllOfKind, CommandFamily.InvertSelectionOfKind)) {
			assertTrue(selectTable.containsAll(registry.inGroup(family).map { command -> command.id }), "$family")
		}

		assertTrue("mesh.selectLinked" in selectTable, "beside the commands no family shows")
		assertTrue(registry.inGroup(CommandFamily.Select).none { command -> command.id == "mesh.selectLinked" })
	}

	@Test
	fun aTablePlacesItselfOnEveryCommandAndKeepsTheOrder() {
		val bare = Command("test.bare", title = null) {}
		val member = Command("test.member", title = null, group = CommandFamily.SelectAllOfKind, groupLabel = Res.string.menu_select_kind_parts) {}

		val table = commandTable(CommandTable.Overlay, member, bare)

		assertEquals(listOf("test.member", "test.bare"), table.map { command -> command.id })
		assertEquals(listOf(CommandTable.Overlay, CommandTable.Overlay), table.map { command -> command.table })
		assertEquals(CommandFamily.SelectAllOfKind, table[0].group, "the family a command is shown in is its own to name, whichever table holds it")
		assertNull(table[1].group, "and a command that names none is shown in none")
		assertSame(bare.handler, table[1].handler, "placing a command changes nothing else about it")
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

	/** The brush's mode is a session setting the sidebar's tool panel switches, so no command carries it. */
	@Test
	fun theBrushModeIsASettingAndNotACommand() {
		val brushModeCommands = everyCommandTable().map { command -> command.id }.filter { id -> id.startsWith("mesh.deformBrush.mode.") }

		assertEquals(emptyList(), brushModeCommands)
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

		for (family in listOf(CommandFamily.SelectMode, CommandFamily.PivotMode, CommandFamily.ProportionalFalloff, CommandFamily.EditorMode, CommandFamily.ViewportTool)) {
			assertEquals(emptyList(), registry.inGroup(family).filter { command -> command.icon == null }.map { command -> command.id }, "$family")
		}
	}

	/** The commands a single button stands for each have a glyph for it to wear, which the palette shows too. */
	@Test
	fun theCommandsAButtonStandsForHaveAGlyph() {
		val byId = everyCommandTable().associateBy { command -> command.id }
		val buttons = listOf("file.importArtwork", "sources.matchAutomatically", "document.reloadArtwork", "logs.export", "mesh.proportional.toggle")

		assertEquals(emptyList(), buttons.filter { commandId -> byId.getValue(commandId).icon == null })
	}

	/**
	 * The toolbar's tools, in its order: the select tools, then the transforms, then the Deform brush.  Each is
	 * lit exactly while it is armed or in flight, which the session says and the toolbar only shows.
	 */
	@Test
	fun theToolbarsToolsAreLitWhileArmedOrInFlight() {
		val session = commandFixtureSession(EditorMode.Object)
		val registry = CommandRegistry().also { registry -> everyCommandTable(session).forEach { command -> registry.register(command) } }
		val lit = { registry.inGroup(CommandFamily.ViewportTool).filter { command -> command.isOn() }.map { command -> command.id } }

		assertEquals(
			listOf("mesh.boxSelect", "mesh.circleSelect", "mesh.grab", "mesh.rotate", "mesh.scale", "mesh.deformBrush"),
			registry.inGroup(CommandFamily.ViewportTool).map { command -> command.id },
		)
		assertEquals(emptyList(), lit(), "nothing is armed")

		session.beginBoxSelect("area")
		assertEquals(listOf("mesh.boxSelect"), lit())

		session.beginCircleSelect("area")
		assertEquals(listOf("mesh.circleSelect"), lit())

		session.clearSelectTool()
		session.beginObjectOperator(MeshOperatorKind.Rotate, "area")
		assertEquals(listOf("mesh.rotate"), lit())

		session.clearObjectOperator()
		assertEquals(emptyList(), lit())
	}

	/** Proportional editing's toggle is on exactly while the tool is, which is what lights its header chip. */
	@Test
	fun theProportionalToggleIsOnWhileTheToolIs() {
		val session = commandFixtureSession(EditorMode.Edit)
		val toggle = everyCommandTable(session).first { command -> command.id == "mesh.proportional.toggle" }

		val before = toggle.isOn()

		assertEquals(session.proportionalEdit.value != null, before)

		toggle.handler.run(null)

		assertEquals(!before, toggle.isOn(), "read live, never sampled at registration")
		assertEquals(session.proportionalEdit.value != null, toggle.isOn())
		assertFalse(everyCommandTable().first { command -> command.id == "mesh.proportional.toggle" }.isOn(), "and off with no document")
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