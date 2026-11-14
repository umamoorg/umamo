package org.umamo.ui.action

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.ui.kit.menu.MenuItem

/*
 * The fixtures the menu tests share: a command family, or an arrangement of rows, built into its menu the way the
 * surface showing it builds it, and the menu read back as text.
 */

/**
 * The builder of [family]'s menu over [commands], as the shell's bar holds it: composed once, then called each time
 * the menu opens.  The composition is gone by the time this returns, so calling the builder twice reads the rows'
 * grayed state twice with nothing recomposed in between.
 *
 * @param CommandFamily family The family the menu stands for.
 * @param CommandRegistry commands The registry the menu is listed from and dispatches through.
 * @param Keymap keymap The keymap the accelerators are read from; unbound unless a case binds one.
 * @return Function The builder.
 */
@OptIn(ExperimentalTestApi::class)
internal fun familyMenuBuilderOf(
	family: CommandFamily,
	commands: CommandRegistry,
	keymap: Keymap = Keymap(emptyMap()),
): () -> List<MenuItem> {
	var builder: (() -> List<MenuItem>)? = null
	runComposeUiTest {
		setContent {
			CompositionLocalProvider(LocalCommands provides commands, LocalKeymap provides keymap) {
				builder = rememberFamilyMenu(family)
			}
		}
		waitForIdle()
	}

	return checkNotNull(builder) { "the menu was never composed" }
}

/**
 * [family]'s menu over [commands] as it is when opened now.
 *
 * @param CommandFamily family The family the menu stands for.
 * @param CommandRegistry commands The registry the menu is listed from and dispatches through.
 * @param Keymap keymap The keymap the accelerators are read from; unbound unless a case binds one.
 * @return List<MenuItem> The menu's entries.
 */
internal fun familyMenuOf(
	family: CommandFamily,
	commands: CommandRegistry,
	keymap: Keymap = Keymap(emptyMap()),
): List<MenuItem> = familyMenuBuilderOf(family, commands, keymap)()

/**
 * The builder of an arranged menu over [commands], composed once the way a surface holds it, then called each time
 * the menu opens.
 *
 * @param List<CommandMenuRow> rows The menu's rows.
 * @param CommandRegistry commands The registry the menu is read from and dispatches through.
 * @param Keymap keymap The keymap the accelerators are read from; unbound unless a case binds one.
 * @return Function The builder.
 */
@OptIn(ExperimentalTestApi::class)
internal fun commandMenuBuilderOf(
	rows: List<CommandMenuRow>,
	commands: CommandRegistry,
	keymap: Keymap = Keymap(emptyMap()),
): () -> List<MenuItem> {
	var builder: (() -> List<MenuItem>)? = null
	runComposeUiTest {
		setContent {
			CompositionLocalProvider(LocalCommands provides commands, LocalKeymap provides keymap) {
				builder = rememberCommandMenu(rows)
			}
		}
		waitForIdle()
	}

	return checkNotNull(builder) { "the menu was never composed" }
}

/**
 * An arranged menu over [commands] as it is when opened now.
 *
 * @param List<CommandMenuRow> rows The menu's rows.
 * @param CommandRegistry commands The registry the menu is read from and dispatches through.
 * @param Keymap keymap The keymap the accelerators are read from; unbound unless a case binds one.
 * @return List<MenuItem> The menu's entries.
 */
internal fun commandMenuOf(
	rows: List<CommandMenuRow>,
	commands: CommandRegistry,
	keymap: Keymap = Keymap(emptyMap()),
): List<MenuItem> = commandMenuBuilderOf(rows, commands, keymap)()

/**
 * A menu as lines of text, top to bottom: a row by its label, a submenu by its label and a trailing arrow with its
 * own rows indented under it, a rule as three dashes.  A grayed row or submenu ends in "(off)".
 *
 * @param List<MenuItem> items The menu's entries.
 * @param String indent What each line starts with; a submenu's rows take two more spaces.
 * @return List<String> The lines.
 */
internal fun menuOutline(items: List<MenuItem>, indent: String = ""): List<String> =
	items.flatMap { item ->
		when (item) {
			is MenuItem.Action -> listOf(indent + item.label + if (item.enabled) "" else " (off)")
			is MenuItem.Submenu -> listOf(indent + item.label + " >" + if (item.enabled) "" else " (off)") + menuOutline(item.items, "$indent  ")
			is MenuItem.Heading -> listOf("$indent# ${item.label}")
			is MenuItem.Search -> listOf("$indent?")
			MenuItem.Separator -> listOf("$indent---")
		}
	}

/**
 * Chooses every row of a menu in order, nested rows included and grayed ones too: what a row dispatches does not
 * depend on whether it can be picked.
 *
 * @param List<MenuItem> items The menu's entries.
 */
internal fun chooseEveryRow(items: List<MenuItem>) {
	for (item in items) {
		when (item) {
			is MenuItem.Action -> {
				item.onSelect()
			}

			is MenuItem.Submenu -> {
				chooseEveryRow(item.items)
			}

			else -> {}
		}
	}
}

/**
 * A registry holding [tables] with every handler swapped for one that records the dispatch, and every command made
 * to apply, so choosing a row always reaches its recorder.  A command run with an argument is recorded as "id(argument)".
 *
 * @param List<Command> tables The commands to register, in order.
 * @param MutableList<String> dispatched Collects what the rows dispatch.
 * @return CommandRegistry The registry.
 */
internal fun recordingRegistryOf(tables: List<Command>, dispatched: MutableList<String>): CommandRegistry =
	CommandRegistry().also { registry ->
		for (command in tables) {
			registry.register(
				command.copy(
					availability = CommandAvailability.Always,
					handler = { argument -> dispatched.add(if (argument == null) command.id else "${command.id}($argument)") },
				),
			)
		}
	}