package org.umamo.ui.workspace.spaces

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.EditorMode
import org.umamo.edit.createParameter
import org.umamo.edit.createParameterGroup
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.RuntimeFeature
import org.umamo.ui.kit.BelowAnchorPositionProvider
import org.umamo.ui.kit.Checkbox
import org.umamo.ui.kit.DropdownChip
import org.umamo.ui.kit.FilterPopupChip
import org.umamo.ui.kit.FilterSectionLabel
import org.umamo.ui.kit.Menu
import org.umamo.ui.kit.MenuItem
import org.umamo.ui.kit.OverflowRowScope
import org.umamo.ui.kit.button.IconButton
import org.umamo.ui.kit.button.IconButtonAppearance
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalLiveParams
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.workspace.AreaScope

/**
 * The parameters panel's area-header controls (mounted via SpaceDescriptor.headerContent): the leading
 * Add Parameter dropdown and New Group button, then a flexible gap, then Reset All and the filter chip
 * at the header's end - so the panel body keeps its full height for the parameter list. Renders nothing
 * without an open document, matching the other header controls.
 *
 * These stay inline handlers rather than registry commands on purpose - adding a parameter, creating a
 * group, and resetting the pose are direct manipulation like the sliders themselves; a command becomes
 * warranted only when a shortcut or menu needs to reach it. Creating either opens it for inline rename
 * immediately, so the new id is parked on the shared [scope] view state (the header and body are
 * sibling subtrees).
 *
 * @param AreaScope scope The hosting area's scope carrying the panel's view state.
 */
internal fun OverflowRowScope.parametersHeaderControls(scope: AreaScope) {
	val viewState = scope.spaceState(PARAMETERS_VIEW_STATE_KEY) { ParametersViewState() }
	// Add Parameter and New Group ride one item: both create, they were always 4.dp apart rather than the
	// strip's 8.dp, and splitting a create pair across the strip and the overflow panel would read as noise.
	item("add") {
		if (LocalPuppet.current != null) {
			AddParameterChip(viewState)
		}
	}
	item("new") {
		if (LocalPuppet.current != null) {
			NewParameterGroupButton(viewState)
		}
	}
	flexibleSpace(minWidth = 8.dp)
	item("resetAll") {
		if (LocalPuppet.current != null) {
			ResetAllParametersButton()
		}
	}
	item("filter") {
		if (LocalPuppet.current != null) {
			ParametersFilterChip(viewState)
		}
	}
}

/**
 * The Add Parameter dropdown: the rigger picks the kind up front - a key-form (circle) or a blend-shape
 * (square) parameter.  Either creates a document edit and opens the new row for inline rename.  The full
 * add-ticks / keyform-capture workflow is not built yet.
 *
 * @param ParametersViewState viewState The panel's shared view state, which parks the id to rename.
 */
@Composable
private fun AddParameterChip(viewState: ParametersViewState) {
	val puppet = LocalPuppet.current ?: return
	val session = LocalEditorSession.current
	val defaultParameterName = stringResource(Res.string.parameter_default_name)
	var addMenuExpanded by remember { mutableStateOf(false) }
	val addKeyFormLabel = stringResource(Res.string.parameter_menu_add_keyform)
	val addBlendShapeLabel = stringResource(Res.string.parameter_menu_add_blendshape)
	DropdownChip(
		expanded = addMenuExpanded,
		onExpandRequest = { addMenuExpanded = true },
		contentDescription = stringResource(Res.string.parameter_menu_add),
		icon = LocalUmamoIcons.parameterAdd,
		enabled = session != null,
	) {
		Menu(
			items =
				listOfNotNull(
					MenuItem.Action(
						label = addKeyFormLabel,
						onSelect = {
							session?.let {
								viewState.renamingParameterId = it.createParameter(defaultParameterName, ParameterKind.NORMAL)
							}
						},
						enabled = session != null,
					),
					// Blend-shape parameters are a 4.2 feature; under an older runtime target the creation
					// entry disappears (existing blend-shape parameters keep working and rendering).
					if (!puppet.runtimeTarget.supports(RuntimeFeature.BlendShapeParameters)) {
						null
					} else {
						MenuItem.Action(
							label = addBlendShapeLabel,
							onSelect = {
								session?.let {
									viewState.renamingParameterId = it.createParameter(defaultParameterName, ParameterKind.BLEND_SHAPE)
								}
							},
							enabled = session != null,
						)
					},
				),
			onDismissRequest = { addMenuExpanded = false },
			positionProvider = BelowAnchorPositionProvider,
		)
	}
}

/**
 * The New Group button: creates an empty parameter group and opens it for inline rename.
 *
 * @param ParametersViewState viewState The panel's shared view state, which parks the id to rename.
 */
@Composable
private fun NewParameterGroupButton(viewState: ParametersViewState) {
	val session = LocalEditorSession.current
	val defaultGroupName = stringResource(Res.string.parameter_group_default_name)
	IconButton(
		icon = LocalUmamoIcons.groupAdd,
		onClick = { session?.let { viewState.renamingGroupId = it.createParameterGroup(defaultGroupName) } },
		contentDescription = stringResource(Res.string.parameter_new_group),
		appearance = IconButtonAppearance.Filled(LocalUmamoShapes.current.small),
	)
}

/**
 * Reset All: returns every parameter to its default in one undo step.
 *
 * The panel body gates every pose write on Edit mode (Edit mode is pinned to the neutral pose), and this
 * must replicate that lock or a locked panel becomes writable from the header.  Group create / delete /
 * rename are document edits rather than pose writes, so they are NOT gated.
 */
@Composable
private fun ResetAllParametersButton() {
	val puppet = LocalPuppet.current ?: return
	val liveParams = LocalLiveParams.current
	val session = LocalEditorSession.current
	val editorMode by remember(session) { session?.mode ?: MutableStateFlow(EditorMode.Object) }.collectAsState()
	IconButton(
		icon = LocalUmamoIcons.resetAll,
		onClick = {
			if (editorMode != EditorMode.Edit) {
				// The same two-phase shape as the panel's sliders: preview every default, then one
				// commit so the whole reset is a single undo step.
				puppet.parameters.forEach { parameter -> liveParams?.preview(parameter.id, parameter.default) }
				liveParams?.commit(puppet.parameters.map { it.id }.toSet())
			}
		},
		contentDescription = stringResource(Res.string.parameter_reset_all),
		appearance = IconButtonAppearance.Filled(LocalUmamoShapes.current.small),
	)
}

/**
 * The panel's view filters.  The same stay-open filter panel the outliner uses, rather than a bare toggle
 * button: this panel grows more view toggles as the keyform tracks land, and a shared chip keeps the two
 * headers from drifting apart.  The funnel glyph still reports the filtered / unfiltered state at a glance.
 *
 * @param ParametersViewState viewState The panel's shared view state.
 */
@Composable
private fun ParametersFilterChip(viewState: ParametersViewState) {
	FilterPopupChip(
		contentDescription = stringResource(Res.string.common_filters),
		icon = if (viewState.showOnlySelected) LocalUmamoIcons.filterFiltered else LocalUmamoIcons.filterUnfiltered,
	) {
		FilterSectionLabel(stringResource(Res.string.common_filters))
		Checkbox(
			checked = viewState.showOnlySelected,
			onCheckedChange = { checked -> viewState.showOnlySelected = checked },
			label = stringResource(Res.string.parameter_filter_selected),
		)
	}
}