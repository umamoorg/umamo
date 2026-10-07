package org.umamo.edit

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.KeyableTarget
import org.umamo.runtime.model.PuppetModel

/**
 * The single mutable owner of one open document: the live [model], the ephemeral editor state
 * ([selection], [mode]), the undo [History], and the change-event bus. Every edit flows through here so
 * undo, change events, and dirty-tracking stay consistent.
 *
 * Two channels, per the history design: [changes] emits on every mutation (the "everything emits a
 * change event" completeness), while the history stack records only the undoable subset. A document
 * mutation ([mutate]) and a selection gesture ([setSelection]) each push one step; a mode toggle
 * ([setMode]) and future tool/brush switches are transient — they emit but never become steps.
 *
 * Undo is by immutable snapshot, never inverse op: [undo] / [redo] just republish a stored
 * [EditorSnapshot]. Dirty is reference-equality of the model against the last-saved instance — because
 * undo restores the exact prior model instance and selection-only steps reuse the same instance,
 * `model !== savedModel` is correct across undo/redo and is never tripped by a bare selection change.
 *
 * Held on the UI thread (Compose drives it); the render host observes [model] / [selection] as flows.
 * Compose-free by design (its module mandate), so it exposes coroutines flows, not Compose state.
 *
 * The primary constructor is private so the public one can hand it the [SessionCollaborators] built
 * beforehand: the tool latches, the tool settings, and the request buses the header delegates
 * [SessionToolLatches], [SessionToolSettings], and [SessionRequests] to, and the notice channel the settings
 * post through.
 *
 * @param PuppetModel initialModel The document model at open.
 * @param Pose initialPose The pose at open.
 * @param Int initialHistoryLimit The retained-undo-step cap at open.
 * @param SessionViewState? initialViewState The session state the document was saved with, or null.
 * @param SessionCollaborators collaborators The ready-made tool latches, request buses, and notice channel.
 */
class EditorSession private constructor(
	initialModel: PuppetModel,
	initialPose: Pose,
	initialHistoryLimit: Int,
	initialViewState: SessionViewState?,
	private val collaborators: SessionCollaborators,
) : SessionToolLatches by collaborators.latches,
	SessionToolSettings by collaborators.settings,
	SessionRequests by collaborators.requestBus {
	/**
	 * Opens a session on [initialModel].
	 *
	 * @param PuppetModel initialModel The document model at open.
	 * @param Pose initialPose The pose at open (the displayed scrub values); defaults to every parameter's
	 *   default. The host passes the renderer's starting values so the session, the panel, and the viewport
	 *   agree from frame one (e.g. a headless dump's overridden pose is not reset to defaults).
	 * @param Int initialHistoryLimit The retained-undo-step cap at open; [historyLimit] carries it and the
	 *   host reassigns it when the preference changes.
	 * @param SessionViewState? initialViewState The session state the document was saved with, or null for a plain
	 *   open.  It is fitted to the model and laid into the FIRST snapshot and the tool latches, so what a rigger
	 *   reopens to is where the history starts - never a run of undo steps, and never a dirty mark.
	 */
	constructor(
		initialModel: PuppetModel,
		initialPose: Pose = initialModel.parameters.associate { parameter -> parameter.id to parameter.default },
		initialHistoryLimit: Int = DEFAULT_HISTORY_LIMIT,
		initialViewState: SessionViewState? = null,
	) : this(initialModel, initialPose, initialHistoryLimit, initialViewState, SessionCollaborators())

	// The saved session state with every reference the model cannot satisfy taken out, and the snapshot the
	// session opens on.  Declared first: the history and every snapshotted flow below start from it.
	private val openingViewState: SessionViewState? = initialViewState?.fittedTo(initialModel)
	private val openingSnapshot: EditorSnapshot = openingSnapshotOf(initialModel, initialPose, openingViewState)

	// The session's collaborators - the undo machinery (stack, saved baseline, derived flags) and the
	// remembered-selection memory built here, the tool latches, the tool settings, the area-request buses,
	// and the notice channel handed in.  The latches, settings, and bus are reached through the three delegated interfaces
	// in the header; what stays below is everything that writes a snapshotted flow, so every flow-write
	// ordering stays in this facade.  The latches and the element memory are internal, not private, for
	// the session's own extension files (ToolArming, SelectionEdits) - no other file may touch them.
	private val history = HistoryCore(openingSnapshot, initialHistoryLimit)
	internal val elementMemory = MeshElementMemory()
	internal val latches: ToolLatches = collaborators.latches
	private val settings: ToolSettings = collaborators.settings
	private val requestBus: SessionRequestBus = collaborators.requestBus
	private val notices: SessionNotices = collaborators.notices

	init {
		// The saved tool state is laid into the settings as the session is built, so what a rigger reopens
		// to is where every flow starts - never a gesture, and never a notice.
		openingViewState?.let(settings::seed)
	}

	// The live step's predecessor as of the last push - the base an operation registering itself as
	// adjustable ran from.  Consumed by the registration and voided by a restore, so a registration can
	// never pair an operation with some other step's base.
	private var lastBaseSnapshot: EditorSnapshot? = null

	private val mutableAdjustableOperation = MutableStateFlow<AdjustableOperation?>(null)

	/**
	 * The one operation that may still be adjusted (Blender's redo strip), or null.  Set by
	 * [registerAdjustableOperation] right after an operation commits; cleared by any other history
	 * push, selection pushes included, and by every undo / redo / jump - the strip belongs to the newest
	 * step only.
	 */
	val adjustableOperation: StateFlow<AdjustableOperation?> = mutableAdjustableOperation.asStateFlow()

	/**
	 * The model this session opened on - the document's own puppet, before any edit.
	 *
	 * Exposed so a host can verify that a session belongs to the document it is about to act on:
	 * a session outlives nothing, but a stale one paired with a fresh document would silently
	 * apply the PREVIOUS model's rig (see the export guard in EditorApp).
	 */
	val baselineModel: PuppetModel = initialModel

	private val mutableModel = MutableStateFlow(initialModel)

	/** The live document model; panels read it, the render host observes it. */
	val model: StateFlow<PuppetModel> = mutableModel.asStateFlow()

	private val mutableSelection = MutableStateFlow(openingSnapshot.selection)

	/** The live object-mode selection. */
	val selection: StateFlow<Selection> = mutableSelection.asStateFlow()

	private val mutableParameterSelection = MutableStateFlow(openingSnapshot.parameterSelection)

	/**
	 * The parameters targeted for keyform authoring - which parameter an insert would write a key on.
	 *
	 * Independent of [selection]: the object selection says WHAT to key, this says on WHICH AXIS.
	 */
	val parameterSelection: StateFlow<ParameterSelection> = mutableParameterSelection.asStateFlow()

	private val mutablePendingChannelEdits = MutableStateFlow<Map<KeyableTarget, ChannelValue>>(emptyMap())

	/**
	 * Channel values edited but NOT yet keyed - Blender's model, where changing a keyed property off a key
	 * takes effect now but is lost unless you key it.
	 *
	 * Deliberately NOT in the document: a pending edit never reaches [PuppetModel], so it costs nothing in
	 * document terms and a keyform insert can consume it without that being a document edit either. It IS in
	 * the undo history, one step per gesture end (see [commitPendingChannelEdit]) rather than per keystroke -
	 * a per-frame preview ([setPendingChannelEdit]) records nothing on its own. Every pending value is
	 * cleared whenever the pose moves, because a value chosen FOR one pose is meaningless at another; a
	 * history jump does not clear them, since [restore] lands on the very pose they were chosen for and
	 * restores them alongside it.
	 *
	 * The keyed-field tint reads this to show the edited-but-unkeyed state, and a keyform insert consumes
	 * it: the whole point is that `I` captures what you just typed rather than what is still stored.
	 */
	val pendingChannelEdits: StateFlow<Map<KeyableTarget, ChannelValue>> = mutablePendingChannelEdits.asStateFlow()

	private val mutableKeySelection = MutableStateFlow<Set<TrackKeyRef>>(emptySet())

	/**
	 * The keyform sheet's selected keys - what its Delete and nudge commands act on.
	 *
	 * Shell-wide rather than per-sheet: it is snapshotted, and undo restores session state.  Two open sheets
	 * therefore share one selection, which is the same call [parameterSelection] made and for the same
	 * reason - a second sheet showing a different answer to "what is selected" is worse than agreement.
	 */
	val keySelection: StateFlow<Set<TrackKeyRef>> = mutableKeySelection.asStateFlow()

	private val mutablePose = MutableStateFlow(initialPose)

	/** The live pose (parameter scrub values); the render host mirrors it so undo / redo re-poses. */
	val pose: StateFlow<Pose> = mutablePose.asStateFlow()

	private val mutableMode = MutableStateFlow(openingSnapshot.mode)

	/**
	 * The live interaction mode. Snapshotted (a mode change is its own undo step), and pose-neutral by
	 * contract: entering or leaving Edit mode NEVER writes the pose — Edit mode's rest view is a
	 * display-only override in the render host, so the Object-mode pose survives an Edit session
	 * untouched with no stashed state to juggle.
	 */
	val mode: StateFlow<EditorMode> = mutableMode.asStateFlow()

	private val mutableMeshSelection = MutableStateFlow(openingSnapshot.meshSelection)

	/** The live Edit-mode element selection; snapshotted, so a selection gesture is undoable. */
	val meshSelection: StateFlow<MeshSelection> = mutableMeshSelection.asStateFlow()

	/**
	 * The current transient user notice, or null when none is showing. A short message the shell surfaces
	 * briefly (near the status bar) to explain why an action did nothing. Deliberately off the undo history and
	 * the change bus - a notice is momentary feedback, never document state. Used today when an Object-mode
	 * transform blocks because the selection holds a part, a deformer, or a mesh-less drawable. A StateFlow (not
	 * a one-shot event) so the shell reads it with the standard collectAsState path and a late subscriber still
	 * sees an in-flight notice; the [Notice.serial] lets the shell time its dismissal and re-trigger on a repeat
	 * of the same text.
	 */
	val notice: StateFlow<Notice?> = notices.notice

	/**
	 * Emits a transient user notice (see [notice]); it stays current until dismissed via [clearNotice] or
	 * replaced by a newer one.
	 *
	 * @param String messageKey The stable notice key the UI layer resolves to a localized message.
	 * @param NoticePlacement placement Where the shell surfaces the notice.
	 * @param List<String> arguments The values the message formats in, in its placeholder order.
	 */
	fun emitNotice(messageKey: String, placement: NoticePlacement = NoticePlacement.StatusBar, arguments: List<String> = emptyList()) {
		notices.emit(messageKey, placement, arguments)
	}

	/**
	 * Dismisses the notice with the given [serial], but only if it is still the current one - so a dismissal
	 * timer for an older notice never clears a newer message that arrived in the meantime.
	 *
	 * @param Long serial The serial of the notice to dismiss (from [Notice.serial]).
	 */
	fun clearNotice(serial: Long) {
		notices.clear(serial)
	}

	private val mutableChanges = MutableSharedFlow<Change>(extraBufferCapacity = 64)

	/** The change-event bus: every mutation emits here, the undoable ones and the transient ones alike. */
	val changes: SharedFlow<Change> = mutableChanges.asSharedFlow()

	/** True when the document model differs from the last-saved state (drives the title/status marker). */
	val dirty: StateFlow<Boolean> = history.dirty

	/** True when there is a step to undo (drives the Edit-menu item's enabled state). */
	val canUndo: StateFlow<Boolean> = history.canUndo

	/** True when there is a step to redo. */
	val canRedo: StateFlow<Boolean> = history.canRedo

	/** The undo stack projected for the history panel; updates on every edit, undo, redo, jump, and save. */
	val historyView: StateFlow<HistoryView> = history.historyView

	/**
	 * The retained-undo-step cap.  The host writes it from the user preference - once when the document
	 * opens and again on every committed change - so the session never reads settings itself.
	 *
	 * Lowering it trims the stack at once rather than waiting for the next edit, but never past the live
	 * step, so the current state and its redo branch always survive; the excess sheds on subsequent
	 * pushes.  The write republishes the derived flags, so the panel drops the same rows the stack did and
	 * [canUndo] stays honest when the trim lands the cursor on the oldest entry.
	 */
	var historyLimit: Int
		get() = history.limit
		set(value) {
			history.limit = value
			refreshFlags()
		}

	/**
	 * Records one history step - the single path every push takes, so the adjustable-operation record
	 * cannot outlive the step it belongs to and the base of the next registration is always the step
	 * being left.
	 *
	 * @param EditorSnapshot snapshot The new live state.
	 * @param Change change The change that produced it.
	 */
	private fun pushStep(snapshot: EditorSnapshot, change: Change) {
		mutableAdjustableOperation.value = null
		lastBaseSnapshot = history.current
		history.push(snapshot, change)
	}

	/**
	 * Records one undo step whose state is the live state with the given fields replaced, and publishes it:
	 * the snapshot is pushed, every snapshotted flow takes its value from it (model first, mode last - the
	 * order [restore] publishes in, so an undo and the edit it reverts agree), the derived flags refresh, and
	 * [change] goes out on the bus.  The one path every step takes: a member that pushes a step calls this
	 * rather than writing the flows itself, and an operation in another file reaches the history only
	 * through here.
	 *
	 * Every field defaults to the LIVE value, never to an empty one: a field added later is then carried
	 * unchanged by every existing caller instead of silently recorded empty.  The one default that is not
	 * the live value is [pendingChannelEdits]: a pose that differs from the live pose discards them, because
	 * every pending value was chosen FOR the pose being left - the rule lives here and nowhere else.
	 *
	 * @param Change change The descriptor of this step (for the bus and the history-panel label).
	 * @param PuppetModel model The document model after the step.
	 * @param Selection selection The object selection after the step.
	 * @param Pose pose The pose after the step.
	 * @param MeshSelection meshSelection The Edit-mode element selection after the step.
	 * @param EditorMode mode The interaction mode after the step.
	 * @param ParameterSelection parameterSelection The keyform-authoring target after the step.
	 * @param Map<KeyableTarget, ChannelValue> pendingChannelEdits The unkeyed channel edits after the step.
	 * @param Set<TrackKeyRef> keySelection The keyform-sheet key selection after the step.
	 */
	internal fun commitStep(
		change: Change,
		model: PuppetModel = mutableModel.value,
		selection: Selection = mutableSelection.value,
		pose: Pose = mutablePose.value,
		meshSelection: MeshSelection = mutableMeshSelection.value,
		mode: EditorMode = mutableMode.value,
		parameterSelection: ParameterSelection = mutableParameterSelection.value,
		pendingChannelEdits: Map<KeyableTarget, ChannelValue> = if (pose == mutablePose.value) mutablePendingChannelEdits.value else emptyMap(),
		keySelection: Set<TrackKeyRef> = mutableKeySelection.value,
	) {
		val snapshot = EditorSnapshot(model, selection, pose, meshSelection, mode, parameterSelection, pendingChannelEdits, keySelection)
		pushStep(snapshot, change)
		publish(snapshot)
		refreshFlags()
		mutableChanges.tryEmit(change)
	}

	/**
	 * Writes every snapshotted flow from [snapshot], model first and mode last.  Shared by [commitStep] and
	 * [restore] so a step and the undo that reverts it publish in one order; a field the snapshot did not
	 * change is an equal write the flow drops.
	 *
	 * @param EditorSnapshot snapshot The state to publish.
	 */
	private fun publish(snapshot: EditorSnapshot) {
		mutableModel.value = snapshot.model
		mutableSelection.value = snapshot.selection
		mutablePose.value = snapshot.pose
		mutableMeshSelection.value = snapshot.meshSelection
		mutableParameterSelection.value = snapshot.parameterSelection
		mutablePendingChannelEdits.value = snapshot.pendingChannelEdits
		mutableKeySelection.value = snapshot.keySelection
		mutableMode.value = snapshot.mode
	}

	/**
	 * Registers the operation that just committed [committedModel] as adjustable: the strip shows its
	 * [parameters], and editing one calls [rerun] with the updated record, which recomputes from the
	 * record's base and lands through [amendLastCommit].
	 *
	 * Call it right after the operation's own commit.  Refuses (null) when nothing was pushed since the
	 * last registration or restore, or when the live model is not [committedModel] - a client that
	 * committed nothing (a no-op edit) must not register against some other step's base.
	 *
	 * @param PuppetModel committedModel The model the operation's commit published.
	 * @param String? areaId The area the operation ran in, or null.
	 * @param List parameters The operation's settings as it ran.
	 * @param Function rerun Runs the operation again under the record it is given.
	 * @return AdjustableOperation? The record now live, or null when the registration was refused.
	 */
	fun registerAdjustableOperation(
		committedModel: PuppetModel,
		areaId: String?,
		parameters: List<OperatorParameter>,
		rerun: (AdjustableOperation) -> Unit,
	): AdjustableOperation? {
		val base = lastBaseSnapshot ?: return null
		val change = history.currentChange ?: return null
		if (mutableModel.value !== committedModel) {
			return null
		}
		lastBaseSnapshot = null
		val record = AdjustableOperation(change, areaId, parameters, base, rerun, identity = Any())
		mutableAdjustableOperation.value = record
		return record
	}

	/**
	 * Adjusts the live adjustable operation: publishes [parameters] as its settings and runs it again.
	 * A no-op while no operation is adjustable.
	 *
	 * @param List parameters The new settings, in the record's order.
	 */
	fun adjustLastOperation(parameters: List<OperatorParameter>) {
		val current = mutableAdjustableOperation.value ?: return
		val updated = current.withParameters(parameters)
		mutableAdjustableOperation.value = updated
		updated.rerun(updated)
	}

	/**
	 * Lands an adjustment: replaces the operation's own history step with [model] and publishes it,
	 * keeping the record live for the next adjustment.  Nothing is undone and nothing is replayed - the
	 * step is rewritten in place ([History.amendTop]).
	 *
	 * Refuses when [operation] is not the live record (it was cleared by another push, an undo, or a
	 * document swap while an asynchronous rerun was in flight - the result is dropped, never committed
	 * over whatever the rigger did meanwhile) or when the stack refuses the amend.
	 *
	 * @param AdjustableOperation operation The record the rerun was given.
	 * @param PuppetModel model The recomputed document model.
	 * @return Boolean True when the step was replaced and published.
	 */
	fun amendLastCommit(operation: AdjustableOperation, model: PuppetModel): Boolean {
		val current = mutableAdjustableOperation.value
		if (current == null || current.identity !== operation.identity) {
			return false
		}
		if (!history.amendTop(snapshot(model = model), operation.change)) {
			return false
		}
		mutableModel.value = model
		refreshFlags()
		mutableChanges.tryEmit(operation.change)
		return true
	}

	/**
	 * Applies a document edit: computes the new model via [transform], records it as one undo step, and
	 * publishes it. The [change] describes the edit for the bus and the history-panel label. A transform
	 * that returns the same model instance (a no-op edit) records nothing, so callers need not pre-check.
	 *
	 * @param Change change The descriptor of this edit (its [Change.undoability] is assumed undoable here).
	 * @param Function transform Produces the new model from the current one.
	 */
	fun mutate(change: Change, transform: (PuppetModel) -> PuppetModel) {
		commit(change, transform(mutableModel.value), mutablePose.value)
	}

	/**
	 * Commits one undo step from an already-computed [model] and [pose], recording it and publishing both.
	 * The single choke point behind [mutate] (model edits), [commitPose] (scrubs), and [setParameterRange]
	 * (both at once). A commit that changes neither the model instance nor the pose records nothing, so
	 * callers need not pre-check. Dirty is measured against the model only, so a pose-only commit (a scrub)
	 * is an undo step without marking the document unsaved, exactly like a selection gesture.
	 *
	 * @param Change change The descriptor of this edit (for the bus and the history-panel label).
	 * @param PuppetModel model The new document model (same instance as now for a pose-only commit).
	 * @param Pose pose The new live pose (same value as now for a model-only commit).
	 */
	private fun commit(change: Change, model: PuppetModel, pose: Pose) {
		if (model === mutableModel.value && pose == mutablePose.value) {
			return
		}
		commitStep(change, model = model, pose = pose)
	}

	/**
	 * A snapshot of the session's current state, with any field overridden - what [amendLastCommit] rewrites
	 * the live step with.  A push never builds its snapshot here: it goes through [commitStep], whose
	 * parameters follow the same rule for the same reason.  [EditorSnapshot]'s own defaults are dangerous: a
	 * field added later would default to its EMPTY value at every existing call site, which compiles cleanly
	 * but would silently record the wrong state - for example, undoing an unrelated edit would clear the
	 * parameter target instead of leaving it as it was.  Defaulting to live state makes the omission harmless.
	 */
	private fun snapshot(
		model: PuppetModel = mutableModel.value,
		selection: Selection = mutableSelection.value,
		pose: Pose = mutablePose.value,
		meshSelection: MeshSelection = mutableMeshSelection.value,
		mode: EditorMode = mutableMode.value,
		parameterSelection: ParameterSelection = mutableParameterSelection.value,
		pendingChannelEdits: Map<KeyableTarget, ChannelValue> = mutablePendingChannelEdits.value,
		keySelection: Set<TrackKeyRef> = mutableKeySelection.value,
	): EditorSnapshot =
		EditorSnapshot(
			model,
			selection,
			pose,
			meshSelection,
			mode,
			parameterSelection,
			pendingChannelEdits,
			keySelection,
		)

	/** Whether the current mode pins the pose (see [pinsPose]), which refuses every pose move asked of the session. */
	val posePinned: Boolean get() = mutableMode.value.pinsPose

	/**
	 * The pose the editor shows, and so where an edit aimed at "the pose" acts: the rig's pose, and while it
	 * is pinned, every parameter at its default.  Edit mode shows the rig at rest, so a key inserted "at the
	 * pose" there lands where the rigger is looking, not at a pose that returns only when Edit mode is left.
	 */
	val shownPose: Pose
		get() =
			if (posePinned) {
				mutableModel.value.parameters.associate { parameter -> parameter.id to parameter.default }
			} else {
				mutablePose.value
			}

	/**
	 * Commits a parameter scrub as one undo step: the live [pose] reached a new resting position (a slider
	 * or 2D-pad gesture released, a value typed, a reset). Mid-gesture preview frames bypass this and reach
	 * the renderer directly, so a whole drag is a single step. The model is unchanged, so this does not
	 * mark the document dirty. A commit equal to the current pose records nothing.
	 *
	 * Refused while the pose is pinned ([posePinned]), whoever asks.  The refusal is here rather than left
	 * to each caller because of what a caller has to hand over: in Edit mode the pose a view holds is the
	 * rest pose it is being shown, which names no parameter, and recording that as the rig's pose would
	 * drop every value the rig held.  A document edit that has to move the pose with it - a range
	 * narrowed under the value - does not come through here, and is not refused.
	 *
	 * @param Change change The scrub descriptor (a [ParameterChange.SetValue]).
	 * @param Pose pose The pose to commit (the gesture's final parameter values).
	 */
	fun commitPose(change: Change, pose: Pose) {
		if (posePinned) {
			return
		}
		commit(change, mutableModel.value, pose)
	}

	/**
	 * Records [value] as an unkeyed edit of [target] - a value the user typed that is not stored anywhere yet.
	 *
	 * Transient by construction: no history step, no model change.  The next pose move discards it, which is
	 * the behaviour rather than a limitation - the value was chosen FOR this pose, so carrying it to another
	 * would be applying an edit somewhere it was never meant.
	 *
	 * @param KeyableTarget target The property edited.
	 * @param ChannelValue value The value typed.
	 */
	fun setPendingChannelEdit(target: KeyableTarget, value: ChannelValue) {
		mutablePendingChannelEdits.value = mutablePendingChannelEdits.value + (target to value)
	}

	/**
	 * Records [value] as an unkeyed edit of [target] AND as one undo step, described by [change].
	 *
	 * What a keyable property field calls when its gesture ends, where [setPendingChannelEdit] is what it
	 * calls per frame while the gesture is still running.  A pending edit is still transient - the next pose
	 * move discards it - but discarding is not the same as never having happened: it is a deliberate edit
	 * the user can see take effect in the viewport, so it must be undoable independent of whether it ever
	 * reaches the document.
	 *
	 * Pushes its own step through [commitStep] rather than going through [mutate] / [commit], for the same
	 * reason [setSelection] and [setMeshSelection] do: neither the model nor the pose changes, so the commit
	 * choke point would short-circuit and record nothing.  Not a document edit, so it leaves dirty untouched.
	 *
	 * [change] is the SAME descriptor the unkeyed path would have used, so the history entry reads "Set
	 * Opacity" whichever branch the edit took - which branch it took is an implementation detail of where
	 * the value could be stored, not something a rigger asked for.
	 *
	 * @param KeyableTarget target The property edited.
	 * @param ChannelValue value The value the user chose.
	 * @param Change change The descriptor of this edit (for the bus and the history-panel label).
	 */
	fun commitPendingChannelEdit(target: KeyableTarget, value: ChannelValue, change: Change) {
		// Compared against what HISTORY holds, not against the live map: a scrub has already written its last
		// preview frame there, so a live-map comparison makes the release of every drag look like a no-op and
		// records nothing - the one case this exists for.
		if (history.current.pendingChannelEdits[target] == value) {
			// Still published: the guard says this step would record nothing new, not that the value is live.
			// The two diverge whenever something retired the pending edit without recording that it did - a
			// scrub previews through clearPendingChannelEdits and then ends where it began - and returning
			// outright left the re-typed value out of the viewport and the field untinted.
			setPendingChannelEdit(target, value)
			return
		}
		commitStep(change, pendingChannelEdits = mutablePendingChannelEdits.value + (target to value))
	}

	/**
	 * Discards every pending unkeyed edit.
	 *
	 * Called when a pose PREVIEW moves - a scrub frame that reaches the renderer without recording a step -
	 * since every pending value was chosen for the pose being left; a recorded pose move discards them the
	 * same way through [commitStep]'s default.  A history jump does NOT call this: [restore] publishes the
	 * snapshot's own [EditorSnapshot.pendingChannelEdits] instead, since the pose it lands on is exactly the
	 * pose those values were chosen for.  A keyform insert that consumed one target's value uses
	 * [clearPendingChannelEdit] instead, because the other targets' values are still valid for the unchanged
	 * pose.
	 */
	fun clearPendingChannelEdits() {
		if (mutablePendingChannelEdits.value.isNotEmpty()) {
			mutablePendingChannelEdits.value = emptyMap()
		}
	}

	/**
	 * Discards the pending unkeyed edit of [target] alone.
	 *
	 * The keyform-insert path: the capture consumed this one value, and the pose did not move, so every
	 * other target's pending value is still the value its user chose for the current pose.
	 *
	 * @param KeyableTarget target The property whose pending edit was consumed.
	 */
	fun clearPendingChannelEdit(target: KeyableTarget) {
		if (mutablePendingChannelEdits.value.containsKey(target)) {
			mutablePendingChannelEdits.value = mutablePendingChannelEdits.value - target
		}
	}

	/**
	 * Records a selection gesture as its own undo step (the chosen Blender-faithful granularity), so a
	 * misclick that clears the selection is recoverable. A no-op (selecting the already-current
	 * selection) records nothing.
	 *
	 * @param Selection selection The new selection.
	 */

	fun setSelection(selection: Selection) {
		if (selection == mutableSelection.value) {
			return
		}
		(selection.active as? SelectionTarget.Drawable)?.let { activeDrawable ->
			elementMemory.lastActiveDrawableId = activeDrawable.id
		}
		commitStep(EditorStateChange.SelectionChanged, selection = selection)
	}

	/**
	 * Sets the parameters targeted for keyform authoring as its own undo step.
	 *
	 * Pushes its own snapshot rather than going through [mutate] / [commit], exactly like [setSelection]:
	 * neither the model nor the pose changes, so the commit choke point would short-circuit and record
	 * nothing.  Shared session state rather than a panel's view state, so every area agrees on which
	 * parameter an insert would write to.
	 *
	 * @param ParameterSelection parameterSelection The new target set.
	 */
	fun setParameterSelection(parameterSelection: ParameterSelection) {
		if (parameterSelection == mutableParameterSelection.value) {
			return
		}
		commitStep(EditorStateChange.ParameterSelectionChanged, parameterSelection = parameterSelection)
	}

	/**
	 * Sets the interaction mode as its own undo step, so undo restores the mode (and the mesh selection
	 * it seeds) together - the editor never lands in Edit mode showing a state captured in Object mode.
	 * Entering Edit seeds the session with EVERY selected mesh-carrying drawable (multi-mesh edit; the
	 * object selection's active drawable becomes the session's active mesh), falling back to the last
	 * drawable that was active (Blender's remembered selection) when nothing is selected, and past that
	 * to the topmost editable drawable in Parts-panel order (so a fresh document lands on something to
	 * edit rather than an inert session); the remembered fallback is skipped if that drawable no longer
	 * exists in the model. When the model has nothing editable at all, entering Edit is refused - the
	 * mode stays Object and nothing is recorded - so Edit mode always holds meshes. Leaving Edit stashes the
	 * element selection and clears it; re-entering on the same drawable restores the stash when every
	 * element still fits the mesh (Blender's remembered mesh selection). Leaving also ends any in-flight
	 * operator. The mode is editor state, not document content, so it does not dirty the document. A no-op
	 * (already in [mode]) records nothing.
	 *
	 * @param EditorMode mode The new mode.
	 */
	fun setMode(mode: EditorMode) {
		if (mode == mutableMode.value) {
			return
		}
		val newMeshSelection =
			when (mode) {
				EditorMode.Edit -> {
					val model = mutableModel.value
					// The session spans EVERY selected mesh-carrying drawable (multi-mesh edit, needed for
					// glue work); the object selection's active drawable becomes the session's active mesh.
					// The model having nothing editable refuses Edit rather than opening an inert session
					// (Blender needs an active object too): stay in Object, record nothing.
					val editSeed = editSeedOf(model, mutableSelection.value, elementMemory.lastActiveDrawableId) ?: return
					val seedDrawableIds = editSeed.drawableIds
					val seedActiveId = editSeed.activeId
					if (!editSeed.activeSelected) {
						// Seeded from the remembered drawable or the topmost fallback: remember it so the next
						// entry is stable. The object selection is left untouched - a soft seed, not a
						// re-selection, matching the remembered-drawable behavior.
						elementMemory.lastActiveDrawableId = seedActiveId
					}
					// Clear the transient tool state on entry too: the select tool is shared with Object mode, so
					// an object tool armed before the switch must not leak in and drive the Edit overlay.
					latches.clearTransient(clearViewportGesture = true)
					// Restore each seeded drawable's remembered elements where they still fit its mesh;
					// the rest start empty (a different mesh set no longer forgets the others' memory).
					elementMemory.restore(MeshSelection.editing(seedDrawableIds, seedActiveId), model)
				}
				EditorMode.Object -> {
					// Stash each mesh's element selection so re-entering Edit mode restores it per mesh.
					elementMemory.stash(mutableMeshSelection.value)
					latches.clearTransient(clearViewportGesture = true)
					MeshSelection()
				}
			}
		commitStep(EditorStateChange.ModeChanged(mode), meshSelection = newMeshSelection, mode = mode)
	}

	/**
	 * Records an Edit-mode element-selection gesture as its own undo step (the same Blender-faithful
	 * granularity as object selection), so a misclick that loses a selection is recoverable. A no-op
	 * (selecting the already-current selection) records nothing. Not a document edit — leaves dirty
	 * untouched.
	 *
	 * @param MeshSelection meshSelection The new element selection.
	 */
	fun setMeshSelection(meshSelection: MeshSelection) {
		if (meshSelection == mutableMeshSelection.value) {
			return
		}
		commitStep(EditorStateChange.MeshSelectionChanged, meshSelection = meshSelection)
	}

	/**
	 * Records a keyform-sheet key-selection gesture as its own undo step, so a misclick that loses a
	 * carefully built multi-key selection is recoverable.  A no-op records nothing.  Not a document edit -
	 * leaves dirty untouched.
	 *
	 * Shared session state rather than a sheet's view state, for the same reason the parameter target is: a
	 * thing that undo restores has to live where undo can reach it, and two open sheets agreeing on one
	 * selection is the same trade [setParameterSelection] already made.
	 *
	 * Also the CONFIRM half of [stageKeySelection]: calling this with the staged selection after the
	 * gesture's edit records the selection exactly when the edit did not.
	 *
	 * @param Set<TrackKeyRef> keySelection The new key selection.
	 */
	fun setKeySelection(keySelection: Set<TrackKeyRef>) {
		// Compared against what HISTORY holds, not against the live flow, because a stage has already moved
		// the flow: comparing there makes a confirm look like a no-op in precisely the case it exists for -
		// the one where the edit it followed recorded nothing and the staged selection reached no snapshot.
		if (keySelection == history.current.keySelection) {
			// Published rather than merely dropped: this step records nothing new, which is not the same as
			// the live flow already holding it (a restore or an abandoned stage can leave it elsewhere).
			mutableKeySelection.value = keySelection
			return
		}
		commitStep(EditorStateChange.KeySelectionChanged, keySelection = keySelection)
	}

	/**
	 * Publishes [keySelection] WITHOUT recording a step of its own, for a gesture whose own step is about to
	 * follow.
	 *
	 * A keyform-sheet gesture selects and edits at once - a drag re-points the selection at the ordinals its
	 * keys landed on, an empty-track press drops the selection and scrubs - and they are one gesture, so
	 * they are one entry.  [snapshot] defaults every field it is not given to live state, so staging the
	 * selection BEFORE the edit folds it into that edit's own step.  Recording both would make one drag take
	 * two presses of Ctrl+Z to reverse.
	 *
	 * STAGE, EDIT, CONFIRM - every caller runs all three.  The edit may decline to record anything (a drag
	 * clamped to zero, a scrub that ended where it began, a move onto a key's own value), and a stage that
	 * reached no snapshot is a selection change undo cannot see.  So the gesture ends by calling
	 * [setKeySelection] with the same selection: that compares against history rather than against the live
	 * flow, so it is a no-op when the edit's push already carried the selection and records a step of its
	 * own when it did not.  Staging without confirming is a bug, not a shortcut.
	 *
	 * @param Set<TrackKeyRef> keySelection The new key selection.
	 */
	fun stageKeySelection(keySelection: Set<TrackKeyRef>) {
		mutableKeySelection.value = keySelection
	}

	/**
	 * Selects [keySelection] and moves the pose to [pose] as ONE undo step, named for the selection.
	 *
	 * Clicking a mark in the keyform sheet does both - it selects the key and lands the pose exactly on it -
	 * and they are one gesture, so they are one entry.  Naming it for the selection rather than for the
	 * scrub is what makes the history read as what the user did: they clicked a keyframe, and the pose
	 * moving is the consequence.
	 *
	 * Recorded even when the pose does not move (clicking a key the pose already sits on), which is why this
	 * exists rather than staging the selection and letting a pose commit carry it: the pose commit
	 * short-circuits on an unchanged pose, and the selection would then vanish from history entirely.
	 *
	 * While the pose is pinned ([posePinned]) the click selects and the pose stays: selecting a key is a
	 * selection, which Edit mode allows, and landing on it is a pose move, which it does not.
	 *
	 * @param Set<TrackKeyRef> keySelection The keys the click selected.
	 * @param Pose landedPose The pose the click landed on, which is taken only while the pose is free to move.
	 */
	fun selectKeysAtPose(keySelection: Set<TrackKeyRef>, landedPose: Pose) {
		val pose = if (posePinned) mutablePose.value else landedPose
		// Against history rather than the live flow, for the reason [setKeySelection] spells out: a staged
		// selection has already moved the flow, and a click that lands on the selection a stage left there
		// must still record it.
		if (keySelection == history.current.keySelection && pose == history.current.pose) {
			mutableKeySelection.value = keySelection
			mutablePose.value = pose
			return
		}
		commitStep(EditorStateChange.KeySelectionChanged, pose = pose, keySelection = keySelection)
	}

	/**
	 * Where the 2D cursor is: its placed point, or the world origin while it is unplaced.  An unplaced
	 * cursor is not drawn, but everything that uses it as a point - the transform pivot in
	 * [TransformPivotMode.Cursor] and the snap commands - treats it as resting on the world axes, the way
	 * Blender's 3D cursor starts at the origin.  Resolved on read, so the persisted cursor stays unplaced.
	 *
	 * @return Cursor2d The cursor's world position.
	 */
	fun cursor2dOrWorldOrigin(): Cursor2d =
		cursor2d.value ?: model.value.let { current -> Cursor2d(current.worldOriginX, current.worldOriginZ) }

	/**
	 * Whether the grid follows the application's default rather than a value the document brought with it.
	 *
	 * The viewport binding pushes the default grid setting into the session at open and on every change to it; a
	 * document that saved a grid of its own keeps it against that push, which is what makes it the document's.
	 */
	val gridFollowsApplication: Boolean = openingViewState?.gridConfig == null

	/**
	 * The session state a saved document carries (docs/format/UMA.md § 7.4), as it stands now - the gather side of
	 * the constructor's initialViewState.  The pose is not part of it; a save reads [pose] beside it.
	 *
	 * @return SessionViewState The state to save.
	 */
	fun viewState(): SessionViewState =
		SessionViewState(
			selection = mutableSelection.value,
			parameterSelection = mutableParameterSelection.value,
			mode = mutableMode.value,
			selectMode = mutableMeshSelection.value.selectMode,
			cursor2d = settings.cursor2d.value,
			uvCursor = settings.uvCursor.value,
			pivotMode = settings.pivotMode.value,
			proportionalEnabled = settings.proportionalEdit.value != null,
			proportionalSettings = settings.proportionalSettings,
			gridConfig = settings.gridConfig.value.takeUnless { gridFollowsApplication },
		)

	/**
	 * Steps back one undo level, republishing the model and selection. No-op when nothing to undo, and while
	 * a select drag is held ([viewportGestureActive]): the drag lands or is abandoned first (Blender parity),
	 * rather than landing on top of the restored state and wiping redo.
	 */
	fun undo() {
		if (latches.viewportGestureActive.value) {
			return
		}
		restore(history.undo() ?: return)
	}

	/**
	 * Steps forward one redo level, republishing the model and selection. No-op when nothing to redo, and
	 * while a select drag is held (see [undo]).
	 */
	fun redo() {
		if (latches.viewportGestureActive.value) {
			return
		}
		restore(history.redo() ?: return)
	}

	/**
	 * Jumps the history cursor directly to [index], republishing the model and selection at that step. The
	 * history panel calls this when a row is clicked, so the user can leap across several undo levels at
	 * once. No-op when [index] is already the live step, and while a select drag is held (see [undo]).
	 *
	 * @param Int index The target step index within [historyView].
	 */
	fun jumpTo(index: Int) {
		if (latches.viewportGestureActive.value) {
			return
		}
		restore(history.jumpTo(index) ?: return)
	}

	/**
	 * Marks the live model as the saved baseline, clearing the dirty marker - the form for a save that
	 * wrote the model that is current now.
	 */
	fun markSaved() {
		markSaved(mutableModel.value)
	}

	/**
	 * Marks [model] as the saved baseline: the instance a save snapshotted and wrote, which is the live
	 * model unless an edit landed while the file was being written.  In that case the document stays
	 * dirty, since what is on screen is not what is on disk, and undoing back to [model] clears it.
	 *
	 * @param PuppetModel model The model instance just persisted.
	 */
	fun markSaved(model: PuppetModel) {
		history.markSaved(model)
		refreshFlags()
	}

	/**
	 * Restores every session flow from [snapshot] - the history mechanism behind undo, redo, and jumpTo.
	 * Also updates the remembered active drawable and tears down all transient tool state (see the inline
	 * comment), publishes the snapshot through [publish], and republishes the derived flags.
	 *
	 * @param EditorSnapshot snapshot The history snapshot to restore.
	 */
	private fun restore(snapshot: EditorSnapshot) {
		// The strip belongs to the newest step only: moving the cursor ends the adjustable operation,
		// and voids the base so nothing can register against the step just left.
		mutableAdjustableOperation.value = null
		lastBaseSnapshot = null
		// The remembered drawable tracks whatever was most recently shown active, undo/redo included.
		(snapshot.selection.active as? SelectionTarget.Drawable)?.let { activeDrawable ->
			elementMemory.lastActiveDrawableId = activeDrawable.id
		}
		// An undo / redo ends any in-flight gesture or armed tool, regardless of the restored mode: the select
		// tool and its overlays are shared across modes, so a tool armed in one mode must not survive a restore
		// into a snapshot of the other and drive the wrong overlay.  Cleared BEFORE the flows publish, so the
		// mode never flips while a tool is still armed.
		latches.clearTransient(clearAxisConstraint = true, clearViewportGesture = true)
		latches.setPreviewSelection(null)
		latches.setMeshPreviewSelection(null)
		latches.closePieMenu()
		// The pending edits are published, not cleared: the snapshot carries the pose these values were chosen
		// for, so publishing the pair together keeps them coherent - an undo must land on the step's pose WITH
		// the step's pending edits, not on the pose alone.
		publish(snapshot)
		refreshFlags()
	}

	/**
	 * Republishes the flags derived from the model and the history stack - dirty, canUndo, canRedo, and
	 * the projected history view.  Called after every mutation, restore, and saved-baseline move.
	 */
	private fun refreshFlags() {
		history.refreshFlags(mutableModel.value)
	}
}