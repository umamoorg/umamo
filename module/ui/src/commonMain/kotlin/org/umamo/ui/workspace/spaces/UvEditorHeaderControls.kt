package org.umamo.ui.workspace.spaces

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import org.umamo.edit.EditorMode
import org.umamo.edit.TransformPivotMode
import org.umamo.ui.kit.Text
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoTypography

/**
 * The UV editor's space-specific header strip (mounted via SpaceDescriptor.headerContent): the
 * vertex / edge / face select-mode buttons, the active mesh's name, the transform pivot dropdown, and
 * the proportional-editing controls - the shared EditHeaderControls.kt composables the 2D viewport's
 * header also mounts, so the two surfaces stay one behavior.  All of it drives the SHARED session
 * state (the selection and its select mode are one, Blender's UV sync selection): switching to face
 * mode here switches the viewport too, by design.  Everything but the pivot is Edit-mode only, since
 * the UV editor's Object-mode face is a read-only preview.
 *
 * UV エディタ固有のヘッダ内容。選択モードボタン・アクティブメッシュ名・ピボット・プロポーショナル
 * 編集は共有部品で、2D ビューポートと同じ挙動になる。
 */
@Composable
internal fun UvEditorHeaderControls() {
	val session = LocalEditorSession.current
	val enabled = session != null
	val editorMode = session?.mode?.collectAsState()?.value ?: EditorMode.Object
	val meshSelection = session?.meshSelection?.collectAsState()?.value
	val model = session?.model?.collectAsState()?.value
	val pivotMode = session?.pivotMode?.collectAsState()?.value ?: TransformPivotMode.MedianPoint
	val proportionalEdit = session?.proportionalEdit?.collectAsState()?.value
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
		if (editorMode == EditorMode.Edit && meshSelection != null) {
			MeshSelectModeButtons(selectMode = meshSelection.selectMode)
			// The active mesh's name, mirroring the viewport header: which drawable's mapping the
			// element clicks and operators land on.  A document name is user data, rendered verbatim.
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
		if (editorMode == EditorMode.Edit) {
			ProportionalEditControls(proportionalEdit = proportionalEdit)
		}
	}
}
