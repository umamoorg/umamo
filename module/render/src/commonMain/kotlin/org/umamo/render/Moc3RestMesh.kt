package org.umamo.render

import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import kotlin.math.max
import kotlin.math.min

/**
 * Rewrites each drawable's rest mesh to its default-pose canvas-space geometry, matching the CMO3
 * convention the editor is built on.
 *
 * The runtime convention (set by CMO3, verified against the corpus): Drawable.mesh.positions is the
 * EDITABLE canvas-space geometry - what the gizmo overlay edits and the viewport maps gestures into -
 * while each MeshForm's absolute positions (base + delta) live in the drawable's PARENT-DEFORMER
 * space.  The deformation eval only ever sees `base + Σ wᵢ·Δᵢ` with weights summing to 1, so the base
 * cancels and this mixed-space encoding is exact, not an approximation.
 *
 * A `.moc3` stores only the parent-space keyforms, so [org.umamo.runtime.ingest.Moc3Import] can give
 * a warp/rotation-parented drawable nothing better than a parent-local base.  This pass finishes the
 * import: it evaluates the default pose through the validated deformer cascade (glue excluded - the
 * weld is a render-time effect, not rest geometry), takes the pre-Y-negation canvas positions as the
 * new base, and re-expresses every keyform delta against it so `base + delta` still reconstructs the
 * same parent-space absolutes.  Drawables the raw default pose hides (a keyform axis whose keys do
 * not bracket the driving parameter's default - the toggle-part authoring pattern) get a second
 * evaluation at a pose with those parameters clamped into their axes' key ranges, so their rest
 * meshes still land in canvas space.  It lives in `:render` (not `:runtime`) because it is the
 * evaluator that turns parent-space keyforms into canvas geometry.
 *
 * MOC3: 親空間キーフォームしか持たない moc の基準メッシュを、デフォルトポーズのキャンバス座標へ書き直す。
 * デフォルト値がキー範囲外のオブジェクトは、範囲内へクランプしたポーズで再評価する。
 *
 * @param PuppetModel model An imported model whose rest meshes may be parent-local (a MOC3 import).
 * @return PuppetModel The model with canvas-space rest meshes (a drawable that stays hidden even at
 *                     the clamped pose keeps its parent-local base, which still evaluates correctly).
 */
fun restMeshesToCanvasSpace(model: PuppetModel): PuppetModel {
	val preGlueModel = model.copy(glues = emptyList())
	val evaluator = CpuDeformationEvaluator()
	val defaultPose = evaluator.evaluate(preGlueModel, emptyMap())

	// Second chance for drawables absent from the raw default pose: clamp every involved parameter
	// into its axes' key ranges and evaluate once more.  Only the still-missing drawables read from
	// this pose, so in-range drawables keep their true default geometry.
	val hiddenAtDefault =
		model.drawables.filter { drawable ->
			drawable.mesh != null && drawable.keyforms != null && defaultPose.worldPositions[drawable.id] == null
		}.map { drawable -> drawable.id }
	val clampedPose =
		if (hiddenAtDefault.isEmpty()) {
			null
		} else {
			evaluator.evaluate(preGlueModel, clampedDefaultsFor(model, hiddenAtDefault))
		}

	val drawables =
		model.drawables.map { drawable ->
			val mesh = drawable.mesh ?: return@map drawable
			val worldPositions =
				defaultPose.worldPositions[drawable.id]
					?: clampedPose?.worldPositions?.get(drawable.id)
					?: return@map drawable
			if (worldPositions.size != mesh.positions.size) {
				return@map drawable
			}
			// All-or-nothing: a size-mismatched delta array holds ABSOLUTE positions by the importer's
			// fallback convention, so rebasing any prefix of it would double-count the base and mix two
			// spaces in one array.  A drawable carrying such a cell keeps its whole grid AND base
			// untouched - partially rewriting either would corrupt what malformed data still encodes.
			val anyCellMismatches =
				drawable.keyforms?.cells?.any { cell -> cell.form.positionDeltas.size != mesh.positions.size } ?: false
			if (anyCellMismatches) {
				return@map drawable
			}
			// The eval negates Y into world space; canvas space is the pre-negation Y-down convention.
			val canvasBase =
				FloatArray(worldPositions.size) { coordIndex ->
					if (coordIndex % 2 == 1) -worldPositions[coordIndex] else worldPositions[coordIndex]
				}
			val rebasedKeyforms =
				drawable.keyforms?.let { grid ->
					KeyformGrid(
						grid.axes,
						grid.cells.map { cell ->
							val oldDeltas = cell.form.positionDeltas
							val rebasedDeltas =
								FloatArray(oldDeltas.size) { coordIndex ->
									(mesh.positions[coordIndex] + oldDeltas[coordIndex]) - canvasBase[coordIndex]
								}
							KeyformCell(cell.coordinate, MeshForm(rebasedDeltas, cell.form.drawOrder, cell.form.opacity))
						},
					)
				}
			drawable.copy(
				mesh = DrawableMesh(canvasBase, mesh.uvs, mesh.indices),
				keyforms = rebasedKeyforms,
			)
		}
	return model.copy(drawables = drawables)
}

/**
 * The clamped evaluation pose for drawables hidden at the raw default: every parameter driving a
 * hidden drawable's own keyform grid or any grid on its ancestor deformer chain is coerced into the
 * intersection of those axes' key ranges (the tightest span that satisfies every involved axis).
 * When the intersection is empty (conflicting axes - pathological), the lower bound wins; unlisted
 * parameters keep their defaults via the evaluator's fallback.
 *
 * @param PuppetModel      model     The imported model.
 * @param List<DrawableId> hiddenIds The drawables absent from the raw default pose.
 * @return Map<ParameterId, Float> The clamped values for the involved parameters only.
 */
private fun clampedDefaultsFor(model: PuppetModel, hiddenIds: List<DrawableId>): Map<ParameterId, Float> {
	val drawableById = model.drawables.associateBy { it.id }
	val deformerById = model.deformers.associateBy { it.id }
	val lowerByParameter = HashMap<ParameterId, Float>()
	val upperByParameter = HashMap<ParameterId, Float>()

	fun addAxes(grid: KeyformGrid<*>?) {
		for (axis in grid?.axes.orEmpty()) {
			if (axis.keys.isEmpty()) {
				continue
			}
			val firstKey = axis.keys.first()
			val lastKey = axis.keys.last()
			lowerByParameter[axis.parameterId] = max(lowerByParameter[axis.parameterId] ?: firstKey, firstKey)
			upperByParameter[axis.parameterId] = min(upperByParameter[axis.parameterId] ?: lastKey, lastKey)
		}
	}

	for (drawableId in hiddenIds) {
		val drawable = drawableById[drawableId] ?: continue
		addAxes(drawable.keyforms)
		var parentId = drawable.parentDeformerId
		var chainSteps = 0
		while (parentId != null && chainSteps <= model.deformers.size) {
			val deformer = deformerById[parentId] ?: break
			when (deformer) {
				is Deformer.Warp -> addAxes(deformer.keyforms)
				is Deformer.Rotation -> addAxes(deformer.keyforms)
			}
			parentId = deformer.parent
			chainSteps++
		}
	}

	val defaultByParameter = model.parameters.associate { parameter -> parameter.id to parameter.default }
	return buildMap {
		for ((parameterId, lowerBound) in lowerByParameter) {
			val upperBound = upperByParameter[parameterId] ?: lowerBound
			val defaultValue = defaultByParameter[parameterId] ?: 0f
			val clampedValue = if (lowerBound <= upperBound) defaultValue.coerceIn(lowerBound, upperBound) else lowerBound
			put(parameterId, clampedValue)
		}
	}
}
