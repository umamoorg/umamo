package org.umamo.ui.action

import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonSkippableComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.kit.button.Button
import org.umamo.ui.kit.button.IconButton
import org.umamo.ui.kit.button.IconButtonAppearance
import org.umamo.ui.theme.UmamoIcon

/*
 * The buttons that stand for one command.  A button built here holds nothing of its own: it runs its command through
 * the registry, wears the command's glyph or title, is lit while the command is the one in effect, and is grayed
 * exactly when the command does not apply - so the button, the menu row, the palette, and the key cannot come to
 * disagree about what the operation is or when it can run.
 *
 * Whether a command applies, and whether it is in effect, are live queries the snapshot system cannot see.  Each
 * button reads them every time its caller recomposes, and is never skipped for having the same command as last
 * time, so the caller is what observes whatever those queries read ([ObserveForCommands]).
 */

/**
 * Names what the commands behind a control read, so the calling composition recomposes when any of it changes.
 *
 * A control showing whether a command applies, or is in effect, reads the state that depends on here - a session
 * flow's value, the model - and that read, made in the caller's scope as the arguments are evaluated, is what
 * subscribes the caller.  The values themselves are not used.
 *
 * @param Any? values What was read.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
internal fun ObserveForCommands(vararg values: Any?) {
}

/**
 * A text button that runs one command: labeled by the command's title and enabled exactly when the command applies,
 * so the chord, the palette, and this button run the same thing over the same selection.  Draws nothing while the
 * command is not registered.
 *
 * @param String commandId The command.
 * @param Modifier modifier The layout modifier.
 */
@Composable
@NonSkippableComposable
internal fun CommandButton(commandId: String, modifier: Modifier = Modifier) {
	val commands = LocalCommands.current
	val command = commands[commandId] ?: return
	Button(
		label = command.title?.let { title -> stringResource(title) } ?: command.id,
		onClick = { commands.invoke(command.id) },
		modifier = modifier,
		primary = false,
		enabled = command.isAvailable(),
	)
}

/**
 * An icon button that runs one command, by id.  Draws nothing while the command is not registered.
 *
 * @param String commandId The command.
 * @param Modifier modifier The layout modifier.
 * @param IconButtonAppearance appearance Flat glyph, or a filled chip.
 * @param DpSize size The button's box.
 * @param Dp glyphSize The drawn glyph size.
 * @param UmamoIcon? icon The glyph to wear in place of the command's, for a button whose face also says something
 *   the command does not know; null for the command's own.
 * @param String? description The accessible label and hover text in place of the command's title, for a button
 *   whose hover text explains more than a title can; null for the title.
 * @param Function around Runs the dispatch it is handed, for a button that has to do something of its own around it.
 */
@Composable
@NonSkippableComposable
internal fun CommandIconButton(
	commandId: String,
	modifier: Modifier = Modifier,
	appearance: IconButtonAppearance = IconButtonAppearance.Flat,
	size: DpSize = DpSize(20.dp, 20.dp),
	glyphSize: Dp = 16.dp,
	icon: UmamoIcon? = null,
	description: String? = null,
	around: (dispatch: () -> Unit) -> Unit = { dispatch -> dispatch() },
) {
	val command = LocalCommands.current[commandId] ?: return
	CommandIconButton(command, modifier, appearance, size, glyphSize, icon, description, around)
}

/**
 * An icon button that runs [command]: the command's glyph, its title as the accessible label and hover text, lit
 * while the command is the one in effect, and grayed when it does not apply.  The title, not the label the command
 * goes by under its family's heading: a button stands with no heading over it.  Draws nothing for a command with no
 * glyph to wear.
 *
 * @param Command command The command.
 * @param Modifier modifier The layout modifier.
 * @param IconButtonAppearance appearance Flat glyph, or a filled chip.
 * @param DpSize size The button's box.
 * @param Dp glyphSize The drawn glyph size.
 * @param UmamoIcon? icon The glyph to wear in place of the command's; null for the command's own.
 * @param String? description The accessible label and hover text in place of the command's title; null for the title.
 * @param Function around Runs the dispatch it is handed, for a button that has to do something of its own around it.
 */
@Composable
@NonSkippableComposable
internal fun CommandIconButton(
	command: Command,
	modifier: Modifier = Modifier,
	appearance: IconButtonAppearance = IconButtonAppearance.Flat,
	size: DpSize = DpSize(20.dp, 20.dp),
	glyphSize: Dp = 16.dp,
	icon: UmamoIcon? = null,
	description: String? = null,
	around: (dispatch: () -> Unit) -> Unit = { dispatch -> dispatch() },
) {
	val commands = LocalCommands.current
	val glyph = icon ?: command.icon ?: return
	// A button runs its command with no argument, so the state it shows is the one that takes none.
	val state = command.state
	val lit = state?.isOn() == true
	IconButton(
		icon = glyph,
		onClick = { around { commands.invoke(command.id) } },
		contentDescription = description ?: command.title?.let { title -> stringResource(title) } ?: command.id,
		// A command that can be the one in effect says so to assistive technology as well as by its fill.
		modifier = if (state != null) modifier.semantics { selected = lit } else modifier,
		appearance = appearance,
		size = size,
		glyphSize = glyphSize,
		active = lit,
		enabled = command.isAvailable(),
	)
}