package org.umamo.render.puppet

import org.umamo.render.eval.PartRenderState
import org.umamo.render.eval.RenderPlanComposite
import org.umamo.render.eval.RenderPlanDrawable
import org.umamo.render.eval.RenderPlanNode
import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PartId

/**
 * One pose's composite acceleration state: what lets the renderer skip or shrink layer work without
 * changing a pixel.
 *
 * @property Set<PartId>                flattenable            Isolated parts whose composite is a
 *   pose-identity Normal/Over over an all-Normal/Over subtree.  The renderer draws those inline - no
 *   layer, no snapshot, no composite draw; premultiplied Over is associative, so the pixels are identical
 *   up to one less 8-bit quantization.
 * @property Map<PartId, PosedAabb>     compositeBounds        The conservative world bounds of each
 *   isolated subtree.  The renderer scissors the layer clear, snapshot copy, and composite draw to them -
 *   except Out-alpha composites, whose blend erases the destination wherever the layer is EMPTY, so they
 *   keep the full-viewport path.
 * @property Map<DrawableId, PosedAabb> extendedDrawableBounds The conservative world bounds of each
 *   extended-blend drawable, scissored to the same way.
 */
internal class CompositeAcceleration(
	val flattenable: Set<PartId>,
	val compositeBounds: Map<PartId, PosedAabb>,
	val extendedDrawableBounds: Map<DrawableId, PosedAabb>,
) {
	companion object {
		/** Nothing flattens and nothing is bounded: every composite takes the full-viewport path. */
		val NONE: CompositeAcceleration = CompositeAcceleration(emptySet(), emptyMap(), emptyMap())
	}
}

/**
 * Derives the per-pose composite acceleration state from the freshly resolved plan.  A subtree
 * containing a drawable whose bounds cannot be computed publishes NO bounds, so render() falls back
 * to the full-viewport path - conservatism is the contract, an undersized bound would clip pixels.
 *
 * Backend-neutral - it decides WHICH layer work can be skipped or confined, never how a layer is drawn.
 *
 * @param List<RenderPlanNode>               plan                 The pose's resolved render plan.
 * @param Map<PartId, PartRenderState>       compositeStates      The pose-blended composite channels per
 *   isolated part.
 * @param Map<DrawableId, GpuDrawable>       residents            The resident drawables, carrying this
 *   pose's stamps.
 * @param Map<DrawableId, List<DrawableId>>  gluePartnersById     Glue weld partners by drawable, both
 *   directions.
 * @param Boolean                            flattenEnabled       Whether identity composites draw inline.
 * @param Boolean                            boundsScissorEnabled Whether layer work is confined to bounds.
 * @return CompositeAcceleration The pose's acceleration state.
 * @pre The residents carry THIS pose's stamps: the bounds are read from each resident's corners, parent
 *   transform, and blend state, so this runs after the pose is applied to them.
 */
internal fun planCompositeAcceleration(
	plan: List<RenderPlanNode>,
	compositeStates: Map<PartId, PartRenderState>,
	residents: Map<DrawableId, GpuDrawable>,
	gluePartnersById: Map<DrawableId, List<DrawableId>>,
	flattenEnabled: Boolean,
	boundsScissorEnabled: Boolean,
): CompositeAcceleration {
	if (!flattenEnabled && !boundsScissorEnabled) {
		return CompositeAcceleration.NONE
	}

	val flattenable = HashSet<PartId>()
	val compositeBounds = HashMap<PartId, PosedAabb>()
	val extendedBounds = HashMap<DrawableId, PosedAabb>()
	// Memoized per-drawable UNWELDED world bounds; a glue mesh's welded bounds union its posed
	// partners' in (welds move each side within the hull of both unwelded shapes).
	val ownBoundsById = HashMap<DrawableId, PosedAabb?>()

	/**
	 * One drawable's own world bounds this pose, before any weld moves it.
	 *
	 * @param DrawableId id The drawable.
	 * @return PosedAabb? The bounds, or null when the drawable is unposed or its bounds cannot be computed.
	 */
	fun ownWorldBounds(id: DrawableId): PosedAabb? {
		if (id in ownBoundsById) {
			return ownBoundsById[id]
		}

		val gpuDrawable = residents[id]
		val corners = gpuDrawable?.corners
		// visible == posed this frame, so corners are fresh (they stay stale-but-non-null on a
		// drawable that dropped out of the pose); an unposed one has nothing to bound.
		val bounds =
			if (gpuDrawable == null || corners == null || !gpuDrawable.visible) {
				null
			} else {
				deformedWorldBounds(gpuDrawable.boundsBase, gpuDrawable.boundsCells, corners, gpuDrawable.parentWorld, gpuDrawable.blend)
			}
		ownBoundsById[id] = bounds

		return bounds
	}

	/**
	 * One drawable's world bounds this pose with its posed glue partners' unioned in.
	 *
	 * @param DrawableId id The drawable.
	 * @return PosedAabb? The bounds, or null when its own or a posed partner's cannot be computed.
	 */
	fun weldedWorldBounds(id: DrawableId): PosedAabb? {
		var bounds = ownWorldBounds(id) ?: return null

		for (partnerId in gluePartnersById[id].orEmpty()) {
			if (residents[partnerId]?.visible != true) {
				continue // an unposed partner welds at zero intensity and moves nothing
			}

			val partnerBounds = ownWorldBounds(partnerId) ?: return null
			bounds = unionBounds(bounds, partnerBounds)
		}

		return bounds
	}

	/**
	 * Walks one composite subtree, returning its bounds (null = at least one drawn child had no
	 * computable bounds) and whether its CONTENT is flatten-transparent: every drawn drawable
	 * plain Normal/Over, and every nested composite itself blending Normal/Over (its internals
	 * only shape its own layer; Over's associativity makes its placement dest-agnostic).
	 *
	 * @param RenderPlanComposite node The isolated part's plan node.
	 * @return Pair<PosedAabb?, Boolean> The subtree's bounds, and whether its content flattens.
	 */
	fun walkComposite(node: RenderPlanComposite): Pair<PosedAabb?, Boolean> {
		var bounds: PosedAabb? = null
		var boundsComplete = true
		var contentFlattenable = true

		for (child in node.children) {
			when (child) {
				is RenderPlanDrawable -> {
					val gpuDrawable = residents[child.id] ?: continue
					val extended = gpuDrawable.isExtendedBlend

					if (gpuDrawable.blendMode != BlendMode.Normal || gpuDrawable.alphaBlendMode != AlphaBlendMode.Over) {
						contentFlattenable = false
					}

					val childBounds = weldedWorldBounds(child.id)

					if (childBounds == null) {
						boundsComplete = false
					} else {
						if (extended) {
							extendedBounds[child.id] = childBounds
						}

						bounds = bounds?.let { unionBounds(it, childBounds) } ?: childBounds
					}
				}

				is RenderPlanComposite -> {
					val (childBounds, childContent) = walkComposite(child)

					if (child.composite.blendMode != BlendMode.Normal || child.composite.alphaBlendMode != AlphaBlendMode.Over) {
						contentFlattenable = false
					}

					if (childBounds == null) {
						boundsComplete = false
					} else {
						bounds = bounds?.let { unionBounds(it, childBounds) } ?: childBounds
					}
				}
			}
		}

		val subtreeBounds = if (boundsComplete) bounds else null
		subtreeBounds?.let { compositeBounds[node.partId] = it }

		if (flattenEnabled && contentFlattenable && compositeStateIsIdentity(node, compositeStates)) {
			flattenable.add(node.partId)
		}

		return subtreeBounds to contentFlattenable
	}

	for (node in plan) {
		when (node) {
			is RenderPlanDrawable -> {
				val gpuDrawable = residents[node.id] ?: continue

				if (gpuDrawable.isExtendedBlend) {
					weldedWorldBounds(node.id)?.let { extendedBounds[node.id] = it }
				}
			}

			is RenderPlanComposite -> {
				walkComposite(node)
			}
		}
	}

	return CompositeAcceleration(
		flattenable,
		if (boundsScissorEnabled) compositeBounds else emptyMap(),
		if (boundsScissorEnabled) extendedBounds else emptyMap(),
	)
}

/**
 * Whether an isolated part's composite is a pose-identity source-over: Normal/Over, unmasked,
 * and its pose-blended channels exactly at identity.  Exact equality on purpose - a channel a
 * hair off identity takes the real composite path rather than a visibly-approximate flatten.
 *
 * @param RenderPlanComposite          node            The isolated part's plan node.
 * @param Map<PartId, PartRenderState> compositeStates The pose-blended composite channels per isolated part.
 * @return Boolean True when compositing the part changes nothing a plain draw would not.
 */
private fun compositeStateIsIdentity(node: RenderPlanComposite, compositeStates: Map<PartId, PartRenderState>): Boolean {
	val composite = node.composite

	if (composite.blendMode != BlendMode.Normal || composite.alphaBlendMode != AlphaBlendMode.Over) {
		return false
	}

	if (composite.maskedBy.isNotEmpty()) {
		return false
	}

	val state = compositeStates[node.partId]
	val opacity = state?.opacity ?: composite.opacity
	val multiply = state?.multiplyColor ?: composite.multiplyColor
	val screen = state?.screenColor ?: composite.screenColor

	return opacity == 1f && multiply == ColorRgb.MultiplyIdentity && screen == ColorRgb.ScreenIdentity
}