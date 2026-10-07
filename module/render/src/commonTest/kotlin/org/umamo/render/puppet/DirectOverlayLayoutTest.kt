package org.umamo.render.puppet

import org.umamo.runtime.model.DrawableId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Pins the direct overlay layout: offsets follow the overlay's mesh order over each mesh's own vertex
 * count, each placement carries the overlay's own arrays, and a mesh whose positions or triangle indices
 * disagree with it is left out without shifting what follows.
 */
class DirectOverlayLayoutTest {
	private val first = DrawableId("first")
	private val second = DrawableId("second")
	private val third = DrawableId("third")
	private val sizes = MeshOverlaySizes(3.5f, 1f, 2.5f)

	@Test
	fun offsetsFollowTheOverlayOrderAndCarryTheArrays() {
		val firstPositions = FloatArray(8)
		val firstIndices = intArrayOf(0, 1, 2, 1, 3, 2)
		val layout =
			planDirectOverlayLayout(
				DirectMeshOverlay(
					overlay(MeshOverlayKind.Edit, mesh(first, 4, faceCount = 2), mesh(second, 3, faceCount = 1), mesh(third, 6, faceCount = 0)),
					mapOf(first to firstPositions, second to FloatArray(6), third to FloatArray(12)),
					mapOf(first to firstIndices, second to intArrayOf(0, 1, 2), third to intArrayOf(0, 1, 2, 3, 4, 5)),
				),
			)
		assertEquals(listOf(first to 0, second to 4, third to 7), layout.placed.map { placement -> placement.mesh.drawableId to placement.baseOffset })
		assertEquals(13, layout.totalVertexCount)
		assertSame(firstPositions, layout.placed[0].positions, "a placement uploads the overlay's own positions")
		assertSame(firstIndices, layout.placed[0].triangleIndices, "and builds from its own indices")
	}

	@Test
	fun aMeshMissingItsArraysIsSkipped() {
		val layout =
			planDirectOverlayLayout(
				DirectMeshOverlay(
					overlay(MeshOverlayKind.Edit, mesh(first, 3, faceCount = 1), mesh(second, 3, faceCount = 1), mesh(third, 3, faceCount = 1)),
					mapOf(second to FloatArray(6), third to FloatArray(6)),
					mapOf(first to triangle(), third to triangle()),
				),
			)
		assertEquals(listOf(third to 0), layout.placed.map { placement -> placement.mesh.drawableId to placement.baseOffset }, "no positions or no indices, no place")
	}

	@Test
	fun disagreeingArraysAreSkippedWithoutShiftingWhatFollows() {
		val cases =
			listOf(
				"positions of another vertex count" to (FloatArray(8) to triangle()),
				"indices in a partial triangle" to (FloatArray(6) to intArrayOf(0, 1, 2, 0)),
				"an index outside the mesh" to (FloatArray(6) to intArrayOf(0, 1, 3)),
				"a negative index" to (FloatArray(6) to intArrayOf(0, -1, 2)),
			)
		for ((label, arrays) in cases) {
			val layout =
				planDirectOverlayLayout(
					DirectMeshOverlay(
						overlay(MeshOverlayKind.Edit, mesh(first, 3, faceCount = 1), mesh(second, 3, faceCount = 1)),
						mapOf(first to arrays.first, second to FloatArray(6)),
						mapOf(first to arrays.second, second to triangle()),
					),
				)
			assertEquals(listOf(second to 0), layout.placed.map { placement -> placement.mesh.drawableId to placement.baseOffset }, label)
			assertEquals(3, layout.totalVertexCount, label)
		}
	}

	@Test
	fun aFaceCountMismatchSkipsUnderEditAndIsIgnoredByTheObjectWireframe() {
		val positions = mapOf(first to FloatArray(6))
		val indices = mapOf(first to triangle())
		val staleFaces = mesh(first, 3, faceCount = 2)
		assertEquals(0, planDirectOverlayLayout(DirectMeshOverlay(overlay(MeshOverlayKind.Edit, staleFaces), positions, indices)).placed.size, "Edit draws faces, so a stale face flag count cannot pair")
		assertEquals(1, planDirectOverlayLayout(DirectMeshOverlay(overlay(MeshOverlayKind.ObjectWireframe, staleFaces), positions, indices)).placed.size, "the object wireframe reads edges only")
		assertEquals(1, planDirectOverlayLayout(DirectMeshOverlay(overlay(MeshOverlayKind.Edit, mesh(first, 3, faceCount = 0)), positions, indices)).placed.size, "empty face flags pair with any triangle count")
	}

	/**
	 * One triangle over a mesh's first three vertices.
	 *
	 * @return IntArray The indices, a new array each call.
	 */
	private fun triangle(): IntArray = intArrayOf(0, 1, 2)

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