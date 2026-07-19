package org.umamo.render.eval

import org.umamo.runtime.eval.WeightedCell
import org.umamo.runtime.eval.blendScalarsFromCorners
import org.umamo.runtime.eval.gridCorners
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.GluePair
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RenderGroup

/**
 * Backend-neutral per-pose deform inputs for one drawable - the cheap output of [preparePose]: which
 * keyform cells are active and their weights ([corners]), the mesh's baked parent-deformer transform
 * ([parentWorld]; null for a direct, deformer-less mesh), and the blended scalar attributes. [corners]
 * null means the mesh is hidden at this pose; [isParented] with a null [parentWorld] means a hidden
 * ancestor deformer (also hidden). Nothing here is per-vertex - the CPU finishes it in [applyCpuDeform],
 * and the GPU path uploads the same corners (→ weight uniforms) + baked transform instead.
 */
internal class DrawableDeformInputs(
	val drawableId: DrawableId,
	val corners: List<WeightedCell>?,
	val parentWorld: DeformerWorld?,
	val isParented: Boolean,
	val drawOrder: Float,
	val opacity: Float,
	/** The drawable's resolved blend-shape state at this pose; null when it has no bindings. */
	val blend: MeshBlendState? = null,
)

/** A glue affecter with its pose-blended weld [intensity] already resolved, so the apply pass is param-free. */
internal class GlueInputs(
	val meshA: DrawableId,
	val meshB: DrawableId,
	val pairs: List<GluePair>,
	val intensity: Float,
)

/**
 * The full per-pose, backend-neutral deform inputs ([preparePose]'s output): per-drawable weights + baked
 * transforms and the resolved glues. The CPU finishes it per vertex via [applyCpuDeform]; the GPU path
 * uploads the same data as uniforms instead of re-uploading every deformed vertex - that re-use is exactly
 * why the deformation is split into a cheap "prepare" and a backend-specific "apply".
 */
internal class PoseDeformInputs(
	val drawables: List<DrawableDeformInputs>,
	val glues: List<GlueInputs>,
	/** Pose-blended draw order per grouped part (animated part order); missing → the static value. */
	val partDrawOrders: Map<PartId, Float> = emptyMap(),
	/**
	 * Pose-blended composite channels per ISOLATED part (opacity, multiply/screen colors), present
	 * for every isolated group - a static or out-of-range part carries its PartComposite fallbacks,
	 * so the composite pass never has to re-derive them.
	 */
	val partCompositeStates: Map<PartId, PartRenderState> = emptyMap(),
)

/**
 * Computes the cheap, backend-neutral deform inputs for [model] at [parameters]: the multilinear keyform
 * weights per mesh and each mesh's baked deformer-cascade transform - never per-vertex geometry. Shared
 * verbatim by the CPU ([applyCpuDeform]) and the eventual GPU apply paths.
 *
 * @param PuppetModel model      The rig.
 * @param Map         parameters Parameter id → value (partial; the rest default).
 * @return PoseDeformInputs The backend-neutral per-pose inputs.
 */
internal fun preparePose(model: PuppetModel, parameters: Map<ParameterId, Float>): PoseDeformInputs {
	val defaults = model.parameters.associate { it.id to it.default }
	val paramValue: (ParameterId) -> Float = { parameters[it] ?: defaults[it] ?: 0f }
	val defaultValue: (ParameterId) -> Float = { defaults[it] ?: 0f }
	val deformerWorlds = buildDeformerWorlds(model.deformers, paramValue, defaultValue)
	val drawables = ArrayList<DrawableDeformInputs>(model.drawables.size)
	for (drawable in model.drawables) {
		val grid = drawable.keyforms ?: continue
		if (drawable.mesh?.positions == null) {
			continue
		}
		val corners = gridCorners(grid, paramValue)
		val parentDeformerId = drawable.parentDeformerId
		val parentWorld = parentDeformerId?.let { deformerWorlds[it] }
		val scalars = corners?.let { blendScalarsFromCorners(grid, it) }
		val blend = meshBlendState(drawable, paramValue, defaultValue)
		var drawOrder = scalars?.drawOrder ?: CUBISM_DEFAULT_DRAW_ORDER
		var opacity = scalars?.opacity ?: 1f
		// Blend shapes: additive scalar deltas (opacity clamps to [0,1] AFTER summing; the Umamo C++
		// Runtime rounds draw order (int)(0.001+v) at sort time - Umamo sorts floats, recorded in
		// MOC3.md §5.6).
		if (blend != null) {
			for (contribution in blend.contributions) {
				drawOrder += contribution.weight * (contribution.form.drawOrder - blend.referenceDrawOrder)
				opacity += contribution.weight * (contribution.form.opacity - blend.referenceOpacity)
			}
			opacity = opacity.coerceIn(0f, 1f)
		}
		drawables.add(
			DrawableDeformInputs(
				drawableId = drawable.id,
				corners = corners,
				parentWorld = parentWorld,
				isParented = parentDeformerId != null,
				drawOrder = drawOrder,
				opacity = opacity,
				blend = blend,
			),
		)
	}
	val glues =
		model.glues.map { glue ->
			GlueInputs(
				meshA = glue.meshA,
				meshB = glue.meshB,
				pairs = glue.pairs,
				intensity = glue.intensity?.let { sampleGlueIntensity(it, paramValue) } ?: 1f,
			)
		}
	// Blend each group's (animated) part draw order, so the renderer can position whole groups per
	// pose - part groups with parameter-driven draw order swap front/back as their parameter moves.
	// The same walk blends each ISOLATED group's composite channels (opacity, multiply/screen
	// colors), falling back to the PartComposite statics when the part has no grid or the axis is
	// out of range.
	val partDrawOrders = HashMap<PartId, Float>()
	val partCompositeStates = HashMap<PartId, PartRenderState>()

	fun blendGroupStates(group: RenderGroup) {
		val partId = group.partId
		val grid = group.formGrid
		if (partId != null && grid != null) {
			samplePartDrawOrder(grid, paramValue)?.let { partDrawOrders[partId] = it }
		}
		val composite = group.composite
		if (partId != null && composite != null) {
			partCompositeStates[partId] =
				grid?.let { samplePartRenderState(it, paramValue) }
					?: PartRenderState(composite.opacity, composite.multiplyColor, composite.screenColor)
		}
		for (child in group.children) {
			if (child is RenderGroup) {
				blendGroupStates(child)
			}
		}
	}
	blendGroupStates(model.renderRoot)
	return PoseDeformInputs(drawables, glues, partDrawOrders, partCompositeStates)
}

/**
 * Finishes [inputs] on the CPU: per drawable, blends the active corners into local positions, pushes them
 * through the baked parent transform, negates Y, then welds the glue pairs in place. Output is identical
 * to evaluating the model in one pass.
 *
 * @param PuppetModel      model  The rig (for each drawable's base positions + grid).
 * @param PoseDeformInputs inputs The prepared per-pose inputs.
 * @return DeformedGeometry World positions + scalars per visible drawable.
 */
internal fun applyCpuDeform(model: PuppetModel, inputs: PoseDeformInputs): DeformedGeometry {
	val drawableById = model.drawables.associateBy { it.id }
	val worldPositions = HashMap<DrawableId, FloatArray>(inputs.drawables.size)
	val drawOrders = HashMap<DrawableId, Float>(inputs.drawables.size)
	val opacities = HashMap<DrawableId, Float>(inputs.drawables.size)
	for (drawableInputs in inputs.drawables) {
		val corners = drawableInputs.corners ?: continue
		// A parented mesh whose ancestor deformer is hidden produces no geometry (the cascade omitted it).
		if (drawableInputs.isParented && drawableInputs.parentWorld == null) {
			continue
		}
		val drawable = drawableById[drawableInputs.drawableId] ?: continue
		val grid = drawable.keyforms ?: continue
		val base = drawable.mesh?.positions ?: continue
		worldPositions[drawableInputs.drawableId] =
			deformMeshWorldFromCorners(grid, base, corners, drawableInputs.parentWorld, drawableInputs.blend)
		drawOrders[drawableInputs.drawableId] = drawableInputs.drawOrder
		opacities[drawableInputs.drawableId] = drawableInputs.opacity
	}
	applyGluesResolved(inputs.glues, worldPositions)
	return DeformedGeometry(worldPositions, drawOrders, opacities)
}

/**
 * Seam-welds each glue's vertex pairs in place from pre-resolved intensities: `A' = A + (B−A)·wA·i`,
 * `B' = B + (A−B)·wB·i` (the Umamo C++ Runtime's `applyGlue`, on the Y-flipped world buffers).
 *
 * @param List<GlueInputs>            glues          The resolved glue affecters.
 * @param Map<DrawableId,FloatArray> worldPositions Per-drawable deformed positions (mutated).
 */
internal fun applyGluesResolved(glues: List<GlueInputs>, worldPositions: Map<DrawableId, FloatArray>) {
	for (glue in glues) {
		val vertsA = worldPositions[glue.meshA] ?: continue
		val vertsB = worldPositions[glue.meshB] ?: continue
		val intensity = glue.intensity
		for (pair in glue.pairs) {
			val indexA = pair.indexA * 2
			val indexB = pair.indexB * 2
			if (indexA + 1 >= vertsA.size || indexB + 1 >= vertsB.size) {
				continue
			}
			val ax = vertsA[indexA]
			val ay = vertsA[indexA + 1]
			val bx = vertsB[indexB]
			val by = vertsB[indexB + 1]
			vertsA[indexA] = (bx - ax) * pair.weightA * intensity + ax
			vertsA[indexA + 1] = (by - ay) * pair.weightA * intensity + ay
			vertsB[indexB] = (ax - bx) * pair.weightB * intensity + bx
			vertsB[indexB + 1] = (ay - by) * pair.weightB * intensity + by
		}
	}
}
