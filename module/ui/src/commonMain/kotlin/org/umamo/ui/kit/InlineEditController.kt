package org.umamo.ui.kit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * A coordination seam between a transient inline text editor (today: renaming a workspace tab) and a
 * host that runs its own keyboard dispatch.  A bare text field cannot defend itself here: the editor
 * lives inside the editor shell, whose root key handler previews every keystroke before the focused
 * field sees it, so the shell would claim Space (palette), letters (file commands), and Escape while the
 * user is merely typing a name.  While an editor is open it parks its cancel callback here; the host
 * checks this first and, finding it non-null, routes Escape to the cancel and lets every other key fall
 * through to the field instead of running its own shortcuts.  Holds null whenever no inline editor is
 * open.  Only the editor should write it.
 *
 * インライン編集（ワークスペース名の変更）とホストのキー処理をつなぐ仲介。編集中だけキャンセル関数を
 * 預け、ホストは Escape をそこへ回し、他のキーはフィールドに通す（自前のショートカットを抑止する）。
 *
 * @property Function cancel Cancels the open inline editor, or null when none is open.
 */
class InlineEditController {
	var cancel: (() -> Unit)? by mutableStateOf(null)
}

/**
 * Supplies the [InlineEditController] a host shares with the inline editors nested under it.  Defaults to
 * a standalone instance so an editor used without a coordinating host still functions (it falls back to
 * its own focus-loss commit and to the field's own key handling).
 */
val LocalInlineEditController = staticCompositionLocalOf { InlineEditController() }
