package org.umamo.edit

import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ElementOwner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The remembered element selection: forgetting a mesh drops only that mesh's memory, a restore lands in the seeded
 * select mode with each mesh's elements converted from the mode they were stashed in, and elements that no longer
 * fit are dropped whole.
 */
class MeshElementMemoryTest {
	private val drawableA = DrawableId("a")
	private val drawableB = DrawableId("b")

	@Test
	fun forgetDropsOnlyTheNamedDrawables() {
		val model = SessionTestModels.twoMeshModel()
		val memory = MeshElementMemory()
		val session = MeshSelection.editingDrawables(listOf(drawableA, drawableB)).copy(selectMode = MeshSelectMode.Edge)
		var selected = MeshSelectionOps.add(session, drawableA, MeshElement.Edge.of(0, 1))
		selected = MeshSelectionOps.add(selected, drawableB, MeshElement.Edge.of(1, 2))
		memory.stash(selected)

		memory.forget(listOf(ElementOwner.Drawable(drawableA)))
		val restored = memory.restore(session, model)

		assertTrue(restored.elementsOf(drawableA).isEmpty())
		assertEquals(setOf<MeshElement>(MeshElement.Edge(1, 2)), restored.elementsOf(drawableB))
		assertEquals(MeshSelectMode.Edge, restored.selectMode)
	}

	/** A restore never changes the seeded select mode: elements stashed in another mode convert into it. */
	@Test
	fun restoreConvertsIntoTheSeededSelectMode() {
		val model = SessionTestModels.twoMeshModel()
		val memory = MeshElementMemory()
		var selected = MeshSelection.editingDrawables(listOf(drawableA))
		selected = MeshSelectionOps.add(selected, drawableA, MeshElement.Vertex(0))
		selected = MeshSelectionOps.add(selected, drawableA, MeshElement.Vertex(1))
		selected = MeshSelectionOps.add(selected, drawableA, MeshElement.Vertex(2))
		memory.stash(selected)

		val restored = memory.restore(MeshSelection.editingDrawables(listOf(drawableA)).copy(selectMode = MeshSelectMode.Face), model)

		assertEquals(MeshSelectMode.Face, restored.selectMode)
		assertEquals(setOf<MeshElement>(MeshElement.Face(0)), restored.elementsOf(drawableA), "all three corners derive the face")
		assertNull(restored.activeElement, "a remembered vertex cannot be the active element of a face selection")
	}

	/** Elements that no longer fit the mesh drop the owner's memory whole rather than converting what is left. */
	@Test
	fun aStashThatNoLongerFitsIsDroppedBeforeConverting() {
		val model = SessionTestModels.twoMeshModel()
		val memory = MeshElementMemory()
		var selected = MeshSelection.editingDrawables(listOf(drawableA))
		selected = MeshSelectionOps.add(selected, drawableA, MeshElement.Vertex(0))
		selected = MeshSelectionOps.add(selected, drawableA, MeshElement.Vertex(1))
		selected = MeshSelectionOps.add(selected, drawableA, MeshElement.Vertex(5))
		memory.stash(selected)

		val restored = memory.restore(MeshSelection.editingDrawables(listOf(drawableA)).copy(selectMode = MeshSelectMode.Edge), model)

		assertEquals(MeshSelectMode.Edge, restored.selectMode)
		assertTrue(restored.isEmpty, "vertex 5 is not on the triangle, so the edge 0-1 is not derived from the rest")
	}
}