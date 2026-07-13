package org.umamo.edit

import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.Glue
import org.umamo.runtime.model.GluePair
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Verifies the topology-edit infrastructure and the op builders: withMeshTopologyEdit rebuilds every
 * keyform cell's deltas per VertexSource (copy / average / lerp) and remaps or drops glue pairs;
 * duplicate appends copies with copied faces; merge collapses onto the survivor per target and drops
 * degenerate triangles; rip splits a fan by the side predicate; connect cuts crossed triangles at
 * lerped vertices and refuses degenerate requests; object-mode duplicate inserts after its source with
 * a unique id; and the session commits model + selection as one undo step.
 */
class MeshTopologyEditsTest {
	private val paramA = ParameterId("A")

	// The strip: a unit-ish quad of two triangles sharing edge (1, 2).
	// v0 (0,0), v1 (2,0), v2 (0,2), v3 (2,2).
	private val stripPositions = floatArrayOf(0f, 0f, 2f, 0f, 0f, 2f, 2f, 2f)
	private val stripUvs = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
	private val stripIndices = intArrayOf(0, 1, 2, 1, 3, 2)
	private val stripMesh = DrawableMesh(stripPositions, stripUvs, stripIndices)

	// Per-vertex deltas 10*(index+1) on x, 0 on y - distinguishable per vertex.
	private fun stripForm(): MeshForm = MeshForm(floatArrayOf(10f, 0f, 20f, 0f, 30f, 0f, 40f, 0f))

	private fun stripDrawable(id: String): Drawable =
		Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = stripMesh,
			keyforms = KeyformGrid(listOf(KeyformAxis(paramA, floatArrayOf(0f))), listOf(KeyformCell(intArrayOf(0), stripForm()))),
		)

	private fun model(): PuppetModel =
		PuppetModel(
			parameters = listOf(Parameter(paramA, "A", -1f, 1f, 0f)),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = listOf(stripDrawable("d"), stripDrawable("other")),
			rootChildren = listOf(OrgChild.Drawable(DrawableId("d")), OrgChild.Drawable(DrawableId("other"))),
			rootPartId = null,
			glues =
				listOf(
					Glue(
						meshA = DrawableId("d"),
						meshB = DrawableId("other"),
						pairs = listOf(GluePair(1, 1, 0.5f, 0.5f), GluePair(3, 3, 0.5f, 0.5f)),
						intensity = null,
					),
				),
		)

	/** withMeshTopologyEdit rebuilds keyform deltas per source kind and remaps / drops glue pairs. */
	@Test
	fun topologyEditRemapsDeltasAndGlue() {
		// New mesh: keep v0 and v1, average v2+v3 into a survivor, and add a lerp point on (0, 1).
		val newMesh =
			DrawableMesh(
				floatArrayOf(0f, 0f, 2f, 0f, 1f, 2f, 1f, 0f),
				FloatArray(8),
				intArrayOf(0, 1, 2),
			)
		val edit =
			MeshTopologyEdit(
				newMesh,
				listOf(
					VertexSource.FromOld(0),
					VertexSource.FromOld(1),
					VertexSource.AverageOf(listOf(2, 3)),
					VertexSource.LerpOf(0, 1, 0.25f),
				),
			)
		val edited = model().withMeshTopologyEdit(DrawableId("d"), edit)
		val editedDrawable = edited.drawables.first { it.id == DrawableId("d") }
		assertSame(newMesh, editedDrawable.mesh, "the replacement mesh swaps in")
		val deltas = editedDrawable.keyforms!!.cells.single().form.positionDeltas
		assertEquals(10f, deltas[0], "v0 copies its delta")
		assertEquals(20f, deltas[2], "v1 copies its delta")
		assertEquals(35f, deltas[4], "the merge survivor averages v2 and v3 (30 + 40) / 2")
		assertEquals(12.5f, deltas[6], 1e-4f, "the split point lerps v0 and v1 by t = 0.25")

		// Glue: pair on old v1 remaps to new v1 (same slot); pair on old v3 collapses onto the survivor.
		val glue = edited.glues.single()
		assertEquals(listOf(1, 2), glue.pairs.map { pair -> pair.indexA }, "old v1 keeps its slot; old v3 remaps to the survivor")
		assertEquals(listOf(1, 3), glue.pairs.map { pair -> pair.indexB }, "the other mesh's indices are untouched")

		// A malformed edit (source list size mismatch) is a no-op, never a corruption.
		val malformed = MeshTopologyEdit(newMesh, listOf(VertexSource.FromOld(0)))
		assertSame(model().let { it to it.withMeshTopologyEdit(DrawableId("d"), malformed) }.let { (before, after) -> before === after }, true)
	}

	/** duplicateElements appends copies of the covered vertices plus every fully-covered face. */
	@Test
	fun duplicateAppendsCopiesAndFaces() {
		val result = MeshTopologyOps.duplicateElements(stripMesh, setOf(0, 1, 2))!!
		val mesh = result.edit.newMesh
		assertEquals(7, mesh.vertexCount, "three copies append after the four originals")
		assertEquals(3, mesh.indices.size / 3, "only the fully-covered triangle duplicates")
		assertEquals(listOf(4, 5, 6), mesh.indices.toList().subList(6, 9), "the copied face uses the copies")
		assertEquals(0f, mesh.positions[8], "copy of v0 keeps its position x")
		assertEquals(stripUvs[0], mesh.uvs[8], "copy of v0 keeps its uv")
		assertEquals(VertexSource.FromOld(0), result.edit.vertexSources[4], "the copy's deltas copy from its source")
		assertEquals(
			setOf<MeshElement>(MeshElement.Vertex(4), MeshElement.Vertex(5), MeshElement.Vertex(6)),
			result.newElements,
			"the copies are the new selection",
		)
		assertNull(MeshTopologyOps.duplicateElements(stripMesh, emptySet()), "nothing covered refuses")
	}

	/** mergeVertices collapses per target, drops degenerate triangles, and keeps the survivor selected. */
	@Test
	fun mergeCollapsesPerTarget() {
		// Merge v1 and v3 (the strip's right edge) at center: survivor at (2, 1).
		val atCenter = MeshTopologyOps.mergeVertices(stripMesh, listOf(1, 3), MergeTarget.AtCenter)!!
		val centerMesh = atCenter.edit.newMesh
		assertEquals(3, centerMesh.vertexCount, "four vertices merge into three")
		val survivor = 2
		assertEquals(2f, centerMesh.positions[survivor * 2], "survivor x is the midpoint")
		assertEquals(1f, centerMesh.positions[survivor * 2 + 1], "survivor y is the midpoint")
		// Triangle (1, 3, 2) held BOTH merged vertices, so it degenerates and drops; (0, 1, 2) survives.
		assertEquals(1, centerMesh.indices.size / 3, "the triangle that held both merged vertices drops")
		assertEquals(setOf<MeshElement>(MeshElement.Vertex(survivor)), atCenter.newElements)
		assertTrue(atCenter.edit.vertexSources[survivor] is VertexSource.AverageOf, "the survivor averages its members")

		// AtFirst keeps v1's position verbatim.
		val atFirst = MeshTopologyOps.mergeVertices(stripMesh, listOf(1, 3), MergeTarget.AtFirst)!!
		assertEquals(2f, atFirst.edit.newMesh.positions[4], "survivor x = v1.x")
		assertEquals(0f, atFirst.edit.newMesh.positions[5], "survivor y = v1.y")
		assertEquals(VertexSource.FromOld(1), atFirst.edit.vertexSources[2])

		// AtLast keeps v3's position.
		val atLast = MeshTopologyOps.mergeVertices(stripMesh, listOf(1, 3), MergeTarget.AtLast)!!
		assertEquals(2f, atLast.edit.newMesh.positions[4])
		assertEquals(2f, atLast.edit.newMesh.positions[5])

		// Merging two corners of ONE triangle degenerates it: only the other triangle survives.
		val degenerate = MeshTopologyOps.mergeVertices(stripMesh, listOf(0, 1), MergeTarget.AtCenter)!!
		assertEquals(1, degenerate.edit.newMesh.indices.size / 3, "the triangle that collapsed drops")

		assertNull(MeshTopologyOps.mergeVertices(stripMesh, listOf(1), MergeTarget.AtCenter), "one vertex refuses")
	}

	/** ripElements duplicates only vertices whose fan splits, re-pointing the marked side. */
	@Test
	fun ripSplitsFanBySidePredicate() {
		// Rip the shared edge's vertices (1, 2), marking triangle 1 as the side that takes the copies.
		val result = MeshTopologyOps.ripElements(stripMesh, setOf(1, 2)) { triangleIndex -> triangleIndex == 1 }!!
		val mesh = result.edit.newMesh
		assertEquals(6, mesh.vertexCount, "both shared vertices duplicate")
		// Triangle 0 keeps the originals; triangle 1 re-points at the copies.
		assertEquals(listOf(0, 1, 2), mesh.indices.toList().subList(0, 3))
		assertEquals(listOf(4, 3, 5), mesh.indices.toList().subList(3, 6), "the marked triangle uses the copies (v1 -> 4, v2 -> 5)")
		assertEquals(setOf<MeshElement>(MeshElement.Vertex(4), MeshElement.Vertex(5)), result.newElements)

		// Every adjacent triangle marked (or none): nothing to split, refused.
		assertNull(MeshTopologyOps.ripElements(stripMesh, setOf(1, 2)) { true }, "a one-sided rip refuses")
	}

	/** connectVertices cuts the crossed shared edge at a lerped vertex and retriangulates both sides. */
	@Test
	fun connectCutsAcrossTheSharedEdge() {
		// Connect v0 (0,0) and v3 (2,2): the diagonal crosses the shared edge (1,2) at its midpoint.
		val result = MeshTopologyOps.connectVertices(stripMesh, 0, 3)!!
		val mesh = result.edit.newMesh
		assertEquals(5, mesh.vertexCount, "one crossing vertex appends")
		assertEquals(1f, mesh.positions[8], 1e-4f, "the crossing sits at the shared edge's midpoint x")
		assertEquals(1f, mesh.positions[9], 1e-4f, "and midpoint y")
		assertEquals(4, mesh.indices.size / 3, "each crossed triangle splits in two")
		val crossingSource = result.edit.vertexSources[4]
		assertTrue(crossingSource is VertexSource.LerpOf && absDiff(crossingSource.t, 0.5f) < 1e-4f, "the crossing lerps at t = 0.5")
		assertTrue(MeshElement.Vertex(4) in result.newElements, "the cut path is selected")

		// Already-connected vertices (an existing edge) refuse.
		assertNull(MeshTopologyOps.connectVertices(stripMesh, 1, 2), "an existing edge refuses")
		assertNull(MeshTopologyOps.connectVertices(stripMesh, 0, 0), "a self-connect refuses")
	}

	/** Object-mode duplicate: a unique .001 id, org-tree insertion after the source, no glue membership. */
	@Test
	fun withDrawableDuplicatedInsertsAfterSource() {
		val (edited, copyId) = model().withDrawableDuplicated(DrawableId("d"))!!
		assertEquals(DrawableId("d.001"), copyId)
		assertEquals(3, edited.drawables.size)
		assertEquals(
			listOf<OrgChild>(OrgChild.Drawable(DrawableId("d")), OrgChild.Drawable(copyId), OrgChild.Drawable(DrawableId("other"))),
			edited.rootChildren,
			"the copy sits immediately after its source",
		)
		val copy = edited.drawables.first { it.id == copyId }
		val source = edited.drawables.first { it.id == DrawableId("d") }
		assertTrue(copy.mesh!!.positions !== source.mesh!!.positions, "positions are a fresh array")
		assertSame(source.mesh!!.uvs, copy.mesh!!.uvs, "uvs share by reference (COW)")
		assertTrue(edited.glues.single().meshA != copyId && edited.glues.single().meshB != copyId, "the copy joins no glue")
		assertEquals(DrawableId("d"), copy.textureSourceId, "the copy resolves its atlas binding through its source")

		// A second duplicate of the same source lands at .002.
		val (again, secondId) = edited.withDrawableDuplicated(DrawableId("d"))!!
		assertEquals(DrawableId("d.002"), secondId)
		assertEquals(4, again.drawables.size)

		// Duplicating the COPY still binds the texture through the ORIGINAL (the id the atlas map knows).
		val (chained, chainedId) = again.withDrawableDuplicated(copyId)!!
		val chainedCopy = chained.drawables.first { it.id == chainedId }
		assertEquals(DrawableId("d"), chainedCopy.textureSourceId, "a copy of a copy inherits the original's atlas binding")
	}

	/** The session commits a topology edit and its result selection as ONE undo step. */
	@Test
	fun sessionCommitsTopologyAtomically() {
		val session = EditorSession(model())
		session.setSelection(SelectionOps.replace(SelectionTarget.Drawable(DrawableId("d"))))
		session.setMode(EditorMode.Edit)
		session.setMeshSelection(
			MeshSelectionOps.add(MeshSelectionOps.add(session.meshSelection.value, DrawableId("d"), MeshElement.Vertex(0)), DrawableId("d"), MeshElement.Vertex(1)),
		)

		val stepsBefore = session.historyView.value.steps.size
		session.duplicateSelectedElements()
		assertEquals(stepsBefore + 1, session.historyView.value.steps.size, "duplicate is one undo step")
		assertEquals(6, session.model.value.drawables.first { it.id == DrawableId("d") }.mesh!!.vertexCount, "two copies appended")
		assertEquals(
			setOf<MeshElement>(MeshElement.Vertex(4), MeshElement.Vertex(5)),
			session.meshSelection.value.elementsOf(DrawableId("d")),
			"the copies are selected",
		)
		assertTrue(session.dirty.value, "a topology edit dirties the document")

		session.undo()
		assertEquals(4, session.model.value.drawables.first { it.id == DrawableId("d") }.mesh!!.vertexCount, "undo restores the topology")
		assertEquals(
			setOf<MeshElement>(MeshElement.Vertex(0), MeshElement.Vertex(1)),
			session.meshSelection.value.elementsOf(DrawableId("d")),
			"undo restores the selection the edit replaced - never torn from the topology",
		)
	}

	/** A face-mode duplicate keeps face mode, leaving the new face selected (Blender keeps the mode). */
	@Test
	fun faceModeDuplicateKeepsFaceMode() {
		val session = EditorSession(model())
		session.setSelection(SelectionOps.replace(SelectionTarget.Drawable(DrawableId("d"))))
		session.setMode(EditorMode.Edit)
		session.setMeshSelectMode(MeshSelectMode.Face)
		session.setMeshSelection(MeshSelectionOps.replace(session.meshSelection.value, DrawableId("d"), MeshElement.Face(0)))

		session.duplicateSelectedElements()

		// Face 0's three vertices copied to 4..6 plus one new triangle appended at index 2.
		assertEquals(MeshSelectMode.Face, session.meshSelection.value.selectMode, "duplicate never switches the select mode")
		assertEquals(
			setOf<MeshElement>(MeshElement.Face(2)),
			session.meshSelection.value.elementsOf(DrawableId("d")),
			"the copied face is selected in face mode",
		)
		assertEquals(
			ActiveMeshElement(DrawableId("d"), MeshElement.Face(2)),
			session.meshSelection.value.activeElement,
			"the copied face becomes active",
		)
	}

	/** An edge-mode duplicate covering a whole face keeps edge mode with the copied edges selected. */
	@Test
	fun edgeModeDuplicateKeepsEdgeMode() {
		val session = EditorSession(model())
		session.setSelection(SelectionOps.replace(SelectionTarget.Drawable(DrawableId("d"))))
		session.setMode(EditorMode.Edit)
		session.setMeshSelectMode(MeshSelectMode.Edge)
		// All three edges of face 0, so the whole triangle duplicates.
		var withEdges = MeshSelectionOps.replace(session.meshSelection.value, DrawableId("d"), MeshElement.Edge(0, 1))
		withEdges = MeshSelectionOps.add(withEdges, DrawableId("d"), MeshElement.Edge(1, 2))
		withEdges = MeshSelectionOps.add(withEdges, DrawableId("d"), MeshElement.Edge(0, 2))
		session.setMeshSelection(withEdges)

		session.duplicateSelectedElements()

		assertEquals(MeshSelectMode.Edge, session.meshSelection.value.selectMode, "duplicate never switches the select mode")
		assertEquals(
			setOf<MeshElement>(MeshElement.Edge(4, 5), MeshElement.Edge(5, 6), MeshElement.Edge(4, 6)),
			session.meshSelection.value.elementsOf(DrawableId("d")),
			"the copied triangle's edges are selected in edge mode",
		)
	}

	/**
	 * An edge-mode duplicate of one lone edge copies two loose vertices no edge covers, so the result
	 * falls back to vertex mode - stranding the copies unselected would hide them and starve the
	 * follow-up auto-grab.
	 */
	@Test
	fun looseEdgeDuplicateFallsBackToVertexMode() {
		val session = EditorSession(model())
		session.setSelection(SelectionOps.replace(SelectionTarget.Drawable(DrawableId("d"))))
		session.setMode(EditorMode.Edit)
		session.setMeshSelectMode(MeshSelectMode.Edge)
		session.setMeshSelection(MeshSelectionOps.replace(session.meshSelection.value, DrawableId("d"), MeshElement.Edge(0, 1)))

		session.duplicateSelectedElements()

		assertEquals(MeshSelectMode.Vertex, session.meshSelection.value.selectMode, "loose copies exist only in the vertex domain")
		assertEquals(
			setOf<MeshElement>(MeshElement.Vertex(4), MeshElement.Vertex(5)),
			session.meshSelection.value.elementsOf(DrawableId("d")),
			"the loose vertex copies are selected",
		)
	}

	/** Object-mode duplicateSelectedDrawables selects the copies as one undo step. */
	@Test
	fun sessionDuplicatesDrawablesAndSelectsCopies() {
		val session = EditorSession(model())
		session.setSelection(SelectionOps.replace(SelectionTarget.Drawable(DrawableId("d"))))

		val stepsBefore = session.historyView.value.steps.size
		val copies = session.duplicateSelectedDrawables()
		assertEquals(listOf(DrawableId("d.001")), copies)
		assertEquals(stepsBefore + 1, session.historyView.value.steps.size, "one undo step")
		assertEquals(
			setOf<SelectionTarget>(SelectionTarget.Drawable(DrawableId("d.001"))),
			session.selection.value.targets,
			"the copies become the selection",
		)

		session.undo()
		assertEquals(2, session.model.value.drawables.size, "undo removes the copy")
		assertEquals(
			setOf<SelectionTarget>(SelectionTarget.Drawable(DrawableId("d"))),
			session.selection.value.targets,
			"undo restores the prior selection",
		)
	}

	private fun absDiff(left: Float, right: Float): Float = if (left > right) left - right else right - left
}
