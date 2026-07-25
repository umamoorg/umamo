package org.umamo.render.eval

import org.umamo.runtime.eval.WeightedCell
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.gridCorners
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.eval.scalarOrNull
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.GluePair
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.Part
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
	val multiplyColor: ColorRgb = ColorRgb.MultiplyIdentity,
	val screenColor: ColorRgb = ColorRgb.ScreenIdentity,
	val blend: MeshBlendState? = null,
)

/**
 * The corner set of an entity with no keyform grid: one full-weight corner into a cell map that holds
 * nothing, so every blend contributes zero and the entity evaluates at its rest state.
 *
 * Shared rather than rebuilt per drawable per frame, and deliberately NOT an empty list - an empty corner
 * set would make the weights sum to zero, which is a different thing entirely.
 */
private val REST_CORNERS: List<WeightedCell> = listOf(WeightedCell(0, 1f))

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
	// A non-isolated part's opacity has no other home in the render pipeline (an isolated part applies
	// its own at the composite pass), so cascade the product of each drawable's non-isolated ancestor
	// part opacities into its drawable opacity below - the general Cubism part-opacity behavior.
	val partOpacityByDrawable = foldNonIsolatedPartOpacity(model, paramValue)
	val drawables = ArrayList<DrawableDeformInputs>(model.drawables.size)
	for (drawable in model.drawables) {
		if (drawable.mesh?.positions == null) {
			continue
		}
		// An UNKEYED drawable renders at its rest mesh rather than vanishing: one full-weight corner into
		// an empty cell map contributes no deltas, and every blend site already treats a missing cell as a
		// zero contribution. Null corners keep meaning HIDDEN (a pose outside a keyed grid's range), so
		// "no grid" and "out of range" stay distinguishable - they are opposite outcomes.
		val grid = drawable.geometryGrid
		val corners = if (grid != null) gridCorners(grid, paramValue) else REST_CORNERS
		val parentDeformerId = drawable.parentDeformerId
		val parentWorld = parentDeformerId?.let { deformerWorlds[it] }
		val blend = meshBlendState(drawable, paramValue, defaultValue)
		// Each channel resolves against its own track and falls back to the drawable's static; a channel
		// out of range never hides, which is the geometry grid's decision alone (corners == null above).
		var drawOrder = drawable.channelGrids.scalarAt(FormChannel.DRAW_ORDER, drawable.drawOrder, paramValue)
		var opacity = drawable.channelGrids.scalarAt(FormChannel.OPACITY, drawable.opacity, paramValue)
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
		var multiplyColor = drawable.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, drawable.multiplyColor, paramValue)
		var screenColor = drawable.channelGrids.colorAt(FormChannel.SCREEN_COLOR, drawable.screenColor, paramValue)
		// Then the parent deformer chain's accumulated channels. A deformer's opacity multiplies, its
		// multiply color multiplies, its screen color screens - each already folded over every ancestor
		// deformer by buildDeformerWorlds, so one composition here covers the whole chain. Clamped
		// because a blended color can leave [0,1] at the ends of a keyed range.
		parentWorld?.let { world ->
			opacity *= world.accumulatedOpacity
			multiplyColor =
				ColorRgb(
					(multiplyColor.red * world.accumulatedMultiplyColor.red).coerceIn(0f, 1f),
					(multiplyColor.green * world.accumulatedMultiplyColor.green).coerceIn(0f, 1f),
					(multiplyColor.blue * world.accumulatedMultiplyColor.blue).coerceIn(0f, 1f),
				)
			screenColor =
				ColorRgb(
					screenCompose(screenColor.red, world.accumulatedScreenColor.red).coerceIn(0f, 1f),
					screenCompose(screenColor.green, world.accumulatedScreenColor.green).coerceIn(0f, 1f),
					screenCompose(screenColor.blue, world.accumulatedScreenColor.blue).coerceIn(0f, 1f),
				)
		}
		// Finally the non-isolated ancestor part opacity (both already in [0,1], so the product stays in
		// range). Last on purpose: this one is a Umamo fold with no counterpart in the runtime core,
		// which applies part opacity in the renderer rather than on the drawable - so keeping it at the
		// end leaves everything before it directly comparable against the oracle.
		partOpacityByDrawable[drawable.id]?.let { partOpacity -> opacity *= partOpacity }
		drawables.add(
			DrawableDeformInputs(
				drawableId = drawable.id,
				corners = corners,
				parentWorld = parentWorld,
				isParented = parentDeformerId != null,
				drawOrder = drawOrder,
				opacity = opacity,
				multiplyColor = multiplyColor,
				screenColor = screenColor,
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
				intensity = glue.channelGrids.scalarAt(FormChannel.GLUE_INTENSITY, glue.intensity, paramValue),
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
		val channels = group.channelGrids
		if (partId != null) {
			// Left ABSENT when the part has no draw-order track or the pose is out of its range, so the
			// renderer keeps the part's static slot - the map's sparseness is the signal.
			channels.scalarOrNull(FormChannel.DRAW_ORDER, paramValue)?.let { partDrawOrders[partId] = it }
		}
		val composite = group.composite
		if (partId != null && composite != null) {
			// Each composite channel resolves independently against its own static, so an isolated part
			// can key opacity alone without dragging its tints onto the same axes.
			partCompositeStates[partId] =
				PartRenderState(
					channels.scalarAt(FormChannel.OPACITY, composite.opacity, paramValue),
					channels.colorAt(FormChannel.MULTIPLY_COLOR, composite.multiplyColor, paramValue),
					channels.colorAt(FormChannel.SCREEN_COLOR, composite.screenColor, paramValue),
				)
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
 * Folds each drawable's NON-ISOLATED ancestor part opacities into one factor, walking the org tree and
 * carrying a running product.  An isolated part is skipped - it applies its own opacity at the composite
 * pass, so cascading it here would apply it twice; a non-isolated part's opacity has no other home, so it
 * multiplies onto its whole subtree.  A drawable under only identity-opacity parts (the common case) is
 * absent from the map, so the caller multiplies by 1 for free.
 *
 * @param PuppetModel model      The rig.
 * @param Function    paramValue Current value for a given parameter id.
 * @return Map<DrawableId, Float> Per-drawable cascaded part opacity, entries only where it is not 1.
 */
internal fun foldNonIsolatedPartOpacity(model: PuppetModel, paramValue: (ParameterId) -> Float): Map<DrawableId, Float> {
	val partById = model.parts.associateBy { it.id }
	val result = HashMap<DrawableId, Float>()

	// A part's pose-blended opacity: its opacity track when it has one, else the static PartComposite
	// value (populated from the neutral keyform on ingest, so it holds the authored opacity either way).
	fun partOpacity(part: Part): Float = part.channelGrids.scalarAt(FormChannel.OPACITY, part.composite.opacity, paramValue)

	fun walk(children: List<OrgChild>, inheritedOpacity: Float) {
		for (child in children) {
			when (child) {
				is OrgChild.Drawable ->
					if (inheritedOpacity != 1f) {
						result[child.id] = inheritedOpacity
					}

				is OrgChild.Part -> {
					val part = partById[child.id] ?: continue
					val childOpacity = if (part.isIsolated) inheritedOpacity else inheritedOpacity * partOpacity(part)
					walk(part.children, childOpacity)
				}
			}
		}
	}
	walk(model.rootChildren, 1f)
	return result
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
		val base = drawable.mesh?.positions ?: continue
		// A null grid is an unkeyed drawable, which deforms to its rest mesh - see preparePose.
		worldPositions[drawableInputs.drawableId] =
			deformMeshWorldFromCorners(drawable.geometryGrid, base, corners, drawableInputs.parentWorld, drawableInputs.blend)
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
