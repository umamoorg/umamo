package org.umamo.ui.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import org.umamo.edit.EditorSession
import org.umamo.edit.SelectionTarget
import org.umamo.edit.relation.RelationSlot
import org.umamo.edit.relation.relationAdmitter
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.partByDrawable
import org.umamo.ui.transform.bindRelation

/**
 * The kinds of rig entity a relation pick will accept, mirroring [SelectionTarget]'s taxonomy without
 * carrying an id.  A request names the kinds its field can bind, so the viewport overlay and the outliner
 * both know which clicks to honour and which to ignore.
 */
enum class PickKind {
	/** An organizational tree part. */
	Part,

	/** A textured drawable mesh. */
	Drawable,

	/** A warp or rotation deformer. */
	Deformer,
}

/**
 * The kind of [target], so a request's accepted set can be tested against a concrete pick.
 *
 * @return PickKind The target's kind.
 */
fun SelectionTarget.pickKind(): PickKind =
	when (this) {
		is SelectionTarget.Part -> PickKind.Part
		is SelectionTarget.Drawable -> PickKind.Drawable
		is SelectionTarget.Deformer -> PickKind.Deformer
	}

/**
 * The kinds a pick for [slot] accepts: A part for an parent part, a deformer for a parent deformer, and for clip masks
 * whatever may clip the owner (drawables, and parts too for a part's composite).
 *
 * @param RelationSlot slot The relation.
 * @return Set<PickKind> The accepted kinds.
 */
fun pickKindsOf(slot: RelationSlot): Set<PickKind> =
	when (slot) {
		is RelationSlot.ParentPart -> setOf(PickKind.Part)
		is RelationSlot.ParentDeformer -> setOf(PickKind.Deformer)
		is RelationSlot.Masks, is RelationSlot.Mask -> if (slot.owner is SelectionTarget.Part) setOf(PickKind.Drawable, PickKind.Part) else setOf(PickKind.Drawable)
	}

/**
 * What a surface should do with a click while a relation pick may be armed.  Stated once here because the
 * wrong answer is subtle: letting an unaccepted click through changes the selection, which swaps the
 * Properties panel - and the very field that armed the pick - out from under the user mid-interaction.
 */
enum class PickClickOutcome {
	/** The click bound the entity; the pick is over.  The surface should not also select. */
	Resolved,

	/** A pick is armed but this entity is not one it accepts: swallow the click and stay armed. */
	Swallowed,

	/** No pick is armed; the surface should handle the click normally (select it). */
	Ignored,
}

/**
 * One in-flight "pick a relation" interaction: which kinds the arming field accepts, which entities of those
 * kinds it admits, an [owner] key identifying the field that armed it, and the callback that receives the
 * chosen entity.  [onPicked] is invoked at most once - the resolving surface clears the request as it fires.
 *
 * @property Set accepts The entity kinds this pick will honour; a click on anything else is ignored.
 * @property Any? owner The arming field's identity, so only that field lights its eyedropper.
 * @property Function admits Whether an entity of an accepted kind may be bound: the field's own exclusions (a
 *   deformer cannot parent itself), so the eyedropper refuses what the field's list leaves out.
 * @property Function model The document the pick binds in, read as a target resolves, so a drawable can stand for
 *   its part or its parent deformer ([targetFor]); null when there is none, and a drawable then stands only for
 *   itself.
 * @property Function onPicked Receives the picked entity.
 */
class RelationPickRequest(
	val accepts: Set<PickKind>,
	val owner: Any?,
	val admits: (SelectionTarget) -> Boolean,
	private val model: () -> PuppetModel? = { null },
	val onPicked: (SelectionTarget) -> Unit,
) {
	/** The drawable lookups [targetFor] reads, kept until the model changes. */
	private var artLookups: ArtLookups? = null

	/**
	 * Whether this pick would bind [target]: its kind is accepted and the field admits it.
	 *
	 * @param SelectionTarget target The entity under the pointer.
	 * @return Boolean True when a click on it resolves the pick.
	 */
	fun takes(target: SelectionTarget): Boolean = target.pickKind() in accepts && admits(target)

	/**
	 * What a click on [entity] binds, or null when that is nothing this pick takes.  A part or a deformer stands for
	 * itself.  A drawable stands for its art, which binds the first kind the pick accepts: the drawable itself, its
	 * parent part, or its parent deformer.  Deliberately one mapping and not a fallback chain, so a refused drawable
	 * never binds its whole part instead.  Every surface that resolves a pick reads this, so a drawable binds the same
	 * thing whether its art in a viewport or its row in the outliner is clicked.
	 *
	 * @param SelectionTarget entity The entity under the pointer.
	 * @return SelectionTarget? The target a click on it binds, or null.
	 */
	fun targetFor(entity: SelectionTarget): SelectionTarget? {
		val target =
			if (entity !is SelectionTarget.Drawable || PickKind.Drawable in accepts) {
				entity
			} else {
				artStandIn(entity.id)
			}

		return target?.takeIf { candidate -> takes(candidate) }
	}

	/**
	 * What a drawable's art stands for in a pick that does not take the drawable itself: Tts parent part, or its
	 * parent deformer, by the kind the pick accepts.
	 *
	 * @param DrawableId drawableId The drawable.
	 * @return SelectionTarget? The part or the deformer, or null when it has none or the pick has no model.
	 */
	private fun artStandIn(drawableId: DrawableId): SelectionTarget? {
		val current = model() ?: return null
		val lookups = artLookups?.takeIf { cached -> cached.model === current } ?: ArtLookups(current).also { built -> artLookups = built }

		return when {
			PickKind.Part in accepts -> lookups.partOf(drawableId)?.let { partId -> SelectionTarget.Part(partId) }
			PickKind.Deformer in accepts -> lookups.deformerOf(drawableId)?.let { deformerId -> SelectionTarget.Deformer(deformerId) }
			else -> null
		}
	}
}

/**
 * The parent part and the parent deformer of each drawable in [model], each table built on first use and kept for the
 * model's life: a viewport asks on every pointer move, and the parent parts take a walk of the whole org tree.
 *
 * @property PuppetModel model The model the tables describe.
 */
private class ArtLookups(val model: PuppetModel) {
	private val partByDrawable by lazy { model.partByDrawable() }
	private val deformerByDrawable by lazy { model.drawables.associate { drawable -> drawable.id to drawable.parentDeformerId } }

	/**
	 * The part that holds [drawableId] in the org tree.
	 *
	 * @param DrawableId drawableId The drawable.
	 * @return PartId? The part, or null at the root.
	 */
	fun partOf(drawableId: DrawableId): PartId? = partByDrawable[drawableId]

	/**
	 * The deformer that deforms [drawableId].
	 *
	 * @param DrawableId drawableId The drawable.
	 * @return DeformerId? The deformer, or null for none.
	 */
	fun deformerOf(drawableId: DrawableId): DeformerId? = deformerByDrawable[drawableId]
}

/**
 * The shell slot holding the one in-flight relation pick, mirroring [org.umamo.ui.workspace.rowdrag.RowDragCancelController].  A pick is
 * deliberately NOT an [org.umamo.edit.ToolLatches] latch: every latch is scoped to one viewport area, but a
 * pick must also resolve from the outliner.  Parking it here also keeps a panel concern out of the edit
 * module.  One pointer means at most one pick anywhere, so a single slot serves every field.
 *
 * Resolving a pick must NOT go through the real selection: [org.umamo.edit.EditorSession.setSelection]
 * records an undo step and would swap the Properties panel out from under the field that armed the pick.
 *
 * @property RelationPickRequest? request The in-flight pick, or null when none is armed.
 */
class RelationPickController {
	var request: RelationPickRequest? by mutableStateOf(null)
		private set

	/**
	 * What the pointer is currently over, as the in-flight pick would resolve it, or null when it is over
	 * nothing pickable.  Published by the resolving surfaces (the viewport's hit test, an outliner row) so
	 * the shell's cursor overlay can name the target before the user commits to it.
	 */
	var hoveredTarget: SelectionTarget? by mutableStateOf(null)
		private set

	/** The entity a surface last reported under the pointer, which [unhover] matches: [hoveredTarget] is what it binds. */
	private var hoveredEntity: SelectionTarget? = null

	/**
	 * Arms a pick, replacing any already in flight.
	 *
	 * @param Set accepts The entity kinds to honour.
	 * @param Any? owner The arming field's identity, so only it lights its eyedropper.
	 * @param Function admits Whether an entity of an accepted kind may be bound; every one by default.
	 * @param Function model The document the pick binds in, so a drawable can stand for its part or its parent
	 *   deformer; none by default.
	 * @param Function onPicked Receives the picked entity.
	 */
	fun arm(
		accepts: Set<PickKind>,
		owner: Any? = null,
		admits: (SelectionTarget) -> Boolean = { true },
		model: () -> PuppetModel? = { null },
		onPicked: (SelectionTarget) -> Unit,
	) {
		hoveredTarget = null
		hoveredEntity = null
		request = RelationPickRequest(accepts, owner, admits, model, onPicked)
	}

	/**
	 * Arms a pick for [slot]: owned by the slot, which is the field's identity, admitting what the slot admits in the
	 * model as it is when the pointer reaches a target, and binding the pick through bindRelation, so the eyedropper
	 * button and the menu's Pick arm the same pick.  A drawable stands for its part or its parent deformer in the
	 * session's model.
	 *
	 * @param RelationSlot slot The relation.
	 * @param EditorSession session The session the binding is made in.
	 * @param Function stripArea The area the operation strip shows in, asked as the pick lands.
	 */
	fun armFor(slot: RelationSlot, session: EditorSession, stripArea: () -> String?) {
		// The admitter is built once per model, not per test: an armed deformer pick filters every lattice the viewport
		// lists through it, and each build walks the hierarchy.
		var admitterModel: PuppetModel? = null
		var admitter: (SelectionTarget) -> Boolean = { false }
		val admits = { target: SelectionTarget ->
			val model = session.model.value

			if (model !== admitterModel) {
				admitter = model.relationAdmitter(slot)
				admitterModel = model
			}

			admitter(target)
		}
		arm(pickKindsOf(slot), owner = slot, admits = admits, model = { session.model.value }) { target ->
			session.bindRelation(slot, target, stripArea())
		}
	}

	/**
	 * Publishes what the pointer is over, named as what a click there would bind ([RelationPickRequest.targetFor]).
	 * Ignored when no pick is armed, and an entity that binds nothing the pick takes (an unaccepted kind, or one the
	 * field does not admit) reports as nothing - so a surface may report freely without gating itself, and the badge
	 * never names a target the click would refuse.
	 *
	 * @param SelectionTarget? target The entity under the pointer, or null for nothing pickable.
	 */
	fun hover(target: SelectionTarget?) {
		val pending = request ?: return
		hoveredEntity = target
		hoveredTarget = target?.let { entity -> pending.targetFor(entity) }
	}

	/**
	 * Withdraws [target] as the hover, but only when it is still the reported one - so a surface the pointer
	 * has left cannot clear a hover another surface has since published.
	 *
	 * @param SelectionTarget target The entity this surface last reported.
	 */
	fun unhover(target: SelectionTarget) {
		if (hoveredEntity == target) {
			hoveredEntity = null
			hoveredTarget = null
		}
	}

	/**
	 * Whether the in-flight pick was armed by [owner] - the field's cue to light its eyedropper.
	 *
	 * @param Any? owner The field identity to test.
	 * @return Boolean True when a pick armed by that field is in flight.
	 */
	fun isPickingFor(owner: Any?): Boolean = owner != null && request?.owner == owner

	/** Cancels the in-flight pick, if any (a key press, a right-click, or the arming field going away). */
	fun cancel() {
		request = null
		hoveredTarget = null
		hoveredEntity = null
	}

	/**
	 * Cancels the in-flight pick only when [owner] armed it.  Scoped rather than blanket so a field tearing
	 * down cannot cancel a pick some other field owns.
	 *
	 * @param Any? owner The field identity whose pick should be dropped.
	 */
	fun cancelFor(owner: Any?) {
		if (isPickingFor(owner)) {
			cancel()
		}
	}

	/**
	 * How a selecting surface (an outliner row) should treat a click on [target].  While ANY pick is armed
	 * the surface must not change the selection - even for an entity the pick will not accept - because
	 * selecting would swap the Properties panel, and the arming field with it, out from under the pick.
	 *
	 * @param SelectionTarget target The entity the user clicked.
	 * @return PickClickOutcome Resolved (bound it), Swallowed (armed but unaccepted), or Ignored (no pick).
	 */
	fun click(target: SelectionTarget): PickClickOutcome =
		when {
			request == null -> PickClickOutcome.Ignored
			resolve(target) -> PickClickOutcome.Resolved
			else -> PickClickOutcome.Swallowed
		}

	/**
	 * Resolves the in-flight pick with what [target] binds ([RelationPickRequest.targetFor]), clearing the request
	 * first so the callback cannot re-enter.  An entity that binds nothing the pick takes leaves the pick armed, so
	 * a stray click on the wrong thing does not silently end the interaction.
	 *
	 * @param SelectionTarget target The entity the user clicked.
	 * @return Boolean True when the pick was resolved (the caller should consume the click).
	 */
	fun resolve(target: SelectionTarget): Boolean {
		val pending = request ?: return false
		val bound = pending.targetFor(target) ?: return false
		request = null
		hoveredTarget = null
		hoveredEntity = null
		pending.onPicked(bound)

		return true
	}
}

/**
 * Supplies the [RelationPickController] the shell shares with the picking fields and the surfaces that
 * resolve them.  Defaults to a standalone instance so a panel hosted without the shell still composes (its
 * eyedropper simply never resolves).
 */
val LocalRelationPick = staticCompositionLocalOf { RelationPickController() }

/**
 * Whether a pick armed by [owner] is in flight, AND the guarantee that it is cancelled if that field
 * leaves the composition.
 *
 * The two belong together: an armed pick parks a callback plus a full-window hidden cursor in shell state,
 * so a field that arms one and then disappears - a Properties tab switch, a selection change, closing the
 * document - would otherwise strand the editor with an invisible pointer, an eyedropper badge, and a
 * callback closing over entities from a document that is gone.  Reading the flag through this function
 * means a call site cannot take the cue without also taking the cleanup.
 *
 * @param Any? owner The field identity to test.
 * @return Boolean True when a pick armed by that field is in flight.
 */
@Composable
fun RelationPickController.pickingFor(owner: Any?): Boolean {
	DisposableEffect(this, owner) {
		onDispose { cancelFor(owner) }
	}

	return isPickingFor(owner)
}