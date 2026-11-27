package org.umamo.ui.workspace.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.input.pointer.PointerIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.umamo.ui.progress.ProgressTracker
import org.umamo.ui.theme.LocalUmamoCursors
import org.umamo.ui.theme.hiddenPointerIcon
import org.umamo.ui.theme.umamoPointerIcon

/**
 * What the shell root is saying about the pointer, window-wide, over every cursor a panel or gizmo would
 * otherwise choose for itself.  A whole-window claim is how a mode announces itself: the pointer changes
 * the moment the mode begins, wherever the hand happens to be.  Busy is the one claim that is not a mode:
 * it reports work running in the background while the editor stays usable.
 */
internal enum class ShellCursorClaim {
	// No mode is running; each panel, splitter, and gizmo keeps its own cursor.
	None,

	// A relation pick is armed: the OS pointer is hidden and the shell draws the eyedropper itself.
	Hidden,

	// Text entry is live: the platform's own text pointer, everywhere, until the field lets go.
	TextEdit,

	// A tracked operation has run past the grace: the wait pointer, wherever no control asks for its own.
	Busy,
}

/**
 * Whether this claim must beat the cursors its descendants ask for.  A mode does - one that let a
 * splitter edge or a scrub zone keep its own pointer would stop reading as a mode at all.  Busy does not:
 * the work runs off the UI thread and every control still works, so a splitter or a scrub zone that keeps
 * its own cursor is telling the truth.  [ShellCursorClaim.None] must not either, since with nothing
 * claimed the descendants are the whole answer.
 */
internal val ShellCursorClaim.overridesDescendants: Boolean
	get() =
		when (this) {
			ShellCursorClaim.Hidden, ShellCursorClaim.TextEdit -> true
			ShellCursorClaim.Busy, ShellCursorClaim.None -> false
		}

/**
 * Resolves the one cursor the shell root claims, from the modes that can be running.
 *
 * A pick outranks text entry because the shell DRAWS the eyedropper at the pointer: a visible I-beam
 * under it would read as two cursors.  The two cannot overlap in practice anyway - arming a pick is a
 * click, which ends text entry - so the order is here to be stated rather than to be relied on.  Both
 * outrank busy, since they are what the rigger is in the middle of, and busy is only background work.
 *
 * Text entry gives way while the pointer rests on a control drawn in a text editor (a search field's clear
 * button): the control is a click target, and an I-beam over it would say otherwise.
 *
 * @param Boolean relationPickArmed      Whether a relation pick is waiting for its target.
 * @param Boolean textEntryActive        Whether a text editor has parked its cancel hook.
 * @param Boolean pointerOnEditorControl Whether the pointer rests on a control inside or over a text editor.
 * @param Boolean busy                   Whether a tracked operation has run past the grace.
 * @return ShellCursorClaim The claim the root applies to the whole window.
 */
internal fun shellCursorClaim(
	relationPickArmed: Boolean,
	textEntryActive: Boolean,
	pointerOnEditorControl: Boolean,
	busy: Boolean,
): ShellCursorClaim =
	when {
		relationPickArmed -> ShellCursorClaim.Hidden
		textEntryActive && !pointerOnEditorControl -> ShellCursorClaim.TextEdit
		busy -> ShellCursorClaim.Busy
		else -> ShellCursorClaim.None
	}

/**
 * The pointer icon this claim puts on the whole window: none of the OS's for a pick, the platform's text
 * pointer for text entry, the wait pointer while busy, and the plain pointer when nothing is claimed.
 *
 * @return PointerIcon The icon the shell root applies.
 * @note The hidden icon is a native cursor built on every call, so a caller remembers the result per claim.
 */
internal fun ShellCursorClaim.pointerIcon(): PointerIcon =
	when (this) {
		ShellCursorClaim.Hidden -> hiddenPointerIcon()
		ShellCursorClaim.TextEdit -> PointerIcon.Text
		ShellCursorClaim.Busy -> umamoPointerIcon(LocalUmamoCursors.wait)
		ShellCursorClaim.None -> PointerIcon.Default
	}

/**
 * The pointer this claim leaves a region that has no cursor of its own, which the shell hands down as
 * LocalRestingPointerIcon.  A claim that yields to the descendants leaves its own icon, since a region's
 * hand-written plain pointer would otherwise beat it.  A claim that overrides them shows regardless, so it
 * leaves the plain pointer, and starting a pick or text entry recomposes none of the regions that read it.
 *
 * @return PointerIcon The icon a region shows in place of the plain pointer.
 */
internal fun ShellCursorClaim.restingPointerIcon(): PointerIcon = if (overridesDescendants) PointerIcon.Default else pointerIcon()

/** How long (ms) a tracked operation runs before the pointer says the window is busy, so a quick one never flickers it. */
internal const val BUSY_POINTER_GRACE_MS = 300L

/**
 * Whether the pointer says the window is busy: true once some task of [tracker] has run for
 * [BUSY_POINTER_GRACE_MS], false the moment none runs.  The state changes only when that answer flips, so
 * the steps a task reports do not recompose its reader.
 *
 * @param ProgressTracker? tracker The app's tracker, or null where none is provided (the standalone shell, tests).
 * @return State<Boolean> Whether the busy pointer shows.
 */
@Composable
internal fun collectBusyPointerAsState(tracker: ProgressTracker?): State<Boolean> =
	produceState(initialValue = false, tracker) {
		val tasks = tracker?.tasks ?: return@produceState

		tasks.map { running -> running.isNotEmpty() }.distinctUntilChanged().collectLatest { running ->
			// A task that ends inside the grace cancels this wait, so it never shows at all.
			if (running) {
				delay(BUSY_POINTER_GRACE_MS)
			}

			value = running
		}
	}