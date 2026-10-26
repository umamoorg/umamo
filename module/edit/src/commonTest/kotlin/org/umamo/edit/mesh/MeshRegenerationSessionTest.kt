package org.umamo.edit.mesh

import org.umamo.edit.ActiveMeshElement
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshSelectionOps
import org.umamo.edit.SelectionOps
import org.umamo.edit.SelectionTarget
import org.umamo.edit.mesh.RegenerationFixtures.drawableId
import org.umamo.edit.mesh.RegenerationFixtures.partnerId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Committing a regeneration: one undo step for every replaced mesh, the replaced meshes' elements
 * cleared and forgotten while everything else in the selection stays, nothing recorded for no edits or
 * an edit that does not apply, and the step's history label.
 */
class MeshRegenerationSessionTest {
	@Test
	fun regenerateCommitsOneUndoStepOverEveryDrawable() {
		val model = RegenerationFixtures.model()
		val session = EditorSession(model)
		val stepsBefore = session.historyView.value.steps.size
		assertTrue(session.commitMeshRegenerate(mapOf(drawableId to regeneratedEdit(model))))
		assertEquals(stepsBefore + 1, session.historyView.value.steps.size)
		assertEquals("change.mesh.generateFromArt", session.historyView.value.steps.last().labelKey)
		assertNotEquals(9, session.model.value.drawables.first { it.id == drawableId }.mesh!!.vertexCount)
		session.undo()
		assertSame(model, session.model.value, "undo restores the mesh, its keyforms, blend shapes, and glue")
	}

	@Test
	fun regenerateClearsOnlyTheRegeneratedElements() {
		val model = RegenerationFixtures.model()
		val session = EditorSession(model)
		session.setSelection(SelectionOps.add(SelectionOps.replace(SelectionTarget.Drawable(drawableId)), SelectionTarget.Drawable(partnerId)))
		session.setMode(EditorMode.Edit)
		var selection = MeshSelectionOps.add(session.meshSelection.value, partnerId, MeshElement.Vertex(1))
		selection = MeshSelectionOps.add(selection, drawableId, MeshElement.Vertex(0))
		session.setMeshSelection(selection)
		val before = session.meshSelection.value
		assertEquals(ActiveMeshElement(drawableId, MeshElement.Vertex(0)), before.activeElement)

		session.commitMeshRegenerate(mapOf(drawableId to regeneratedEdit(model)))

		val after = session.meshSelection.value
		assertTrue(after.elementsOf(drawableId).isEmpty())
		assertEquals(setOf<MeshElement>(MeshElement.Vertex(1)), after.elementsOf(partnerId))
		assertNull(after.activeElement)
		assertEquals(before.drawableIds, after.drawableIds)
		assertEquals(before.activeDrawableId, after.activeDrawableId)
		assertEquals(before.selectMode, after.selectMode)
	}

	@Test
	fun regenerateForgetsTheRememberedElements() {
		val model = RegenerationFixtures.model()
		val session = EditorSession(model)
		session.setSelection(SelectionOps.replace(SelectionTarget.Drawable(drawableId)))
		session.setMode(EditorMode.Edit)
		session.setMeshSelection(MeshSelectionOps.add(session.meshSelection.value, drawableId, MeshElement.Vertex(0)))
		session.setMode(EditorMode.Object)
		session.setMode(EditorMode.Edit)
		assertEquals(setOf<MeshElement>(MeshElement.Vertex(0)), session.meshSelection.value.elementsOf(drawableId), "re-entering Edit restores the memory")
		session.setMode(EditorMode.Object)

		session.commitMeshRegenerate(mapOf(drawableId to regeneratedEdit(model)))
		session.setMode(EditorMode.Edit)

		assertTrue(session.meshSelection.value.elementsOf(drawableId).isEmpty(), "vertex 0 still fits the new mesh but names another vertex")
	}

	@Test
	fun noEditsOrAnEditThatDoesNotApplyRecordsNothing() {
		val model = RegenerationFixtures.model()
		val session = EditorSession(model)
		val stepsBefore = session.historyView.value.steps.size
		// A source list one short of the new mesh: the whole commit is refused, not just this edit.
		val valid = regeneratedEdit(model)
		val malformed = MeshTopologyEdit(valid.newMesh, valid.vertexSources.drop(1))
		assertFalse(session.commitMeshRegenerate(emptyMap()))
		assertFalse(session.commitMeshRegenerate(mapOf(drawableId to valid, partnerId to malformed)))
		assertEquals(stepsBefore, session.historyView.value.steps.size)
		assertSame(model, session.model.value)
	}

	@Test
	fun aFinishedModelLandsOnlyOverItsOwnBaseAndClearsEveryTarget() {
		val model = RegenerationFixtures.model()
		val session = EditorSession(model)
		session.setSelection(SelectionOps.add(SelectionOps.replace(SelectionTarget.Drawable(drawableId)), SelectionTarget.Drawable(partnerId)))
		session.setMode(EditorMode.Edit)
		var selection = MeshSelectionOps.add(session.meshSelection.value, partnerId, MeshElement.Vertex(1))
		selection = MeshSelectionOps.add(selection, drawableId, MeshElement.Vertex(0))
		session.setMeshSelection(selection)
		val generated = model.withMeshTopologyEdits(mapOf(drawableId to regeneratedEdit(model)))!!
		val stepsBefore = session.historyView.value.steps.size

		assertFalse(session.commitGeneratedMeshes(RegenerationFixtures.model(), generated, listOf(drawableId)), "another base is refused")
		assertFalse(session.commitGeneratedMeshes(model, model, listOf(drawableId)), "an unchanged model records nothing")
		assertEquals(stepsBefore, session.historyView.value.steps.size)
		assertTrue(session.commitGeneratedMeshes(model, generated, listOf(drawableId, partnerId)))

		assertSame(generated, session.model.value)
		assertTrue(session.meshSelection.value.elementsOf(partnerId).isEmpty(), "a target whose mesh did not change is cleared too")
		assertTrue(session.meshSelection.value.elementsOf(drawableId).isEmpty())
	}

	@Test
	fun theGenerationKindNamesBothStepsAndFromArtIsTheDefault() {
		val model = RegenerationFixtures.model()
		val generated = model.withMeshTopologyEdits(mapOf(drawableId to regeneratedEdit(model)))!!
		val session = EditorSession(model)

		assertTrue(session.commitGeneratedMeshes(model, generated, listOf(drawableId), MeshGenerationKind.Quad))
		assertEquals("change.mesh.generateQuad", session.historyView.value.steps.last().labelKey)
		assertTrue(session.commitUnchangedGeneration(generated, listOf(drawableId), MeshGenerationKind.Quad))
		assertEquals("change.mesh.generateQuad", session.historyView.value.steps.last().labelKey)
		assertTrue(session.commitUnchangedGeneration(generated, listOf(drawableId)))
		assertEquals("change.mesh.generateFromArt", session.historyView.value.steps.last().labelKey)
	}

	/**
	 * The rigged drawable's regeneration over a 5x5 point grid.
	 *
	 * @param PuppetModel model The model.
	 * @return MeshTopologyEdit The edit.
	 */
	private fun regeneratedEdit(model: PuppetModel): MeshTopologyEdit {
		val input = RegenerationFixtures.readyInput(model)

		return RegenerationFixtures.built(model, RegenerationFixtures.generated(RegenerationFixtures.pointGrid(4.0, 12.0, 5), input.pinPoints)).edit
	}
}