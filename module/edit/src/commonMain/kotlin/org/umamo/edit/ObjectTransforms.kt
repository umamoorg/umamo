package org.umamo.edit

import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel

/**
 * The drawables an object-mode Grab / Scale / Rotate may transform, or null when the gesture must be
 * blocked.  Object G / S / R writes only DrawableMesh.positions, so it can move a drawable but not a
 * deformer (whose shape lives in absolute per-keyform forms with no writer) nor a part (a container with
 * no geometry of its own).  Those ineligible targets are silently IGNORED rather than blocking: a Select
 * All / Invert sweeps parts and deformers into the selection, and the user expectation is that G still
 * moves the meshes it can.  Only a selection with nothing transformable at all (empty, or holding only
 * parts / deformers / mesh-less drawables) returns null, and the caller blocks with a note.
 *
 * The future Deformer to Part to Mesh cascade (transforming a part transforms its meshes; transforming a
 * deformer cascades through its parts into their meshes) is a separate project; until it and a
 * deformer-shape writer exist, parts and deformers are skipped.
 *
 * オブジェクトモードの G / S / R が変形できる描画メッシュ。パーツ・デフォーマ・メッシュ無しは黙って
 * 除外し、変形可能な描画オブジェクトの ID 一覧を返す。1つも無ければ null（ジェスチャをブロック）。
 *
 * @param Selection selection The object-mode selection to evaluate.
 * @param PuppetModel model The rig the targets index into.
 * @return List<DrawableId>? The transformable drawable ids, or null when nothing is transformable.
 */
fun eligibleTransformDrawables(selection: Selection, model: PuppetModel): List<DrawableId>? {
	if (selection.isEmpty) {
		return null
	}
	val drawableIds = ArrayList<DrawableId>(selection.size)
	for (target in selection.targets) {
		// Parts and deformers cannot be transformed yet (see the docblock); skip them, not the gesture.
		val drawableTarget = target as? SelectionTarget.Drawable ?: continue
		// A drawable that carries no source geometry has no positions to move; skip it too.
		val drawable = model.drawables.firstOrNull { it.id == drawableTarget.id }
		if (drawable?.mesh == null) {
			continue
		}
		drawableIds.add(drawableTarget.id)
	}
	return drawableIds.ifEmpty { null }
}

/**
 * Reports whether the live pose sits at every parameter's default value.  Object-mode transforms capture
 * the displayed (posed) geometry and write it back through the deformer-chain inverse, which is only
 * exact at the neutral pose - so [EditorSession.beginObjectOperator] refuses to start while any
 * parameter is scrubbed away from its default.  A parameter missing from the pose map counts as neutral
 * (the renderer substitutes the default for it).
 *
 * @param PuppetModel model The rig whose parameter defaults define neutral.
 * @param Pose pose The live parameter values to test.
 * @return Boolean True when every posed value equals its parameter's default.
 */
fun isPoseNeutral(model: PuppetModel, pose: Pose): Boolean {
	for (parameter in model.parameters) {
		val value = pose[parameter.id] ?: continue
		if (value != parameter.default) {
			return false
		}
	}
	return true
}
