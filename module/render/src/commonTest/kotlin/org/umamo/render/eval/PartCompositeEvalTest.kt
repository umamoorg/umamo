package org.umamo.render.eval

import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshForm
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
				groupMode = PartGroupMode.Isolated,
				composite =
					PartComposite(
						opacity = 0.3f,
						multiplyColor = ColorRgb(0.1f, 0.2f, 0.3f),
						screenColor = ColorRgb(0.4f, 0.5f, 0.6f),
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

	private fun drawable(id: String, ownOpacity: Float): Drawable {
		val positions = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)
		return Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh(positions, FloatArray(positions.size), intArrayOf(0, 1, 2)),
			keyforms =
				KeyformGrid(
					listOf(KeyformAxis(paramA, floatArrayOf(0f))),
					listOf(KeyformCell(intArrayOf(0), MeshForm(FloatArray(positions.size), opacity = ownOpacity))),
				),
		)
	}

	/** A part with a static (grid-less) opacity, so the cascade reads it off PartComposite. */
	private fun partWith(id: String, mode: PartGroupMode, opacity: Float, children: List<OrgChild>): Part =
		Part(id = PartId(id), name = id, children = children, groupMode = mode, composite = PartComposite(opacity = opacity))

	private fun cascadeModel(parts: List<Part>, drawables: List<Drawable>, rootChildren: List<OrgChild>): PuppetModel =
		PuppetModel(
			parameters = listOf(Parameter(paramA, "A", -1f, 1f, 0f)),
			parts = parts,
			deformers = emptyList(),
			drawables = drawables,
			rootChildren = rootChildren,
			rootPartId = null,
			canvasWidth = 0f,
			canvasHeight = 0f,
			worldOriginX = 0f,
			worldOriginY = 0f,
		).withDerivedRenderRoot()

	/** The pose-resolved opacity of drawable [id] in [model] at the default pose. */
	private fun drawableOpacity(model: PuppetModel, id: String): Float =
		preparePose(model, emptyMap()).drawables.first { it.drawableId == DrawableId(id) }.opacity

	@Test
	fun nonIsolatedPartOpacityCascadesToItsDrawables() {
		val part = partWith("grp", PartGroupMode.Grouped, opacity = 0.5f, children = listOf(OrgChild.Drawable(DrawableId("d"))))
		val model = cascadeModel(listOf(part), listOf(drawable("d", ownOpacity = 0.8f)), listOf(OrgChild.Part(PartId("grp"))))
		assertEquals(0.4f, drawableOpacity(model, "d"), 1e-6f, "own 0.8 x part 0.5")
	}

	@Test
	fun isolatedPartOpacityDoesNotCascadeToDrawables() {
		// An isolated part applies its opacity at the composite pass, so its drawable keeps its own opacity
		// (the part's 0.5 shows up in partCompositeStates instead - covered above).
		val part = partWith("fx", PartGroupMode.Isolated, opacity = 0.5f, children = listOf(OrgChild.Drawable(DrawableId("d"))))
		val model = cascadeModel(listOf(part), listOf(drawable("d", ownOpacity = 0.8f)), listOf(OrgChild.Part(PartId("fx"))))
		assertEquals(0.8f, drawableOpacity(model, "d"), 1e-6f, "isolated part opacity rides its composite, not the drawable")
	}

	@Test
	fun nestedNonIsolatedPartOpacitiesMultiply() {
		val inner = partWith("inner", PartGroupMode.Grouped, opacity = 0.5f, children = listOf(OrgChild.Drawable(DrawableId("d"))))
		val outer = partWith("outer", PartGroupMode.PassThrough, opacity = 0.5f, children = listOf(OrgChild.Part(PartId("inner"))))
		val model = cascadeModel(listOf(outer, inner), listOf(drawable("d", ownOpacity = 1f)), listOf(OrgChild.Part(PartId("outer"))))
		assertEquals(0.25f, drawableOpacity(model, "d"), 1e-6f, "outer 0.5 x inner 0.5")
	}

	@Test
	fun drawableKeyformColorResolvesInPreparePose() {
		// The 5.3 per-art-mesh multiply/screen color rides the drawable's keyform grid; preparePose must
		// blend it onto DrawableDeformInputs so the renderer can tint (GL-independent proof of the resolve).
		val positions = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)
		val drawable =
			Drawable(
				id = DrawableId("d"),
				name = "d",
				parentDeformerId = null,
				blendMode = BlendMode.Normal,
				maskedBy = emptyList(),
				mesh = DrawableMesh(positions, FloatArray(positions.size), intArrayOf(0, 1, 2)),
				keyforms =
					KeyformGrid(
						listOf(KeyformAxis(paramA, floatArrayOf(0f))),
						listOf(
							KeyformCell(
								intArrayOf(0),
								MeshForm(FloatArray(positions.size), multiplyColor = ColorRgb(1f, 0f, 0f), screenColor = ColorRgb(0f, 0f, 0.5f)),
							),
						),
					),
			)
		val model = cascadeModel(emptyList(), listOf(drawable), listOf(OrgChild.Drawable(DrawableId("d"))))
		val resolved = preparePose(model, emptyMap()).drawables.first { it.drawableId == DrawableId("d") }
		assertEquals(ColorRgb(1f, 0f, 0f), resolved.multiplyColor)
		assertEquals(ColorRgb(0f, 0f, 0.5f), resolved.screenColor)
	}

	@Test
	fun nonIsolatedAncestorCascadesThroughAnIsolatedChild() {
		// A non-isolated outer part cascades onto every descendant drawable, including one under an isolated
		// child; the isolated child's own opacity still rides its composite, not the drawable.
		val inner = partWith("fx", PartGroupMode.Isolated, opacity = 0.5f, children = listOf(OrgChild.Drawable(DrawableId("d"))))
		val outer = partWith("outer", PartGroupMode.Grouped, opacity = 0.5f, children = listOf(OrgChild.Part(PartId("fx"))))
		val model = cascadeModel(listOf(outer, inner), listOf(drawable("d", ownOpacity = 1f)), listOf(OrgChild.Part(PartId("outer"))))
		assertEquals(0.5f, drawableOpacity(model, "d"), 1e-6f, "outer 0.5 cascades; inner isolated 0.5 does not")
	}
}
