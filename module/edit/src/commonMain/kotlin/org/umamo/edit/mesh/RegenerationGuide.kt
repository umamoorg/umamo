package org.umamo.edit.mesh

import org.umamo.geometry.triangulation.refine.PiecewiseLinearGuide
import org.umamo.geometry.triangulation.refine.areaScaleOf
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetModel

/*
 * What a regenerated mesh should keep of the rig built on the old one: the old mesh laid out in its art
 * pixels - the same points the pins and the transfer read - carrying one field per thing the rig draws
 * through it.  The rest pose's world positions, the keyform-space base, and the base moved by each
 * keyform cell's deltas and by each blend shape's forms.  The mesher keeps the old vertices where the
 * new mesh would stray from any of them by more than the deformation tolerance, and the transfer copies a
 * kept vertex's rig exactly, since it lands on the old vertex's own point.
 *
 * Each field reads in art pixels through its scale: the world's own (canvas pixels per art pixel), the
 * base's own (keyform-space units per art pixel - a warp child's base spans 0 to 1), and the base's
 * scale again for every deformed form.  A form keeps the base's scale, so a keyform that squashes the
 * art is not judged at its squashed size, which would magnify its errors.
 */

/**
 * The guide that keeps a drawable's rig through a regeneration, or null when the drawable has no mesh
 * the input was read from or its rig gives no field with area.  The input carries the old mesh laid
 * out in art pixels, so nothing is prepared again here.
 *
 * @param RegenerationInput.Ready input              The input the mesh is generated for, read off this model.
 * @param FloatArray?             restWorldPositions The drawable's world positions in the rest pose, one pair per old vertex, or null when it has none (hidden, or nothing places it).
 * @return PiecewiseLinearGuide? The guide.
 */
fun PuppetModel.deformationGuideFor(input: RegenerationInput.Ready, restWorldPositions: FloatArray?): PiecewiseLinearGuide? {
	val drawable = drawables.firstOrNull { candidate -> candidate.id == input.drawableId } ?: return null
	val mesh = drawable.mesh ?: return null

	// A mesh of another size is another model's: the input no longer describes it.
	if (mesh.positions.size != input.oldVertexPoints.size) {
		return null
	}

	val points = input.oldVertexPoints
	val triangles = mesh.indices
	val fields = ArrayList<FloatArray>()
	val scales = ArrayList<Double>()

	if (restWorldPositions != null && restWorldPositions.size == mesh.positions.size) {
		addField(fields, scales, restWorldPositions, areaScaleOf(points, triangles, restWorldPositions))
	}

	val baseScale = areaScaleOf(points, triangles, mesh.localPositions)

	if (baseScale > 0.0) {
		addField(fields, scales, mesh.localPositions, baseScale)

		for (cell in drawable.geometryGrid?.cells.orEmpty()) {
			addDeformedBase(fields, scales, mesh, cell.form.positionDeltas, baseScale)
		}

		for (binding in drawable.blendShapes) {
			for ((keyIndex, form) in binding.forms.withIndex()) {
				if (keyIndex != binding.neutralIndex && form != null) {
					addDeformedBase(fields, scales, mesh, form.positionDeltas, baseScale)
				}
			}
		}
	}

	if (fields.isEmpty()) {
		return null
	}

	val pinned = input.pinnedOldVertices.toHashSet()
	val candidates = input.referencedOldVertices.filter { vertex -> vertex !in pinned }.toIntArray()

	return PiecewiseLinearGuide(points, triangles, fields, scales.toDoubleArray(), candidates)
}

/**
 * Adds one field when its scale says it has area.
 *
 * @param MutableList<FloatArray> fields The fields so far.
 * @param MutableList<Double>     scales Their scales.
 * @param FloatArray              field  The field.
 * @param Double                  scale  Its units per art pixel.
 */
private fun addField(fields: MutableList<FloatArray>, scales: MutableList<Double>, field: FloatArray, scale: Double) {
	if (scale > 0.0 && scale.isFinite()) {
		fields.add(field)
		scales.add(scale)
	}
}

/**
 * Adds the keyform-space base moved by one form's deltas, at the base's scale; a form that moves nothing
 * is the base itself and adds nothing.
 *
 * @param MutableList<FloatArray> fields    The fields so far.
 * @param MutableList<Double>     scales    Their scales.
 * @param DrawableMesh            mesh      The old mesh.
 * @param FloatArray              deltas    The form's position deltas, one pair per old vertex.
 * @param Double                  baseScale The base's units per art pixel.
 */
private fun addDeformedBase(fields: MutableList<FloatArray>, scales: MutableList<Double>, mesh: DrawableMesh, deltas: FloatArray, baseScale: Double) {
	if (deltas.size != mesh.localPositions.size || deltas.all { delta -> delta == 0f }) {
		return
	}

	addField(fields, scales, FloatArray(deltas.size) { component -> mesh.localPositions[component] + deltas[component] }, baseScale)
}