package org.umamo.ui.viewport.uv

import androidx.compose.ui.geometry.Offset
import org.umamo.edit.MeshElement
import org.umamo.ui.viewport.gizmo.meshMarquee
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the UV Edit overlay's selection pieces over the rig's page: the shared mesh marquee boxes the shown
 * geometry it reads when the box lands and publishes no mesh preview to the session.  What the wireframe
 * lights up is the scene producer's (UvEditOverlayProducerTest).
 */
class UvEditGizmoSelectionTest {
	/** A box drag lands on the vertices it encloses, in the geometry read at the landing. */
	@Test
	fun theMarqueeBoxesTheShownGeometry() {
		val session = uvEditSession()
		var geometries = uvRigGeometries(session.model.value, uvRigPageFrame(), shown = emptyList())
		val marquee = meshMarquee(session, { geometries })
		marquee.beginBox(Offset(130f, 170f))
		marquee.dragBox(Offset(250f, 200f))
		geometries = uvRigGeometries(session.model.value, uvRigPageFrame())

		marquee.landBox(Offset(130f, 170f), Offset(250f, 200f), additive = false, camera = UV_RIG_PAGE_CAMERA, size = UV_RIG_AREA_SIZE)

		assertNull(marquee.boxStart, "the band clears as the box lands")
		assertEquals(setOf<MeshElement>(MeshElement.Vertex(0), MeshElement.Vertex(1)), session.meshSelection.value.elementsOf(UV_RIG_QUAD))
		assertNull(session.meshPreviewSelection.value, "a UV area keeps its stroke to itself, so nothing reaches the session's preview")
	}
}