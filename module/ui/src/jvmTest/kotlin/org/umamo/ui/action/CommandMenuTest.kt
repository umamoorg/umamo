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
 * family inside it is a submenu, and each row is built from its command alone.
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
}