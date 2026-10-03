package org.umamo.render.puppet

import org.umamo.runtime.model.DrawableId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pins the overlay value's self-consistency contract (what a producer must get right, and what throws
 * when it does not) and the classic palette's correspondence to the settings defaults.
 */
class MeshOverlayTest {
	private val drawableId = DrawableId("quad")
	private val quadEdges = intArrayOf(0, 1, 1, 2, 0, 2, 1, 3, 2, 3)

	@Test
	fun aConsistentMeshIsAccepted() {
		val mesh = mesh(vertexFlags = ByteArray(4), edgeFlags = ByteArray(5), faceFlags = ByteArray(2), activeVertex = 3, activeEdge = 4, activeFace = 1)
		assertEquals(5, mesh.edgeCount)
	}

	@Test
	fun emptyFlagArraysAreAccepted() {
		val mesh = mesh(vertexFlags = ByteArray(0), edgeFlags = ByteArray(0), faceFlags = ByteArray(0))
		assertEquals(4, mesh.vertexCount)
	}

	@Test
	fun anOddEndpointArrayIsRejected() {
		assertFailsWith<IllegalArgumentException> { mesh(edgeEndpoints = intArrayOf(0, 1, 2)) }
	}

	@Test
	fun anEndpointOutsideTheMeshIsRejected() {
		assertFailsWith<IllegalArgumentException> { mesh(edgeEndpoints = intArrayOf(0, 4)) }
		assertFailsWith<IllegalArgumentException> { mesh(edgeEndpoints = intArrayOf(-1, 1)) }
	}

	@Test
	fun aWrongSizedFlagArrayIsRejected() {
		assertFailsWith<IllegalArgumentException> { mesh(vertexFlags = ByteArray(3)) }
		assertFailsWith<IllegalArgumentException> { mesh(edgeFlags = ByteArray(4)) }
	}

	@Test
	fun anActiveOrdinalOutsideItsDomainIsRejected() {
		assertFailsWith<IllegalArgumentException> { mesh(activeVertex = 4) }
		assertFailsWith<IllegalArgumentException> { mesh(activeEdge = 5) }
		assertFailsWith<IllegalArgumentException> { mesh(faceFlags = ByteArray(2), activeFace = 2) }
		assertFailsWith<IllegalArgumentException> { mesh(faceFlags = ByteArray(0), activeFace = 0) }
	}

	@Test
	fun theClassicPaletteIsTheSettingsDefaults() {
		val classic = MeshOverlayPalette.Classic
		assertEquals(OverlayColor(1f, 0f, 0xEC / 255f, 1f), classic.vertexIdle)
		assertEquals(OverlayColor(1f, 0x7A / 255f, 0f, 1f), classic.vertexSelected)
		assertEquals(OverlayColor(0x7D / 255f, 0xE4 / 255f, 0f, 1f), classic.vertexActive)
		assertEquals(OverlayColor(0f, 0f, 0f, 0x99 / 255f), classic.edgeIdle)
		assertEquals(classic.vertexSelected, classic.edgeSelected)
		assertEquals(classic.vertexActive, classic.edgeActive)
		assertEquals(OverlayColor(0f, 0f, 0f, 0x22 / 255f), classic.faceIdle)
		assertEquals(OverlayColor(1f, 0x7A / 255f, 0f, 0x66 / 255f), classic.faceSelected)
		assertEquals(classic.vertexActive, classic.faceActive)
	}

	/**
	 * A two-triangle quad's overlay entry with every field defaulted to a consistent value.
	 *
	 * @param IntArray edgeEndpoints The edge list.
	 * @param ByteArray vertexFlags The vertex flags.
	 * @param ByteArray edgeFlags The edge flags.
	 * @param ByteArray faceFlags The face flags.
	 * @param Int? activeVertex The active vertex.
	 * @param Int? activeEdge The active edge.
	 * @param Int? activeFace The active face.
	 * @return MeshOverlayMesh The entry.
	 */
	private fun mesh(
		edgeEndpoints: IntArray = quadEdges,
		vertexFlags: ByteArray = ByteArray(4),
		edgeFlags: ByteArray = ByteArray(edgeEndpoints.size / 2),
		faceFlags: ByteArray = ByteArray(2),
		activeVertex: Int? = null,
		activeEdge: Int? = null,
		activeFace: Int? = null,
	): MeshOverlayMesh = MeshOverlayMesh(drawableId, 4, edgeEndpoints, vertexFlags, edgeFlags, faceFlags, activeVertex, activeEdge, activeFace)
}