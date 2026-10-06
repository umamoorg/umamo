package org.umamo.ui.viewport.uv

import androidx.compose.ui.geometry.Offset
import org.umamo.edit.MeshElement
import org.umamo.ui.viewport.gizmo.BoxRelease
import org.umamo.ui.viewport.gizmo.meshMarquee
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pins the UV Edit overlay's selection pieces over the rig's page: the shared mesh marquee boxes the shown
 * geometry it reads when the box lands and publishes no mesh preview of its own, and the highlights follow
 * the selection they are handed, the active element on its own mesh only.
 */
class UvEditGizmoSelectionTest {
	/** A box drag lands on the vertices it encloses, in the geometry read at the release. */
	@Test
	fun theMarqueeBoxesTheShownGeometry() {
		val session = uvEditSession()
		var geometries = uvRigGeometries(session.model.value, uvRigPageFrame(), shown = emptyList())
		val marquee = meshMarquee(session, { geometries })
		marquee.beginBox(Offset(130f, 170f))
		marquee.dragBox(Offset(250f, 200f))
		geometries = uvRigGeometries(session.model.value, uvRigPageFrame())

		val release = marquee.releaseBox(Offset(250f, 200f), additive = false, camera = UV_RIG_PAGE_CAMERA, size = UV_RIG_AREA_SIZE)

		assertEquals(BoxRelease.Boxed, release)
		assertEquals(setOf<MeshElement>(MeshElement.Vertex(0), MeshElement.Vertex(1)), session.meshSelection.value.elementsOf(UV_RIG_QUAD))
		assertNull(session.meshPreviewSelection.value, "the UV editor draws its own stroke, so nothing is published")
	}

	/** The highlights mark the selected and the active vertex, on the active element's own mesh only. */
	@Test
	fun theHighlightsFollowTheSelection() {
		val session = uvEditSession(editing = listOf(UV_RIG_QUAD, UV_RIG_OTHER), elements = listOf(MeshElement.Vertex(0), MeshElement.Vertex(2)))
		val geometries = uvRigGeometries(session.model.value, uvRigPageFrame())

		val highlights = uvEditHighlights(session.meshSelection.value, geometries)

		val quad = assertNotNull(highlights[UV_RIG_QUAD])
		assertEquals(setOf(0, 2), quad.selectedVertexIndices)
		assertEquals(2, quad.activeVertexIndex, "the last element given is the active one")
		val other = assertNotNull(highlights[UV_RIG_OTHER])
		assertEquals(emptySet(), other.selectedVertexIndices)
		assertNull(other.activeVertexIndex)
	}
}