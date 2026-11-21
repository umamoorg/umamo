package org.umamo.ui.workspace.commands

import org.umamo.edit.EditorSession
import org.umamo.edit.parameter.setParameterRepeat
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandTable
import org.umamo.ui.resources.*

/**
 * The commands a parameter's context menu lists: Toggle Repeat.  It runs with the parameter the menu was opened on as
 * its argument, so it grays by that parameter and is never offered where nothing aims it (the palette, a chord).  A
 * document edit rather than a pose write, so it runs in Edit mode as a range edit does.
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
	)
}