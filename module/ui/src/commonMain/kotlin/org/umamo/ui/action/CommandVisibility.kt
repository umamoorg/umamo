package org.umamo.ui.action

import org.umamo.ui.workspace.SpaceKind

/**
 * Reports whether this command is offered over a surface of [kind]: it belongs to that space and
 * currently applies.  The one rule the command palette and the status bar share, so the two cannot
 * come to disagree about what "offered here" means.
 *
 * @param SpaceKind? kind The space the pointer last touched, or null before it touched any.
 * @return Boolean True when the command is listed and suggested there.
 */
internal fun Command.isOfferedIn(kind: SpaceKind?): Boolean = spaces.appliesIn(kind) && availability.isAvailable()

/**
 * The commands the palette offers over a surface of [hoveredKind]: titled, currently available, and
 * belonging to that space.
 *
 * A null title marks a command as not a palette entry at all (internal toggles like palette.toggle and
 * area.dragCancel, and the argument-only document commands); an unavailable command is hidden rather
 * than dimmed, and so is one outside its spaces - the palette lists what would do something from where
 * the pointer is, in the current mode.  The hidden commands stay discoverable in the keybindings editor,
 * which lists the whole table.
 *
 * @param List<Command> commands The registered commands, in registration order.
 * @param SpaceKind? hoveredKind The space the pointer last touched, or null before it touched any.
 * @return List<Command> The commands to list, in the order given.
 */
internal fun paletteCommands(commands: List<Command>, hoveredKind: SpaceKind?): List<Command> =
	commands.filter { command -> command.title != null && command.isOfferedIn(hoveredKind) }