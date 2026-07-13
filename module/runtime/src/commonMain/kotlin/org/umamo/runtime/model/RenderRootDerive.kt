package org.umamo.runtime.model

/**
 * A copy of this model with [PuppetModel.renderRoot] recomputed from the current org tree. Every edit that
 * changes the org tree (a move, a delete) ends with this, so draw order stays a consistent derivation of
 * the tree rather than something the edit has to maintain by hand.
 *
 * @return PuppetModel This model with a freshly derived render root.
 */
fun PuppetModel.withDerivedRenderRoot(): PuppetModel = copy(renderRoot = deriveRenderRoot())

/**
 * Derives the draw-order group tree ([PuppetModel.renderRoot]) from the organisational tree. The org tree
 * ([PuppetModel.rootChildren] / [Part.children]) is the single source of truth for hierarchy + panel
 * order; draw order is a pure function of it plus the explicit draw-order levers ([Part.isDrawOrderGroup] /
 * [Part.drawOrder] / [Part.drawOrderGrid], and each drawable's keyformed draw order). This walks the org
 * tree to materialise the structure the renderer consumes, so any org-tree edit yields correct draw order
 * by recomputing it - there is no parallel render structure to drift out of sync.
 *
 * Mirrors Cubism: panel order is top = front, so the render tree (back-to-front) walks each child list
 * reversed; a "Group by Draw Order" part becomes a [RenderGroup] boundary (carrying its part draw order
 * and grid), a non-group part is transparent (its children hoist into the enclosing group). A safety net
 * appends any drawable the org walk missed, so the render never silently drops a mesh.
 *
 * 組織ツリーから描画順グループツリー（renderRoot）を導出する純粋関数。組織ツリーが唯一の真実で、描画順はその関数。
 *
 * @return RenderGroup The derived render-order root.
 */
fun PuppetModel.deriveRenderRoot(): RenderGroup {
	val partById = parts.associateBy { it.id }
	val drawableIds = drawables.mapTo(HashSet()) { it.id }

	fun collect(children: List<OrgChild>, into: ArrayList<RenderNode>) {
		// Panel order is top = front; render order is back-to-front, so walk reversed.
		for (child in children.asReversed()) {
			when (child) {
				is OrgChild.Drawable ->
					if (child.id in drawableIds) {
						into.add(RenderDrawable(child.id))
					}

				is OrgChild.Part -> {
					val part = partById[child.id] ?: continue
					if (part.isDrawOrderGroup) {
						val groupChildren = ArrayList<RenderNode>()
						collect(part.children, groupChildren)
						into.add(RenderGroup(part.id, part.drawOrder, groupChildren, part.drawOrderGrid))
					} else {
						collect(part.children, into) // transparent: hoist its children up
					}
				}
			}
		}
	}

	val rootNodes = ArrayList<RenderNode>()
	collect(rootChildren, rootNodes)
	val root = RenderGroup(null, CUBISM_DEFAULT_PART_DRAW_ORDER, rootNodes)

	// Safety net: append any drawable the org walk did not place (e.g. one reachable only under a deformer),
	// so the render never silently drops a mesh.
	val placed = HashSet<DrawableId>()

	fun collectPlaced(node: RenderNode) {
		when (node) {
			is RenderDrawable -> placed.add(node.id)
			is RenderGroup -> node.children.forEach(::collectPlaced)
		}
	}
	collectPlaced(root)
	val missing = drawables.map { it.id }.filter { it !in placed }.map { RenderDrawable(it) }
	return if (missing.isEmpty()) {
		root
	} else {
		root.copy(children = root.children + missing)
	}
}
