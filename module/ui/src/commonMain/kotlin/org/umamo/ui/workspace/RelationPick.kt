package org.umamo.ui.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import org.umamo.edit.SelectionTarget

/**
 * The kinds of rig entity a relation pick will accept, mirroring [SelectionTarget]'s taxonomy without
 * carrying an id.  A request names the kinds its field can bind, so the viewport overlay and the outliner
 * both know which clicks to honour and which to ignore.
 *
 * リレーション選択が受け付ける要素種別。
 */
enum class PickKind {
	/** An organisational tree part. */
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
 * One in-flight "pick a relation" interaction: which kinds the arming field accepts, an [owner] key
 * identifying the field that armed it, and the callback that receives the chosen entity.  [onPicked] is
 * invoked at most once - the resolving surface clears the request as it fires.
 *
 * @property Set accepts The entity kinds this pick will honour; a click on anything else is ignored.
 * @property Any? owner The arming field's identity, so only that field lights its eyedropper.
 * @property Function onPicked Receives the picked entity.
 */
class RelationPickRequest(
	val accepts: Set<PickKind>,
	val owner: Any?,
	val onPicked: (SelectionTarget) -> Unit,
)

/**
 * The shell slot holding the one in-flight relation pick, mirroring [RowDragCancelController].  A pick is
 * deliberately NOT an [org.umamo.edit.ToolLatches] latch: every latch is scoped to one viewport area, but a
 * pick must also resolve from the outliner, and the latches are non-reactive vars while both resolving
 * surfaces need to gate their composition on this state.  Parking it here also keeps a panel concern out of
 * the edit module.  One pointer means at most one pick anywhere, so a single slot serves every field.
 *
 * Resolving a pick must NOT go through the real selection: [org.umamo.edit.EditorSession.setSelection]
 * records an undo step and would swap the Properties panel out from under the field that armed the pick.
 *
 * 進行中のリレーション選択を一つだけ預かるシェルのスロット。ビューポートとアウトライナの双方が解決できる。
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

	/**
	 * Arms a pick, replacing any already in flight.
	 *
	 * @param Set accepts The entity kinds to honour.
	 * @param Any? owner The arming field's identity, so only it lights its eyedropper.
	 * @param Function onPicked Receives the picked entity.
	 */
	fun arm(accepts: Set<PickKind>, owner: Any? = null, onPicked: (SelectionTarget) -> Unit) {
		hoveredTarget = null
		request = RelationPickRequest(accepts, owner, onPicked)
	}

	/**
	 * Publishes what the pointer is over.  Ignored when no pick is armed, and a target of an unaccepted kind
	 * reports as nothing - so a surface may report freely without gating itself.
	 *
	 * @param SelectionTarget? target The entity under the pointer, or null for nothing pickable.
	 */
	fun hover(target: SelectionTarget?) {
		val pending = request ?: return
		hoveredTarget = target?.takeIf { candidate -> candidate.pickKind() in pending.accepts }
	}

	/**
	 * Withdraws [target] as the hover, but only when it is still the reported one - so a surface the pointer
	 * has left cannot clear a hover another surface has since published.
	 *
	 * @param SelectionTarget target The entity this surface last reported.
	 */
	fun unhover(target: SelectionTarget) {
		if (hoveredTarget == target) {
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

	/** Cancels the in-flight pick, if any (Escape, a right-click, or the arming field going away). */
	fun cancel() {
		request = null
		hoveredTarget = null
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
	 * Resolves the in-flight pick with [target] when its kind is accepted, clearing the request first so the
	 * callback cannot re-enter.  A target of an unaccepted kind leaves the pick armed, so a stray click on
	 * the wrong kind of thing does not silently end the interaction.
	 *
	 * @param SelectionTarget target The entity the user clicked.
	 * @return Boolean True when the pick was resolved (the caller should consume the click).
	 */
	fun resolve(target: SelectionTarget): Boolean {
		val pending = request ?: return false
		if (target.pickKind() !in pending.accepts) {
			return false
		}
		request = null
		hoveredTarget = null
		pending.onPicked(target)
		return true
	}
}

/**
 * Supplies the [RelationPickController] the shell shares with the picking fields and the surfaces that
 * resolve them.  Defaults to a standalone instance so a panel hosted without the shell still composes (its
 * eyedropper simply never resolves).
 */
val LocalRelationPick = staticCompositionLocalOf { RelationPickController() }
