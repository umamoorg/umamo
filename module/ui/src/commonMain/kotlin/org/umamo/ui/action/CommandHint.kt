package org.umamo.ui.action

import org.jetbrains.compose.resources.StringResource
import org.umamo.ui.workspace.SpaceScope

/**
 * What the status bar suggests a [Command] under, and when.
 *
 * A command is suggested when its own [Command.availability] and [suggestedWhen] both hold.
 * [suggestedWhen] narrows the SUGGESTION alone: dispatch and the command palette never read it, so a
 * command can run in a mode the bar does not advertise it in - Tab toggles Edit mode from either side,
 * and is worth suggesting only from Object mode.
 *
 * The status bar recomputes its suggestions when the registered table (and so the document), the editor
 * mode, the hovered space, or the keymap changes.  Both predicates of a hinted command must therefore
 * depend on the document and the mode alone, or the suggestion goes stale.
 *
 * @property StringResource? label The short name the command is suggested under, or null to read under its
 *   own [Command.title].  The bar is a narrow one-line strip whose suggestions are what get cut when the
 *   window narrows, so a command whose title is long declares a short name here rather than costing the
 *   strip the room.  A name is also what lets commands go by one name together: the select modes are
 *   suggested as the single entry "1/2/3 Select Mode", which needs a name none of the three titles
 *   carries.
 * @property CommandAvailability suggestedWhen Whether the command is worth suggesting right now, on top
 *   of being available; defaults to always.
 * @property Function suggestedOver The spaces the suggestion shows over right now, when narrower than the command's
 *   own [Command.spaces] - a command that acts over a space only in some modes is worth suggesting there only in
 *   those modes; null shows it over every space the command belongs to.  Read whenever the bar recomputes, so it
 *   follows the same document-and-mode rule as [suggestedWhen], and like it, dispatch and the palette never read it.
 */
class CommandHint(
	val label: StringResource? = null,
	val suggestedWhen: CommandAvailability = CommandAvailability.Always,
	val suggestedOver: () -> SpaceScope? = { null },
)