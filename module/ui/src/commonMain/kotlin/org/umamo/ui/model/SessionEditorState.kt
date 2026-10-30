package org.umamo.ui.model

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.PuppetModel

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
 * @property EditorSession session The session every selection gesture and mode change is routed to.
 * @property State selectionMirror The session's selection, mirrored as Compose state.
 * @property State modeMirror The session's mode, mirrored as Compose state.
 * @property State lastActive The item last made active, kept across a deselect, or null before any.
 */
class SessionEditorState internal constructor(
	private val session: EditorSession,
	private val selectionMirror: State<Selection>,
	private val modeMirror: State<EditorMode>,
	private val lastActive: State<SelectionTarget?>,
) : SelectionHandle, EditorModeHandle {
	override val selection: Selection get() = selectionMirror.value

	/**
	 * The item the one-item panels show, remembering the item last made active across a deselect, as
	 * Blender keeps showing the active object (see [shownTargetOf]).
	 *
	 * @param PuppetModel model The open document's model.
	 * @return SelectionTarget? The item to show, or null when there is none.
	 */
	override fun shownTarget(model: PuppetModel): SelectionTarget? = shownTargetOf(selection, lastActive.value, model)

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
	 * Routes a mode change to the session, which records it as its own undo step.
	 *
	 * @param EditorMode mode The new mode.
	 */
	override fun set(mode: EditorMode) {
		session.setMode(mode)
	}

	/**
	 * Routes the Edit toggle to the session, which remembers the mode Edit was entered from.
	 */
	override fun toggleEdit() {
		session.toggleEditMode()
	}

	/**
	 * Routes the Pose toggle to the session.
	 */
	override fun togglePose() {
		session.togglePoseMode()
	}
}

/**
 * Remembers a [SessionEditorState] over [session], collecting its selection and mode flows into Compose
 * state so the returned handle's reads are observable. Stable while [session] is, so providing it into
 * [LocalSelection] / [LocalEditorMode] does not thrash the composition.  It also remembers the item last
 * made active, from every selection the session publishes (undo and redo included), so the shown item
 * survives a deselect.
 *
 * @param EditorSession session The open document's session.
 * @return SessionEditorState The session-backed selection + mode handle.
 */
@Composable
fun rememberSessionEditorState(session: EditorSession): SessionEditorState {
	val selectionMirror = session.selection.collectAsState()
	val modeMirror = session.mode.collectAsState()
	val lastActive = remember(session) { mutableStateOf(session.selection.value.active) }
	LaunchedEffect(session) {
		session.selection.collect { selection ->
			selection.active?.let { active -> lastActive.value = active }
		}
	}

	return remember(session) { SessionEditorState(session, selectionMirror, modeMirror, lastActive) }
}