package org.umamo.ui.workspace.spaces

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.EditorMode
import org.umamo.edit.createParameter
import org.umamo.edit.createParameterGroup
import org.umamo.runtime.model.ParameterKind
import org.umamo.ui.kit.BelowAnchorPositionProvider
import org.umamo.ui.kit.DropdownChip
import org.umamo.ui.kit.Menu
import org.umamo.ui.kit.MenuItem
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
 * The parameters panel's area-header controls (mounted via SpaceDescriptor.headerContent): a leading
 * New Group button, then a flexible gap, then the Reset All button at the header's end - so the panel
 * body keeps its full height for the parameter list. Renders nothing without an open document, matching
 * the other header controls.
 *
 * Both stay inline handlers rather than registry commands on purpose - creating a group and resetting
 * the pose are direct manipulation like the sliders themselves; a command becomes warranted only when a
 * shortcut or menu needs to reach it. Creating a group opens it for inline rename immediately, so the
 * new group id is parked on the shared [scope] view state (the header and body are sibling subtrees).
 *
 * パラメータパネルのエリアヘッダ内容。左に「新規グループ」、右に「すべて既定値に」。ドキュメント未オープン
 * 時は何も描画しない。
 *
 * @param AreaScope scope The hosting area's scope carrying the panel's view state.
 */
@Composable
internal fun RowScope.ParametersHeaderControls(scope: AreaScope) {
	val puppet = LocalPuppet.current
	if (puppet == null) {
		return
	}
	val liveParams = LocalLiveParams.current
	val session = LocalEditorSession.current
	val viewState = scope.spaceState(PARAMETERS_VIEW_STATE_KEY) { ParametersViewState() }
	val defaultGroupName = stringResource(Res.string.parameter_group_default_name)
	val defaultParameterName = stringResource(Res.string.parameter_default_name)
	// The panel body gates every pose write on Edit mode (Edit mode is pinned to the neutral pose);
	// this header's Reset All must replicate that lock or a locked panel becomes writable from up here.
	// Group create / delete / rename are document edits, not pose writes, so they are NOT gated.
	val editorMode by remember(session) { session?.mode ?: MutableStateFlow(EditorMode.Object) }.collectAsState()
	// Add Parameter is a dropdown so the rigger picks the kind up front: a key-form (circle) or a
	// blend-shape (square) parameter.  Both create a document edit and open the row for inline rename,
	// exactly as the single button did.  The full add-ticks / keyform-capture workflow is not built yet.
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
				listOf(
					MenuItem.Action(
						label = addKeyFormLabel,
						onSelect = {
							session?.let {
								viewState.renamingParameterId = it.createParameter(defaultParameterName, ParameterKind.NORMAL)
							}
						},
						enabled = session != null,
					),
					MenuItem.Action(
						label = addBlendShapeLabel,
						onSelect = {
							session?.let {
								viewState.renamingParameterId = it.createParameter(defaultParameterName, ParameterKind.BLEND_SHAPE)
							}
						},
						enabled = session != null,
					),
				),
			onDismissRequest = { addMenuExpanded = false },
			positionProvider = BelowAnchorPositionProvider,
		)
	}
	Spacer(modifier = Modifier.width(4.dp))
	IconButton(
		icon = LocalUmamoIcons.groupAdd,
		onClick = { session?.let { viewState.renamingGroupId = it.createParameterGroup(defaultGroupName) } },
		contentDescription = stringResource(Res.string.parameter_new_group),
		appearance = IconButtonAppearance.Filled(LocalUmamoShapes.current.small),
	)
	Spacer(modifier = Modifier.weight(1f))
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
	Spacer(modifier = Modifier.width(4.dp))
	// A toggle that restricts the list to the selected object's effective parameters; lit (accent) when on.
	IconButton(
		icon = if (viewState.showOnlySelected) LocalUmamoIcons.filterFiltered else LocalUmamoIcons.filterUnfiltered,
		onClick = { viewState.showOnlySelected = !viewState.showOnlySelected },
		contentDescription = stringResource(Res.string.parameter_filter_selected),
		active = viewState.showOnlySelected,
		appearance = IconButtonAppearance.Filled(LocalUmamoShapes.current.small),
	)
	Spacer(modifier = Modifier.width(4.dp))
}
