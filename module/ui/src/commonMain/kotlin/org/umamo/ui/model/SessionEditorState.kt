package org.umamo.ui.model

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.Selection

/**
 * Backs the common [SelectionHandle] and [EditorModeHandle] with an [EditorSession], so every selection
 * gesture and mode toggle the panels make flows through the session — which records selection as an undo
 * step (the chosen Blender-faithful granularity) and republishes through its flows. Reads go through
 * Compose [State] mirrors of the session's flows, so panels recompose on change, including when undo /
 * redo restore a prior selection.
 *
 * Keeps :edit free of Compose (its module mandate): the session exposes coroutines flows, and this thin
 * :ui adapter is the only place they become Compose-observable state. Writes are one-way to the session,
 * which is the single source of truth; the mirrors only follow it.
 *
 * 共通の選択/モードハンドルを EditorSession に接続する :ui 側アダプタ。読みは Compose State ミラー、
 * 書きはセッションへ一方向（取り消しに記録される）。
 */
class SessionEditorState internal constructor(
	private val session: EditorSession,
	private val selectionMirror: State<Selection>,
	private val modeMirror: State<EditorMode>,
) : SelectionHandle, EditorModeHandle {
	override val selection: Selection get() = selectionMirror.value

	/**
	 * Routes a selection gesture to the session, which records it as an undo step.
	 *
	 * @param Selection selection The new selection.
	 */
	override fun set(selection: Selection) {
		session.setSelection(selection)
	}

	override val mode: EditorMode get() = modeMirror.value

	/**
	 * Routes a mode change to the session (transient — not an undo step).
	 *
	 * @param EditorMode mode The new mode.
	 */
	override fun set(mode: EditorMode) {
		session.setMode(mode)
	}
}

/**
 * Remembers a [SessionEditorState] over [session], collecting its selection and mode flows into Compose
 * state so the returned handle's reads are observable. Stable while [session] is, so providing it into
 * [LocalSelection] / [LocalEditorMode] does not thrash the composition.
 *
 * @param EditorSession session The open document's session.
 * @return SessionEditorState The session-backed selection + mode handle.
 */
@Composable
fun rememberSessionEditorState(session: EditorSession): SessionEditorState {
	val selectionMirror = session.selection.collectAsState()
	val modeMirror = session.mode.collectAsState()
	return remember(session) { SessionEditorState(session, selectionMirror, modeMirror) }
}
