package org.umamo.edit.mesh

import org.umamo.runtime.model.DrawableMesh

/**
 * A drawable's rest shape as the two arrays a geometry write-back sets together: the canvas editable mesh
 * ([DrawableMesh.positions]) and the keyform-space base the deltas are measured from
 * ([DrawableMesh.localPositions]).
 *
 * The two move apart under a deformer: a world-space move shifts the canvas mesh by canvas pixels and the
 * base by however far that is in the parent's space.  For a drawable with no deformer they are one space,
 * and a write-back passes the same array twice so the mesh keeps sharing it.
 *
 * @property FloatArray positions      The new canvas editable mesh.
 * @property FloatArray localPositions The new keyform-space base, [positions]' length.
 */
class MeshRestPositions(
	val positions: FloatArray,
	val localPositions: FloatArray,
) {
	companion object {
		/**
		 * The rest shape of a drawable whose base is its canvas mesh, one shared array: a drawable with no
		 * deformer.
		 *
		 * @param FloatArray positions The new positions, both canvas and base.
		 * @return MeshRestPositions The rest shape.
		 */
		fun shared(positions: FloatArray): MeshRestPositions = MeshRestPositions(positions, positions)
	}
}