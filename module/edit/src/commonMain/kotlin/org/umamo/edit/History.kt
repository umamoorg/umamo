package org.umamo.edit

import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.KeyableTarget
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.WarpLatticeForm

/** The default cap on retained undo steps; the oldest is dropped past this (a memory backstop). */
const val DEFAULT_HISTORY_LIMIT: Int = 200

/**
 * A pose: the live value of every parameter (the current scrub position the renderer deforms to).  Held
 * as ephemeral editor state outside [PuppetModel] - it is not document content (keyforms are), but it is
 * snapshotted so a scrub is undoable.  A small immutable map, so capturing one in a snapshot is cheap.
 */
typealias Pose = Map<ParameterId, Float>

/**
 * One undoable state: the document model plus the ephemeral editor state that rides with it - the mode,
 * the selections, the pose, and the pending edits - held outside [PuppetModel] so undo restores the editor
 * as it was at each step, not just the document.  Every field is an immutable value, so a snapshot is just
 * a few references - cheap to keep and to restore.
 *
 * @property PuppetModel model The document model at this step.
 * @property Selection selection The object-mode selection at this step.
 * @property Pose pose The live parameter values (scrub position) at this step.
 * @property MeshSelection meshSelection The Edit-mode element selection at this step.
 * @property EditorMode mode The interaction mode (Object / Edit / Pose) at this step.
 */
data class EditorSnapshot(
	val model: PuppetModel,
	val selection: Selection,
	val pose: Pose,
	val meshSelection: MeshSelection = MeshSelection(),
	val mode: EditorMode = EditorMode.Object,
	/**
	 * The parameters targeted for keyform authoring.  Restored like the other selections, so undoing back
	 * across a parameter click returns the target the edit was made against.
	 */
	val parameterSelection: ParameterSelection = ParameterSelection(),
	/**
	 * The channel values edited but not yet keyed at this step.
	 *
	 * Snapshotted even though a pending edit never reaches the document: it is a deliberate user action
	 * that shows in the viewport, so Blender records one and so do we.  The pose rides in the same
	 * snapshot, which is what makes restoring these coherent - a pending value was chosen FOR a pose, and
	 * undo returns to both together.
	 */
	val pendingChannelEdits: Map<KeyableTarget, ChannelValue> = emptyMap(),
	/**
	 * The keyform-sheet keys selected at this step.
	 *
	 * The fourth selection to be snapshotted, and for the same reason as the other three: a misclick that
	 * loses a carefully built selection is otherwise unrecoverable, and the sheet's selection takes the most
	 * work to build of any of them.  Ordinals renumber when keys cross, so a restored ref can name a key
	 * that no longer exists there - the sheet prunes at use, which is what makes that harmless.
	 */
	val keySelection: Set<TrackKeyRef> = emptySet(),
	/**
	 * The warp lattices shaped in Pose mode but not yet keyed at this step, each standing in for its warp's grid
	 * blend at [pose].
	 *
	 * Snapshotted for the reason [pendingChannelEdits] is: a pending lattice is a deliberate edit the viewport shows,
	 * and it was shaped FOR the pose this snapshot carries, so undo returns to the two together.
	 */
	val pendingLatticeEdits: Map<DeformerId, WarpLatticeForm> = emptyMap(),
)

/**
 * One history entry: a [snapshot] and the [change] that produced it (null for the initial state).  The
 * change is metadata — the snapshot does the undoing — used for the history-panel label and coalescing.
 *
 * @property EditorSnapshot snapshot The state after [change] was applied.
 * @property Change change The change that produced [snapshot], or null for the seed entry.
 */
data class HistoryEntry(
	val snapshot: EditorSnapshot,
	val change: Change?,
)

/**
 * A flattened, immutable projection of the undo stack for the history panel: each step's display
 * [labelKey] and the [cursor] index of the live step.  Rebuilt on every history change so a Compose
 * panel renders it as plain state (clicking a row jumps the session there).  Compose-free, so the label
 * stays a key the UI resolves to a localized string.
 *
 * @property List<HistoryStep> steps The stack oldest-first.
 * @property Int cursor The index of the live step within [steps].
 */
data class HistoryView(
	val steps: List<HistoryStep>,
	val cursor: Int,
)

/**
 * One row of a [HistoryView].
 *
 * @property String? labelKey The label key of the change that produced this step, or null for the seed
 *   (the initial open) entry; the UI maps it to a localized label.
 * @property Boolean saved Whether this exact step is the last-saved baseline (the panel marks it).
 */
data class HistoryStep(
	val labelKey: String?,
	val saved: Boolean,
)

/**
 * A linear undo/redo stack of immutable snapshots.  The entry at [cursor] is the live state; [push]
 * records a new step (discarding any redo branch, the chosen v1 behavior), and [undo] / [redo] move the
 * cursor and return the state to restore.  Snapshots structurally share their unchanged sub-trees, so the
 * stack's cost is bounded by what each step actually changed plus the [limit] cap on retained steps.
 *
 * Undo is a cursor move, never an inverse op — correctness is structural, since each entry holds the
 * whole prior value.  A future branching "undo tree" can replace the linear backing without touching the
 * session API; v1 is deliberately linear.
 *
 * @param EditorSnapshot initial The seed entry — the state the document opened on.
 * @param Int initialLimit The starting cap; [limit] carries it and may be reassigned later.
 * @property Int limit The maximum retained entries; the oldest is dropped past this.  Settable at
 *   runtime, since the cap is a user preference.
 */
class History(
	initial: EditorSnapshot,
	initialLimit: Int = DEFAULT_HISTORY_LIMIT,
) {
	private val entries: MutableList<HistoryEntry> = mutableListOf(HistoryEntry(initial, null))
	private var cursor: Int = 0

	/**
	 * The maximum retained entries, settable at runtime because the cap is a user preference.  Assigning a
	 * lower value trims the stack at once (see [trimToLimit]), so lowering it frees memory without waiting
	 * for the next edit.  Clamped to at least 1 so a degenerate cap never empties the stack.
	 */
	var limit: Int = initialLimit.coerceAtLeast(1)
		set(value) {
			field = value.coerceAtLeast(1)
			trimToLimit()
		}

	/** The live state — the snapshot at the current cursor. */
	val current: EditorSnapshot get() = entries[cursor].snapshot

	/** The change that produced the live state, or null at the seed entry (the label for "Undo X"). */
	val currentChange: Change? get() = entries[cursor].change

	/** The cursor's index into the stack — the position of the live step (the history panel highlights it). */
	val cursorIndex: Int get() = cursor

	/** A read-only copy of the stack, oldest-first — the source the history panel renders. */
	val steps: List<HistoryEntry> get() = entries.toList()

	/** True when there is a prior step to undo to. */
	val canUndo: Boolean get() = cursor > 0

	/** True when a redo step is available (an undo with no intervening [push]). */
	val canRedo: Boolean get() = cursor < entries.lastIndex

	/**
	 * Records [snapshot] as a new step produced by [change].  Any redo branch ahead of the cursor is
	 * discarded (linear history); once the stack exceeds [limit] the oldest entry is dropped so the
	 * cursor stays valid.  The seed entry is never dropped below a single retained step.
	 *
	 * @param EditorSnapshot snapshot The new live state.
	 * @param Change change The change that produced it.
	 */
	fun push(snapshot: EditorSnapshot, change: Change) {
		// Drop the redo branch: everything after the cursor is now an abandoned future.
		while (entries.lastIndex > cursor) {
			entries.removeAt(entries.lastIndex)
		}

		entries.add(HistoryEntry(snapshot, change))
		cursor = entries.lastIndex
		trimToLimit()
	}

	/**
	 * Replaces the live entry with [snapshot] and [change], keeping the cursor where it is - the
	 * mechanism behind adjusting the last operation, whose recomputed result lands over its own step.
	 *
	 * Refuses when a redo branch exists, since an adjustment applies only to the newest step.  Works at
	 * cursor 0 too, since a cap of one drops the base entry on push and the adjusting record holds that
	 * base itself.
	 *
	 * @param EditorSnapshot snapshot The recomputed live state.
	 * @param Change change The change that produced it (normally the entry's own).
	 * @return Boolean True when the entry was replaced, false when a redo branch made it refuse.
	 */
	fun amendTop(snapshot: EditorSnapshot, change: Change): Boolean {
		if (canRedo) {
			return false
		}

		entries[cursor] = HistoryEntry(snapshot, change)

		return true
	}

	/**
	 * Drops the oldest entries until the stack fits [limit], stopping at the live step so the current state
	 * and its redo branch are never discarded - which makes a lowered cap safe on a stack whose cursor sits
	 * mid-history: it frees what it can at once and sheds the rest on later pushes.
	 */
	private fun trimToLimit() {
		while (entries.size > limit && cursor > 0) {
			entries.removeAt(0)
			cursor--
		}
	}

	/**
	 * Moves back one step and returns the state to restore, or null when already at the oldest entry.
	 *
	 * @return EditorSnapshot? The prior state, or null if nothing to undo.
	 */
	fun undo(): EditorSnapshot? {
		if (!canUndo) {
			return null
		}

		cursor--

		return current
	}

	/**
	 * Moves forward one step and returns the state to restore, or null when already at the newest entry.
	 *
	 * @return EditorSnapshot? The next state, or null if nothing to redo.
	 */
	fun redo(): EditorSnapshot? {
		if (!canRedo) {
			return null
		}

		cursor++

		return current
	}

	/**
	 * Moves the cursor directly to [index] (clamped to the valid range) and returns the state to restore,
	 * or null when [index] is already the cursor (a no-op jump).  This lets the history panel jump across
	 * several steps at once — multi-undo or multi-redo — rather than walking one level at a time.
	 *
	 * @param Int index The target step index within the stack.
	 * @return EditorSnapshot? The state at [index], or null when it is already the live step.
	 */
	fun jumpTo(index: Int): EditorSnapshot? {
		val target = index.coerceIn(0, entries.lastIndex)

		if (target == cursor) {
			return null
		}

		cursor = target

		return current
	}
}