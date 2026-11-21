package org.umamo.ui.action

import org.jetbrains.compose.resources.StringResource
import org.umamo.ui.kit.menu.MenuItem
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.menu_exit
import org.umamo.ui.resources.menu_file_new
import org.umamo.ui.resources.menu_file_open
import org.umamo.ui.resources.menu_file_save
import org.umamo.ui.resources.menu_import_cmo3
import org.umamo.ui.resources.menu_import_moc3
import org.umamo.ui.resources.menu_open_recent
import org.umamo.ui.theme.LocalUmamoIcons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * How a command family becomes a menu, over commands made up for each case: a family's sections are its blocks, a
 * family inside it is a submenu, and each row is built from its command alone.  An arranged menu, which names
 * commands and families row by row, is built the same way.
 */
class CommandMenuTest {
	/**
	 * A registry holding [commands], in order.
	 *
	 * @param Command commands The commands to register.
	 * @return CommandRegistry The registry.
	 */
	private fun registryOf(vararg commands: Command): CommandRegistry = CommandRegistry().also { registry -> commands.forEach { command -> registry.register(command) } }

	/**
	 * A command that is a row of [group] under [label].
	 *
	 * @param String id The command's id.
	 * @param CommandListing group The family or section the row sits in.
	 * @param StringResource label The row's label.
	 * @param Boolean available Whether the command applies.
	 * @return Command The command.
	 */
	private fun rowOf(
		id: String,
		group: CommandListing,
		label: StringResource,
		available: Boolean = true,
	): Command = Command(id, title = null, availability = CommandAvailability { available }, group = group, groupLabel = label, handler = {})

	/** A family with sections is a block per section, in declared order, with a rule between two that both hold a row. */
	@Test
	fun aBlockNothingIsRegisteredInIsLeftOutWithItsRule() {
		val registry =
			registryOf(
				rowOf("test.about", CommandSection.HelpAbout, Res.string.menu_exit),
				rowOf("test.link", CommandSection.HelpLinks, Res.string.menu_file_new),
			)

		assertEquals(listOf("New", "---", "Exit"), menuOutline(familyMenuOf(CommandFamily.Help, registry)), "the links come first, and one rule stands for the empty block between")
	}

	/** A section that asks for no rule joins the block before it, in declared order, whichever was registered first. */
	@Test
	fun aSectionThatAsksForNoRuleJoinsTheBlockBeforeIt() {
		val registry =
			registryOf(
				rowOf("test.logs", CommandSection.HelpLogs, Res.string.menu_file_save),
				rowOf("test.updates", CommandSection.HelpUpdates, Res.string.menu_file_open),
				rowOf("test.setup", CommandSection.HelpSetup, Res.string.menu_file_new),
			)

		assertEquals(listOf("New", "Open…", "Save"), menuOutline(familyMenuOf(CommandFamily.Help, registry)))
	}

	/**
	 * The block a joining section belongs to still stands apart when the section that opens it holds nothing: the
	 * rule is the block's, not the opening section's.
	 */
	@Test
	fun aJoiningSectionKeepsItsBlocksRuleWhenTheSectionItJoinsIsEmpty() {
		val registry =
			registryOf(
				rowOf("test.link", CommandSection.HelpLinks, Res.string.menu_file_new),
				rowOf("test.updates", CommandSection.HelpUpdates, Res.string.menu_file_open),
			)

		assertEquals(listOf("New", "---", "Open…"), menuOutline(familyMenuOf(CommandFamily.Help, registry)))
	}

	/** A family with no sections is one block: its rows in registration order, with no rule. */
	@Test
	fun aFamilyWithNoSectionsIsOneBlock() {
		val registry =
			registryOf(
				rowOf("test.first", CommandFamily.MergeTarget, Res.string.menu_file_new),
				rowOf("test.second", CommandFamily.MergeTarget, Res.string.menu_file_open),
			)

		assertEquals(listOf("New", "Open…"), menuOutline(familyMenuOf(CommandFamily.MergeTarget, registry)))
	}

	/**
	 * A family inside a section is a submenu in that block, after the block's own rows however it was registered, and
	 * one with no row registered is left out altogether.
	 */
	@Test
	fun aFamilyInsideABlockIsASubmenuAfterTheBlocksOwnRows() {
		val registry =
			registryOf(
				rowOf("test.cmo3", CommandSection.ImportPuppet, Res.string.menu_import_cmo3),
				rowOf("test.new", CommandSection.FileDocument, Res.string.menu_file_new),
				rowOf("test.exit", CommandSection.FileSession, Res.string.menu_exit),
			)

		assertEquals(
			listOf("New", "Import >", "  CMO3…", "---", "Exit"),
			menuOutline(familyMenuOf(CommandFamily.File, registry)),
			"Export has no row, so it has no submenu",
		)
	}

	/** A submenu can be opened while any row under it can be picked, and is grayed when none can. */
	@Test
	fun aSubmenuIsGrayedWhenNoneOfItsRowsCanBePicked() {
		val allOff =
			registryOf(
				rowOf("test.cmo3", CommandSection.ImportPuppet, Res.string.menu_import_cmo3, available = false),
				rowOf("test.moc3", CommandSection.ImportPuppet, Res.string.menu_import_moc3, available = false),
			)
		val oneOn =
			registryOf(
				rowOf("test.cmo3", CommandSection.ImportPuppet, Res.string.menu_import_cmo3, available = false),
				rowOf("test.moc3", CommandSection.ImportPuppet, Res.string.menu_import_moc3),
			)

		assertEquals(listOf("Import > (off)", "  CMO3… (off)", "  MOC3… (off)"), menuOutline(familyMenuOf(CommandFamily.File, allOff)))
		assertEquals(listOf("Import >", "  CMO3… (off)", "  MOC3…"), menuOutline(familyMenuOf(CommandFamily.File, oneOn)))
	}

	/** A row is its command: the command's glyph, the chord the keymap binds to it, and a pick that runs it and nothing else. */
	@Test
	fun aRowIsBuiltFromItsCommandAlone() {
		var runCount = 0
		val save =
			Command(
				"file.save",
				title = null,
				group = CommandFamily.MergeTarget,
				groupLabel = Res.string.menu_file_save,
				icon = LocalUmamoIcons.cursor,
				handler = { runCount++ },
			)
		val keymap = defaultKeymap()
		val row = familyMenuOf(CommandFamily.MergeTarget, registryOf(save), keymap).single() as MenuItem.Action

		assertEquals("Save", row.label)
		assertSame(LocalUmamoIcons.cursor, row.icon)
		assertEquals(keymap.chordFor("file.save")?.let { chord -> formatAccelerator(chord) }, row.shortcut)
		assertTrue(row.shortcut != null, "the default keymap binds Save, so the row has a chord to show")

		row.onSelect()

		assertEquals(1, runCount)
	}

	/**
	 * A command offered with a list of arguments is a submenu of them: a row apiece under the argument's own label,
	 * each running the command with its argument, and no chord, since the chord runs the command with none.
	 */
	@Test
	fun aCommandOfferedWithArgumentsIsASubmenuOfThem() {
		val received = ArrayList<Any?>()
		val recent =
			Command(
				"file.save",
				title = null,
				group = CommandFamily.MergeTarget,
				groupLabel = Res.string.menu_open_recent,
				choices = CommandChoices { listOf(CommandChoice("hero.uma", "/rigs/hero.uma"), CommandChoice("villain.uma", 7)) },
				handler = { argument -> received.add(argument) },
			)
		val submenu = familyMenuOf(CommandFamily.MergeTarget, registryOf(recent), defaultKeymap()).single() as MenuItem.Submenu

		assertEquals("Open Recent", submenu.label)
		assertTrue(submenu.enabled)
		assertEquals(listOf("hero.uma", "villain.uma"), submenu.items.map { row -> (row as MenuItem.Action).label })
		assertTrue(submenu.items.all { row -> (row as MenuItem.Action).shortcut == null })

		chooseEveryRow(submenu.items)

		assertEquals(listOf<Any?>("/rigs/hero.uma", 7), received)
	}

	/** With nothing to offer, or while its command does not apply, a command's submenu of arguments cannot open. */
	@Test
	fun aSubmenuOfArgumentsCannotOpenWithNothingToOffer() {
		val empty = Command("test.empty", title = null, group = CommandFamily.MergeTarget, groupLabel = Res.string.menu_open_recent, choices = CommandChoices { emptyList() }, handler = {})
		val unavailable =
			Command(
				"test.unavailable",
				title = null,
				availability = CommandAvailability { false },
				group = CommandFamily.MergeTarget,
				groupLabel = Res.string.menu_open_recent,
				choices = CommandChoices { listOf(CommandChoice("hero.uma", "/rigs/hero.uma")) },
				handler = {},
			)

		assertEquals(listOf("Open Recent > (off)"), menuOutline(familyMenuOf(CommandFamily.MergeTarget, registryOf(empty))))
		assertEquals(listOf("Open Recent > (off)", "  hero.uma"), menuOutline(familyMenuOf(CommandFamily.MergeTarget, registryOf(unavailable))))
	}

	/** A command that names no family is in no menu, whichever table holds it. */
	@Test
	fun aCommandNoFamilyShowsIsInNoMenu() {
		val registry = registryOf(Command("test.bare", title = Res.string.menu_file_new, table = CommandTable.File, handler = {}))

		for (family in CommandFamily.entries) {
			assertEquals(emptyList(), familyMenuOf(family, registry), "$family")
		}

		assertNull(registry["test.bare"]?.group)
	}

	/**
	 * A command that is in no family, under [title].
	 *
	 * @param String id The command's id.
	 * @param StringResource title The command's title.
	 * @param Boolean available Whether the command applies.
	 * @return Command The command.
	 */
	private fun titled(
		id: String,
		title: StringResource,
		available: Boolean = true,
	): Command = Command(id, title = title, availability = CommandAvailability { available }, handler = {})

	/**
	 * An arranged row for one command goes by the command's title, and a group listed inline by each member's label
	 * in its family: the same command reads differently in the two places.
	 */
	@Test
	fun anArrangedRowGoesByTheTitleAndAnInlineMemberByItsLabel() {
		val member = Command("test.member", title = Res.string.menu_file_open, group = CommandSection.HelpLinks, groupLabel = Res.string.menu_file_new, handler = {})
		val registry = registryOf(member)

		assertEquals(listOf("Open…"), menuOutline(commandMenuOf(listOf(CommandMenuRow.Single("test.member")), registry)))
		assertEquals(listOf("New"), menuOutline(commandMenuOf(listOf(CommandMenuRow.Members(CommandSection.HelpLinks)), registry)))
	}

	/** A row whose command is not registered is left out, and so is the rule it would have stood beside. */
	@Test
	fun aRowWithNoCommandIsLeftOutWithTheRuleItStranded() {
		val registry = registryOf(titled("test.first", Res.string.menu_file_new), titled("test.last", Res.string.menu_exit))
		val rows =
			listOf(
				CommandMenuRow.Separator,
				CommandMenuRow.Single("test.first"),
				CommandMenuRow.Separator,
				CommandMenuRow.Single("test.missing"),
				CommandMenuRow.Separator,
				CommandMenuRow.Single("test.last"),
				CommandMenuRow.Separator,
			)

		assertEquals(listOf("New", "---", "Exit"), menuOutline(commandMenuOf(rows, registry)))
		assertEquals(emptyList(), commandMenuOf(rows, CommandRegistry()), "with nothing registered there is no row and no rule")
	}

	/**
	 * A family row is the family's whole menu as a submenu, rules and all, under the family's heading - or under the
	 * title of the command that opens it as a pie, without which the row is left out.
	 */
	@Test
	fun aFamilyRowIsTheFamilysMenuUnderItsHeadingOrItsOpenersTitle() {
		val registry =
			registryOf(
				rowOf("test.selection", CommandSection.SnapSelection, Res.string.menu_file_save),
				rowOf("test.cursor", CommandSection.SnapCursor, Res.string.menu_file_new),
				titled("test.opener", Res.string.menu_file_open),
			)

		assertEquals(
			listOf("Snap >", "  New", "  ---", "  Save"),
			menuOutline(commandMenuOf(listOf(CommandMenuRow.Family(CommandFamily.Snap)), registry)),
			"the cursor's block, a rule, then the selection's",
		)
		assertEquals(
			listOf("Open… >", "  New", "  ---", "  Save"),
			menuOutline(commandMenuOf(listOf(CommandMenuRow.Family(CommandFamily.Snap, openerId = "test.opener")), registry)),
		)
		assertEquals(emptyList(), commandMenuOf(listOf(CommandMenuRow.Family(CommandFamily.Snap, openerId = "test.missing")), registry), "no opener, no row")
		assertEquals(emptyList(), commandMenuOf(listOf(CommandMenuRow.Family(CommandFamily.UvSnap)), registry), "a family with nothing registered has no submenu")
	}

	/** A family listed inline is its whole menu in place, with its own rules. */
	@Test
	fun aFamilyListedInlineIsItsWholeMenuInPlace() {
		val registry =
			registryOf(
				rowOf("test.selection", CommandSection.SnapSelection, Res.string.menu_file_save),
				rowOf("test.cursor", CommandSection.SnapCursor, Res.string.menu_file_new),
				titled("test.after", Res.string.menu_exit),
			)
		val rows = listOf(CommandMenuRow.Members(CommandFamily.Snap), CommandMenuRow.Separator, CommandMenuRow.Single("test.after"))

		assertEquals(listOf("New", "---", "Save", "---", "Exit"), menuOutline(commandMenuOf(rows, registry)))
	}

	/** A named submenu holds its rows, grays when none of them can be picked, and is left out when none is there. */
	@Test
	fun aNamedSubmenuGraysWithItsRowsAndIsLeftOutWithNone() {
		val rows = listOf(CommandMenuRow.Submenu(Res.string.menu_open_recent, listOf(CommandMenuRow.Single("test.first"), CommandMenuRow.Separator, CommandMenuRow.Single("test.second"))))

		assertEquals(
			listOf("Open Recent >", "  New", "  ---", "  Save (off)"),
			menuOutline(commandMenuOf(rows, registryOf(titled("test.first", Res.string.menu_file_new), titled("test.second", Res.string.menu_file_save, available = false)))),
		)
		assertEquals(
			listOf("Open Recent > (off)", "  New (off)"),
			menuOutline(commandMenuOf(rows, registryOf(titled("test.first", Res.string.menu_file_new, available = false)))),
			"the one row that is there cannot be picked, and its rule went with the row that is not",
		)
		assertEquals(emptyList(), commandMenuOf(rows, CommandRegistry()))
	}

	/**
	 * A row's grayed state is read each time the menu opens, with nothing recomposed in between: a surface stays
	 * composed while what its commands read changes under it.
	 */
	@Test
	fun anArrangedRowIsGrayedAsItIsWhenTheMenuOpens() {
		var applies = false
		val gated = Command("test.gated", title = Res.string.menu_file_save, availability = CommandAvailability { applies }, handler = {})
		val menu = commandMenuBuilderOf(listOf(CommandMenuRow.Single("test.gated")), registryOf(gated))

		assertEquals(listOf("Save (off)"), menuOutline(menu()))

		applies = true

		assertEquals(listOf("Save"), menuOutline(menu()))
	}

	/**
	 * An inline group can aim its rows at one thing among several: each row runs its command with that argument, and
	 * shows no chord, since the chord runs the command with none - on a workspace's tab, that would be another tab.
	 */
	@Test
	fun anInlineGroupsRowsRunWithItsArgumentAndShowNoChord() {
		val received = ArrayList<Any?>()
		val save = Command("file.save", title = null, group = CommandFamily.MergeTarget, groupLabel = Res.string.menu_file_save, handler = { argument -> received.add(argument) })
		val registry = registryOf(save)
		val keymap = defaultKeymap()
		val aimed = commandMenuOf(listOf(CommandMenuRow.Members(CommandFamily.MergeTarget, argument = "bravo")), registry, keymap).single() as MenuItem.Action
		val plain = commandMenuOf(listOf(CommandMenuRow.Members(CommandFamily.MergeTarget)), registry, keymap).single() as MenuItem.Action

		assertNull(aimed.shortcut)
		assertTrue(plain.shortcut != null, "the same row with no argument shows the chord")

		aimed.onSelect()
		plain.onSelect()

		assertEquals(listOf<Any?>("bravo", null), received)
	}

	/**
	 * A row aimed at one thing grays when its command does not apply to that thing, though the command applies at
	 * large: the argument is what the menu was opened on.
	 */
	@Test
	fun anInlineGroupsRowGraysWhenItsCommandDoesNotApplyToItsArgument() {
		val save = Command("file.save", title = null, availableFor = { argument -> argument == "bravo" }, group = CommandFamily.MergeTarget, groupLabel = Res.string.menu_file_save, handler = {})
		val registry = registryOf(save)

		assertEquals(listOf("Save"), menuOutline(commandMenuOf(listOf(CommandMenuRow.Members(CommandFamily.MergeTarget, argument = "bravo")), registry)))
		assertEquals(listOf("Save (off)"), menuOutline(commandMenuOf(listOf(CommandMenuRow.Members(CommandFamily.MergeTarget, argument = "alpha")), registry)))
	}

	/**
	 * A family row aimed at one thing runs every row with that argument, as an inline group does, and grays each by
	 * it: a parameter's Control submenu, opened on that parameter.
	 */
	@Test
	fun aFamilyRowRunsItsRowsWithItsArgument() {
		val received = ArrayList<Any?>()
		val save = Command("file.save", title = null, availableFor = { argument -> argument == "bravo" }, group = CommandFamily.MergeTarget, groupLabel = Res.string.menu_file_save, handler = { argument -> received.add(argument) })
		val registry = registryOf(save)
		val aimed = commandMenuOf(listOf(CommandMenuRow.Family(CommandFamily.MergeTarget, argument = "bravo")), registry).single() as MenuItem.Submenu
		val elsewhere = commandMenuOf(listOf(CommandMenuRow.Family(CommandFamily.MergeTarget, argument = "alpha")), registry)

		assertEquals(listOf("Merge >", "  Save"), menuOutline(listOf(aimed)))
		assertEquals(listOf("Merge > (off)", "  Save (off)"), menuOutline(elsewhere), "the row does not apply to that argument")

		(aimed.items.single() as MenuItem.Action).onSelect()

		assertEquals(listOf<Any?>("bravo"), received)
	}

	/**
	 * A menu every item of a list opens on itself is resolved once: the one builder runs its rows with whichever
	 * argument it is called with, and grays and ticks them by that argument.
	 */
	@Test
	fun aMenuResolvedOnceRunsItsRowsWithTheArgumentItOpensOn() {
		val received = ArrayList<Any?>()
		val choice =
			Command(
				"test.choice",
				title = null,
				availableFor = { argument -> argument != "charlie" },
				group = CommandFamily.ParameterControl,
				groupLabel = Res.string.menu_file_save,
				stateFor = { argument -> argument == "bravo" },
				handler = { argument -> received.add(argument) },
			)
		val menu = commandMenuForBuilderOf(listOf(CommandMenuRow.Family(CommandFamily.ParameterControl)), registryOf(choice))
		val rowFor = { argument: String -> (menu(argument).single() as MenuItem.Submenu).items.single() as MenuItem.Action }

		assertEquals(false, rowFor("alpha").checked)
		assertEquals(true, rowFor("bravo").checked, "the tick is the argument's")
		assertEquals(false, rowFor("charlie").enabled, "and so is the grayed state")

		rowFor("alpha").onSelect()
		rowFor("bravo").onSelect()

		assertEquals(listOf<Any?>("alpha", "bravo"), received)
	}

	/**
	 * In a check-list family a command's row is a choice row, ticked while the command is the one in effect for the
	 * argument the row runs it with.  The family opts into that style: in any other family, and in a row built outside
	 * one, a command keeps no tick slot whatever state it reports.
	 */
	@Test
	fun aChoiceRowIsTickedWhileItIsTheOneInEffect() {
		var current = "bravo"
		val choice = Command("test.choice", title = null, group = CommandFamily.ParameterControl, groupLabel = Res.string.menu_file_save, stateFor = { argument -> argument == current }, handler = {})
		val lit = Command("test.lit", title = Res.string.menu_file_new, group = CommandFamily.MergeTarget, state = CommandState { true }, handler = {})
		val plain = Command("test.plain", title = Res.string.menu_file_open, handler = {})
		val registry = registryOf(choice, lit, plain)
		val keymap = defaultKeymap()

		assertEquals(true, (commandMenuItem(choice, "Save", registry, keymap, argument = "bravo", checkList = true) as MenuItem.Action).checked)
		assertEquals(false, (commandMenuItem(choice, "Save", registry, keymap, argument = "alpha", checkList = true) as MenuItem.Action).checked, "the state is that argument's")

		val menu = commandMenuBuilderOf(listOf(CommandMenuRow.Family(CommandFamily.ParameterControl, argument = "bravo")), registry)
		current = "alpha"

		assertEquals(false, ((menu().single() as MenuItem.Submenu).items.single() as MenuItem.Action).checked, "the tick is asked as the menu opens")

		// The style is the family's to opt into: a member that reports a state gets no tick slot in any other menu.
		val plainFamily = commandMenuOf(listOf(CommandMenuRow.Family(CommandFamily.MergeTarget)), registry)

		assertNull(((plainFamily.single() as MenuItem.Submenu).items.single() as MenuItem.Action).checked, "a family that is no check list ticks nothing")
		assertNull((commandMenuItem(lit, "New", registry, keymap) as MenuItem.Action).checked, "and neither does a row built outside one")
		assertNull((commandMenuItem(plain, "Open…", registry, keymap) as MenuItem.Action).checked)
	}

	/**
	 * A site that has to set something up for its command to read wraps the dispatch: the wrapper runs around the
	 * command, once, and the row is otherwise the command's own.
	 */
	@Test
	fun aRowsDispatchCanBeBracketed() {
		val order = ArrayList<String>()
		val command = Command("file.save", title = Res.string.menu_file_save, handler = { order.add("run") })
		val registry = registryOf(command)
		val keymap = defaultKeymap()
		val row =
			commandMenuItem(command, "Save", registry, keymap) { dispatch ->
				order.add("before")
				dispatch()
				order.add("after")
			} as MenuItem.Action

		assertEquals(keymap.chordFor("file.save")?.let { chord -> formatAccelerator(chord) }, row.shortcut)

		row.onSelect()

		assertEquals(listOf("before", "run", "after"), order)
	}
}