package org.umamo.runtime.model

/**
 * A node in the render-order tree - Cubism's draw-order group hierarchy. The model carries this tree so the
 * renderer can reproduce the  hierarchical render order: a drawable's draw order sorts it only
 * among its group siblings, and a "Group by Draw Order" part moves as a unit positioned by the part's
 * draw order. A model with no draw-order groups is one flat [RenderGroup] of all drawables - equivalent to a
 * global sort (what `paintOrder` does).
 *
 * 描画順グループ木。draw order は同じグループ内でのみ並び替え、グループ自体は親の中で「パートの draw order」で並ぶ。
 */
sealed interface RenderNode

/** A drawable leaf; its sort key is its own pose-blended draw order, quantised to an int. */
data class RenderDrawable(val id: DrawableId) : RenderNode

/**
 * A draw-order group: the implicit root, or a part with "Group by Draw Order" (`enableDrawOrderGroup`). Its
 * [children] sort among themselves (in [drawOrder]-then-panel order); the group as a whole takes [drawOrder]
 * (the part's draw order) as its sort key in its parent, so a child can't escape the group's slot.
 * Basically, override the draw order of children of the part.
 *
 * @param PartId?         partId        The owning part, or null for the implicit root.
 * @param Int             drawOrder     The part's default-pose draw order - the static fallback sort key.
 * @param List<RenderNode> children     The group's members, in parts-panel (authoring) order.
 * @param KeyformGrid?    drawOrderGrid The part's keyform grid when it is parameter-driven; null for
 *                                      a static part order, in which case [drawOrder] is used.
 *                                      Blended per pose into the sort key (and, for an offscreen
 *                                      part, into the composite's opacity/color channels).
 * @param PartOffscreen?  offscreen     The owning part's offscreen compositing state, or null when
 *                                      the part is not offscreen - carried on the render tree so the
 *                                      renderer can composite the group's subtree as one layer.
 */
data class RenderGroup(
	val partId: PartId?,
	val drawOrder: Int,
	val children: List<RenderNode>,
	val drawOrderGrid: KeyformGrid<PartForm>? = null,
	val offscreen: PartOffscreen? = null,
) : RenderNode
