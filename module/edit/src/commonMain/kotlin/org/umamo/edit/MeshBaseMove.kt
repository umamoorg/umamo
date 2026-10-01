package org.umamo.edit

/**
 * One drawable's committed base move: the new rest positions, and the movement every keyform makes with them.
 *
 * The two differ for a deformer child.  Its base is the canvas-space editable mesh while its keyforms live in
 * the parent deformer's space, and a gesture's movement is measured in that parent space; added to the canvas
 * base and stored as a float, the movement keeps only the canvas magnitude's precision (1/4096 near 4000).
 * [keyformMovement] is the movement as the gesture meant it, in double, so the keyforms move by exactly that
 * rather than by whatever the rounded base moved.
 *
 * @property FloatArray  positions       The new rest positions (canvas space), the mesh's length.
 * @property DoubleArray keyformMovement The movement per component the keyforms make, the mesh's length.
 */
class MeshBaseMove(
	val positions: FloatArray,
	val keyformMovement: DoubleArray,
)