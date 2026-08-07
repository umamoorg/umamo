package org.umamo.render

import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RotationPivotForm
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The canvas-space conversion's two halves agree about WHICH POSE a hidden drawable is mapped at.
 *
 * A drawable whose keyform axis does not bracket its parameter's default - the toggle-part authoring
 * pattern - is absent from the raw default pose, so [restMeshesToCanvasSpace] maps it forward through a
 * second pose with that parameter clamped into its axis's key range.  The export's inverse has to use
 * the same clamped pose, or it undoes a transform that was never applied.
 *
 * With the two halves disagreeing, this drawable's chain does not resolve at the raw default at all and
 * the seam returns null - which the export turns into a notice and writes the mesh as authored, in the
 * wrong space.  The rotation parent is what makes the poses distinguishable: it is keyed so its scale
 * differs across the toggle's range, so agreeing on the pose is load-bearing rather than incidental.
 */
class Moc3HiddenDrawableSpaceTest {
	private val toggle = ParameterId("Toggle")
	private val drawableId = DrawableId("M")
	private val deformerId = DeformerId("R")

	/** The parent-space rest positions the drawable is authored with. */
	private val parentLocal = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f)

	/**
	 * A rotation deformer whose scale differs across the toggle's key range.
	 *
	 * Keys at 0.5 and 1.0 over a parameter defaulting to 0: the raw default sits BELOW the span, which
	 * is what hides the child, and the clamped pose lands on 0.5.
	 *
	 * @return Deformer.Rotation The parent.
	 */
	private fun togglingRotation(): Deformer.Rotation =
		Deformer.Rotation(
			deformerId,
			"R",
			null,
			null,
			0f,
			KeyformGrid(
				listOf(KeyformAxis(toggle, floatArrayOf(0.5f, 1f))),
				listOf(
					KeyformCell(intArrayOf(0), RotationPivotForm(5f, 5f, 90f, 2f)),
					KeyformCell(intArrayOf(1), RotationPivotForm(5f, 5f, 90f, 4f)),
				),
			),
		)

	/**
	 * A model whose only drawable is hidden at the raw default pose.
	 *
	 * @return PuppetModel The model.
	 */
	private fun hiddenAtDefaultModel(): PuppetModel {
		val drawable =
			Drawable(
				id = drawableId,
				name = "M",
				parentDeformerId = deformerId,
				blendMode = BlendMode.Normal,
				maskedBy = emptyList(),
				mesh = DrawableMesh(parentLocal, FloatArray(parentLocal.size), intArrayOf(0, 1, 2)),
				geometryGrid =
					KeyformGrid(
						listOf(KeyformAxis(toggle, floatArrayOf(0.5f, 1f))),
						listOf(
							KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(parentLocal.size))),
							KeyformCell(intArrayOf(1), MeshDeltaForm(FloatArray(parentLocal.size))),
						),
					),
			)
		return PuppetModel(
			parameters = listOf(Parameter(toggle, "Toggle", min = 0f, max = 1f, default = 0f)),
			parts = emptyList(),
			deformers = listOf(togglingRotation()),
			drawables = listOf(drawable),
			rootChildren = listOf(OrgChild.Drawable(drawableId)),
			rootPartId = null,
		)
	}

	@Test
	fun aHiddenDrawableSurvivesTheCanvasSpaceRoundTrip() {
		val model = hiddenAtDefaultModel()
		// Forward: the import's pass, which rescues this drawable through the clamped pose.
		val canvasSpace = restMeshesToCanvasSpace(model)
		val canvasMesh = assertNotNull(canvasSpace.drawables.single().mesh)
		assertTrue(
			canvasMesh.positions.toList() != parentLocal.toList(),
			"the forward pass must actually have moved the mesh, or this proves nothing",
		)

		// Back: the export's seam. It has to pick the same pose the forward pass used.
		val recovered = assertNotNull(canvasToParentSpaceFor(canvasSpace)(drawableId, canvasMesh.positions))

		for (index in parentLocal.indices) {
			val drift = abs(recovered[index] - parentLocal[index])
			assertTrue(
				drift < 1e-2f,
				"coordinate $index came back as ${recovered[index]}, authored ${parentLocal[index]} - " +
					"the inverse used a different pose than the forward pass",
			)
		}
	}
}
