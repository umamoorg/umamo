package org.umamo.render.puppet

import org.umamo.runtime.model.ElementOwner

/**
 * A mesh overlay over DIRECT positions: the value a UV area's scene draws, where nothing deforms and every
 * vertex already sits where it is shown.  It pairs a [MeshOverlay] (the edges, the flags, the actives, the
 * sizes) with each listed mesh's positions and triangle indices, so the renderer needs no resident drawable
 * to pair it with and uploads the positions rather than capturing them.
 *
 * The positions are in the scene's world units: a UV area's surface texels with y up (the UV display
 * mapping, u * width and (1 - v) * height), the frame its camera projects.  Identity equality, and none of
 * its arrays is mutated after it is published, so the renderer can compare them by identity: a position
 * array that is the same instance as last time uploads nothing.
 *
 * @property MeshOverlay overlay The overlay's edges, flags, actives, and sizes.
 * @property Map<ElementOwner, FloatArray> positionsByOwner Each listed mesh's positions, x then y per vertex.
 * @property Map<ElementOwner, IntArray> triangleIndicesByOwner Each listed mesh's triangle indices, three per
 *   triangle, which the face fills and the face dots read.
 */
class DirectMeshOverlay(
	val overlay: MeshOverlay,
	val positionsByOwner: Map<ElementOwner, FloatArray>,
	val triangleIndicesByOwner: Map<ElementOwner, IntArray>,
)