package org.umamo.edit

import org.umamo.runtime.model.DrawableId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the pure gesture-capture builder the three gizmo overlays share.  This logic - the covered-vertex
 * anchor, the pivot-mode fallback chain, the per-mesh pivot-group derivation, and the proportional halo -
 * lived open-coded in three Compose overlays with no test coverage of its own; the overlays' own tests could
 * not reach it.  These assertions are that missing gate.
 */
class ModalTransformCaptureTest {
	private val meshA = DrawableId("a")
	private val meshB = DrawableId("b")

	/** A single square mesh (4 verts, 2 tris) whose vertices span (0,0)-(2,2), so its centroid is (1,1). */
	private fun square(id: DrawableId, offsetX: Float = 0f, offsetY: Float = 0f): ModalCaptureSource =
		ModalCaptureSource(
			drawableId = id,
			positions =
				floatArrayOf(
					offsetX + 0f,
					offsetY + 0f,
					offsetX + 2f,
					offsetY + 0f,
					offsetX + 2f,
					offsetY + 2f,
					offsetX + 0f,
					offsetY + 2f,
				),
			triangleIndices = intArrayOf(0, 1, 2, 0, 2, 3),
			coveredIndices = setOf(0, 1, 2, 3),
		)

	private fun build(
		sources: List<ModalCaptureSource>,
		pivotMode: TransformPivotMode = TransformPivotMode.MedianPoint,
		scope: IndividualOriginScope = IndividualOriginScope.ConnectivityIsland,
		activeAnchor: Pair<Float, Float>? = null,
		cursorAnchor: Pair<Float, Float>? = null,
	): ModalTransformCapture? =
		buildModalTransformCapture(sources, pivotMode, scope, MeshOperatorKind.Grab, activeAnchor, cursorAnchor)

	/** The median anchor is the covered-vertex mean pooled across every moving mesh, not a per-mesh average. */
	@Test
	fun medianAnchorIsTheCoveredMeanAcrossMeshes() {
		// One square at origin (centroid 1,1) and one shifted to (10,10)-(12,12) (centroid 11,11); the pooled
		// mean of all eight vertices is (6,6).  A per-mesh average would give the midpoint (6,6) too, so make
		// the mesh sizes differ to tell them apart.
		val small = square(meshA)
		val wide =
			ModalCaptureSource(
				drawableId = meshB,
				positions = floatArrayOf(10f, 10f, 12f, 10f), // just two vertices -> pulls the pooled mean less
				triangleIndices = intArrayOf(),
				coveredIndices = setOf(0, 1),
			)
		val capture = build(listOf(small, wide))!!
		// Pooled: (0+2+2+0 + 10+12) / 6 = 26/6, (0+0+2+2 + 10+10) / 6 = 24/6 = 4.
		assertEquals(26f / 6f, capture.anchor.first, 1e-4f)
		assertEquals(24f / 6f, capture.anchor.second, 1e-4f)
	}

	/** Active Element and Cursor each fall back to the median when the caller resolved no anchor. */
	@Test
	fun activeAndCursorFallBackToMedian() {
		val sources = listOf(square(meshA))
		assertEquals(1f to 1f, build(sources, TransformPivotMode.ActiveElement, activeAnchor = null)!!.anchor)
		assertEquals(1f to 1f, build(sources, TransformPivotMode.Cursor, cursorAnchor = null)!!.anchor)
		// A resolved anchor is used verbatim.
		assertEquals(5f to 7f, build(sources, TransformPivotMode.ActiveElement, activeAnchor = 5f to 7f)!!.anchor)
		assertEquals(9f to 3f, build(sources, TransformPivotMode.Cursor, cursorAnchor = 9f to 3f)!!.anchor)
	}

	/** A shared-pivot mode makes one group per mesh, pivoted at the shared anchor. */
	@Test
	fun sharedPivotModesMakeOneGroupAtTheAnchor() {
		val capture = build(listOf(square(meshA), square(meshB, offsetX = 10f)), TransformPivotMode.MedianPoint)!!
		assertEquals(2, capture.entries.size)
		for (entry in capture.entries) {
			assertEquals(1, entry.groups.size, "shared pivot is a single group")
			assertEquals(capture.anchor.first, entry.groups.single().pivotX, 1e-4f)
			assertEquals(capture.anchor.second, entry.groups.single().pivotY, 1e-4f)
			assertEquals(setOf(0, 1, 2, 3), entry.groups.single().vertexIndices)
		}
	}

	/** WholeMesh Individual Origins pivots each mesh about its OWN centroid, not the shared anchor. */
	@Test
	fun individualOriginsWholeMeshUsesEachMeshOwnCentroid() {
		val capture =
			build(
				listOf(square(meshA), square(meshB, offsetX = 10f)),
				TransformPivotMode.IndividualOrigins,
				scope = IndividualOriginScope.WholeMesh,
			)!!
		val groupA = capture.entries.first { it.drawableId == meshA }.groups.single()
		val groupB = capture.entries.first { it.drawableId == meshB }.groups.single()
		assertEquals(1f, groupA.pivotX, 1e-4f)
		assertEquals(11f, groupB.pivotX, 1e-4f) // shifted square's own centroid, not the shared (6,1) anchor
	}

	/** ConnectivityIsland Individual Origins splits one mesh's disjoint islands into separate groups. */
	@Test
	fun individualOriginsConnectivityIslandSplitsDisjointIslands() {
		// Two disjoint triangles in one mesh (verts 0-2 and 3-5, no shared edge).
		val twoIslands =
			ModalCaptureSource(
				drawableId = meshA,
				positions = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 10f, 10f, 11f, 10f, 10f, 11f),
				triangleIndices = intArrayOf(0, 1, 2, 3, 4, 5),
				coveredIndices = setOf(0, 1, 2, 3, 4, 5),
			)
		val groups =
			build(listOf(twoIslands), TransformPivotMode.IndividualOrigins, scope = IndividualOriginScope.ConnectivityIsland)!!
				.entries
				.single()
				.groups
		assertEquals(2, groups.size, "two disjoint islands -> two pivot groups")
		assertEquals(setOf(setOf(0, 1, 2), setOf(3, 4, 5)), groups.map { it.vertexIndices }.toSet())
	}

	/** A source with no covered vertices is dropped; an all-empty source list returns null (no gesture). */
	@Test
	fun emptyCoveredSourcesAreDroppedAndAllEmptyReturnsNull() {
		val empty = ModalCaptureSource(meshB, floatArrayOf(5f, 5f), intArrayOf(), emptySet())
		val capture = build(listOf(square(meshA), empty))!!
		assertEquals(listOf(meshA), capture.drawableIds, "the empty source does not become an entry")
		assertNull(build(listOf(empty)), "no covered vertices anywhere -> no capture")
		assertNull(build(emptyList()))
	}

	/** With proportional off, every mesh's moved set is exactly its covered set. */
	@Test
	fun noProportionalLeavesMovedSetEqualToCovered() {
		val capture = build(listOf(square(meshA)))!!
		capture.applyProportional(null, 100f)
		val entry = capture.entries.single()
		assertTrue(entry.influence.isEmpty())
		assertEquals(entry.coveredIndices, entry.movedIndices)
	}

	/** Proportional editing with a wide radius pulls unselected neighbours into the moved set. */
	@Test
	fun proportionalHaloAddsInfluencedNeighboursToTheMovedSet() {
		// One selected vertex at the origin, one unselected vertex a short hop away, on a shared edge.
		val mesh =
			ModalCaptureSource(
				drawableId = meshA,
				positions = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f),
				triangleIndices = intArrayOf(0, 1, 2),
				coveredIndices = setOf(0),
			)
		val capture = build(listOf(mesh))!!
		capture.applyProportional(ProportionalEditState(ProportionalFalloff.Linear, radiusWorld = 5f), 5f)
		val entry = capture.entries.single()
		assertTrue(entry.influence.isNotEmpty(), "neighbours within the radius take weight")
		assertTrue(entry.movedIndices.containsAll(entry.coveredIndices))
		assertTrue(entry.movedIndices.size > entry.coveredIndices.size, "the halo grew the moved set")
		// Re-deriving from the same frozen positions is idempotent, never compounding.
		val movedOnce = entry.movedIndices
		capture.applyProportional(ProportionalEditState(ProportionalFalloff.Linear, radiusWorld = 5f), 5f)
		assertEquals(movedOnce, entry.movedIndices)
	}

	/** Connected Only measures the halo geodesically, so it cannot leap to a disjoint island. */
	@Test
	fun connectedOnlyHaloDoesNotLeapToADisjointIsland() {
		// Selected vertex 0 on the first triangle; the second triangle (verts 3-5) sits close in space but
		// shares no edge, so a geodesic halo must never reach it.
		val mesh =
			ModalCaptureSource(
				drawableId = meshA,
				positions = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0.5f, 0.5f, 1.5f, 0.5f, 0.5f, 1.5f),
				triangleIndices = intArrayOf(0, 1, 2, 3, 4, 5),
				coveredIndices = setOf(0),
			)
		val capture = build(listOf(mesh))!!
		capture.applyProportional(ProportionalEditState(ProportionalFalloff.Linear, radiusWorld = 100f, connectedOnly = true), 100f)
		val influenced = capture.entries.single().influence.keys
		assertTrue(influenced.all { it in setOf(1, 2) }, "geodesic halo stays on the connected island: $influenced")
	}
}
