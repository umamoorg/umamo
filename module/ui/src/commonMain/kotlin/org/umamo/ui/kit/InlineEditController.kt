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
 * The same seam answers the other question a host has about text entry: whether the press it is
 * watching landed on the editor itself.  A press anywhere else ends text entry (the shell hands the
 * keyboard back to its root), and only the editor's own node can say that the press was meant for it.
 *
 * @property Function cancel Cancels the open inline editor, or null when none is open.
 * @property Boolean pressLandedOnTextEditor Whether the press being dispatched hit a text editor.
 */
class InlineEditController {
	var cancel: (() -> Unit)? by mutableStateOf(null)

	/**
	 * Whether the press currently being dispatched landed on a live text editor's own node.  The host
	 * clears this on the press's Initial pass (an ancestor sees that pass before any descendant), each
	 * text editor sets it on its own Initial pass, and the host reads it on the Final pass, after every
	 * descendant has handled the press.  One synchronous dispatch on one thread, so the flag never has to
	 * tell one press from the next and needs no token.
	 *
	 * A plain var, deliberately, where [cancel] is snapshot state: nothing displays this, and making it
	 * observable would recompose the whole shell twice on every mouse press.
	 */
	var pressLandedOnTextEditor: Boolean = false
}

/**
 * Supplies the [InlineEditController] a host shares with the inline editors nested under it.  Defaults to
 * a standalone instance so an editor used without a coordinating host still functions (it falls back to
 * its own focus-loss commit and to the field's own key handling).
 */
val LocalInlineEditController = staticCompositionLocalOf { InlineEditController() }