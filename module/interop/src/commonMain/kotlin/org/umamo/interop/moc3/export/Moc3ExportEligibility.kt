package org.umamo.interop.moc3.export

import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.partByDrawable

/**
 * Which of a rig's objects an export actually writes, and why the rest were left out.
 *
 * @property List<Drawable>          drawables       The drawables that survive, in model order.
 * @property Set<PartId>             parts           The part ids that survive.
 * @property Map<DrawableId, PartId?> partByDrawable Each drawable's owning part, null when unparented.
 * @property Map<PartId, PartId>     partParentById  The org tree's part→parent link, inverted once.
 * @property Map<DrawableId, String> droppedDrawables Each omitted drawable and the reason, for notices.
 */
internal class Moc3ExportEligibility(
	val drawables: List<Drawable>,
	val parts: Set<PartId>,
	val partByDrawable: Map<DrawableId, PartId?>,
	val partParentById: Map<PartId, PartId>,
	val droppedDrawables: Map<DrawableId, String>,
)

/**
 * Decides which objects of [puppet] the export can write.
 *
 * Runs BEFORE the index plan, because the plan's indices are the file's addressing scheme: a drawable
 * dropped after the plan was built would leave every later index - and every mask reference into them -
 * naming the wrong object.
 *
 * Must also run AFTER the version strip, on the stripped rig.  No strip currently touches the
 * predicates below, so passing the un-stripped rig would be invisible today - which is exactly why the
 * order is stated rather than left to be rediscovered.
 *
 * @param PuppetModel puppet The rig to export, already stripped to the target version.
 * @param CanvasToParentSpace? canvasToParentSpace The unkeyed-drawable space inverse, or null.
 * @return Moc3ExportEligibility The surviving objects and the drop reasons.
 */
internal fun resolveExportEligibility(
	puppet: PuppetModel,
	canvasToParentSpace: CanvasToParentSpace?,
): Moc3ExportEligibility {
	// A SKETCH part is a guide overlay - a scan or a rough the rigger traces over - and the official
	// bake leaves it out of the moc entirely.  The whole subtree goes: a guide's drawables are the
	// thing that would otherwise render in the runtime, sitting on top of the puppet.
	val sketchParts = sketchSubtree(puppet)
	val exportableParts = puppet.parts.mapNotNullTo(LinkedHashSet()) { part -> part.id.takeIf { it !in sketchParts } }
	val partByDrawable = puppet.partByDrawable()
	// The org tree's part->parent link, inverted ONCE.  Both of these are asked per object further
	// down, and re-deriving either there turns its pass into a quadratic rescan of the whole tree.
	val partParentById = HashMap<PartId, PartId>()
	for (part in puppet.parts) {
		for (child in part.children) {
			if (child is OrgChild.Part) {
				partParentById[child.id] = part.id
			}
		}
	}
	val dropped = LinkedHashMap<DrawableId, String>()
	for (drawable in puppet.drawables) {
		if (partByDrawable[drawable.id] in sketchParts) {
			dropped[drawable.id] = "a guide-image (sketch) part is not runtime content"
		} else if (drawable.mesh == null) {
			dropped[drawable.id] = "a drawable with no mesh cannot be written"
		} else if (drawable.geometryGrid == null && drawable.parentDeformerId != null && canvasToParentSpace == null) {
			// The rest mesh is CANVAS-space while a parented drawable stores parent-local values, and
			// with no grid there are no deltas to recover the parent-local form from.  Inverting the
			// deformer chain needs :render's damped-Newton warp inverse, which :interop cannot reach -
			// so without the injected seam the drawable is dropped rather than written at the wrong
			// scale, which is what a canvas-space value under a warp would be.
			dropped[drawable.id] = "an unkeyed drawable under a deformer has no parent-space geometry to write"
		}
	}
	return Moc3ExportEligibility(
		drawables = puppet.drawables.filter { drawable -> drawable.id !in dropped },
		parts = exportableParts,
		partByDrawable = partByDrawable,
		partParentById = partParentById,
		droppedDrawables = dropped,
	)
}

/**
 * Every part in a guide-image subtree, roots included.
 *
 * A guide image is usually one part, but nothing stops a rigger from grouping several under it - and
 * a child of a guide is still a guide.
 *
 * @param PuppetModel puppet The rig.
 * @return Set The part ids to omit.
 */
private fun sketchSubtree(puppet: PuppetModel): Set<PartId> {
	val sketches = puppet.parts.filter { part -> part.isSketch }
	if (sketches.isEmpty()) {
		return emptySet()
	}
	val partsById = puppet.parts.associateBy { part -> part.id }
	val omitted = LinkedHashSet<PartId>()
	val pending = ArrayDeque(sketches.map { part -> part.id })
	while (pending.isNotEmpty()) {
		val partId = pending.removeFirst()
		if (!omitted.add(partId)) {
			continue
		}
		for (child in partsById[partId]?.children.orEmpty()) {
			if (child is OrgChild.Part) {
				pending.addLast(child.id)
			}
		}
	}
	return omitted
}
