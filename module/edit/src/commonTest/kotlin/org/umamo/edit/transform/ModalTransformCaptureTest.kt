package org.umamo.edit.transform

import org.umamo.edit.ElementTopology
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.ProportionalEditState
import org.umamo.edit.ProportionalFalloff
import org.umamo.edit.TransformPivotMode
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ElementOwner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the pure gesture-capture builder the three gizmo overlays share.  This logic - the covered-vertex
 * anchor, the pivot-mode fallback chain, the per-mesh pivot-group derivation, and the proportional halo -
 * lived open-coded in three Compose overlays with no test coverage of its own; the overlays' own tests could
 * not reach it.  These assertions are that missing gate.
 */
class ModalTransformCaptureTest {
	private val meshA: ElementOwner = ElementOwner.Drawable(DrawableId("a"))
	private val meshB: ElementOwner = ElementOwner.Drawable(DrawableId("b"))

	/** A single square mesh (4 verts, 2 tris) whose vertices span (0,0)-(2,2), so its centroid is (1,1). */
	private fun square(id: ElementOwner, offsetX: Float = 0f, offsetY: Float = 0f): ModalCaptureSource =
		ModalCaptureSource(
			owner = id,
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
			topology = trianglesOf(intArrayOf(0, 1, 2, 0, 2, 3)),
			coveredIndices = setOf(0, 1, 2, 3),
		)

	private fun build(
		sources: List<ModalCaptureSource>,
		pivotMode: TransformPivotMode = TransformPivotMode.MedianPoint,
		scope: IndividualOriginScope = IndividualOriginScope.ConnectivityIsland,
		activeAnchor: Pair<Float, Float>? = null,
		cursorAnchor: Pair<Float, Float>? = null,
		bystanders: List<ModalCaptureSource> = emptyList(),
	): ModalTransformCapture? =
		buildModalTransformCapture(sources, pivotMode, scope, MeshOperatorKind.Grab, activeAnchor, cursorAnchor, bystanders)

	/** The median anchor is the covered-vertex mean pooled across every moving mesh, not a per-mesh average. */
	@Test
	fun medianAnchorIsTheCoveredMeanAcrossMeshes() {
		// One square at origin (centroid 1,1) and one shifted to (10,10)-(12,12) (centroid 11,11); the pooled
		// mean of all eight vertices is (6,6).  A per-mesh average would give the midpoint (6,6) too, so make
		// the mesh sizes differ to tell them apart.
		val small = square(meshA)
		val wide =
			ModalCaptureSource(
				owner = meshB,
				positions = floatArrayOf(10f, 10f, 12f, 10f), // just two vertices -> pulls the pooled mean less
				topology = trianglesOf(intArrayOf()),
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
		val groupA = capture.entries.first { it.owner == meshA }.groups.single()
		val groupB = capture.entries.first { it.owner == meshB }.groups.single()
		assertEquals(1f, groupA.pivotX, 1e-4f)
		assertEquals(11f, groupB.pivotX, 1e-4f) // shifted square's own centroid, not the shared (6,1) anchor
	}

	/** ConnectivityIsland Individual Origins splits one mesh's disjoint islands into separate groups. */
	@Test
	fun individualOriginsConnectivityIslandSplitsDisjointIslands() {
		// Two disjoint triangles in one mesh (verts 0-2 and 3-5, no shared edge).
		val twoIslands =
			ModalCaptureSource(
				owner = meshA,
				positions = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 10f, 10f, 11f, 10f, 10f, 11f),
				topology = trianglesOf(intArrayOf(0, 1, 2, 3, 4, 5)),
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
		val empty = ModalCaptureSource(meshB, floatArrayOf(5f, 5f), trianglesOf(intArrayOf()), emptySet())
		val capture = build(listOf(square(meshA), empty))!!
		assertEquals(listOf(meshA), capture.owners, "the empty source does not become an entry")
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
				owner = meshA,
				positions = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f),
				topology = trianglesOf(intArrayOf(0, 1, 2)),
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
				owner = meshA,
				positions = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0.5f, 0.5f, 1.5f, 0.5f, 0.5f, 1.5f),
				topology = trianglesOf(intArrayOf(0, 1, 2, 3, 4, 5)),
				coveredIndices = setOf(0),
			)
		val capture = build(listOf(mesh))!!
		capture.applyProportional(ProportionalEditState(ProportionalFalloff.Linear, radiusWorld = 100f, connectedOnly = true), 100f)
		val influenced = capture.entries.single().influence.keys
		assertTrue(influenced.all { it in setOf(1, 2) }, "geodesic halo stays on the connected island: $influenced")
	}

	/** A bystander joins after the moving meshes with nothing covered, and alone it starts no gesture. */
	@Test
	fun aBystanderJoinsWithNothingCoveredAndCannotStartAGesture() {
		val bystander = square(meshB, offsetX = 3f)
		val capture = assertNotNull(build(listOf(square(meshA)), bystanders = listOf(bystander)))
		assertEquals(listOf(meshA, meshB), capture.owners, "the bystander follows the moving mesh")
		val entry = capture.entries.last()
		assertTrue(entry.coveredIndices.isEmpty() && entry.groups.isEmpty() && entry.movedIndices.isEmpty(), "a bystander moves nothing of its own")

		assertNull(build(emptyList(), bystanders = listOf(bystander)), "a bystander alone is nothing to move")
	}

	/** A straight-line halo reaches a nearby bystander; Connected Only never leaves the selected mesh. */
	@Test
	fun aStraightLineHaloReachesABystanderAndConnectedOnlyDoesNot() {
		// Vertex 1 of mesh A at (2,0) is selected; the bystander's (3,0) corner is 1 away, its (5,2) corner past 3.
		val selected = square(meshA).let { source -> ModalCaptureSource(source.owner, source.positions, source.topology, setOf(1)) }
		val capture = assertNotNull(build(listOf(selected), bystanders = listOf(square(meshB, offsetX = 3f))))
		val bystander = capture.entries.last()

		capture.applyProportional(ProportionalEditState(ProportionalFalloff.Linear, radiusWorld = 3f), 3f)
		assertEquals(setOf(0, 3), bystander.influence.keys, "the bystander's corners within 3 of (2,0) take weight")
		assertEquals(1f - 1f / 3f, assertNotNull(bystander.influence[0]).weight, 1e-6f, "its nearest corner is 1 away")
		assertEquals(bystander.influence.keys, bystander.movedIndices, "the halo is all a bystander moves")

		capture.applyProportional(ProportionalEditState(ProportionalFalloff.Linear, radiusWorld = 3f, connectedOnly = true), 3f)
		assertTrue(bystander.influence.isEmpty() && bystander.movedIndices.isEmpty(), "Connected Only leaves the bystander where it is")
		assertTrue(capture.entries.first().influence.isNotEmpty(), "while the selected mesh keeps its own halo")
	}

	/** Under Individual Origins a bystander's halo turns about the island that reached it, in the other mesh. */
	@Test
	fun aBystandersHaloTakesTheReachingIslandsPivot() {
		val capture = assertNotNull(build(listOf(square(meshA)), pivotMode = TransformPivotMode.IndividualOrigins, bystanders = listOf(square(meshB, offsetX = 3f))))
		capture.applyProportional(ProportionalEditState(ProportionalFalloff.Linear, radiusWorld = 2f), 2f)
		val influence = assertNotNull(capture.entries.last().influence[0], "the bystander's (3,0) corner is 1 from mesh A")
		assertEquals(1f to 1f, influence.pivotX to influence.pivotY, "it turns about mesh A's island centroid, not its own")
	}

	/**
	 * A triangle topology over just the vertices its triangles name.
	 *
	 * @param IntArray triangles Three vertex indices per triangle.
	 * @return ElementTopology The topology.
	 */
	private fun trianglesOf(triangles: IntArray): ElementTopology = ElementTopology.ofTriangles((triangles.maxOrNull() ?: -1) + 1, triangles)
}