package org.umamo.ui.workspace.spaces

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.EditorMode
import org.umamo.edit.TransformPivotMode
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.action.LocalKeymap
import org.umamo.ui.action.formatAccelerator
import org.umamo.ui.kit.BelowAnchorPositionProvider
import org.umamo.ui.kit.DropdownChip
import org.umamo.ui.kit.Menu
import org.umamo.ui.kit.MenuItem
import org.umamo.ui.kit.Text
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoTypography

/**
 * The 2D viewport's space-specific header strip (mounted via SpaceDescriptor.headerContent): the
 * Object / Edit mode dropdown, the vertex / edge / face select-mode buttons (Edit mode), the transform
 * pivot dropdown, the snap menu, and the proportional-editing toggle with its falloff dropdown (Edit
 * mode).  With no open document the chips render disabled rather than vanishing, so the viewport
 * chrome reads the same before the first file opens.
 *
 * Every control observes the session's own flows and mutates only by dispatching registry commands,
 * per the everything-through-the-action-registry rule - a rebind or palette invocation stays
 * consistent with these controls for free.  The Edit-mode pieces (select modes, pivot, proportional)
 * are the shared EditHeaderControls.kt composables the UV editor's header mounts too.
 *
 * 2D ビューポート固有のヘッダ内容。モードドロップダウン、選択モードボタン、ピボット、スナップ、
 * プロポーショナル編集の切替と減衰。変更はすべてコマンドレジストリ経由。ドキュメント未オープン時は
 * 無効表示。
 */
@Composable
fun Viewport2DHeaderControls() {
	val session = LocalEditorSession.current
	val enabled = session != null
	val editorMode = session?.mode?.collectAsState()?.value ?: EditorMode.Object
	val meshSelection = session?.meshSelection?.collectAsState()?.value
	val model = session?.model?.collectAsState()?.value
	val pivotMode = session?.pivotMode?.collectAsState()?.value ?: TransformPivotMode.MedianPoint
	val proportionalEdit = session?.proportionalEdit?.collectAsState()?.value
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
		EditorModeDropdown(editorMode = editorMode, enabled = enabled)
		if (editorMode == EditorMode.Edit && meshSelection != null) {
			MeshSelectModeButtons(selectMode = meshSelection.selectMode)
			// The active mesh's name, so a multi-mesh session reads which drawable element clicks and
			// operators land on as the active element hops between meshes.  A document name is user
			// data, rendered verbatim (never localized).
			val activeName =
				meshSelection.activeDrawableId?.let { activeId ->
					model?.drawables?.firstOrNull { drawable -> drawable.id == activeId }?.name
				}
			if (activeName != null) {
				Text(
					text = activeName,
					style = LocalUmamoTypography.current.labelMedium,
					color = LocalUmamoColors.current.textMuted,
				)
			}
		}
		PivotModeDropdown(pivotMode = pivotMode, enabled = enabled)
		SnapDropdown(enabled = enabled)
		if (editorMode == EditorMode.Edit) {
			ProportionalEditControls(proportionalEdit = proportionalEdit)
		}
	}
}

/**
 * The editor-mode selector, Blender-style: the button face is the current mode's icon plus its label
 * plus a chevron that points right while the menu is closed and down while it is open.  Clicking opens
 * a two-row menu (Object Mode / Edit Mode, each icon + label + bound-shortcut hint); choosing a row
 * dispatches the matching set-mode command.  The chip anatomy is the shared kit [DropdownChip], the
 * same face as the area header's AreaTypeDropdown so the header chips read as one family.
 *
 * エディタモードセレクタ（Blender 式）。現在モードのアイコンとラベルとシェブロンを表示し、クリックで
 * モードメニューを開く。選択は mode.object / mode.edit コマンドを発行する。
 *
 * @param EditorMode editorMode The session's current interaction mode.
 * @param Boolean enabled Whether a document is open (disabled chrome otherwise).
 */
@Composable
private fun EditorModeDropdown(editorMode: EditorMode, enabled: Boolean) {
	val commands = LocalCommands.current
	val keymap = LocalKeymap.current
	val modeIcon =
		when (editorMode) {
			EditorMode.Object -> LocalUmamoIcons.editorModeObject
			EditorMode.Edit -> LocalUmamoIcons.editorModeEdit
		}
	// Resolved in composition because the chip's semantics lambda is not composable.
	val currentLabel =
		when (editorMode) {
			EditorMode.Object -> stringResource(Res.string.cmd_mode_object)
			EditorMode.Edit -> stringResource(Res.string.cmd_mode_edit)
		}
	var expanded by remember { mutableStateOf(false) }
	// One row per mode; the menu's own dismiss closes the popup, so onSelect need not toggle `expanded`.
	// Each row shows its own command's bound chord (none by default - a hint appears if the user binds one).
	val items =
		listOf(
			MenuItem.Action(
				label = stringResource(Res.string.cmd_mode_object),
				onSelect = { commands.invoke("mode.object") },
				shortcut = keymap.chordFor("mode.object")?.let { chord -> formatAccelerator(chord) },
				icon = LocalUmamoIcons.editorModeObject,
			),
			MenuItem.Action(
				label = stringResource(Res.string.cmd_mode_edit),
				onSelect = { commands.invoke("mode.edit") },
				shortcut = keymap.chordFor("mode.edit")?.let { chord -> formatAccelerator(chord) },
				icon = LocalUmamoIcons.editorModeEdit,
			),
		)
	DropdownChip(
		expanded = expanded,
		onExpandRequest = { expanded = true },
		contentDescription = currentLabel,
		icon = modeIcon,
		label = currentLabel,
		enabled = enabled,
	) {
		Menu(
			items = items,
			onDismissRequest = { expanded = false },
			positionProvider = BelowAnchorPositionProvider,
		)
	}
}

/**
 * The snap menu (Blender's Shift+S, as a header dropdown): an icon-only magnet chip whose rows
 * dispatch the eight snap commands - the cursor moves and the selection moves - so the pie and this
 * menu stay one behavior.
 *
 * スナップメニュー（Shift+S のヘッダ版）。各行はスナップコマンドを発行する。
 *
 * @param Boolean enabled Whether a document is open (disabled chrome otherwise).
 */
@Composable
private fun SnapDropdown(enabled: Boolean) {
	val commands = LocalCommands.current
	var expanded by remember { mutableStateOf(false) }
	val items =
		listOf(
			MenuItem.Action(label = stringResource(Res.string.cmd_snap_cursor_world_origin), onSelect = { commands.invoke("snap.cursorToWorldOrigin") }),
			MenuItem.Action(label = stringResource(Res.string.cmd_snap_cursor_grid), onSelect = { commands.invoke("snap.cursorToGrid") }),
			MenuItem.Action(label = stringResource(Res.string.cmd_snap_cursor_selected), onSelect = { commands.invoke("snap.cursorToSelected") }),
			MenuItem.Action(label = stringResource(Res.string.cmd_snap_cursor_active), onSelect = { commands.invoke("snap.cursorToActive") }),
			MenuItem.Action(label = stringResource(Res.string.cmd_snap_selection_grid), onSelect = { commands.invoke("snap.selectionToGrid") }),
			MenuItem.Action(label = stringResource(Res.string.cmd_snap_selection_cursor), onSelect = { commands.invoke("snap.selectionToCursor") }),
			MenuItem.Action(label = stringResource(Res.string.cmd_snap_selection_cursor_offset), onSelect = { commands.invoke("snap.selectionToCursorOffset") }),
			MenuItem.Action(label = stringResource(Res.string.cmd_snap_selection_active), onSelect = { commands.invoke("snap.selectionToActive") }),
		)
	DropdownChip(
		expanded = expanded,
		onExpandRequest = { expanded = true },
		contentDescription = stringResource(Res.string.cmd_snap_pie),
		icon = LocalUmamoIcons.snap,
		enabled = enabled,
	) {
		Menu(
			items = items,
			onDismissRequest = { expanded = false },
			positionProvider = BelowAnchorPositionProvider,
		)
	}
}
