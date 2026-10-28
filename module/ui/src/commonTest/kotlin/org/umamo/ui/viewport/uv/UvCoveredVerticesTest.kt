package org.umamo.ui.viewport.uv

import org.umamo.edit.MeshElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The covered texture coordinates and their median on a UV surface: the mean over every covered vertex across the
 * shown meshes, so a mesh with more covered vertices weighs more, and never an index past a mesh's vertices.  The
 * rig's quad has display corners (100, 100), (120, 100), (120, 120), (100, 120) and its triangle starts at (140, 100).
 */
class UvCoveredVerticesTest {
	/** The median is the mean over every covered vertex of every shown mesh. */
	@Test
	fun theMedianIsWeightedByVertexCount() {
		val session = uvEditSession(editing = listOf(UV_RIG_QUAD, UV_RIG_OTHER))
		session.setMeshSelection(
			session.meshSelection.value.copy(
				elementsByDrawable =
					mapOf(
						UV_RIG_QUAD to setOf(MeshElement.Vertex(0), MeshElement.Vertex(1)),
						UV_RIG_OTHER to setOf(MeshElement.Vertex(0)),
					),
			),
		)
		val geometries = uvRigGeometries(session.model.value, uvRigPageFrame())

		val median = assertNotNull(uvCoveredMedian(uvCoveredVertices(session.meshSelection.value, geometries)))

		assertEquals(120f, median.displayX)
		assertEquals(100f, median.displayY)
		assertEquals(3, median.vertexCount)
	}

	/** A selected index past the mesh, as a selection a frame ahead of the shown geometry can hold, is dropped. */
	@Test
	fun anIndexPastTheMeshIsDropped() {
		val session = uvEditSession(elements = listOf(MeshElement.Vertex(1), MeshElement.Vertex(9)))
		val geometries = uvRigGeometries(session.model.value, uvRigPageFrame(), shown = listOf(UV_RIG_QUAD))

		val covered = uvCoveredVertices(session.meshSelection.value, geometries)

		assertEquals(setOf(1), covered.single().indices)
	}
}