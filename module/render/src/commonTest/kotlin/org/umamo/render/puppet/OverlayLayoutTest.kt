package org.umamo.render.puppet

import org.umamo.runtime.model.DrawableId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the overlay layout's addressing and its pairing rule: offsets follow the overlay's mesh order
 * over the residents' vertex counts, and a mesh whose resident disagrees is left out without shifting
 * what follows.
 */
class OverlayLayoutTest {
	private val first = DrawableId("first")
	private val second = DrawableId("second")
	private val third = DrawableId("third")
	private val sizes = MeshOverlaySizes(3.5f, 1f, 2.5f)

	@Test
	fun offsetsFollowTheOverlayOrderOverTheResidentCounts() {
		val layout =
			planOverlayLayout(
				overlay(MeshOverlayKind.Edit, mesh(first, 4, faceCount = 2), mesh(second, 3, faceCount = 1), mesh(third, 6, faceCount = 4)),
				mapOf(first to 4, second to 3, third to 6),
				mapOf(first to 2, second to 1, third to 4),
			)
		assertEquals(listOf(first to 0, second to 4, third to 7), layout.placed.map { placement -> placement.mesh.drawableId to placement.baseOffset })
		assertEquals(13, layout.totalVertexCount)
	}

	@Test
	fun aVertexCountMismatchIsSkippedWithoutShiftingWhatFollows() {
		val layout =
			planOverlayLayout(
				overlay(MeshOverlayKind.Edit, mesh(first, 4, faceCount = 2), mesh(second, 3, faceCount = 1), mesh(third, 6, faceCount = 4)),
				mapOf(first to 4, second to 5, third to 6),
				mapOf(first to 2, second to 1, third to 4),
			)
		assertEquals(listOf(first to 0, third to 4), layout.placed.map { placement -> placement.mesh.drawableId to placement.baseOffset })
		assertEquals(10, layout.totalVertexCount)
	}

	@Test
	fun aFaceCountMismatchSkipsUnderEditAndIsIgnoredByTheObjectWireframe() {
		val residents = mapOf(first to 4)
		val staleFaces = mapOf(first to 3)
		val editLayout = planOverlayLayout(overlay(MeshOverlayKind.Edit, mesh(first, 4, faceCount = 2)), residents, staleFaces)
		assertEquals(0, editLayout.placed.size, "Edit draws faces, so a stale face flag count cannot pair")
		val wireframeLayout = planOverlayLayout(overlay(MeshOverlayKind.ObjectWireframe, mesh(first, 4, faceCount = 2)), residents, staleFaces)
		assertEquals(1, wireframeLayout.placed.size, "the object wireframe reads edges only")
		val unflaggedLayout = planOverlayLayout(overlay(MeshOverlayKind.Edit, mesh(first, 4, faceCount = 0)), residents, staleFaces)
		assertEquals(1, unflaggedLayout.placed.size, "empty face flags pair with any triangle count")
	}

	@Test
	fun aMeshWithNoResidentIsSkipped() {
		val layout = planOverlayLayout(overlay(MeshOverlayKind.Edit, mesh(first, 4, faceCount = 2), mesh(second, 3, faceCount = 1)), mapOf(second to 3), mapOf(second to 1))
		assertEquals(listOf(second to 0), layout.placed.map { placement -> placement.mesh.drawableId to placement.baseOffset })
	}

	@Test
	fun anEmptyOverlayPlansNothing() {
		val layout = planOverlayLayout(overlay(MeshOverlayKind.Edit), mapOf(first to 4), mapOf(first to 2))
		assertEquals(0, layout.placed.size)
		assertEquals(0, layout.totalVertexCount)
	}

	/**
	 * An overlay over the given meshes.
	 *
	 * @param MeshOverlayKind kind The overlay kind.
	 * @param MeshOverlayMesh meshes The meshes, in layout order.
	 * @return MeshOverlay The overlay.
	 */
	private fun overlay(kind: MeshOverlayKind, vararg meshes: MeshOverlayMesh): MeshOverlay = MeshOverlay(kind, MeshOverlaySelectMode.Vertex, meshes.toList(), sizes)

	/**
	 * An edge-less overlay entry with [faceCount] face flags.
	 *
	 * @param DrawableId drawableId The drawable.
	 * @param Int vertexCount The vertex count the entry claims.
	 * @param Int faceCount How many face flags it carries.
	 * @return MeshOverlayMesh The entry.
	 */
	private fun mesh(drawableId: DrawableId, vertexCount: Int, faceCount: Int): MeshOverlayMesh =
		MeshOverlayMesh(drawableId, vertexCount, IntArray(0), ByteArray(vertexCount), ByteArray(0), ByteArray(faceCount), null, null, null)
}