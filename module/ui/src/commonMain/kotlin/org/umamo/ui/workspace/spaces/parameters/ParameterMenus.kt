package org.umamo.ui.workspace.spaces.parameters

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.EditorSession
import org.umamo.edit.parameter.createParameter
import org.umamo.edit.parameter.createParameterGroup
import org.umamo.edit.parameter.deleteParameter
import org.umamo.edit.parameter.deleteParameterGroup
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.RuntimeFeature
import org.umamo.runtime.model.RuntimeTarget
import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandFamily
import org.umamo.ui.action.CommandMenuRow
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.Keymap
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.action.LocalKeymap
import org.umamo.ui.action.commandMenuItem
import org.umamo.ui.action.rememberCommandMenu
import org.umamo.ui.kit.menu.MenuItem

/*
 * The panel's menus, as lists of entries.  Every place that offers an entry - a row's context menu, the
 * panel background's, the header's Add Parameter dropdown - builds it here, so an entry means the same
 * thing wherever it shows.
 *
 * The builders are composable for the sake of their callbacks: a lambda written in composition is kept
 * across recompositions while what it captures is unchanged.  A row's menu holds a command's row, whose
 * grayed state is a live query, so it is handed back as the builder its menu calls as it opens.
 */

/** The command a parameter menu's Toggle Repeat entry runs, with the parameter as its argument. */
private const val TOGGLE_REPEAT_COMMAND = "parameter.toggleRepeat"

/**
 * What a parameter menu builds its Toggle Repeat rows from: the command and its title, resolved in composition,
 * and the registry and keymap a row dispatches through.
 *
 * @property Command command The registered Toggle Repeat command.
 * @property String title The command's resolved title.
 * @property CommandRegistry commands The registry a row dispatches through.
 * @property Keymap keymap The keymap a row's accelerator is read from.
 */
private class ToggleRepeatEntry(
	val command: Command,
	val title: String,
	private val commands: CommandRegistry,
	private val keymap: Keymap,
) {
	/**
	 * The command's row run on [parameterId], grayed as the command is for it when this is called.
	 *
	 * @param ParameterId parameterId The parameter the row toggles.
	 * @param String label The row's label: the title, or an axis name in a pad's submenu.
	 * @return MenuItem The row.
	 */
	fun rowFor(parameterId: ParameterId, label: String = title): MenuItem = commandMenuItem(command, label, commands, keymap, argument = parameterId)
}

/**
 * The Toggle Repeat entry as this composition resolves it.
 *
 * @return ToggleRepeatEntry? The entry, or null while the command is not registered.
 */
@Composable
private fun toggleRepeatEntry(): ToggleRepeatEntry? {
	val commands = LocalCommands.current
	val keymap = LocalKeymap.current
	val command = commands[TOGGLE_REPEAT_COMMAND] ?: return null
	val title = command.title?.let { resource -> stringResource(resource) } ?: command.id

	return ToggleRepeatEntry(command, title, commands, keymap)
}

/**
 * Creates a parameter and opens its name for inline rename.
 *
 * @param EditorSession? session The editing session, or null with none, which creates nothing.
 * @param ParametersViewState viewState The panel's view state, which holds the rename target.
 * @param String defaultName The name the parameter starts with.
 * @param ParameterKind kind Whether it is a key-form or a blend-shape parameter.
 */
internal fun createParameterForRename(
	session: EditorSession?,
	viewState: ParametersViewState,
	defaultName: String,
	kind: ParameterKind,
) {
	session?.let { viewState.renamingParameterId = it.createParameter(defaultName, kind) }
}

/**
 * Creates an empty parameter group and opens its name for inline rename.
 *
 * @param EditorSession? session The editing session, or null with none, which creates nothing.
 * @param ParametersViewState viewState The panel's view state, which holds the rename target.
 * @param String defaultName The name the group starts with.
 */
internal fun createParameterGroupForRename(session: EditorSession?, viewState: ParametersViewState, defaultName: String) {
	session?.let { viewState.renamingGroupId = it.createParameterGroup(defaultName) }
}

/**
 * The entries that create a parameter: Add Key Form Parameter, and Add Blend Shape Parameter where the
 * runtime target has them.
 *
 * The gate lives here so no creation path can bypass it.  Blend-shape parameters are a 4.2 feature;
 * under an older runtime target the entry disappears, while the blend-shape parameters a document
 * already holds keep working and rendering.
 *
 * @param ParameterLabels labels The panel's localized chrome.
 * @param RuntimeTarget runtimeTarget The document's runtime target, which decides what may be created.
 * @param EditorSession? session The editing session, or null with none, which disables the entries.
 * @param ParametersViewState viewState The panel's view state, which holds the rename target.
 * @return List<MenuItem> The entries, in menu order.
 */
@Composable
internal fun createParameterMenuItems(
	labels: ParameterLabels,
	runtimeTarget: RuntimeTarget,
	session: EditorSession?,
	viewState: ParametersViewState,
): List<MenuItem> =
	listOfNotNull(
		MenuItem.Action(
			label = labels.addKeyFormParameter,
			onSelect = { createParameterForRename(session, viewState, labels.defaultParameterName, ParameterKind.NORMAL) },
			enabled = session != null,
		),
		if (!runtimeTarget.supports(RuntimeFeature.BlendShapeParameters)) {
			null
		} else {
			MenuItem.Action(
				label = labels.addBlendShapeParameter,
				onSelect = { createParameterForRename(session, viewState, labels.defaultParameterName, ParameterKind.BLEND_SHAPE) },
				enabled = session != null,
			)
		},
	)

/**
 * The New Group entry.
 *
 * @param ParameterLabels labels The panel's localized chrome.
 * @param EditorSession? session The editing session, or null with none, which disables the entry.
 * @param ParametersViewState viewState The panel's view state, which holds the rename target.
 * @return MenuItem The entry.
 */
@Composable
internal fun newParameterGroupMenuItem(labels: ParameterLabels, session: EditorSession?, viewState: ParametersViewState): MenuItem =
	MenuItem.Action(
		label = labels.newGroup,
		onSelect = { createParameterGroupForRename(session, viewState, labels.defaultGroupName) },
		enabled = session != null,
	)

/**
 * A group header's context menu: New Group, Rename, and Delete Group.
 *
 * @param ParameterGroupId groupId The group the menu acts on.
 * @param ParameterLabels labels The panel's localized chrome.
 * @param EditorSession? session The editing session, or null with none, which disables the entries.
 * @param ParametersViewState viewState The panel's view state, which holds the rename target.
 * @return List<MenuItem> The entries, in menu order.
 */
@Composable
internal fun parameterGroupMenuItems(
	groupId: ParameterGroupId,
	labels: ParameterLabels,
	session: EditorSession?,
	viewState: ParametersViewState,
): List<MenuItem> =
	listOf(
		newParameterGroupMenuItem(labels, session, viewState),
		MenuItem.Action(
			label = labels.rename,
			onSelect = { viewState.renamingGroupId = groupId },
			enabled = session != null,
		),
		MenuItem.Separator,
		MenuItem.Action(
			label = labels.deleteGroup,
			onSelect = { session?.deleteParameterGroup(groupId) },
			enabled = session != null,
		),
	)

/**
 * A slider row's context menu: Rename, Toggle Repeat, the Control submenu, and Delete Parameter, then the entries that
 * create.
 *
 * @param Parameter parameter The parameter the menu acts on.
 * @param ParameterLabels labels The panel's localized chrome.
 * @param EditorSession? session The editing session, or null with none, which disables the entries.
 * @param ParametersViewState viewState The panel's view state, which holds the rename target.
 * @param List<MenuItem> createMenuItems The entries that create, which every parameter menu ends with.
 * @return Function The builder the menu calls as it opens: the entries, in menu order.
 */
@Composable
internal fun parameterSliderMenuItems(
	parameter: Parameter,
	labels: ParameterLabels,
	session: EditorSession?,
	viewState: ParametersViewState,
	createMenuItems: List<MenuItem>,
): () -> List<MenuItem> {
	val parameterId = parameter.id
	val toggleRepeat = toggleRepeatEntry()
	val control = rememberCommandMenu(remember(parameterId) { listOf(CommandMenuRow.Family(CommandFamily.ParameterControl, argument = parameterId)) })

	return {
		buildList {
			addAll(
				listOfNotNull(
					MenuItem.Action(
						label = labels.rename,
						onSelect = { viewState.renamingParameterId = parameterId },
						enabled = session != null,
					),
					toggleRepeat?.rowFor(parameterId),
				),
			)
			addAll(control())
			addAll(
				listOf(
					MenuItem.Action(
						label = labels.deleteParameter,
						onSelect = { session?.deleteParameter(parameterId) },
						enabled = session != null,
					),
					MenuItem.Separator,
				),
			)
			addAll(createMenuItems)
		}
	}
}

/**
 * A pad row's context menu.  A pad holds two axes, so Rename, Toggle Repeat, and Delete Parameter each open a
 * submenu that asks which one; the axis names in it are user data, never localized.  The Control submenu between
 * them is the pair's, run with its horizontal member.
 *
 * @param Parameter horizontal The pad's X-axis parameter.
 * @param Parameter vertical The pad's Y-axis parameter.
 * @param ParameterLabels labels The panel's localized chrome.
 * @param EditorSession? session The editing session, or null with none, which disables the entries.
 * @param ParametersViewState viewState The panel's view state, which holds the rename target.
 * @param List<MenuItem> createMenuItems The entries that create, which every parameter menu ends with.
 * @return Function The builder the menu calls as it opens: the entries, in menu order.
 */
@Composable
internal fun parameterPadMenuItems(
	horizontal: Parameter,
	vertical: Parameter,
	labels: ParameterLabels,
	session: EditorSession?,
	viewState: ParametersViewState,
	createMenuItems: List<MenuItem>,
): () -> List<MenuItem> {
	val toggleRepeat = toggleRepeatEntry()
	val horizontalId = horizontal.id
	val control = rememberCommandMenu(remember(horizontalId) { listOf(CommandMenuRow.Family(CommandFamily.PairControl, argument = horizontalId)) })

	return {
		buildList {
			addAll(
				listOfNotNull(
					MenuItem.Submenu(
						label = labels.rename,
						items =
							listOf(
								MenuItem.Action(
									horizontal.name,
									onSelect = { viewState.renamingParameterId = horizontal.id },
									enabled = session != null,
								),
								MenuItem.Action(
									vertical.name,
									onSelect = { viewState.renamingParameterId = vertical.id },
									enabled = session != null,
								),
							),
						enabled = session != null,
					),
					toggleRepeat?.let { entry ->
						val rows = listOf(horizontal, vertical).map { axis -> entry.rowFor(axis.id, axis.name) }

						MenuItem.Submenu(label = entry.title, items = rows, enabled = rows.any { row -> (row as? MenuItem.Action)?.enabled == true })
					},
				),
			)
			addAll(control())
			addAll(
				listOf(
					MenuItem.Submenu(
						label = labels.deleteParameter,
						items =
							listOf(
								MenuItem.Action(
									horizontal.name,
									onSelect = { session?.deleteParameter(horizontal.id) },
									enabled = session != null,
								),
								MenuItem.Action(
									vertical.name,
									onSelect = { session?.deleteParameter(vertical.id) },
									enabled = session != null,
								),
							),
						enabled = session != null,
					),
					MenuItem.Separator,
				),
			)
			addAll(createMenuItems)
		}
	}
}