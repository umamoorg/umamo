package org.umamo.edit

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.KeyableTarget
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.WarpLatticeForm

/**
 * The single mutable owner of one open document: the live [model], the ephemeral editor state
 * ([selection], [mode]), the undo [History], and the change-event bus. Every edit flows through here so
 * undo, change events, and dirty-tracking stay consistent.
 *
 * [changes] announces every step the history records, and every amend of one: a document mutation
 * ([mutate]), a selection gesture ([setSelection]), and a mode change ([setMode]) each push one step.  The
 * tool latches and tool settings are transient session state - never steps, and not on the bus.
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
	// the session's own extension files in :edit and its domain subpackages - nothing outside the module
	// may touch them.
	private val history = HistoryCore(openingSnapshot, initialHistoryLimit)
	internal val elementMemory = MeshElementMemory()

	// The mode Edit's toggle returns to: the mode Edit was entered from (Blender's Tab).  Session-side memory
	// like the element memory, not snapshot state, so undo never rewrites it, and never saved, so a document
	// reopened in Edit mode returns to Object.
	private var editReturnMode: EditorMode = EditorMode.Object
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
	 * Deliberately NOT in the document, but in the undo history: one step per gesture end
	 * ([commitPendingChannelEdit]), never per preview frame.  Every pending value is cleared whenever the pose
	 * moves, because a value chosen FOR one pose is meaningless at another; a history jump restores them with the
	 * very pose they were chosen for.  A keyform insert consumes them, so `I` captures what was just typed rather
	 * than what is still stored.
	 */
	val pendingChannelEdits: StateFlow<Map<KeyableTarget, ChannelValue>> = mutablePendingChannelEdits.asStateFlow()

	private val mutablePendingLatticeEdits = MutableStateFlow<Map<DeformerId, WarpLatticeForm>>(emptyMap())

	/**
	 * Warp lattices shaped in Pose mode but NOT yet keyed, each standing in for its warp's grid blend at the pose -
	 * the lattice counterpart of [pendingChannelEdits], under the same rules: never in the document, one undo step
	 * per finished gesture ([commitPendingLatticeEdits]), discarded whenever the pose moves, and restored with the
	 * pose by a history jump.  A commit that rewrites a warp's lattice forms or its parent also drops that warp's
	 * entry, since the shape was taken against the forms it replaced.
	 *
	 * The renderer poses with them, the lattice draws in the "modified, unkeyed" color while one is pending, and a
	 * keyform insert on the warp consumes it.
	 */
	val pendingLatticeEdits: StateFlow<Map<DeformerId, WarpLatticeForm>> = mutablePendingLatticeEdits.asStateFlow()

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
	 * The live interaction mode.  Snapshotted (a mode change is its own undo step), and pose-neutral by
	 * contract: entering or leaving Edit mode NEVER writes the pose - Edit mode's rest view is a display rule
	 * ([EditorMode.shownPose]), so the Object-mode pose survives an Edit session untouched with no stashed state
	 * to juggle.
	 */
	val mode: StateFlow<EditorMode> = mutableMode.asStateFlow()

	private val mutableMeshSelection = MutableStateFlow(openingSnapshot.meshSelection)

	/** The live Edit-mode element selection; snapshotted, so a selection gesture is undoable. */
	val meshSelection: StateFlow<MeshSelection> = mutableMeshSelection.asStateFlow()

	/**
	 * The current transient user notice, or null when none is showing - a short message the shell surfaces
	 * briefly to explain why an action did nothing, kept off the undo history and the change bus.  A StateFlow
	 * rather than a one-shot event, so a late subscriber still sees an in-flight notice; the [Notice.serial] lets
	 * the shell time its dismissal and re-trigger on a repeat of the same text.
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

	/** The change-event bus: every recorded step, and every amend of one, emits here. */
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
	 * Lowering it trims the stack at once, never past the live step ([History.limit]); the write republishes the
	 * derived flags so the panel and [canUndo] follow the trim.
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
	 * Records one undo step whose state is the live state with the given fields replaced, and publishes it.
	 * The one path every step takes: a member that pushes a step calls this rather than writing the flows
	 * itself, and an operation in another file reaches the history only through here.
	 *
	 * Every field defaults to the LIVE value, never to an empty one, so a field added later is carried unchanged
	 * by every existing caller instead of silently recorded empty.  The exceptions are the pending edits: a pose
	 * move discards them, because every pending value was chosen FOR the pose being left, and [pendingLatticeEdits]
	 * also drops the warps whose lattice forms or parent [model] rewrites ([pendingLatticesCarried]).
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
	 * @param Map<DeformerId, WarpLatticeForm> pendingLatticeEdits The unkeyed Pose-mode lattices after the step.
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
		pendingLatticeEdits: Map<DeformerId, WarpLatticeForm> = if (pose == mutablePose.value) pendingLatticesCarried(mutablePendingLatticeEdits.value, mutableModel.value, model) else emptyMap(),
	) {
		val discardedLattices = if (pose != mutablePose.value) mutablePendingLatticeEdits.value.size else 0
		val snapshot = EditorSnapshot(model, selection, pose, meshSelection, mode, parameterSelection, pendingChannelEdits, keySelection, pendingLatticeEdits)
		pushStep(snapshot, change)
		publish(snapshot)
		refreshFlags()
		mutableChanges.tryEmit(change)
		reportDiscardedLattices(discardedLattices)
	}

	/**
	 * Says near the cursor that a pose move discarded [count] pending lattices; nothing for none.
	 *
	 * @param Int count How many were discarded.
	 */
	private fun reportDiscardedLattices(count: Int) {
		if (count > 0) {
			notices.emit("notice.pose.latticeEditsDiscarded", NoticePlacement.NearCursor, listOf(count.toString()))
		}
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
		mutablePendingLatticeEdits.value = snapshot.pendingLatticeEdits
		mutableKeySelection.value = snapshot.keySelection
		mutableMode.value = snapshot.mode
	}

	/**
	 * Registers the operation that just committed [committedModel] as adjustable: the strip shows its
	 * [parameters], and editing one calls [rerun] with the updated record, which recomputes from the
	 * record's base and lands through [amendLastCommit].
	 *
	 * Call it right after the operation's own commit; a client that committed nothing (a no-op edit) is
	 * refused, so it never registers against some other step's base.
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
	 * A no-op while no operation is adjustable.  The rerun runs even when the settings did not change, and
	 * reads [adjustedKey] off the record it is given to learn which one was set.
	 *
	 * @param List    parameters  The new settings, in the record's order.
	 * @param String? adjustedKey The key of the setting the rigger set (even to the value it had), or null.
	 */
	fun adjustLastOperation(parameters: List<OperatorParameter>, adjustedKey: String? = null) {
		val current = mutableAdjustableOperation.value ?: return
		val updated = current.withParameters(parameters, adjustedKey)
		mutableAdjustableOperation.value = updated
		updated.rerun(updated)
	}

	/**
	 * Rewrites the live adjustable operation's settings WITHOUT running it again - for an operation
	 * whose rerun decides that one setting re-seeds others (a preset choice filling in its numbers): the
	 * rows then show what the rerun is about to use.  The record keeps its identity, so a rerun given the
	 * revised record still lands through [amendLastCommit].
	 *
	 * @param AdjustableOperation operation  The record the rerun was given.
	 * @param List                parameters The settings to show, in the record's order.
	 * @return AdjustableOperation? The revised record, or null when [operation] is no longer the live one.
	 */
	fun reviseAdjustableParameters(operation: AdjustableOperation, parameters: List<OperatorParameter>): AdjustableOperation? {
		val current = liveRecordOf(operation) ?: return null
		val revised = current.withParameters(parameters)
		mutableAdjustableOperation.value = revised

		return revised
	}

	/**
	 * Lands an adjustment: rewrites the operation's own history step in place with [model] ([History.amendTop])
	 * and publishes it, keeping the record live for the next adjustment.  A rerun whose record is no longer live
	 * is dropped, never committed over whatever the rigger did meanwhile.
	 *
	 * @param AdjustableOperation operation The record the rerun was given.
	 * @param PuppetModel model The recomputed document model.
	 * @return Boolean True when the step was replaced and published.
	 */
	fun amendLastCommit(operation: AdjustableOperation, model: PuppetModel): Boolean = amendLiveStep(operation, { snapshot(model = model) }) { mutableModel.value = model }

	/**
	 * Lands an adjustment of a pending-lattice step - [amendLastCommit]'s counterpart for an operation that changed no
	 * model: the step is rewritten in place with [forms] laid over the pending lattices it started from, and published.
	 *
	 * Refuses as [amendLastCommit] does: when [operation] is not the live record, or the stack refuses the amend.
	 *
	 * @param AdjustableOperation operation The record the rerun was given.
	 * @param Map<DeformerId, WarpLatticeForm> forms The recomputed lattices of the warps the operation shaped.
	 * @return Boolean True when the step was replaced and published.
	 */
	fun amendLastPendingLatticeEdits(operation: AdjustableOperation, forms: Map<DeformerId, WarpLatticeForm>): Boolean {
		val pending = operation.baseSnapshot.pendingLatticeEdits + forms

		return amendLiveStep(operation, { snapshot(pendingLatticeEdits = pending) }) { mutablePendingLatticeEdits.value = pending }
	}

	/**
	 * The live adjustable record [operation] names, or null when it is no longer live: another push, an undo, or a
	 * document swap cleared it while a rerun was in flight.
	 *
	 * @param AdjustableOperation operation The record a rerun was given.
	 * @return AdjustableOperation? The live record (possibly a revised instance of it), or null.
	 */
	private fun liveRecordOf(operation: AdjustableOperation): AdjustableOperation? = mutableAdjustableOperation.value?.takeIf { current -> current.identity === operation.identity }

	/**
	 * Rewrites live [operation]'s own step in place with the snapshot [amended] builds ([History.amendTop]), then
	 * applies it to the live state through [publish] and announces it, keeping the record live for the next
	 * adjustment.  The one amend both [amendLastCommit] and [amendLastPendingLatticeEdits] land through.
	 *
	 * @param AdjustableOperation operation The record the rerun was given.
	 * @param Function amended Builds the step's new snapshot.
	 * @param Function publish Writes the amended state to its flow.
	 * @return Boolean True when the step was replaced and published; false when the record is not live or the stack
	 *   refuses the amend.
	 */
	private fun amendLiveStep(operation: AdjustableOperation, amended: () -> EditorSnapshot, publish: () -> Unit): Boolean {
		if (liveRecordOf(operation) == null || !history.amendTop(amended(), operation.change)) {
			return false
		}

		publish()
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
	 * Commits one undo step from an already-computed [model] and [pose] - the choke point behind [mutate]
	 * (model edits) and [commitPose] (scrubs).  A commit that changes neither records nothing, so callers need
	 * not pre-check.  Dirty is measured against the model only, so a pose-only commit is an undo step without
	 * marking the document unsaved.
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
	 * A snapshot of the session's current state, with any field overridden - what an amend rewrites the live
	 * step with.
	 *
	 * Every field defaults to live state rather than to [EditorSnapshot]'s empty defaults, so a field added
	 * later is carried unchanged at every existing call site instead of silently recorded empty.
	 *
	 * @param PuppetModel model The document model.
	 * @param Selection selection The object selection.
	 * @param Pose pose The pose.
	 * @param MeshSelection meshSelection The Edit-mode element selection.
	 * @param EditorMode mode The interaction mode.
	 * @param ParameterSelection parameterSelection The keyform-authoring target.
	 * @param Map<KeyableTarget, ChannelValue> pendingChannelEdits The unkeyed channel edits.
	 * @param Set<TrackKeyRef> keySelection The keyform-sheet key selection.
	 * @param Map<DeformerId, WarpLatticeForm> pendingLatticeEdits The unkeyed Pose-mode lattices.
	 * @return EditorSnapshot The live state with the given fields replaced.
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
		pendingLatticeEdits: Map<DeformerId, WarpLatticeForm> = mutablePendingLatticeEdits.value,
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
			pendingLatticeEdits,
		)

	/** Whether the current mode pins the pose (see [pinsPose]), which refuses every pose move asked of the session. */
	val posePinned: Boolean get() = mutableMode.value.pinsPose

	/**
	 * The pose the editor shows, and so where an edit aimed at "the pose" acts: the rig's pose, and while it
	 * is pinned, every parameter at its default.  Edit mode shows the rig at rest, so a key inserted "at the
	 * pose" there lands where the rigger is looking, not at a pose that returns only when Edit mode is left.
	 * The rule itself is [EditorMode.shownPose], which a view collecting the pose and mode reads directly.
	 */
	val shownPose: Pose get() = mutableMode.value.shownPose(mutableModel.value, mutablePose.value)

	/**
	 * Commits a parameter scrub as one undo step: the live [pose] reached a new resting position (a slider
	 * or 2D-pad gesture released, a value typed, a reset).  Mid-gesture preview frames bypass this and reach
	 * the renderer directly, so a whole drag is a single step.
	 *
	 * Refused while the pose is pinned ([posePinned]), whoever asks: in Edit mode the pose a view holds is the
	 * rest pose it is shown, and recording that as the rig's pose would drop every value the rig held.  A
	 * document edit that moves the pose with it (a range narrowed under the value) goes through [commitStep]
	 * and is not refused.
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
	 * the behavior rather than a limitation - the value was chosen FOR this pose, so carrying it to another
	 * would be applying an edit somewhere it was never meant.
	 *
	 * @param KeyableTarget target The property edited.
	 * @param ChannelValue value The value typed.
	 */
	fun setPendingChannelEdit(target: KeyableTarget, value: ChannelValue) {
		mutablePendingChannelEdits.value = mutablePendingChannelEdits.value + (target to value)
	}

	/**
	 * Records [value] as an unkeyed edit of [target] AND as one undo step, described by [change] - what a
	 * keyable property field calls when its gesture ends, where [setPendingChannelEdit] is its per-frame preview.
	 *
	 * A pending edit is transient, but it is a deliberate edit the user sees take effect, so it is undoable
	 * whether or not it ever reaches the document.  [change] is the same descriptor a stored write of the
	 * property uses, so the history entry reads alike whichever branch the edit took.
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
			// outright would leave the re-typed value out of the viewport and the field untinted.
			setPendingChannelEdit(target, value)

			return
		}

		commitStep(change, pendingChannelEdits = mutablePendingChannelEdits.value + (target to value))
	}

	/**
	 * Discards every pending unkeyed edit because a pose PREVIEW moved - a scrub frame that records no step -
	 * since every pending value was chosen for the pose being left; a recorded pose move discards them through
	 * [commitStep]'s default instead.
	 */
	fun clearPendingChannelEdits() {
		if (mutablePendingChannelEdits.value.isNotEmpty()) {
			mutablePendingChannelEdits.value = emptyMap()
		}
	}

	/**
	 * Discards every pending edit because a pose PREVIEW moved: the unkeyed channel values ([clearPendingChannelEdits])
	 * and the unkeyed Pose-mode lattices, saying near the cursor how many lattices went.  The preview path's form of
	 * the rule [commitStep] applies to a recorded pose move; undo brings them back with the step they were made in.
	 */
	fun discardPendingEditsForPose() {
		clearPendingChannelEdits()
		val discardedLattices = mutablePendingLatticeEdits.value.size

		if (discardedLattices > 0) {
			mutablePendingLatticeEdits.value = emptyMap()
			reportDiscardedLattices(discardedLattices)
		}
	}

	/**
	 * Discards every unkeyed edit held at the pose - the channel values and the Pose-mode lattices - as ONE undo step,
	 * so undo brings them back.  The model and the pose are untouched, so this is not a document edit.  Nothing to
	 * discard records nothing, and neither does a mode that pins the pose: it shows none of them.
	 */
	fun clearPoseEdits() {
		val nothingHeld = mutablePendingChannelEdits.value.isEmpty() && mutablePendingLatticeEdits.value.isEmpty()

		if (nothingHeld || posePinned) {
			return
		}

		commitStep(EditorStateChange.PoseEditsCleared, pendingChannelEdits = emptyMap(), pendingLatticeEdits = emptyMap())
	}

	/**
	 * Records [forms] as Pose-mode lattices shaped but not keyed, laid over the pending lattices already held, as ONE
	 * undo step described by [change] - what a finished Pose-mode G / R / S, slide, or snap over lattice points
	 * commits.  The model is untouched, so this is not a document edit and leaves dirty alone; the renderer poses with
	 * the lattices until the pose moves or a key consumes them.  A step that would change nothing records nothing.
	 *
	 * @param Change change The descriptor of this edit (a [DeformerChange.PoseLattice]).
	 * @param Map<DeformerId, WarpLatticeForm> forms Each shaped warp's lattice in its parent's space, standing in for
	 *   its grid blend at the pose.
	 */
	fun commitPendingLatticeEdits(change: Change, forms: Map<DeformerId, WarpLatticeForm>) {
		val pending = mutablePendingLatticeEdits.value + forms

		if (forms.isEmpty() || pending == mutablePendingLatticeEdits.value) {
			return
		}

		commitStep(change, pendingLatticeEdits = pending)
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
	 * Puts the pending unkeyed edit of [target] back to what history holds - the cancelled-scrub path.
	 *
	 * A scrub previews through [setPendingChannelEdit] frame by frame, so a cancelled one leaves its last
	 * frame in the live map with nothing to retire it.  [clearPendingChannelEdit] would be lossy here: a value
	 * COMMITTED earlier through [commitPendingChannelEdit] is pending too, and the scrub was previewing over
	 * it, so it is restored rather than dropped; a target history never recorded is removed.
	 *
	 * @param KeyableTarget target The property whose preview was cancelled.
	 */
	fun restorePendingChannelEdit(target: KeyableTarget) {
		val live = mutablePendingChannelEdits.value
		val recorded = history.current.pendingChannelEdits[target]
		val restored =
			if (recorded == null) {
				live - target
			} else {
				live + (target to recorded)
			}

		if (restored != live) {
			mutablePendingChannelEdits.value = restored
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

		elementMemory.rememberActiveOwnerOf(selection)
		commitStep(EditorStateChange.SelectionChanged, selection = selection)
	}

	/**
	 * Sets the parameters targeted for keyform authoring as its own undo step.  Shared session state rather
	 * than a panel's view state, so every area agrees on which parameter an insert would write to.
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
	 * Sets the interaction mode as its own undo step, so undo restores the mode (and the selections
	 * it seeds) together.
	 *
	 * @param EditorMode mode The new mode.
	 */
	fun setMode(mode: EditorMode) {
		if (mode == mutableMode.value) {
			return
		}

		when (mode) {
			EditorMode.Edit -> {
				val model = mutableModel.value
				// The session spans EVERY selected owner of the active item's kind (multi-mesh edit, needed for
				// glue work; several lattices at once); the object selection's active item becomes the session's
				// active owner.  Nothing editable refuses Edit rather than opening an inert session (Blender needs
				// an active object too): stay in the current mode, record nothing, and say why when there is a why.
				val editSeed =
					when (val entry = editEntryOf(model, mutableSelection.value, elementMemory.lastActiveOwner)) {
						is EditEntry.Opens -> {
							entry.seed
						}

						is EditEntry.Refused -> {
							entry.noticeKey?.let { key -> notices.emit(key, NoticePlacement.NearCursor, emptyList()) }

							return
						}
					}
				// The mode Edit was entered from is where its toggle returns to.
				editReturnMode = mutableMode.value
				openSeededSession(mode, editSeed, model)
			}

			EditorMode.Pose -> {
				val model = mutableModel.value

				// Refuse with a notice rather than open a mode that can do nothing, as Edit refuses a model with
				// nothing editable.
				if (!model.canPose) {
					notices.emit("notice.mode.poseNeedsDeformer", NoticePlacement.NearCursor, emptyList())

					return
				}

				// Pose mode works on lattice points as Edit mode does, over the warps the pose draws (seeded like
				// Tab), and an empty session when there are none - it still scrubs and keys.
				openSeededSession(mode, poseSeedOf(model, mutableSelection.value, elementMemory.lastActiveOwner, mutablePose.value), model)
			}

			EditorMode.Object -> {
				// Stash each owner's element selection so re-entering Edit or Pose mode restores it per owner; the
				// empty session keeps the select mode, so the next entry selects in it.
				elementMemory.stash(mutableMeshSelection.value)
				latches.clearTransient(clearViewportGesture = true)
				commitStep(EditorStateChange.ModeChanged(mode), meshSelection = MeshSelection(selectMode = mutableMeshSelection.value.selectMode), mode = mode)
			}
		}
	}

	/**
	 * Enters [mode], Edit or Pose, on the element session [seed] opens over [model], or an empty one for no seed, as ONE
	 * undo step with the object selection the seed lands on ([EditSeed.selectionOver]), so the panels show what the
	 * session edits and the next mode switch seeds from it.
	 *
	 * @param EditorMode mode The mode entered, Edit or Pose.
	 * @param EditSeed? seed The owners to open on, or null for none.
	 * @param PuppetModel model The model the owners' topologies come from.
	 */
	private fun openSeededSession(mode: EditorMode, seed: EditSeed?, model: PuppetModel) {
		if (seed != null && !seed.activeSelected) {
			elementMemory.lastActiveOwner = seed.activeOwner
		}

		// The select tool is shared with Object mode, so a tool armed before the switch must not drive the element overlay.
		latches.clearTransient(clearViewportGesture = true)
		val selectMode = mutableMeshSelection.value.selectMode
		elementMemory.stash(mutableMeshSelection.value)
		val newSelection = seed?.selectionOver(mutableSelection.value) ?: mutableSelection.value
		val newMeshSelection = seed?.let { opened -> elementMemory.restore(opened.session.copy(selectMode = selectMode), model) } ?: MeshSelection(selectMode = selectMode)
		commitStep(EditorStateChange.ModeChanged(mode), selection = newSelection, meshSelection = newMeshSelection, mode = mode)
	}

	/**
	 * Toggles Edit mode, Blender's Tab: enters Edit from Object or Pose mode, and leaves it for the mode it was
	 * entered from.  Each switch is one [setMode] step, refused as that refuses.
	 */
	fun toggleEditMode() {
		if (mutableMode.value == EditorMode.Edit) {
			// A return to Pose mode the model can no longer hold (its last deformer was deleted during the edit)
			// lands in Object mode instead, so the toggle can never strand the session in Edit mode.
			val returnMode =
				if (editReturnMode == EditorMode.Pose && !mutableModel.value.canPose) {
					EditorMode.Object
				} else {
					editReturnMode
				}
			setMode(returnMode)
		} else {
			setMode(EditorMode.Edit)
		}
	}

	/**
	 * Toggles Pose mode: leaves it for Object mode, and enters it from Object or Edit mode.  Each switch is one
	 * [setMode] step, refused as that refuses.
	 */
	fun togglePoseMode() {
		if (mutableMode.value == EditorMode.Pose) {
			setMode(EditorMode.Object)
		} else {
			setMode(EditorMode.Pose)
		}
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
	 * carefully built multi-key selection is recoverable.  Not a document edit - leaves dirty untouched.
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
	 * Publishes [keySelection] WITHOUT recording a step of its own, for a keyform-sheet gesture that selects
	 * and edits at once: [commitStep] carries every field it is not given from live state, so staging the
	 * selection BEFORE the edit folds it into that edit's own step, and one drag takes one Ctrl+Z.
	 *
	 * STAGE, EDIT, CONFIRM - every caller runs all three.  The edit may record nothing (a drag clamped to zero,
	 * a scrub that ended where it began), and a stage that reached no snapshot is a selection change undo cannot
	 * see, so the gesture ends with [setKeySelection] on the same selection, which records a step only when the
	 * edit's push did not carry it.  Staging without confirming is a bug, not a shortcut.
	 *
	 * @param Set<TrackKeyRef> keySelection The new key selection.
	 */
	fun stageKeySelection(keySelection: Set<TrackKeyRef>) {
		mutableKeySelection.value = keySelection
	}

	/**
	 * Selects [keySelection] and moves the pose to [landedPose] as ONE undo step named for the selection - a
	 * click on a keyform-sheet mark, where the pose moving is the consequence of selecting the key.
	 *
	 * Recorded even when the pose does not move, which a staged selection carried by a pose commit would not
	 * be, since that commit short-circuits on an unchanged pose.  While the pose is pinned ([posePinned]) the
	 * click selects and the pose stays.
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
			pivotDepth = settings.pivotDepth.value,
			depthSurface = settings.depthSurface.value,
			proportionalEnabled = settings.proportionalEdit.value != null,
			proportionalSettings = settings.proportionalSettings.value,
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
	 *
	 * @param EditorSnapshot snapshot The history snapshot to restore.
	 */
	private fun restore(snapshot: EditorSnapshot) {
		// The strip belongs to the newest step only: moving the cursor ends the adjustable operation,
		// and voids the base so nothing can register against the step just left.
		mutableAdjustableOperation.value = null
		lastBaseSnapshot = null
		// The remembered owner tracks whatever was most recently shown active, undo/redo included.
		elementMemory.rememberActiveOwnerOf(snapshot.selection)
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