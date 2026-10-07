package org.umamo.ui.workspace.spaces.parameters

import androidx.compose.runtime.Composable
import org.umamo.edit.EditorSession
import org.umamo.edit.createParameter
import org.umamo.edit.createParameterGroup
import org.umamo.edit.deleteParameter
import org.umamo.edit.deleteParameterGroup
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.RuntimeFeature
import org.umamo.runtime.model.RuntimeTarget
import org.umamo.ui.kit.menu.MenuItem

/*
 * The panel's menus, as lists of entries.  Every place that offers an entry - a row's context menu, the
 * panel background's, the header's Add Parameter dropdown - builds it here, so an entry means the same
 * thing wherever it shows.
 *
 * The builders are composable for the sake of their callbacks: a lambda written in composition is kept
 * across recompositions while what it captures is unchanged.
 */

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
 * A slider row's context menu: Rename and Delete Parameter, then the entries that create.
 *
 * @param ParameterId parameterId The parameter the menu acts on.
 * @param ParameterLabels labels The panel's localized chrome.
 * @param EditorSession? session The editing session, or null with none, which disables the entries.
 * @param ParametersViewState viewState The panel's view state, which holds the rename target.
 * @param List<MenuItem> createMenuItems The entries that create, which every parameter menu ends with.
 * @return List<MenuItem> The entries, in menu order.
 */
@Composable
internal fun parameterSliderMenuItems(
	parameterId: ParameterId,
	labels: ParameterLabels,
	session: EditorSession?,
	viewState: ParametersViewState,
	createMenuItems: List<MenuItem>,
): List<MenuItem> =
	listOf(
		MenuItem.Action(
			label = labels.rename,
			onSelect = { viewState.renamingParameterId = parameterId },
			enabled = session != null,
		),
		MenuItem.Action(
			label = labels.deleteParameter,
			onSelect = { session?.deleteParameter(parameterId) },
			enabled = session != null,
		),
		MenuItem.Separator,
	) + createMenuItems

/**
 * A pad row's context menu.  A pad holds two axes, so Rename and Delete Parameter each open a submenu
 * that asks which one; the axis names in it are user data, never localized.
 *
 * @param Parameter horizontal The pad's X-axis parameter.
 * @param Parameter vertical The pad's Y-axis parameter.
 * @param ParameterLabels labels The panel's localized chrome.
 * @param EditorSession? session The editing session, or null with none, which disables the entries.
 * @param ParametersViewState viewState The panel's view state, which holds the rename target.
 * @param List<MenuItem> createMenuItems The entries that create, which every parameter menu ends with.
 * @return List<MenuItem> The entries, in menu order.
 */
@Composable
internal fun parameterPadMenuItems(
	horizontal: Parameter,
	vertical: Parameter,
	labels: ParameterLabels,
	session: EditorSession?,
	viewState: ParametersViewState,
	createMenuItems: List<MenuItem>,
): List<MenuItem> =
	listOf(
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
	) + createMenuItems