package org.umamo.edit

import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ElementOwner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The remembered element selection: forgetting a mesh drops only that mesh's memory, and the other
 * meshes' elements and the remembered select mode still come back.
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
		val restored = memory.restore(MeshSelection.editingDrawables(listOf(drawableA, drawableB)), model)

		assertTrue(restored.elementsOf(drawableA).isEmpty())
		assertEquals(setOf<MeshElement>(MeshElement.Edge(1, 2)), restored.elementsOf(drawableB))
		assertEquals(MeshSelectMode.Edge, restored.selectMode)
	}
}