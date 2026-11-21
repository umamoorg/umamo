package org.umamo.ui.workspace.commands

import org.jetbrains.compose.resources.StringResource
import org.umamo.edit.EditorSession
import org.umamo.edit.ParameterControl
import org.umamo.edit.parameter.padLinks
import org.umamo.edit.parameter.setParameterRepeat
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandFamily
import org.umamo.ui.action.CommandTable
import org.umamo.ui.resources.*

/**
 * The commands a parameter's context menu lists: Toggle Repeat, and the control the row shows.  Each runs with the
 * parameter the menu was opened on as its argument, so it grays by that parameter and is never offered where nothing
 * aims it (the palette, a chord).  Toggle Repeat is a document edit rather than a pose write, so it runs in Edit mode
 * as a range edit does; a control is the session's alone, so picking one is no undo step and no change to the
 * document.  A pair's control commands run with its horizontal member, the one its row keys on.  The control
 * commands are rows alone: with no title, the keybindings list leaves them out as the palette does.
 *
 * @param EditorSession? editorSession The open document's session, or null (every command then no-ops).
 * @param SessionAvailability availability The shared document-scoped availability tiers.
 * @return List<Command> The commands to register.
 */
internal fun parameterCommands(editorSession: EditorSession?, availability: SessionAvailability): List<Command> {
	/**
	 * The parameter a row runs with, when there is a document holding it.
	 *
	 * @param Any? argument The row's argument.
	 * @return Parameter? The parameter, or null.
	 */
	fun parameterOf(argument: Any?): Parameter? {
		val id = argument as? ParameterId ?: return null

		return editorSession?.model?.value?.parameters?.firstOrNull { parameter -> parameter.id == id }
	}

	/**
	 * The parameter a single row's control runs with: one the document holds that no pad folds in, whose row is its
	 * own.  A member of a link the panel shows no pad for is one of these.
	 *
	 * @param Any? argument The row's argument.
	 * @return ParameterId? The parameter, or null.
	 */
	fun ownRowOf(argument: Any?): ParameterId? {
		val parameterId = parameterOf(argument)?.id ?: return null
		val pads = editorSession?.model?.value?.padLinks().orEmpty()

		return parameterId.takeIf { pads.none { link -> link.horizontal == parameterId || link.vertical == parameterId } }
	}

	/**
	 * The member a pair's control runs with: the horizontal of a link the panel shows as a pad, which the pad keys on.
	 *
	 * @param Any? argument The row's argument.
	 * @return ParameterId? The pair's horizontal member, or null.
	 */
	fun pairOf(argument: Any?): ParameterId? {
		val parameterId = argument as? ParameterId ?: return null

		return parameterId.takeIf { editorSession?.model?.value?.padLinks().orEmpty().any { link -> link.horizontal == parameterId } }
	}

	/**
	 * One member of a control family: it sets [control] on the parameter or pair it runs with, and is the one in
	 * effect while that is the control it shows.
	 *
	 * Untitled, like the other argument-only commands: a row supplies the parameter, and neither the palette nor a
	 * chord has one to offer.
	 *
	 * @param String id The command's id.
	 * @param StringResource label The label of the command's row in its family.
	 * @param CommandFamily family The family it is a member of.
	 * @param ParameterControl? control The control it sets, or null to follow the preference.
	 * @param Function targetOf The parameter a row runs it with, or null where it does not apply.
	 * @return Command The command.
	 */
	fun controlCommand(id: String, label: StringResource, family: CommandFamily, control: ParameterControl?, targetOf: (Any?) -> ParameterId?): Command =
		Command(
			id,
			title = null,
			availability = availability.hasDocument,
			availableFor = { argument -> targetOf(argument) != null },
			group = family,
			groupLabel = label,
			stateFor = { argument -> targetOf(argument)?.let { target -> editorSession?.parameterControls?.value?.get(target) == control } == true },
		) { argument ->
			targetOf(argument)?.let { target -> editorSession?.setParameterControl(target, control) }
		}

	return commandTable(
		CommandTable.Parameter,
		Command(
			"parameter.toggleRepeat",
			title = Res.string.cmd_parameter_toggle_repeat,
			availability = availability.hasDocument,
			availableFor = { argument -> parameterOf(argument) != null },
		) { argument ->
			parameterOf(argument)?.let { parameter -> editorSession?.setParameterRepeat(parameter.id, !parameter.repeat) }
		},
		controlCommand("parameter.control.follow", Res.string.menu_parameter_control_follow, CommandFamily.ParameterControl, null, ::ownRowOf),
		controlCommand("parameter.control.slider", Res.string.menu_parameter_control_slider, CommandFamily.ParameterControl, ParameterControl.Slider, ::ownRowOf),
		controlCommand("parameter.control.dial", Res.string.menu_parameter_control_dial, CommandFamily.ParameterControl, ParameterControl.Dial, ::ownRowOf),
		controlCommand("parameter.pairControl.follow", Res.string.menu_parameter_control_follow, CommandFamily.PairControl, null, ::pairOf),
		controlCommand("parameter.pairControl.pad", Res.string.menu_parameter_pair_control_pad, CommandFamily.PairControl, ParameterControl.Slider, ::pairOf),
		controlCommand("parameter.pairControl.dials", Res.string.menu_parameter_pair_control_dials, CommandFamily.PairControl, ParameterControl.Dial, ::pairOf),
	)
}