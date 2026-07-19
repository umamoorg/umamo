package org.umamo.render.eval

import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartComposite
import org.umamo.runtime.model.PartForm
import org.umamo.runtime.model.PartGroupMode
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.withDerivedRenderRoot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the isolated part channel eval on synthetic grids: [samplePartRenderState]'s multilinear
 * blend per channel, the out-of-range null, and [preparePose]'s aggregation into
 * [PoseDeformInputs.partCompositeStates] with the static-fallback rule.
 */
class PartCompositeEvalTest {
	private val paramA = ParameterId("A")

	private fun values(vararg pairs: Pair<ParameterId, Float>): (ParameterId) -> Float {
		val map = pairs.toMap()
		return { map[it] ?: 0f }
	}

	private fun channelGrid(): KeyformGrid<PartForm> =
		KeyformGrid(
			listOf(KeyformAxis(paramA, floatArrayOf(0f, 1f))),
			listOf(
				KeyformCell(intArrayOf(0), PartForm(500f, opacity = 1f, multiplyColor = ColorRgb(1f, 1f, 1f), screenColor = ColorRgb(0f, 0f, 0f))),
				KeyformCell(intArrayOf(1), PartForm(500f, opacity = 0.5f, multiplyColor = ColorRgb(0f, 0.5f, 1f), screenColor = ColorRgb(1f, 0.5f, 0f))),
			),
		)

	@Test
	fun blendsEveryChannelWithTheGridWeights() {
		val state = assertNotNull(samplePartRenderState(channelGrid(), values(paramA to 0.5f)))
		assertEquals(0.75f, state.opacity, "opacity midpoint")
		assertEquals(ColorRgb(0.5f, 0.75f, 1f), state.multiplyColor, "multiply midpoint")
		assertEquals(ColorRgb(0.5f, 0.25f, 0f), state.screenColor, "screen midpoint")
	}

	@Test
	fun snapsToAKeyExactly() {
		val state = assertNotNull(samplePartRenderState(channelGrid(), values(paramA to 1f)))
		assertEquals(0.5f, state.opacity)
		assertEquals(ColorRgb(0f, 0.5f, 1f), state.multiplyColor)
		assertEquals(ColorRgb(1f, 0.5f, 0f), state.screenColor)
	}

	@Test
	fun hidesWhenTheAxisIsOutOfRange() {
		assertNull(samplePartRenderState(channelGrid(), values(paramA to -1f)))
	}

	private fun modelWithIsolatedPart(formGrid: KeyformGrid<PartForm>?): PuppetModel {
		val part =
			Part(
				id = PartId("fx"),
				name = "fx",
				children = emptyList(),
				groupMode =
					PartGroupMode.Isolated(
						PartComposite(
							opacity = 0.3f,
							multiplyColor = ColorRgb(0.1f, 0.2f, 0.3f),
							screenColor = ColorRgb(0.4f, 0.5f, 0.6f),
						),
					),
				formGrid = formGrid,
			)
		return PuppetModel(
			parameters = listOf(Parameter(paramA, "A", -1f, 1f, 0f)),
			parts = listOf(part),
			deformers = emptyList(),
			drawables = emptyList(),
			rootChildren = listOf(OrgChild.Part(part.id)),
			rootPartId = null,
			canvasWidth = 0f,
			canvasHeight = 0f,
			worldOriginX = 0f,
			worldOriginY = 0f,
		).withDerivedRenderRoot()
	}

	@Test
	fun preparePoseBlendsAGriddedIsolatedPart() {
		val inputs = preparePose(modelWithIsolatedPart(channelGrid()), mapOf(paramA to 0.5f))
		val state = assertNotNull(inputs.partCompositeStates[PartId("fx")], "isolated part carries a state")
		assertEquals(0.75f, state.opacity)
		assertEquals(ColorRgb(0.5f, 0.75f, 1f), state.multiplyColor)
	}

	@Test
	fun preparePoseFallsBackToStaticChannelsWithoutAGrid() {
		val inputs = preparePose(modelWithIsolatedPart(formGrid = null), emptyMap())
		val state = assertNotNull(inputs.partCompositeStates[PartId("fx")])
		assertEquals(0.3f, state.opacity, "PartComposite static opacity")
		assertEquals(ColorRgb(0.1f, 0.2f, 0.3f), state.multiplyColor)
		assertEquals(ColorRgb(0.4f, 0.5f, 0.6f), state.screenColor)
	}

	@Test
	fun preparePoseFallsBackToStaticChannelsOutOfRange() {
		// The controlling axis sits below the grid's key range → the grid sample hides, and the
		// composite falls back to the static channels (mirroring the part draw-order fallback).
		val inputs = preparePose(modelWithIsolatedPart(channelGrid()), mapOf(paramA to -1f))
		val state = assertNotNull(inputs.partCompositeStates[PartId("fx")])
		assertEquals(0.3f, state.opacity)
	}

	@Test
	fun preparePoseLeavesNonIsolatedPartsOutOfTheMap() {
		val plain =
			modelWithIsolatedPart(null)
				.let { source -> source.copy(parts = source.parts.map { it.copy(groupMode = PartGroupMode.Grouped) }) }
				.withDerivedRenderRoot()
		assertTrue(preparePose(plain, emptyMap()).partCompositeStates.isEmpty())
	}
}
