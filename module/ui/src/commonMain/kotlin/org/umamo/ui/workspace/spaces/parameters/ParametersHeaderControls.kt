package org.umamo.ui.workspace.spaces.parameters

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.kit.BelowAnchorPositionProvider
import org.umamo.ui.kit.button.IconButton
import org.umamo.ui.kit.button.IconButtonAppearance
import org.umamo.ui.kit.chip.DropdownChip
import org.umamo.ui.kit.chip.FilterPopupChip
import org.umamo.ui.kit.chip.FilterSectionLabel
import org.umamo.ui.kit.container.OverflowRowScope
import org.umamo.ui.kit.field.Checkbox
import org.umamo.ui.kit.field.SEARCH_FIELD_MIN_WIDTH
import org.umamo.ui.kit.field.SearchField
import org.umamo.ui.kit.menu.Menu
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalLiveParams
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.model.documentIsOpen
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.workspace.AreaScope

/**
 * The parameters panel's area-header controls, which keep the panel body's full height for the parameter list.
 *
 * These stay inline handlers rather than registry commands on purpose: adding a parameter, creating a group,
 * and resetting the pose are direct manipulation like the sliders themselves.  A created item opens for inline
 * rename, so its id is parked on the shared [scope] view state, the header and body being sibling subtrees.
 *
 * @param AreaScope scope The hosting area's scope carrying the panel's view state.
 */
internal fun OverflowRowScope.parametersHeaderControls(scope: AreaScope) {
	val viewState = scope.spaceState(PARAMETERS_VIEW_STATE_KEY) { ParametersViewState() }
	// Add Parameter and New Group are items of their own, like every control on the strip, so a strip too
	// narrow for them folds each into the overflow panel on its own.
	// I know this looks like duplicate `documentIsOpen()`, but it is not.  They are still added, but only show if a document is open.
	item("add") {
		if (documentIsOpen()) {
			AddParameterChip(viewState)
		}
	}
	item("new") {
		if (documentIsOpen()) {
			NewParameterGroupButton(viewState)
		}
	}
	flexibleSpace()
	item("search", minWidth = SEARCH_FIELD_MIN_WIDTH) {
		if (documentIsOpen()) {
			SearchField(value = viewState.query, onValueChange = { newQuery -> viewState.query = newQuery })
		}
	}
	flexibleSpace()
	item("resetAll") {
		if (documentIsOpen()) {
			ResetAllParametersButton()
		}
	}
	item("filter") {
		if (documentIsOpen()) {
			ParametersFilterChip(viewState)
		}
	}
}

/**
 * The Add Parameter dropdown: the rigger picks the kind up front, a keyform (circle) or a blend-shape (square)
 * parameter, and the new row opens for inline rename.
 *
 * Its entries are the ones every parameter menu in the panel body ends with, from the same builder, so
 * what the runtime target hides here it hides there.
 *
 * @param ParametersViewState viewState The panel's shared view state, which parks the id to rename.
 */
@Composable
private fun AddParameterChip(viewState: ParametersViewState) {
	val puppet = LocalPuppet.current ?: return
	val session = LocalEditorSession.current
	val labels = parameterLabels()
	var addMenuExpanded by remember { mutableStateOf(false) }
	DropdownChip(
		expanded = addMenuExpanded,
		onExpandRequest = { addMenuExpanded = true },
		contentDescription = stringResource(Res.string.parameter_menu_add),
		icon = LocalUmamoIcons.parameterAdd,
		enabled = session != null,
	) {
		Menu(
			items = createParameterMenuItems(labels, puppet.runtimeTarget, session, viewState),
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
	val labels = parameterLabels()
	IconButton(
		icon = LocalUmamoIcons.groupAdd,
		onClick = { createParameterGroupForRename(session, viewState, labels.defaultGroupName) },
		contentDescription = labels.newGroup,
		appearance = IconButtonAppearance.Filled(LocalUmamoShapes.current.small),
	)
}

/**
 * Reset All: returns every parameter to its default in one undo step.
 *
 * A pose write, so it goes through the same writer class as the body's sliders and Edit mode refuses it
 * with them.  The header and the body are sibling subtrees, so this holds a writer of its own, echoing into
 * no displayed values; the body's sliders follow the reset through the pose.
 */
@Composable
private fun ResetAllParametersButton() {
	val puppet = LocalPuppet.current ?: return
	val poseWriter = rememberParameterPoseWriter(LocalLiveParams.current, LocalEditorSession.current)
	IconButton(
		icon = LocalUmamoIcons.resetAll,
		onClick = { poseWriter.resetAll(puppet.parameters) },
		contentDescription = stringResource(Res.string.parameter_reset_all),
		appearance = IconButtonAppearance.Filled(LocalUmamoShapes.current.small),
	)
}

/**
 * The panel's view filters, in the same stay-open filter panel the outliner uses rather than a bare toggle
 * button, so the two headers do not drift apart as view toggles are added.
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