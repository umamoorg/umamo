package org.umamo.edit

import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PartComposite
import org.umamo.runtime.model.PartGroupMode
import org.umamo.runtime.model.PartId

/*
 * Scalar property edits on an EditorSession, driven by the Properties panel's editable controls.  Each
 * applies one field change as a single undo step via mutate, dispatching the typed Change plus its
 * PuppetModelEdits transform, and short-circuits to nothing on a no-op (the builder returns the same
 * model instance).  These are the write half of the Properties panel: a checkbox / dropdown / numeric
 * field commit calls exactly one of these.  Continuous numeric scrubbing previews in the widget and
 * commits one of these on release, so there is no per-frame mutation and no history-side coalescing (the
 * same single-commit-per-gesture granularity the parameter scrub documents in ParameterChange.SetValue).
 */

/**
 * Sets drawable [id]'s color blend mode as one undo step.
 *
 * @param DrawableId id The drawable to retarget.
 * @param BlendMode mode The new blend mode.
 */
fun EditorSession.setDrawableBlendMode(id: DrawableId, mode: BlendMode) {
	mutate(DrawableChange.SetBlendMode(id, mode)) { model -> model.withDrawableBlendMode(id, mode) }
}

/**
 * Sets drawable [id]'s alpha blend mode as one undo step.
 *
 * @param DrawableId id The drawable to retarget.
 * @param AlphaBlendMode mode The new alpha blend mode.
 */
fun EditorSession.setDrawableAlphaBlendMode(id: DrawableId, mode: AlphaBlendMode) {
	mutate(DrawableChange.SetAlphaBlendMode(id, mode)) { model -> model.withDrawableAlphaBlendMode(id, mode) }
}

/**
 * Sets drawable [id]'s back-face culling as one undo step.
 *
 * @param DrawableId id The drawable to retarget.
 * @param Boolean culling The new culling state.
 */
fun EditorSession.setDrawableCulling(id: DrawableId, culling: Boolean) {
	mutate(DrawableChange.SetCulling(id, culling)) { model -> model.withDrawableCulling(id, culling) }
}

/**
 * Sets drawable [id]'s mask-inversion flag as one undo step.
 *
 * @param DrawableId id The drawable to retarget.
 * @param Boolean invert The new inverted-mask state.
 */
fun EditorSession.setDrawableInvertMask(id: DrawableId, invert: Boolean) {
	mutate(DrawableChange.SetInvertMask(id, invert)) { model -> model.withDrawableInvertMask(id, invert) }
}

/**
 * Sets rotation deformer [id]'s base angle as one undo step. A no-op on a warp deformer (it has no base
 * angle), so the commit short-circuits.
 *
 * @param DeformerId id The deformer to retarget.
 * @param Float angle The new base angle in degrees.
 */
fun EditorSession.setDeformerBaseAngle(id: DeformerId, angle: Float) {
	mutate(DeformerChange.SetBaseAngle(id, angle)) { model -> model.withDeformerBaseAngle(id, angle) }
}

/**
 * Sets warp deformer [id]'s FFD interpolation mode as one undo step. A no-op on a rotation deformer (it
 * has no lattice), so the commit short-circuits.
 *
 * @param DeformerId id The deformer to retarget.
 * @param Boolean quad The new quad-transform state.
 */
fun EditorSession.setDeformerQuadTransform(id: DeformerId, quad: Boolean) {
	mutate(DeformerChange.SetQuadTransform(id, quad)) { model -> model.withDeformerQuadTransform(id, quad) }
}

/**
 * Sets part [id]'s guide-image (sketch) flag as one undo step.
 *
 * @param PartId id The part to retarget.
 * @param Boolean sketch The new sketch state.
 */
fun EditorSession.setPartSketch(id: PartId, sketch: Boolean) {
	mutate(PartChange.SetSketch(id, sketch)) { model -> model.withPartSketch(id, sketch) }
}

/**
 * Sets part [id]'s own draw order as one undo step.
 *
 * @param PartId id The part to retarget.
 * @param Int order The new draw order.
 */
fun EditorSession.setPartDrawOrder(id: PartId, order: Int) {
	mutate(PartChange.SetDrawOrder(id, order)) { model -> model.withPartDrawOrder(id, order) }
}

/**
 * Sets part [id]'s rendering group mode as one undo step. The [mode] carries the whole value, so a mode
 * switch and any Isolated-composite sub-field edit both flow through here.
 *
 * @param PartId id The part to retarget.
 * @param PartGroupMode mode The new group mode.
 */
fun EditorSession.setPartGroupMode(id: PartId, mode: PartGroupMode) {
	mutate(PartChange.SetGroupMode(id, mode)) { model -> model.withPartGroupMode(id, mode) }
}

/**
 * Sets part [id]'s latent compositing settings as one undo step.  Stored independent of the group mode,
 * so an isolated part's composite survives leaving and re-entering Isolated; applied only while Isolated.
 *
 * @param PartId id The part to retarget.
 * @param PartComposite composite The new composite settings.
 */
fun EditorSession.setPartComposite(id: PartId, composite: PartComposite) {
	mutate(PartChange.SetComposite(id, composite)) { model -> model.withPartComposite(id, composite) }
}

/**
 * Sets the document canvas size (world units) as one undo step.
 *
 * @param Float width The new canvas width.
 * @param Float height The new canvas height.
 */
fun EditorSession.setCanvasSize(width: Float, height: Float) {
	mutate(DocumentChange.SetCanvasSize(width, height)) { model -> model.withCanvasSize(width, height) }
}

/**
 * Sets the world origin (world space) as one undo step.
 *
 * @param Float x The new world-origin x.
 * @param Float y The new world-origin y.
 */
fun EditorSession.setWorldOrigin(x: Float, y: Float) {
	mutate(DocumentChange.SetWorldOrigin(x, y)) { model -> model.withWorldOrigin(x, y) }
}
