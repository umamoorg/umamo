package org.umamo.edit

import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Verifies the pure element-selection gestures maintain the [MeshSelection] invariant (the active element
 * is a member of its drawable's selected set, or null when nothing remains), keep the session meshes and
 * select mode across every op, span multiple meshes correctly (elements are mesh-local), and that
 * select-mode switches follow Blender's flush-down / derive-up conversion rules per mesh.
 */
class MeshSelectionOpsTest {
	private val drawableD = DrawableId("d")
	private val drawableE = DrawableId("e")

	// A single-mesh session over d, and a two-mesh session over d + e.
	private val base = MeshSelection.editing(listOf(drawableD))
	private val dual = MeshSelection.editing(listOf(drawableD, drawableE))

	// The "spoke" fixture: center triangle T (face 0, vertices 0-1-2) shares each of its three edges with
	// exactly one neighbor - A (face 1) across (0,1), B (face 2) across (1,2), C (face 3) across (0,2).
	private val spokeIndices = intArrayOf(0, 1, 2, 0, 1, 3, 1, 2, 4, 2, 0, 5)
	private val spokeMesh = DrawableMesh(FloatArray(12), FloatArray(12), spokeIndices)

	// Two triangles sharing the (1, 2) edge - the second session mesh's smaller topology.
	private val stripIndices = intArrayOf(0, 1, 2, 1, 3, 2)
	private val stripMesh = DrawableMesh(FloatArray(8), FloatArray(8), stripIndices)

	// The mesh provider the whole-session ops resolve topology through.
	private fun meshOf(drawableId: DrawableId): DrawableMesh? =
		when (drawableId) {
			drawableD -> spokeMesh
			drawableE -> stripMesh
			else -> null
		}

	/**
	 * Builds a vertex-domain element set from vertex indices, for terse assertions.
	 *
	 * @param IntArray vertexIndices The vertex indices.
	 * @return Set<MeshElement> The corresponding vertex elements.
	 */
	private fun vertices(vararg vertexIndices: Int): Set<MeshElement> =
		vertexIndices.map { vertexIndex -> MeshElement.Vertex(vertexIndex) }.toSet()

	/**
	 * A single-mesh selection over d with the given elements (and optionally an active element).
	 *
	 * @param Set<MeshElement> elements The selected elements on d.
	 * @param MeshElement? active The active element, or null.
	 * @param MeshSelectMode selectMode The select mode.
	 * @return MeshSelection The built selection.
	 */
	private fun selectionOnD(
		elements: Set<MeshElement>,
		active: MeshElement? = null,
		selectMode: MeshSelectMode = MeshSelectMode.Vertex,
	): MeshSelection =
		base.copy(
			selectMode = selectMode,
			elementsByDrawable = if (elements.isEmpty()) emptyMap() else mapOf(drawableD to elements),
			activeElement = active?.let { ActiveMeshElement(drawableD, it) },
		)

	/** fitsWithinMeshes accepts elements that still exist in their mesh and rejects the rest, per domain. */
	@Test
	fun fitsWithinMeshesValidatesEachDomain() {
		// The spoke fixture has vertices 0..5, four faces, and no (3,4) edge.
		assertTrue(MeshSelectionOps.fitsWithinMeshes(selectionOnD(vertices(0, 5)), ::meshOf), "in-range vertices fit")
		assertTrue(
			MeshSelectionOps.fitsWithinMeshes(selectionOnD(setOf(MeshElement.Edge.of(0, 1)), selectMode = MeshSelectMode.Edge), ::meshOf),
			"an existing edge fits",
		)
		assertTrue(
			MeshSelectionOps.fitsWithinMeshes(selectionOnD(setOf(MeshElement.Face(3)), selectMode = MeshSelectMode.Face), ::meshOf),
			"an existing face fits",
		)
		assertTrue(MeshSelectionOps.fitsWithinMeshes(base, ::meshOf), "an empty selection always fits")

		assertFalse(MeshSelectionOps.fitsWithinMeshes(selectionOnD(vertices(0, 6)), ::meshOf), "an out-of-range vertex does not fit")
		assertFalse(
			MeshSelectionOps.fitsWithinMeshes(selectionOnD(setOf(MeshElement.Edge.of(3, 4)), selectMode = MeshSelectMode.Edge), ::meshOf),
			"an edge absent from the mesh does not fit even with in-range endpoints",
		)
		assertFalse(
			MeshSelectionOps.fitsWithinMeshes(selectionOnD(setOf(MeshElement.Face(4)), selectMode = MeshSelectMode.Face), ::meshOf),
			"an out-of-range face ordinal does not fit",
		)
		assertFalse(
			MeshSelectionOps.fitsWithinMeshes(
				base.copy(elementsByDrawable = mapOf(DrawableId("gone") to vertices(0))),
			) { drawableId -> meshOf(drawableId) },
			"a selection on a mesh the provider cannot resolve does not fit",
		)
	}

	/** replace selects a single element and makes it (and its mesh) active, keeping the session. */
	@Test
	fun replaceSelectsSingleActive() {
		val result = MeshSelectionOps.replace(base, drawableD, MeshElement.Vertex(3))
		assertEquals(vertices(3), result.elementsOf(drawableD))
		assertEquals(ActiveMeshElement(drawableD, MeshElement.Vertex(3)), result.activeElement)
		assertEquals(listOf(drawableD), result.drawableIds)
		assertEquals(drawableD, result.activeDrawableId)
	}

	/** replace on one session mesh clears the other meshes' selections (a plain click replaces everything). */
	@Test
	fun replaceClearsOtherMeshes() {
		val seeded = MeshSelectionOps.add(dual, drawableD, MeshElement.Vertex(1))
		val result = MeshSelectionOps.replace(seeded, drawableE, MeshElement.Vertex(0))
		assertTrue(result.elementsOf(drawableD).isEmpty(), "the other mesh's selection cleared")
		assertEquals(vertices(0), result.elementsOf(drawableE))
		assertEquals(drawableE, result.activeDrawableId, "the clicked mesh becomes active")
	}

	/** toggle adds a missing element (active) and removes a present one (active falls back or clears). */
	@Test
	fun toggleAddsThenRemoves() {
		val added = MeshSelectionOps.toggle(MeshSelectionOps.toggle(base, drawableD, MeshElement.Vertex(1)), drawableD, MeshElement.Vertex(2))
		assertEquals(vertices(1, 2), added.elementsOf(drawableD))
		assertEquals(ActiveMeshElement(drawableD, MeshElement.Vertex(2)), added.activeElement)

		val removed = MeshSelectionOps.toggle(added, drawableD, MeshElement.Vertex(2))
		assertEquals(vertices(1), removed.elementsOf(drawableD))
		val removedActive = removed.activeElement
		assertTrue(
			removedActive != null && removed.contains(removedActive.drawableId, removedActive.element),
			"active stays a member after removal",
		)

		val emptied = MeshSelectionOps.toggle(removed, drawableD, MeshElement.Vertex(1))
		assertTrue(emptied.isEmpty)
		assertNull(emptied.activeElement, "active clears when the set empties")
	}

	/** add extends the set and promotes the added element to active. */
	@Test
	fun addExtendsAndActivates() {
		val result = MeshSelectionOps.add(MeshSelectionOps.add(base, drawableD, MeshElement.Vertex(0)), drawableD, MeshElement.Vertex(5))
		assertEquals(vertices(0, 5), result.elementsOf(drawableD))
		assertEquals(ActiveMeshElement(drawableD, MeshElement.Vertex(5)), result.activeElement)
	}

	/** box replaces (or unions when additive) and keeps the active element only when it remains selected. */
	@Test
	fun boxReplacesOrUnionsAndMaintainsActive() {
		val seeded = MeshSelectionOps.replace(base, drawableD, MeshElement.Vertex(0))
		val replaced = MeshSelectionOps.box(seeded, mapOf(drawableD to vertices(2, 3)), additive = false)
		assertEquals(vertices(2, 3), replaced.elementsOf(drawableD))
		assertNull(replaced.activeElement, "active drops when it is no longer selected")

		val additive = MeshSelectionOps.box(seeded, mapOf(drawableD to vertices(2, 3)), additive = true)
		assertEquals(vertices(0, 2, 3), additive.elementsOf(drawableD))
		assertEquals(ActiveMeshElement(drawableD, MeshElement.Vertex(0)), additive.activeElement, "active kept when it stays selected")
	}

	/** A box spanning two session meshes selects on both; a non-additive box replaces both. */
	@Test
	fun boxSpansSessionMeshes() {
		val seeded = MeshSelectionOps.add(dual, drawableD, MeshElement.Vertex(5))
		val boxed =
			MeshSelectionOps.box(
				seeded,
				mapOf(drawableD to vertices(0, 1), drawableE to vertices(2, 3)),
				additive = false,
			)
		assertEquals(vertices(0, 1), boxed.elementsOf(drawableD))
		assertEquals(vertices(2, 3), boxed.elementsOf(drawableE))
		assertEquals(4, boxed.size, "size sums across meshes")

		val additive =
			MeshSelectionOps.box(seeded, mapOf(drawableE to vertices(0)), additive = true)
		assertEquals(vertices(5), additive.elementsOf(drawableD), "the additive box keeps the other mesh's selection")
		assertEquals(vertices(0), additive.elementsOf(drawableE))
	}

	/** clear empties every mesh's element set but keeps the session and mode (the Edit session stays open). */
	@Test
	fun clearKeepsSessionAndMode() {
		val edgeMode = dual.copy(selectMode = MeshSelectMode.Edge)
		val result = MeshSelectionOps.clear(MeshSelectionOps.add(edgeMode, drawableD, MeshElement.Edge.of(1, 0)))
		assertTrue(result.isEmpty)
		assertNull(result.activeElement)
		assertEquals(listOf(drawableD, drawableE), result.drawableIds)
		assertEquals(MeshSelectMode.Edge, result.selectMode)
	}

	/** The gestures work identically over edge and face elements, and Edge.of canonicalizes endpoint order. */
	@Test
	fun gesturesAreDomainAgnosticAndEdgesCanonicalize() {
		val edgeSelection = MeshSelectionOps.replace(base.copy(selectMode = MeshSelectMode.Edge), drawableD, MeshElement.Edge.of(2, 1))
		assertEquals(setOf<MeshElement>(MeshElement.Edge(1, 2)), edgeSelection.elementsOf(drawableD), "(2,1) and (1,2) are one identity")
		assertTrue(edgeSelection.contains(drawableD, MeshElement.Edge.of(1, 2)))

		val faceSelection = MeshSelectionOps.toggle(base.copy(selectMode = MeshSelectMode.Face), drawableD, MeshElement.Face(3))
		assertEquals(setOf<MeshElement>(MeshElement.Face(3)), faceSelection.elementsOf(drawableD))
		assertEquals(ActiveMeshElement(drawableD, MeshElement.Face(3)), faceSelection.activeElement)
	}

	/** A same-mode switch is a no-op returning the same instance; a converting switch nulls the active element. */
	@Test
	fun changeSelectModeSameModeIsIdentityAndConversionDropsActive() {
		val selection = MeshSelectionOps.add(base, drawableD, MeshElement.Vertex(0))
		assertSame(selection, MeshSelectionOps.changeSelectMode(selection, MeshSelectMode.Vertex) { spokeIndices })

		val converted = MeshSelectionOps.changeSelectMode(selection, MeshSelectMode.Edge) { spokeIndices }
		assertEquals(MeshSelectMode.Edge, converted.selectMode)
		assertNull(converted.activeElement, "no single last-touched element survives a domain change")
		assertEquals(listOf(drawableD), converted.drawableIds)
	}

	/** A mode switch converts EVERY session mesh's elements against its own topology. */
	@Test
	fun changeSelectModeConvertsPerMesh() {
		val selection =
			dual.copy(
				elementsByDrawable =
					mapOf(
						// All of spoke face 0's vertices on d; all of strip face 0's vertices on e.
						drawableD to vertices(0, 1, 2),
						drawableE to vertices(0, 1, 2),
					),
			)
		val converted = MeshSelectionOps.changeSelectMode(selection, MeshSelectMode.Face) { drawableId -> meshOf(drawableId)?.indices }
		assertEquals(setOf<MeshElement>(MeshElement.Face(0)), converted.elementsOf(drawableD), "the spoke's face 0 derives on d")
		assertEquals(setOf<MeshElement>(MeshElement.Face(0)), converted.elementsOf(drawableE), "the strip's face 0 derives on e")
	}

	/** Vertex-to-edge derives only edges whose both endpoints were selected. */
	@Test
	fun vertexToEdgeRequiresBothEndpoints() {
		val converted = MeshSelectionOps.changeSelectMode(selectionOnD(vertices(0, 1, 3)), MeshSelectMode.Edge) { spokeIndices }
		assertEquals(
			setOf<MeshElement>(MeshElement.Edge.of(0, 1), MeshElement.Edge.of(0, 3), MeshElement.Edge.of(1, 3)),
			converted.elementsOf(drawableD),
		)
	}

	/** Vertex-to-face derives only faces whose all three vertices were selected. */
	@Test
	fun vertexToFaceRequiresAllVertices() {
		val allOfT = MeshSelectionOps.changeSelectMode(selectionOnD(vertices(0, 1, 2)), MeshSelectMode.Face) { spokeIndices }
		assertEquals(setOf<MeshElement>(MeshElement.Face(0)), allOfT.elementsOf(drawableD))

		val twoOfThree = MeshSelectionOps.changeSelectMode(selectionOnD(vertices(0, 1, 4)), MeshSelectMode.Face) { spokeIndices }
		assertTrue(twoOfThree.isEmpty, "two of three vertices never derive a face")
	}

	/**
	 * The headline Blender rule: edges of the neighbors can cover every vertex of the center triangle T
	 * without selecting T - a face derives only from all three of its OWN edges, never from vertex coverage.
	 */
	@Test
	fun edgeToFaceRequiresOwnEdgesNotVertexCoverage() {
		val neighborEdges =
			setOf<MeshElement>(
				MeshElement.Edge.of(0, 3),
				MeshElement.Edge.of(1, 3),
				MeshElement.Edge.of(1, 4),
				MeshElement.Edge.of(2, 4),
			)
		// Sanity: these four edges cover all of T's vertices {0, 1, 2}.
		assertTrue(MeshTopology.coveredVertexIndices(neighborEdges, spokeIndices).containsAll(setOf(0, 1, 2)))
		val converted =
			MeshSelectionOps.changeSelectMode(
				selectionOnD(neighborEdges, selectMode = MeshSelectMode.Edge),
				MeshSelectMode.Face,
			) { spokeIndices }
		assertTrue(converted.isEmpty, "no face owns all three of its edges here (T owns none, A and B own two)")

		val ownEdges =
			setOf<MeshElement>(
				MeshElement.Edge.of(0, 1),
				MeshElement.Edge.of(1, 2),
				MeshElement.Edge.of(0, 2),
			)
		val face =
			MeshSelectionOps.changeSelectMode(
				selectionOnD(ownEdges, selectMode = MeshSelectMode.Edge),
				MeshSelectMode.Face,
			) { spokeIndices }
		assertEquals(setOf<MeshElement>(MeshElement.Face(0)), face.elementsOf(drawableD), "all three own edges derive exactly T")
	}

	/** Face-to-edge flushes down to the selected faces' own edges. */
	@Test
	fun faceToEdgeFlushesToOwnEdges() {
		val converted =
			MeshSelectionOps.changeSelectMode(
				selectionOnD(setOf(MeshElement.Face(1)), selectMode = MeshSelectMode.Face),
				MeshSelectMode.Edge,
			) { spokeIndices }
		assertEquals(
			setOf<MeshElement>(MeshElement.Edge.of(0, 1), MeshElement.Edge.of(1, 3), MeshElement.Edge.of(0, 3)),
			converted.elementsOf(drawableD),
		)
	}

	/**
	 * The documented lossy round-trip (Blender-faithful): selecting T's three neighbors, flushing down to
	 * vertices, then deriving back up to faces gains T - the flush covers all six vertices, and T's three
	 * vertices are among them.
	 */
	@Test
	fun faceToVertexToFaceRoundTripGainsTheEnclosedFace() {
		val neighbors =
			selectionOnD(
				setOf(MeshElement.Face(1), MeshElement.Face(2), MeshElement.Face(3)),
				selectMode = MeshSelectMode.Face,
			)
		val flushed = MeshSelectionOps.changeSelectMode(neighbors, MeshSelectMode.Vertex) { spokeIndices }
		assertEquals(vertices(0, 1, 2, 3, 4, 5), flushed.elementsOf(drawableD), "flush-down covers every vertex of the selected faces")

		val derived = MeshSelectionOps.changeSelectMode(flushed, MeshSelectMode.Face) { spokeIndices }
		assertEquals(
			setOf<MeshElement>(MeshElement.Face(0), MeshElement.Face(1), MeshElement.Face(2), MeshElement.Face(3)),
			derived.elementsOf(drawableD),
			"deriving back up now includes the enclosed face T",
		)
	}

	/** Edge-to-vertex flushes down to the selected edges' endpoints. */
	@Test
	fun edgeToVertexFlushesToEndpoints() {
		val converted =
			MeshSelectionOps.changeSelectMode(
				selectionOnD(setOf(MeshElement.Edge.of(1, 3), MeshElement.Edge.of(2, 4)), selectMode = MeshSelectMode.Edge),
				MeshSelectMode.Vertex,
			) { spokeIndices }
		assertEquals(vertices(1, 2, 3, 4), converted.elementsOf(drawableD))
	}

	/** selectAll fills the current mode's whole domain on every session mesh, keeping the active element. */
	@Test
	fun selectAllFillsDomainPerModeAcrossMeshes() {
		val seeded = selectionOnD(vertices(2), active = MeshElement.Vertex(2)).copy(drawableIds = dual.drawableIds)
		val allVertices = MeshSelectionOps.selectAll(seeded, ::meshOf)
		assertEquals(vertices(0, 1, 2, 3, 4, 5), allVertices.elementsOf(drawableD), "the spoke's six vertices")
		assertEquals(vertices(0, 1, 2, 3), allVertices.elementsOf(drawableE), "the strip's four vertices")
		assertEquals(ActiveMeshElement(drawableD, MeshElement.Vertex(2)), allVertices.activeElement, "active kept - it is still selected")

		val allFaces = MeshSelectionOps.selectAll(base.copy(selectMode = MeshSelectMode.Face), ::meshOf)
		assertEquals(
			setOf<MeshElement>(MeshElement.Face(0), MeshElement.Face(1), MeshElement.Face(2), MeshElement.Face(3)),
			allFaces.elementsOf(drawableD),
		)

		val allEdges = MeshSelectionOps.selectAll(base.copy(selectMode = MeshSelectMode.Edge), ::meshOf)
		assertEquals(MeshTopology.uniqueEdges(spokeIndices).toSet(), allEdges.elementsOf(drawableD))
	}

	/** invert flips membership per session mesh and drops a now-deselected active element. */
	@Test
	fun invertComplementsDomainAndDropsDeselectedActive() {
		val selection =
			dual.copy(
				elementsByDrawable = mapOf(drawableD to vertices(0, 1)),
				activeElement = ActiveMeshElement(drawableD, MeshElement.Vertex(0)),
			)
		val inverted = MeshSelectionOps.invert(selection, ::meshOf)
		assertEquals(vertices(2, 3, 4, 5), inverted.elementsOf(drawableD))
		assertEquals(vertices(0, 1, 2, 3), inverted.elementsOf(drawableE), "an untouched session mesh inverts from empty to all")
		assertNull(inverted.activeElement, "the active element was deselected by the inversion")
	}

	/** selectLinked unions a connectivity island's elements in the current domain, additively. */
	@Test
	fun selectLinkedUnionsIslandPerDomain() {
		// The strip mesh (vertices 0-3, faces 0-1) as one island; seed a selection elsewhere to prove union.
		val stripSelection = MeshSelection.editing(listOf(drawableE))
		val island = setOf(0, 1, 2, 3)

		val vertexResult = MeshSelectionOps.selectLinked(stripSelection, drawableE, island, stripIndices)
		assertEquals(vertices(0, 1, 2, 3), vertexResult.elementsOf(drawableE), "vertex mode adds every island vertex")

		val edgeResult =
			MeshSelectionOps.selectLinked(stripSelection.copy(selectMode = MeshSelectMode.Edge), drawableE, island, stripIndices)
		assertEquals(MeshTopology.uniqueEdges(stripIndices).toSet(), edgeResult.elementsOf(drawableE), "edge mode adds the island's edges")

		val faceResult =
			MeshSelectionOps.selectLinked(stripSelection.copy(selectMode = MeshSelectMode.Face), drawableE, island, stripIndices)
		assertEquals(setOf<MeshElement>(MeshElement.Face(0), MeshElement.Face(1)), faceResult.elementsOf(drawableE))

		// Additive: an existing selection on another vertex survives the union.
		val seeded = MeshSelectionOps.add(stripSelection, drawableE, MeshElement.Vertex(3))
		val grown = MeshSelectionOps.selectLinked(seeded, drawableE, setOf(0, 1, 2), stripIndices)
		assertEquals(vertices(0, 1, 2, 3), grown.elementsOf(drawableE), "the union keeps prior members")
		assertEquals(ActiveMeshElement(drawableE, MeshElement.Vertex(3)), grown.activeElement, "the active element is untouched")
	}

	/** remove deselects per mesh and keeps the active element only when it survives. */
	@Test
	fun removeDropsElementsAndFixesActive() {
		val selection = selectionOnD(vertices(0, 1, 2), active = MeshElement.Vertex(2))
		val afterActiveGone = MeshSelectionOps.remove(selection, mapOf(drawableD to vertices(2)))
		assertEquals(vertices(0, 1), afterActiveGone.elementsOf(drawableD))
		assertNull(afterActiveGone.activeElement, "active drops when it is removed")

		val afterOtherGone = MeshSelectionOps.remove(selection, mapOf(drawableD to vertices(0)))
		assertEquals(vertices(1, 2), afterOtherGone.elementsOf(drawableD))
		assertEquals(ActiveMeshElement(drawableD, MeshElement.Vertex(2)), afterOtherGone.activeElement, "active kept when it survives")
	}
}
