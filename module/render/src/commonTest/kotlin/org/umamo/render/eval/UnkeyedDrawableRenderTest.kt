package org.umamo.render.eval

import org.umamo.runtime.model.BlendMode
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pins that an UNKEYED drawable renders at its rest mesh instead of vanishing, and that this stays
 * distinguishable from the genuinely hidden case.
 *
 * Keyform authoring makes "unkeyed" a normal state - a drawable that has never been bound to a parameter,
 * or one whose last axis was just removed - so a drawable with no grid has to be visible or the rigger
 * cannot see the thing they are about to key.  Before this, preparePose skipped any drawable with a null
 * grid outright and the renderer never even uploaded it.
 *
 * The two null-ish states are opposites and must not collapse into one: NO GRID means render at rest,
 * while a keyed grid whose pose falls outside its key range means hide.
 */
class UnkeyedDrawableRenderTest {
	private val paramA = ParameterId("A")
	private val drawableId = DrawableId("M")

	private fun mesh(): DrawableMesh = DrawableMesh(floatArrayOf(10f, 5f, 20f, 7f), FloatArray(0), intArrayOf(0, 1, 0))

	private fun model(drawable: Drawable): PuppetModel =
		PuppetModel(
			parameters = listOf(Parameter(paramA, "A", -1f, 1f, 0f)),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = listOf(drawable),
			rootChildren = listOf(OrgChild.Drawable(drawable.id)),
			rootPartId = null,
		)

	/** With no keyform grid at all the drawable still deforms - to exactly its rest mesh (Y negated). */
	@Test
	fun anUnkeyedDrawableRendersItsRestMesh() {
		val drawable = Drawable(drawableId, "M", null, BlendMode.Normal, emptyList(), mesh(), geometryGrid = null)
		val geometry = CpuDeformationEvaluator().evaluate(model(drawable), emptyMap())
		val positions = assertNotNull(geometry.worldPositions[drawableId], "an unkeyed drawable must still produce geometry")
		assertEquals(listOf(10f, -5f, 20f, -7f), positions.toList())
	}

	/** An unkeyed drawable takes the Cubism default scalars rather than being dropped from the pose. */
	@Test
	fun anUnkeyedDrawableTakesDefaultScalars() {
		val drawable = Drawable(drawableId, "M", null, BlendMode.Normal, emptyList(), mesh(), geometryGrid = null)
		val geometry = CpuDeformationEvaluator().evaluate(model(drawable), emptyMap())
		assertEquals(1f, geometry.opacity[drawableId])
		assertEquals(CUBISM_DEFAULT_DRAW_ORDER, geometry.drawOrder[drawableId])
	}

	/**
	 * A KEYED drawable whose pose is outside its key range is still hidden. This is the case that must not
	 * be confused with the one above - out of range is a deliberate authoring device (the toggle-part
	 * pattern), and turning it into "render at rest" would make hidden art reappear.
	 */
	@Test
	fun anOutOfRangeKeyedDrawableStaysHidden() {
		val grid =
			KeyformGrid(
				listOf(KeyformAxis(paramA, floatArrayOf(0.5f, 1f))),
				listOf(
					KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(4))),
					KeyformCell(intArrayOf(1), MeshDeltaForm(FloatArray(4))),
				),
			)
		val drawable = Drawable(drawableId, "M", null, BlendMode.Normal, emptyList(), mesh(), geometryGrid = grid)
		// The parameter defaults to 0, below the axis's first key of 0.5.
		val geometry = CpuDeformationEvaluator().evaluate(model(drawable), emptyMap())
		assertNull(geometry.worldPositions[drawableId], "a pose outside the key range still hides the drawable")
	}

	/** The gizmo's local-posed read falls back to the rest mesh too, so an unkeyed drawable is editable. */
	@Test
	fun localPosedFallsBackToTheRestMesh() {
		val drawable = Drawable(drawableId, "M", null, BlendMode.Normal, emptyList(), mesh(), geometryGrid = null)
		val local = assertNotNull(drawableLocalPosed(model(drawable), emptyMap(), drawableId))
		assertEquals(listOf(10f, 5f, 20f, 7f), local.toList(), "local space is the rest mesh, un-negated")
	}
}
