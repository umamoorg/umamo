package org.umamo.ui.action

import org.jetbrains.compose.resources.StringResource

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
 * @property StringResource label The short label the command is suggested under ("Grab", where the title
 *   reads "Grab Vertices").  Commands sharing one label are suggested as one entry ("1/2/3 Select Mode").
 * @property CommandAvailability suggestedWhen Whether the command is worth suggesting right now, on top
 *   of being available; defaults to always.
 */
class CommandHint(
	val label: StringResource,
	val suggestedWhen: CommandAvailability = CommandAvailability.Always,
)