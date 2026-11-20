package org.umamo.edit

import org.umamo.edit.mesh.commitLatticeReshape
import org.umamo.edit.transform.BrushStroke
import org.umamo.edit.transform.brushPointOffsets
import org.umamo.runtime.keyform.withLatticeShifted
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the deform brush's commit: a whole stroke lands as ONE undo step, however many stamps it laid
 * down, and one undo puts the lattice back where it was.
 */
class BrushStrokeCommitTest {
	private val parameter = ParameterId("P")
	private val cage = DeformerId("cage")

	/**
	 * A 2 x 2 lattice over a 40-unit square, as nine points row by row.
	 *
	 * @return FloatArray The points.
	 */
	private fun square(): FloatArray =
		FloatArray(18) { componentIndex ->
			val pointIndex = componentIndex / 2

			if (componentIndex % 2 == 0) (pointIndex % 3) * 20f else (pointIndex / 3) * 20f
		}

	/**
	 * The rig: one keyed warp over the square.
	 *
	 * @return PuppetModel The model.
	 */
	private fun model(): PuppetModel =
		PuppetModel(
			parameters = listOf(Parameter(parameter, "P", -1f, 1f, 0f)),
			parts = emptyList(),
			deformers =
				listOf(
					SessionTestModels.keyedWarp(cage, 2, 2, parameter, floatArrayOf(-1f, 1f)) { square() },
				),
			drawables = emptyList(),
			rootChildren = emptyList(),
			rootPartId = null,
		)

	/**
	 * The model with the warp's lattice shifted by the offsets [stroke] gives it.
	 *
	 * @param PuppetModel model The model.
	 * @param BrushStroke stroke The stroke.
	 * @return PuppetModel The shifted model.
	 */
	private fun smeared(model: PuppetModel, stroke: BrushStroke): PuppetModel {
		// The warp sits at the model's root, so its parent space is the space the brush measures in.
		val offsets = brushPointOffsets(stroke.stamps, square(), 1f, ProportionalFalloff.Smooth)

		return model.copy(
			deformers =
				model.deformers.map { deformer ->
					if (deformer.id == cage) (deformer as Deformer.Warp).withLatticeShifted(offsets) else deformer
				},
		)
	}

	/**
	 * A stroke long enough to lay many stamps down, from one corner of the lattice across it.
	 *
	 * @return BrushStroke The stroke.
	 */
	private fun stroke(): BrushStroke = BrushStroke.starting(0f, 0f, 25f).stampedTo(40f, 0f, 25f)

	/** A whole stroke lands as one step, and one undo puts the lattice back. */
	@Test
	fun aStrokeIsOneStepWhateverItsLength() {
		val session = EditorSession(model())
		val before = session.model.value
		val stroke = stroke()
		val smeared = smeared(before, stroke)
		val stepsBefore = session.historyView.value.steps.size

		assertTrue(stroke.stamps.size > 2, "the stroke laid several stamps down")

		session.commitLatticeReshape(DeformerChange.BrushLattice(listOf(cage)), smeared, unconvertedCount = 0)

		assertEquals(stepsBefore + 1, session.historyView.value.steps.size, "one stroke, one step")
		assertEquals("change.lattice.brush", session.historyView.value.steps.last().labelKey)

		session.undo()
		assertSame(before, session.model.value, "one undo puts the lattice back")
	}

	/** A stroke that changed nothing records nothing, and so has no step to amend. */
	@Test
	fun aStrokeThatChangedNothingRecordsNothing() {
		val session = EditorSession(model())
		val stepsBefore = session.historyView.value.steps.size

		session.commitLatticeReshape(DeformerChange.BrushLattice(listOf(cage)), session.model.value, unconvertedCount = 0)

		assertEquals(stepsBefore, session.historyView.value.steps.size, "no step for a stroke that moved nothing")
	}
}