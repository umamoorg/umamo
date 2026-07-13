package org.umamo.ui.kit

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue

/**
 * A single-line inline rename text field, the shared core behind every in-place rename in the editor
 * (the workspace tab and the outliner row).  The whole name starts selected so typing replaces it; it
 * commits on Enter or when focus leaves (clicking away), and cancels on Escape.  Escape is routed in by
 * the host through [LocalInlineEditController], because the editor shell's root key handler previews
 * Escape (and every shortcut) before this field could see it.  A blank name commits as a cancel, so the
 * entity keeps its old name.  The caller supplies the surrounding styling (a tab fill, a tree row); this
 * owns only the field and its commit/cancel lifecycle.
 *
 * 共通のインライン名前編集フィールド（タブとアウトライナーで再利用）。Enter／フォーカス喪失で確定、
 * Escape で取消（シェル経由）。空名は取消扱い。装飾は呼び出し側が与える。
 *
 * @param String initialName The current name, pre-selected for replacement.
 * @param TextStyle textStyle The text style (the caller supplies the colour to match its context).
 * @param Color cursorColor The text-cursor colour.
 * @param Function onCommit Called with the trimmed new name when the edit is confirmed (never blank).
 * @param Function onCancel Called when the edit is abandoned (Escape or a blank name).
 * @param Modifier modifier The layout modifier for the field.
 */
@Composable
fun InlineRenameField(
	initialName: String,
	textStyle: TextStyle,
	cursorColor: Color,
	onCommit: (String) -> Unit,
	onCancel: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val controller = LocalInlineEditController.current
	val focusRequester = remember { FocusRequester() }
	var fieldValue by remember {
		mutableStateOf(TextFieldValue(initialName, selection = TextRange(0, initialName.length)))
	}
	// Guards against resolving twice: e.g. Enter commits, then the editor's disposal fires onFocusChanged,
	// which would otherwise commit again.
	var resolved by remember { mutableStateOf(false) }
	var hasFocused by remember { mutableStateOf(false) }

	fun commit() {
		if (resolved) {
			return
		}
		resolved = true
		val trimmed = fieldValue.text.trim()
		if (trimmed.isEmpty()) {
			onCancel()
		} else {
			onCommit(trimmed)
		}
	}

	fun cancel() {
		if (resolved) {
			return
		}
		resolved = true
		onCancel()
	}

	// While mounted, lend the shell this editor's cancel so it can route Escape here (it intercepts Escape
	// before the field would receive it); clear the hook on dispose.
	DisposableEffect(Unit) {
		controller.cancel = { cancel() }
		onDispose { controller.cancel = null }
	}
	LaunchedEffect(Unit) {
		focusRequester.requestFocus()
	}

	BasicTextField(
		value = fieldValue,
		onValueChange = { newValue -> fieldValue = newValue },
		singleLine = true,
		textStyle = textStyle,
		cursorBrush = SolidColor(cursorColor),
		keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
		keyboardActions = KeyboardActions(onDone = { commit() }),
		modifier =
			modifier
				.focusRequester(focusRequester)
				.onFocusChanged { focusState ->
					// hasFocus, not isFocused: BasicTextField focuses an internal child node, so this node is
					// never itself focused - only its subtree is.  isFocused would never fire, and the click-away
					// commit below would never run.
					if (focusState.hasFocus) {
						hasFocused = true
					} else if (hasFocused) {
						// Clicking away commits, mirroring renaming a file in an explorer.
						commit()
					}
				}
				.onKeyEvent { event ->
					// Hardware Enter confirms (the soft-keyboard Done action is wired above).  Escape is left for
					// the shell to route into cancel(), so it is deliberately not handled here.
					if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
						commit()
						true
					} else {
						false
					}
				},
	)
}
