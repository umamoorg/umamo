package org.umamo.edit

import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PartComposite
import org.umamo.runtime.model.PartGroupMode
import org.umamo.runtime.model.PartId

/**
 * How a [Change] participates in undo. Reconciles the two channels a mutation feeds: the change-event
 * bus (everything emits) and the undo stack (only a subset are steps). A change declares one of these,
 * so the [EditorSession] knows whether to push a history step without the call site hardcoding it.
 *
 * 変更が取り消しにどう関与するか。全ては変更イベントを発火するが、履歴段になるのは一部だけ。
 */
sealed interface Undoability {
	/** Pushes its own history step. The common case (rename, reparent, visibility, a selection gesture). */
	object Undoable : Undoability

	/**
	 * Pushes a step, but a run that shares [key] within one gesture merges into a single step (a slider
	 * scrub, a continuous brush drag). Reserved for gestures like these; no call site produces a
	 * [Coalescing] change yet.
	 *
	 * @property String key The coalescing identity; consecutive changes sharing it merge.
	 */
	data class Coalescing(val key: String) : Undoability

	/** Emits on the change bus but is never a history step (a tool/brush switch, a live preview frame). */
	object Transient : Undoability
}

/**
 * A described edit. Carries the description of what changed — not how to undo it — because undo is by
 * immutable snapshot, not by inverse op (see [History]). Every mutation emits a [Change] on the
 * session's change bus; the [undoability] decides whether it also becomes a history step, and [labelKey]
 * is a stable key the UI resolves to a localized history-panel label (kept here as a string so :edit
 * stays free of Compose resources).
 *
 * 記述された編集。取り消しはスナップショット方式なので逆操作は持たず、何が変わったかだけを持つ。
 */
sealed interface Change {
	/** Whether this change is a history step, and how it coalesces. */
	val undoability: Undoability

	/** A stable key the UI maps to a localized history-panel / Edit-menu label. */
	val labelKey: String
}

/** Changes to a part (the organisational tree node). */
sealed interface PartChange : Change {
	/**
	 * Toggles a part's Parts-panel eyeball. Cascades at render time (hiding a part hides its subtree),
	 * but the stored change is just this one part's flag.
	 *
	 * @property PartId id The part whose visibility changed.
	 * @property Boolean visible The new visibility.
	 */
	data class SetVisibility(val id: PartId, val visible: Boolean) : PartChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.part.visibility"
	}

	/**
	 * Renames a part.
	 *
	 * @property PartId id The renamed part.
	 * @property String to The new name.
	 */
	data class Rename(val id: PartId, val to: String) : PartChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.part.rename"
	}

	/**
	 * Toggles whether a part is viewport-selectable.
	 *
	 * @property PartId id The part whose selectability changed.
	 * @property Boolean selectable The new selectable state.
	 */
	data class SetSelectable(val id: PartId, val selectable: Boolean) : PartChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.part.selectable"
	}

	/**
	 * Moves a part within the hierarchy (reparent and / or reorder among siblings).
	 *
	 * @property PartId id The moved part.
	 * @property PartId? toParentId The destination parent, or null for the top level.
	 * @property PartId? beforeId The sibling it was inserted before, or null when appended.
	 */
	data class Move(val id: PartId, val toParentId: PartId?, val beforeId: PartId?) : PartChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.part.move"
	}

	/**
	 * Deletes a part. A cascade removes the whole subtree (the part, descendant parts, and their drawables);
	 * an ungroup removes only the folder and re-homes its contents up one level.
	 *
	 * @property PartId id The deleted part.
	 * @property Boolean cascade True for a subtree delete, false for an ungroup.
	 */
	data class Delete(val id: PartId, val cascade: Boolean) : PartChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.part.delete"
	}

	/**
	 * Toggles a part's guide-image (sketch) flag - an editor-only reference overlay, excluded from
	 * runtime (MOC3) export.
	 *
	 * @property PartId id The part whose sketch flag changed.
	 * @property Boolean sketch The new sketch state.
	 */
	data class SetSketch(val id: PartId, val sketch: Boolean) : PartChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.part.sketch"
	}

	/**
	 * Sets a part's own draw order (the sort key for its slot when it is grouped or isolated).
	 *
	 * @property PartId id The part whose draw order changed.
	 * @property Int order The new draw order.
	 */
	data class SetDrawOrder(val id: PartId, val order: Int) : PartChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.part.drawOrder"
	}

	/**
	 * Sets a part's rendering group mode (PassThrough / Grouped / Isolated), carrying the whole mode
	 * value - an Isolated switch brings its full composite, so one change covers a mode swap and any
	 * composite sub-field edit alike.
	 *
	 * @property PartId id The part whose group mode changed.
	 * @property PartGroupMode mode The new group mode.
	 */
	data class SetGroupMode(val id: PartId, val mode: PartGroupMode) : PartChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.part.groupMode"
	}

	/**
	 * Sets a part's compositing settings (opacity / blend / alpha / mask), stored latently regardless of
	 * group mode - so an isolated part's composite survives leaving and re-entering Isolated.  Applied only
	 * while the part is Isolated.
	 *
	 * @property PartId id The part whose composite changed.
	 * @property PartComposite composite The new composite settings.
	 */
	data class SetComposite(val id: PartId, val composite: PartComposite) : PartChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.part.composite"
	}
}

/** Changes to a drawable (an art mesh). */
sealed interface DrawableChange : Change {
	/**
	 * Toggles a drawable's own Parts-panel eyeball.
	 *
	 * @property DrawableId id The drawable whose visibility changed.
	 * @property Boolean visible The new visibility.
	 */
	data class SetVisibility(val id: DrawableId, val visible: Boolean) : DrawableChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.drawable.visibility"
	}

	/**
	 * Renames a drawable.
	 *
	 * @property DrawableId id The renamed drawable.
	 * @property String to The new name.
	 */
	data class Rename(val id: DrawableId, val to: String) : DrawableChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.drawable.rename"
	}

	/**
	 * Toggles whether a drawable is viewport-selectable.
	 *
	 * @property DrawableId id The drawable whose selectability changed.
	 * @property Boolean selectable The new selectable state.
	 */
	data class SetSelectable(val id: DrawableId, val selectable: Boolean) : DrawableChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.drawable.selectable"
	}

	/**
	 * Moves a drawable in the organisational tree: to a new owning part (null = the root level) and / or a
	 * new position among its siblings. Because the parts-panel order is also the draw-order tiebreak, this
	 * can change draw order, so the viewport follows.
	 *
	 * @property DrawableId id The moved drawable.
	 * @property PartId? toPartId The destination part, or null for the root level.
	 */
	data class Move(val id: DrawableId, val toPartId: PartId?) : DrawableChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.drawable.move"
	}

	/**
	 * Duplicates whole drawables (Object-mode Shift+D): each copy gets a fresh id and name, sits after
	 * its source in the org tree, and carries no glue membership.
	 *
	 * @property List<DrawableId> ids The created copies.
	 */
	data class Duplicate(val ids: List<DrawableId>) : DrawableChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.drawable.duplicate"
	}

	/**
	 * Deletes a drawable (and scrubs its mask / glue / render references).
	 *
	 * @property DrawableId id The deleted drawable.
	 */
	data class Delete(val id: DrawableId) : DrawableChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.drawable.delete"
	}

	/**
	 * Sets a drawable's color blend mode.
	 *
	 * @property DrawableId id The drawable whose blend mode changed.
	 * @property BlendMode mode The new blend mode.
	 */
	data class SetBlendMode(val id: DrawableId, val mode: BlendMode) : DrawableChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.drawable.blendMode"
	}

	/**
	 * Sets a drawable's alpha blend mode (Cubism 5.3's Porter-Duff-style alpha compositing).
	 *
	 * @property DrawableId id The drawable whose alpha blend mode changed.
	 * @property AlphaBlendMode mode The new alpha blend mode.
	 */
	data class SetAlphaBlendMode(val id: DrawableId, val mode: AlphaBlendMode) : DrawableChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.drawable.alphaBlendMode"
	}

	/**
	 * Toggles a drawable's back-face culling (false = double-sided, Cubism's default).
	 *
	 * @property DrawableId id The drawable whose culling changed.
	 * @property Boolean culling The new culling state.
	 */
	data class SetCulling(val id: DrawableId, val culling: Boolean) : DrawableChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.drawable.culling"
	}

	/**
	 * Toggles whether a drawable's clipping mask is inverted (the drawable shows outside the mask
	 * coverage instead of inside).
	 *
	 * @property DrawableId id The drawable whose mask inversion changed.
	 * @property Boolean invert The new inverted state.
	 */
	data class SetInvertMask(val id: DrawableId, val invert: Boolean) : DrawableChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.drawable.invertMask"
	}

	/**
	 * Binds a drawable to the deformer that deforms it (or unbinds it).  This is a cross-link, not a tree
	 * edge - a drawable is never a child of a deformer in the org tree - so it is a flat field write with
	 * no hierarchy surgery and no draw-order rederive.
	 *
	 * @property DrawableId id The drawable being rebound.
	 * @property DeformerId? parentDeformerId The deformer that now deforms it, or null to unbind.
	 */
	data class SetParentDeformer(val id: DrawableId, val parentDeformerId: DeformerId?) : DrawableChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.drawable.parentDeformer"
	}

	/**
	 * Replaces the whole list of drawables whose alpha clips this one.  The list flows as one value, so
	 * adding or removing a single mask is one undo step.
	 *
	 * @property DrawableId id The drawable whose mask list changed.
	 * @property List maskedBy The drawables that now clip it.
	 */
	data class SetMaskedBy(val id: DrawableId, val maskedBy: List<DrawableId>) : DrawableChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.drawable.maskedBy"
	}

	/**
	 * Sets a drawable's 5.3 per-art-mesh multiply color uniformly across its keyform grid.  The color is a
	 * keyformed channel, so this writes every cell (see PuppetModelEdits.withDrawableMultiplyColor for the
	 * flatten / re-upload follow-up).
	 *
	 * @property DrawableId id The drawable whose multiply color changed.
	 * @property ColorRgb color The new multiply color.
	 */
	data class SetMultiplyColor(val id: DrawableId, val color: ColorRgb) : DrawableChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.drawable.multiplyColor"
	}

	/**
	 * Sets a drawable's 5.3 per-art-mesh screen color uniformly across its keyform grid.
	 *
	 * @property DrawableId id The drawable whose screen color changed.
	 * @property ColorRgb color The new screen color.
	 */
	data class SetScreenColor(val id: DrawableId, val color: ColorRgb) : DrawableChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.drawable.screenColor"
	}
}

/** Changes to a deformer (warp or rotation). */
sealed interface DeformerChange : Change {
	/**
	 * Renames a deformer.
	 *
	 * @property DeformerId id The renamed deformer.
	 * @property String to The new name.
	 */
	data class Rename(val id: DeformerId, val to: String) : DeformerChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.deformer.rename"
	}

	/**
	 * Toggles whether a deformer is viewport-selectable.
	 *
	 * @property DeformerId id The deformer whose selectability changed.
	 * @property Boolean selectable The new selectable state.
	 */
	data class SetSelectable(val id: DeformerId, val selectable: Boolean) : DeformerChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.deformer.selectable"
	}

	/**
	 * Moves a deformer within the nesting hierarchy (reparent, which changes deformation, and / or reorder).
	 *
	 * @property DeformerId id The moved deformer.
	 * @property DeformerId? toParentId The destination parent deformer, or null for an armature root.
	 * @property DeformerId? beforeId The sibling it was inserted before, or null when appended.
	 */
	data class Move(val id: DeformerId, val toParentId: DeformerId?, val beforeId: DeformerId?) : DeformerChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.deformer.move"
	}

	/**
	 * Binds a deformer to the organisational part that owns it (or clears the binding).  A loose reference
	 * into the org tree - no [org.umamo.runtime.model.Part.children] entry corresponds to it - so this is a
	 * flat field write, not tree surgery.
	 *
	 * @property DeformerId id The deformer being rebound.
	 * @property PartId? partId The part that now owns it, or null to clear.
	 */
	data class SetPart(val id: DeformerId, val partId: PartId?) : DeformerChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.deformer.part"
	}

	/**
	 * Deletes a deformer by unwrapping it - its child deformers and bound drawables re-home to its parent.
	 *
	 * @property DeformerId id The deleted deformer.
	 */
	data class Delete(val id: DeformerId) : DeformerChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.deformer.delete"
	}

	/**
	 * Sets a rotation deformer's static editor reference angle. A no-op on a warp deformer (it has no
	 * base angle), so the transform short-circuits.
	 *
	 * @property DeformerId id The rotation deformer whose base angle changed.
	 * @property Float angle The new base angle in degrees.
	 */
	data class SetBaseAngle(val id: DeformerId, val angle: Float) : DeformerChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.deformer.baseAngle"
	}

	/**
	 * Sets a warp deformer's FFD interpolation mode (true = bilinear / quad, false = triangle split).
	 * A no-op on a rotation deformer (it has no lattice), so the transform short-circuits.
	 *
	 * @property DeformerId id The warp deformer whose interpolation mode changed.
	 * @property Boolean quad The new quad-transform state.
	 */
	data class SetQuadTransform(val id: DeformerId, val quad: Boolean) : DeformerChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.deformer.quadTransform"
	}
}

/** Changes to a parameter (an animation axis). */
sealed interface ParameterChange : Change {
	/**
	 * Commits a parameter scrub: the live value (pose) of one or more parameters reached a new resting
	 * position. The pose is ephemeral editor state (not document content), snapshotted so the scrub is
	 * undoable. Coalescing is realized upstream, at the gesture boundary: a continuous slider / 2D-pad
	 * drag streams transient preview frames straight to the renderer and commits exactly one [SetValue]
	 * on release, so a whole drag is a single undo step while two separate drags are two. Hence this is a
	 * plain [Undoability.Undoable] step (one per commit), not a history-side merge.
	 *
	 * @property List<ParameterId> ids The parameters whose live value this gesture moved.
	 */
	data class SetValue(val ids: List<ParameterId>) : ParameterChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.parameter.value"
	}

	/**
	 * Sets a parameter's range (minimum / maximum) and default. Document content - it changes the model
	 * and marks the document dirty. The default is clamped within the range, and the live pose value is
	 * re-clamped into the new range as part of the same undo step.
	 *
	 * @property ParameterId id The parameter whose range changed.
	 * @property Float min The new minimum (normalized so it does not exceed [max]).
	 * @property Float default The new default, clamped within [min]..[max].
	 * @property Float max The new maximum.
	 */
	data class SetRange(val id: ParameterId, val min: Float, val default: Float, val max: Float) : ParameterChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.parameter.range"
	}

	/**
	 * Links two adjacent parameters into one 2D pad, or removes that link. Document content - it
	 * changes the model and marks the document dirty. The live pose is untouched: both parameters
	 * keep their values, only the panel presentation folds into (or splits out of) a pad.
	 *
	 * @property ParameterId horizontal The X-axis (upper) parameter.
	 * @property ParameterId vertical The Y-axis parameter (the next parameter below in panel order).
	 * @property Boolean linked True to create the link, false to remove it.
	 */
	data class SetLink(val horizontal: ParameterId, val vertical: ParameterId, val linked: Boolean) : ParameterChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = if (linked) "change.parameter.link" else "change.parameter.unlink"
	}

	/**
	 * Reorders a parameter row in the panel's group tree, or moves it into or out of a group. Layout only
	 * - the flat parameters list and the links are untouched. The subject is one or two adjacent parameter
	 * leaves (a slider or a linked pad) or a whole group.
	 *
	 * @property ParameterMoveSubject subject The leaves or group being moved.
	 * @property ParameterGroupId? toParentGroupId The destination group, or null for the root level.
	 */
	data class MoveNode(val subject: ParameterMoveSubject, val toParentGroupId: ParameterGroupId?) : ParameterChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.parameter.move"
	}

	/**
	 * Creates a new empty parameter group.
	 *
	 * @property ParameterGroupId id The minted id of the new group.
	 */
	data class CreateGroup(val id: ParameterGroupId) : ParameterChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.parameter.groupCreate"
	}

	/**
	 * Deletes a parameter group by unwrapping it - its child parameters splice into the parent in place,
	 * so no parameter is lost.
	 *
	 * @property ParameterGroupId id The dissolved group.
	 */
	data class DeleteGroup(val id: ParameterGroupId) : ParameterChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.parameter.groupDelete"
	}

	/**
	 * Renames a parameter group.
	 *
	 * @property ParameterGroupId id The renamed group.
	 * @property String to The new display name.
	 */
	data class RenameGroup(val id: ParameterGroupId, val to: String) : ParameterChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.parameter.groupRename"
	}

	/**
	 * Creates a new animatable parameter (a fresh axis).
	 *
	 * @property ParameterId id The minted id of the new parameter.
	 */
	data class Create(val id: ParameterId) : ParameterChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.parameter.create"
	}

	/**
	 * Renames a parameter (its display name; the id is format-level and stays fixed).
	 *
	 * @property ParameterId id The renamed parameter.
	 * @property String to The new display name.
	 */
	data class Rename(val id: ParameterId, val to: String) : ParameterChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.parameter.rename"
	}

	/**
	 * Deletes a parameter everywhere - the axis list, the panel tree, any link, the pose, and every
	 * object's keyform grid (that axis collapses to the parameter's default slice).
	 *
	 * @property ParameterId id The deleted parameter.
	 */
	data class Delete(val id: ParameterId) : ParameterChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.parameter.delete"
	}
}

/** Changes to document-wide properties that live on the model root rather than on any one entity. */
sealed interface DocumentChange : Change {
	/**
	 * Sets the document canvas size in world units. Document content, so it marks the document dirty.
	 *
	 * @property Float width The new canvas width.
	 * @property Float height The new canvas height.
	 */
	data class SetCanvasSize(val width: Float, val height: Float) : DocumentChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.document.canvasSize"
	}

	/**
	 * Sets the world origin (where the viewport axis lines cross, in world space). Document content, so
	 * it marks the document dirty.
	 *
	 * @property Float x The new world-origin x.
	 * @property Float y The new world-origin y.
	 */
	data class SetWorldOrigin(val x: Float, val y: Float) : DocumentChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.document.worldOrigin"
	}
}

/**
 * Changes to a drawable's art mesh (its interior geometry, edited in Edit mode).
 *
 * The three whole-gesture transforms ([TransformVertices], [TransformDrawables], [TransformUvs]) each carry
 * the [MeshOperatorKind] they performed, so the history panel can name the operation rather than filing
 * every Grab, Scale, and Rotate under one "Move" label.  The kind names WHAT the transform was, not how it
 * was driven: the Properties panel's typed Size row records Scale with no modal operator ever latching.
 *
 * Each label key is resolved by an exhaustive `when` over the kind rather than by interpolating a suffix
 * into a namespace.  Interpolation would happily mint a key like `change.object.slide` that no string
 * resource answers, and an unmapped key degrades silently to the generic "Edit" label in the history panel.
 */
sealed interface MeshChange : Change {
	/**
	 * Transforms a set of mesh vertices to new rest positions, per drawable - an Edit session spans several
	 * meshes, so one gesture can move vertices on each of them as a single undo step. The pose is
	 * unaffected; this edits the rest geometry, which is document content.
	 *
	 * @property Map<DrawableId, List<Int>> vertexIndicesByDrawable The vertices this gesture moved, per
	 *   drawable (for the history-panel detail).
	 * @property MeshOperatorKind kind The operator this gesture ran, which selects the history label.
	 */
	data class TransformVertices(
		val vertexIndicesByDrawable: Map<DrawableId, List<Int>>,
		val kind: MeshOperatorKind,
	) : MeshChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String =
			when (kind) {
				MeshOperatorKind.Grab -> "change.mesh.move"
				MeshOperatorKind.Scale -> "change.mesh.scale"
				MeshOperatorKind.Rotate -> "change.mesh.rotate"
				MeshOperatorKind.VertexSlide -> "change.mesh.slide"
			}
	}

	/**
	 * A topology edit of one mesh (duplicate / merge / rip / connect): the vertex count changes, every
	 * keyform cell's deltas are rebuilt, and the glue pairs remap.  One change kind per operation so the
	 * history panel names what happened.
	 *
	 * @property DrawableId drawableId The drawable whose mesh's topology changed.
	 * @property String labelKey The operation's history label key (change.mesh.duplicate / merge / rip / connect).
	 */
	data class TopologyEdit(val drawableId: DrawableId, override val labelKey: String) : MeshChange {
		override val undoability: Undoability = Undoability.Undoable
	}

	/**
	 * Transforms every vertex of one or more whole drawables to new rest positions - an Object-mode Grab /
	 * Scale / Rotate of the selected drawables, or the Properties panel's typed Position / Size rows.
	 * Recorded as one undo step for the whole gesture even though several drawables move.  The pose is
	 * unaffected; this edits rest geometry, which is document content.
	 *
	 * @property List<DrawableId> drawableIds The drawables this gesture moved (for the history-panel detail).
	 * @property MeshOperatorKind kind The operator this gesture ran, which selects the history label.
	 */
	data class TransformDrawables(val drawableIds: List<DrawableId>, val kind: MeshOperatorKind) : MeshChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String =
			when (kind) {
				// Object mode cannot latch a Vertex Slide (the command is Edit-mode gated), so it can only
				// arrive here through a future caller - where reading as a plain move is the honest label.
				MeshOperatorKind.Grab, MeshOperatorKind.VertexSlide -> "change.object.move"
				MeshOperatorKind.Scale -> "change.object.scale"
				MeshOperatorKind.Rotate -> "change.object.rotate"
			}
	}

	/**
	 * Transforms a set of mesh vertices' texture coordinates to new atlas positions, per drawable - the UV
	 * editor's modal Grab / Scale / Rotate, the texture-mapping counterpart of [TransformVertices].  Rest
	 * geometry and the pose are unaffected; this edits which atlas texels the mesh samples, which is
	 * document content.
	 *
	 * @property Map<DrawableId, List<Int>> vertexIndicesByDrawable The vertices whose UVs this gesture
	 *   moved, per drawable (for the history-panel detail).
	 * @property MeshOperatorKind kind The operator this gesture ran, which selects the history label.
	 */
	data class TransformUvs(
		val vertexIndicesByDrawable: Map<DrawableId, List<Int>>,
		val kind: MeshOperatorKind,
	) : MeshChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String =
			when (kind) {
				// The UV editor has no Vertex Slide yet (see TODO § UV Editor); until it does, a slide can
				// only reach here as a move.
				MeshOperatorKind.Grab, MeshOperatorKind.VertexSlide -> "change.uv.move"
				MeshOperatorKind.Scale -> "change.uv.scale"
				MeshOperatorKind.Rotate -> "change.uv.rotate"
			}
	}

	/**
	 * Mirrors the selected vertices' texture coordinates about the transform pivot - the UV editor's
	 * explicit Mirror U / V commands, which serve the duplicated-and-flipped texture regions workflow
	 * (e.g. both eyes sampling one eye texture).  Rest geometry and the pose are unaffected; document
	 * content like [TransformUvs].
	 *
	 * @property List<DrawableId> drawableIds The drawables whose UVs mirrored (for the history-panel detail).
	 * @property Boolean mirrorU True for a U (horizontal) mirror, false for a V (vertical) mirror.
	 */
	data class MirrorUvs(val drawableIds: List<DrawableId>, val mirrorU: Boolean) : MeshChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.uv.mirror"
	}
}

/**
 * Changes to ephemeral editor state — selection and mode — which ride alongside the document model in
 * a history snapshot rather than inside [org.umamo.runtime.model.PuppetModel]. Selection gestures are
 * full undo steps (the user's chosen Blender-faithful granularity); a mode toggle is transient.
 *
 * 一時的なエディタ状態（選択・モード）の変更。選択はそれぞれ独立した取り消し段になる。
 */
sealed interface EditorStateChange : Change {
	/** A selection gesture (click, deselect, range). Its own undo step so a misclick is recoverable. */
	object SelectionChanged : EditorStateChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.selection"
	}

	/**
	 * An Edit-mode element-selection gesture (click, box, deselect). Its own undo step, like its
	 * object-mode counterpart [SelectionChanged], so a misclick that loses a selection is recoverable.
	 */
	object MeshSelectionChanged : EditorStateChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.mesh.select"
	}

	/**
	 * An Edit-mode select-mode switch (vertex / edge / face). Its own undo step, so undoing back across
	 * the switch restores both the prior mode and the selection it converted - the flush-down / derive-up
	 * conversion is lossy by design (Blender-faithful), so only the snapshot can bring the set back.
	 *
	 * @property MeshSelectMode selectMode The new select mode.
	 */
	data class MeshSelectModeChanged(val selectMode: MeshSelectMode) : EditorStateChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.mesh.selectMode"
	}

	/**
	 * An Object/Edit mode toggle. Its own undo step (like a selection gesture), so undoing back across a
	 * mode change restores the prior mode together with the vertex selection it seeded - the editor never
	 * ends up in Edit mode showing a state that was captured in Object mode.
	 *
	 * @property EditorMode mode The new mode.
	 */
	data class ModeChanged(val mode: EditorMode) : EditorStateChange {
		override val undoability: Undoability = Undoability.Undoable
		override val labelKey: String = "change.mode"
	}
}
