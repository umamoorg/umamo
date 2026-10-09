package org.umamo.ui.workspace.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalWindowInfo
import kotlinx.coroutines.flow.drop

/**
 * Keeps the keyboard on the shell root: takes root focus back after every transition that can leave it
 * null, a text editor closing inside an open overlay included, whenever the window regains OS focus, and
 * after a language switch.
 *
 * The shell calls this outside its locale key, so a language switch restarts none of the effects.
 *
 * @param ShellControllers controllers The shell's controllers: the root's focus and the states that trigger
 *   a reclaim.
 * @param String           languageTag The active UI language, whose every switch rebuilds the shell's content.
 */
@Composable
internal fun ReclaimShellFocus(controllers: ShellControllers, languageTag: String) {
	val workspaces = controllers.workspaces
	val overlays = controllers.overlays
	val inlineEditController = controllers.inlineEditController
	val menuBarController = controllers.menuBarController
	val focusRequester = controllers.focusRequester
	// THE focus-reclaim effect.  Compose leaves focus null whenever the focused node leaves composition
	// (a join/split disposing the focused leaf, a closing overlay/popup/menu/inline editor taking its
	// field along), and a null focus silently kills every keyboard shortcut until the next click - so the
	// root must reclaim focus after each such transition.  One effect keyed on every reclaim trigger:
	//  - structuralEditCount: area-tree edits and popup-invoked workspace CRUD;
	//  - selfFocusedOverlayOpen / inline edit: reclaim when the palette, preferences, Help dialogs, or an
	//    inline rename CLOSE (while one is open it owns focus, so the effect waits; an editor closing INSIDE
	//    an open overlay is the trigger of its own below);
	//  - topmostModalAlert: the confirm dialog, the file-open alert, the app layer's alerts, the export report,
	//    and the repack refusal report do NOT own focus - root focus is (re)claimed on open too, so their
	//    Escape/Enter route through the modal ladder while open.  Keyed on the topmost arrival rather than on
	//    whether any is open, because they queue: a dismissed dialog can take its focused text along while the
	//    next queued one shows, and nothing else would reclaim focus for it;
	//  - menu-bar close: an open menu's popup holds focus and its teardown takes it along.
	// The two-frame wait lets a closing popup's teardown finish stealing focus first - an immediate
	// request would be nulled right back out (the menu bar demonstrably needs this; it is harmless for
	// the other triggers).
	val overlaySelfFocused = overlays.selfFocusedOverlayOpen || inlineEditController.cancel != null
	val menuBarOpen = menuBarController.closeOpenMenu != null
	LaunchedEffect(workspaces.structuralEditCount, overlaySelfFocused, overlays.topmostModalAlert, menuBarOpen) {
		if (overlaySelfFocused || menuBarOpen) {
			return@LaunchedEffect
		}
		withFrameNanos {}
		withFrameNanos {}
		focusRequester.requestFocus()
	}

	// A text editor closing inside an overlay the root holds focus for.  Preferences owns no focus node of
	// its own: the root keeps the keyboard while it is open, which is how its Escape reaches the ladder.  A
	// number field in it takes focus to type and confirms Enter by clearing focus, so the field's close
	// leaves focus null with the overlay still open.  The effect above keys on the overlay and the editor
	// together, so it sees no change there and waits for the open overlay in any case, and the press release
	// runs for presses alone, never for a key.  So the editor's close is a trigger of its own: keyed on the
	// editor alone, so an overlay opening never fires it and the palette's search field keeps the focus it
	// asks for; gated to an open overlay, since outside one the effect above already reclaims; and the root
	// takes focus back only when nothing under it holds focus by then, the language switch's check.
	val textEntryActive = inlineEditController.cancel != null
	LaunchedEffect(textEntryActive) {
		if (textEntryActive || !overlays.selfFocusedOverlayOpen) {
			return@LaunchedEffect
		}
		withFrameNanos {}
		withFrameNanos {}
		if (!controllers.rootHoldsFocus) {
			focusRequester.requestFocus()
		}
	}

	// An OS-level focus round-trip (alt-tab away and back) restores focus to the WINDOW but to no Compose
	// node - whatever was focused before the blur stays unfocused, so onPreviewKeyEvent never fires and
	// every shortcut is dead until something focusable is clicked.  Reclaim root focus on window-focus
	// regain.  Skipped while an overlay that owns its own focus is up (the reclaim effect above covers
	// their close); the guard reads the live state inside the collector, never captures.  A modal alert does
	// not own focus - its Escape and Enter route through the ladder on the root - so it is no reason to skip:
	// copying an alert's text out to another window and coming back is the everyday case.
	val windowInfo = LocalWindowInfo.current
	LaunchedEffect(windowInfo) {
		snapshotFlow { windowInfo.isWindowFocused }.collect { windowFocused ->
			val overlayOwnsFocus =
				inlineEditController.cancel != null ||
					overlays.selfFocusedOverlayOpen
			if (windowFocused && !overlayOwnsFocus) {
				focusRequester.requestFocus()
			}
		}
	}

	// A language switch rebuilds everything under the locale key, so a field that held focus there - one
	// being edited in Preferences when the language was picked - is gone, and focus with it.  No trigger
	// above fires for that (Preferences is still open), so the switch is a trigger of its own: once the
	// rebuild has settled, the root takes focus back.  Unless something under it already holds focus by
	// then - an overlay that focuses its own field when it composes keeps it - since nothing was lost.
	val currentLanguageTag by rememberUpdatedState(languageTag)
	LaunchedEffect(Unit) {
		snapshotFlow { currentLanguageTag }.drop(1).collect {
			withFrameNanos {}
			withFrameNanos {}
			if (!controllers.rootHoldsFocus) {
				focusRequester.requestFocus()
			}
		}
	}
}