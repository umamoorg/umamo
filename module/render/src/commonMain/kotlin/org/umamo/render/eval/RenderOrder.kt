package org.umamo.render.eval

import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.RenderDrawable
import org.umamo.runtime.model.RenderGroup
import org.umamo.runtime.model.RenderNode

/** Cubism's neutral drawable draw order; raising a drawable's value moves it toward the front. */
const val CUBISM_DEFAULT_DRAW_ORDER = 500f

/**
 * The back-to-front paint order for one pose, computed hierarchically over the draw-order group tree.
 * Within each [RenderGroup] the children are stably sorted by an int sort key (a [RenderDrawable] by
 * its own quantised draw order, a sub-[RenderGroup] by the part's draw order), then emitted depth-first.
 * So a drawable's draw order only sorts it among its group siblings, and a "Group by Draw Order" part
 * moves as a unit - a child can't escape the group's slot.
 *
 * A group-less model is one flat group of all drawables, so this reduces exactly to [paintOrder]'s global
 * sort.  Quantisation (`+0.001`→int) matches the Umamo C++ runtime and de-noises tied float draw orders
 * (see [paintOrder]).
 *
 * A sub-group's key is its part draw order, which can itself be parameter-driven ([partDrawOrders])
 * - blended per pose, quantised the same way; missing → the group's static [RenderGroup.drawOrder].
 * So whole groups swap front/back as their controlling parameters move.
 *
 * @param RenderGroup           root           The draw-order group tree root (`PuppetModel.renderRoot`).
 * @param Map<DrawableId,Float> drawOrder      Blended draw order per drawable (missing → the Cubism default).
 * @param Map<PartId,Float>     partDrawOrders Blended per-group part draw order (missing → the static value).
 * @return List<DrawableId> The drawables in back-to-front paint order.
 */
fun renderOrder(
	root: RenderGroup,
	drawOrder: Map<DrawableId, Float>,
	partDrawOrders: Map<PartId, Float> = emptyMap(),
): List<DrawableId> {
	val result = ArrayList<DrawableId>()

	fun sortKey(node: RenderNode): Int =
		when (node) {
			is RenderDrawable -> ((drawOrder[node.id] ?: CUBISM_DEFAULT_DRAW_ORDER) + 0.001f).toInt()
			is RenderGroup -> node.partId?.let { partDrawOrders[it] }?.let { (it + 0.001f).toInt() } ?: node.drawOrder
		}

	fun emit(group: RenderGroup) {
		for (child in group.children.sortedBy(::sortKey)) {
			when (child) {
				is RenderDrawable -> result.add(child.id)
				is RenderGroup -> emit(child)
			}
		}
	}
	emit(root)
	return result
}

/**
 * The back-to-front paint order for one pose. [baseOrder] is the model's parts-tree (panel) drawable
 * order - already back-to-front when draw orders are equal - and this stably re-sorts it by the
 * per-drawable blended [drawOrder], the primary key. A higher draw order paints later (in front) across
 * the whole model regardless of part; equal draw orders keep [baseOrder]. This mirrors how the Cubism
 * editor stacks drawables (drawOrder first, the parts tree breaking ties).
 *
 * Quantise to int before sorting: (`drawDrawOrders[i] = (int)(0.001f + drawOrder)`).
 * Draw order is authored as an integer (0-1000, default 500); the multilinear keyform blend
 * `Σ wᵢ·orderᵢ` only lands near an integer (float-summation gives e.g. 499.99997 vs 500.00003), so
 * sorting the raw float reshuffles drawables that should be tied - a sub-ULP-noise reorder that makes a
 * coincident-order drawable (e.g. a masked head shadow at 500) flicker in front of the face at the
 * parameter values where the noise crosses. Flooring `+0.001` collapses the noise to a true tie so the
 * stable sort preserves [baseOrder]; the `+0.001` rescues a value sitting just below its intended integer.
 *
 * Because draw order rides on the keyform, it is param-dependent - pass the [DeformedGeometry.drawOrder]
 * for the current pose, so a parameter that animates stacking reorders correctly per frame.
 *
 * @param List<DrawableId>      baseOrder The model's drawables, in parts-tree back-to-front order.
 * @param Map<DrawableId,Float> drawOrder Blended draw order per drawable (missing → the Cubism default).
 * @return List<DrawableId> The drawables in back-to-front paint order.
 */
fun paintOrder(baseOrder: List<DrawableId>, drawOrder: Map<DrawableId, Float>): List<DrawableId> =
	baseOrder.sortedBy { ((drawOrder[it] ?: CUBISM_DEFAULT_DRAW_ORDER) + 0.001f).toInt() }
