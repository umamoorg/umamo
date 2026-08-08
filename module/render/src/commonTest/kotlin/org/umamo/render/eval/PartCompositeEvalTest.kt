package org.umamo.render.eval

import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.eval.scalarOrNull
import org.umamo.runtime.keyform.fanOutMesh
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartComposite
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
 * Pins the isolated part channel eval on synthetic tracks: the per-channel multilinear blend, the
 * out-of-range fallback to the owner's statics, and [preparePose]'s aggregation into
 * [PoseDeformInputs.partCompositeStates].
 */
class PartCompositeEvalTest {
	private val paramA = ParameterId("A")

	private fun values(vararg pairs: Pair<ParameterId, Float>): (ParameterId) -> Float {
		val map = pairs.toMap()
		return { map[it] ?: 0f }
	}

	/** A two-key scalar track on paramA. */
	private fun scalarTrack(low: Float, high: Float): KeyformGrid<ChannelValue> =
		KeyformGrid(
			listOf(KeyformAxis(paramA, floatArrayOf(0f, 1f))),
			listOf(
				KeyformCell<ChannelValue>(intArrayOf(0), ChannelValue.Scalar(low)),
				KeyformCell<ChannelValue>(intArrayOf(1), ChannelValue.Scalar(high)),
			),
		)

	/** A two-key color track on paramA. */
	private fun colorTrack(low: ColorRgb, high: ColorRgb): KeyformGrid<ChannelValue> =
		KeyformGrid(
			listOf(KeyformAxis(paramA, floatArrayOf(0f, 1f))),
			listOf(
				KeyformCell<ChannelValue>(intArrayOf(0), ChannelValue.Color(low)),
				KeyformCell<ChannelValue>(intArrayOf(1), ChannelValue.Color(high)),
			),
		)

	/** The composite channels as independent tracks - what one bundled part grid fans out into. */
	private fun compositeChannels(): ChannelGrids =
		ChannelGrids(
			mapOf(
				FormChannel.DRAW_ORDER to scalarTrack(500f, 500f),
				FormChannel.OPACITY to scalarTrack(1f, 0.5f),
				FormChannel.MULTIPLY_COLOR to colorTrack(ColorRgb(1f, 1f, 1f), ColorRgb(0f, 0.5f, 1f)),
				FormChannel.SCREEN_COLOR to colorTrack(ColorRgb(0f, 0f, 0f), ColorRgb(1f, 0.5f, 0f)),
			),
		)

	private val staticComposite =
		PartComposite(
			opacity = 0.3f,
			multiplyColor = ColorRgb(0.1f, 0.2f, 0.3f),
			screenColor = ColorRgb(0.4f, 0.5f, 0.6f),
		)

	@Test
	fun blendsEveryChannelWithTheGridWeights() {
		val channels = compositeChannels()
		val pose = values(paramA to 0.5f)
		assertEquals(0.75f, channels.scalarAt(FormChannel.OPACITY, staticComposite.opacity, pose), "opacity midpoint")
		assertEquals(
			ColorRgb(0.5f, 0.75f, 1f),
			channels.colorAt(FormChannel.MULTIPLY_COLOR, staticComposite.multiplyColor, pose),
			"multiply midpoint",
		)
		assertEquals(
			ColorRgb(0.5f, 0.25f, 0f),
			channels.colorAt(FormChannel.SCREEN_COLOR, staticComposite.screenColor, pose),
			"screen midpoint",
		)
	}

	@Test
	fun snapsToAKeyExactly() {
		val channels = compositeChannels()
		val pose = values(paramA to 1f)
		assertEquals(0.5f, channels.scalarAt(FormChannel.OPACITY, staticComposite.opacity, pose))
		assertEquals(ColorRgb(0f, 0.5f, 1f), channels.colorAt(FormChannel.MULTIPLY_COLOR, staticComposite.multiplyColor, pose))
		assertEquals(ColorRgb(1f, 0.5f, 0f), channels.colorAt(FormChannel.SCREEN_COLOR, staticComposite.screenColor, pose))
	}

	/**
	 * Out of range a channel falls back to its owner's static value and NEVER hides.  Hiding is the
	 * geometry grid's decision alone - a part has no geometry, and making a keyed opacity able to hide art
	 * would turn keying opacity on a narrow parameter into a disappearing act at the slider's ends.
	 */
	@Test
	fun outOfRangeChannelsFallBackToTheirStatics() {
		val channels = compositeChannels()
		val pose = values(paramA to -1f)
		assertEquals(staticComposite.opacity, channels.scalarAt(FormChannel.OPACITY, staticComposite.opacity, pose))
		assertEquals(staticComposite.multiplyColor, channels.colorAt(FormChannel.MULTIPLY_COLOR, staticComposite.multiplyColor, pose))
		// The nullable read still reports absence, which is what keeps the part draw-order map sparse.
		assertNull(channels.scalarOrNull(FormChannel.DRAW_ORDER, pose))
	}

	private fun modelWithIsolatedPart(channelGrids: ChannelGrids): PuppetModel {
		val part =
			Part(
				id = PartId("fx"),
				name = "fx",
				children = emptyList(),
				groupMode = PartGroupMode.Isolated,
				composite = staticComposite,
				channelGrids = channelGrids,
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
		val inputs = preparePose(modelWithIsolatedPart(compositeChannels()), mapOf(paramA to 0.5f))
		val state = assertNotNull(inputs.partCompositeStates[PartId("fx")], "isolated part carries a state")
		assertEquals(0.75f, state.opacity)
		assertEquals(ColorRgb(0.5f, 0.75f, 1f), state.multiplyColor)
	}

	@Test
	fun preparePoseFallsBackToStaticChannelsWithoutAGrid() {
		val inputs = preparePose(modelWithIsolatedPart(ChannelGrids.Empty), emptyMap())
		val state = assertNotNull(inputs.partCompositeStates[PartId("fx")])
		assertEquals(0.3f, state.opacity, "PartComposite static opacity")
		assertEquals(ColorRgb(0.1f, 0.2f, 0.3f), state.multiplyColor)
		assertEquals(ColorRgb(0.4f, 0.5f, 0.6f), state.screenColor)
	}

	@Test
	fun preparePoseFallsBackToStaticChannelsOutOfRange() {
		// The controlling axis sits below the grid's key range → the grid sample hides, and the
		// composite falls back to the static channels (mirroring the part draw-order fallback).
		val inputs = preparePose(modelWithIsolatedPart(compositeChannels()), mapOf(paramA to -1f))
		val state = assertNotNull(inputs.partCompositeStates[PartId("fx")])
		assertEquals(0.3f, state.opacity)
	}

	@Test
	fun preparePoseLeavesNonIsolatedPartsOutOfTheMap() {
		val plain =
			modelWithIsolatedPart(ChannelGrids.Empty)
				.let { source -> source.copy(parts = source.parts.map { it.copy(groupMode = PartGroupMode.Grouped) }) }
				.withDerivedRenderRoot()
		assertTrue(preparePose(plain, emptyMap()).partCompositeStates.isEmpty())
	}

	private fun drawable(id: String, ownOpacity: Float): Drawable {
		val positions = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)
		val fanned =
			KeyformGrid(
				listOf(KeyformAxis(paramA, floatArrayOf(0f))),
				listOf(KeyformCell(intArrayOf(0), MeshForm(FloatArray(positions.size), opacity = ownOpacity))),
			).fanOutMesh()
		return Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh(positions, FloatArray(positions.size), intArrayOf(0, 1, 2)),
			// Bundled then fanned, so the fixture matches importer output and the opacity lands on its
			// own track rather than being hand-placed.
			geometryGrid = fanned.geometry,
			channelGrids = fanned.channels,
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
	fun nonIsolatedPartOpacityDoesNotCascadeToItsDrawables() {
		// Cubism only exposes/applies a part's composite opacity while it is Isolated (offscreen drawing);
		// a Grouped or PassThrough part's latent composite.opacity is stored but never rendered.
		val part = partWith("grp", PartGroupMode.Grouped, opacity = 0.5f, children = listOf(OrgChild.Drawable(DrawableId("d"))))
		val model = cascadeModel(listOf(part), listOf(drawable("d", ownOpacity = 0.8f)), listOf(OrgChild.Part(PartId("grp"))))
		assertEquals(0.8f, drawableOpacity(model, "d"), 1e-6f, "own opacity only; the non-isolated part's composite opacity is inert")
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
	fun nestedNonIsolatedPartOpacitiesDoNotCascade() {
		val inner = partWith("inner", PartGroupMode.Grouped, opacity = 0.5f, children = listOf(OrgChild.Drawable(DrawableId("d"))))
		val outer = partWith("outer", PartGroupMode.PassThrough, opacity = 0.5f, children = listOf(OrgChild.Part(PartId("inner"))))
		val model = cascadeModel(listOf(outer, inner), listOf(drawable("d", ownOpacity = 1f)), listOf(OrgChild.Part(PartId("outer"))))
		assertEquals(1f, drawableOpacity(model, "d"), 1e-6f, "neither non-isolated part's opacity applies")
	}

	@Test
	fun drawableKeyformColorResolvesInPreparePose() {
		// The 5.3 per-art-mesh multiply/screen colors ride their own channel tracks; preparePose must blend
		// them onto DrawableDeformInputs so the renderer can tint (a GL-independent proof of the resolve).
		val positions = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)
		val fanned =
			KeyformGrid(
				listOf(KeyformAxis(paramA, floatArrayOf(0f))),
				listOf(
					KeyformCell(
						intArrayOf(0),
						MeshForm(FloatArray(positions.size), multiplyColor = ColorRgb(1f, 0f, 0f), screenColor = ColorRgb(0f, 0f, 0.5f)),
					),
				),
			).fanOutMesh()
		val drawable =
			Drawable(
				id = DrawableId("d"),
				name = "d",
				parentDeformerId = null,
				blendMode = BlendMode.Normal,
				maskedBy = emptyList(),
				mesh = DrawableMesh(positions, FloatArray(positions.size), intArrayOf(0, 1, 2)),
				geometryGrid = fanned.geometry,
				channelGrids = fanned.channels,
			)
		val model = cascadeModel(emptyList(), listOf(drawable), listOf(OrgChild.Drawable(DrawableId("d"))))
		val resolved = preparePose(model, emptyMap()).drawables.first { it.drawableId == DrawableId("d") }
		assertEquals(ColorRgb(1f, 0f, 0f), resolved.multiplyColor)
		assertEquals(ColorRgb(0f, 0f, 0.5f), resolved.screenColor)
	}

	@Test
	fun nonIsolatedAncestorOpacityDoesNotReachAnIsolatedChild() {
		// Neither the non-isolated outer part's opacity nor the isolated inner part's opacity (which rides
		// its own composite pass) touches the drawable directly.
		val inner = partWith("fx", PartGroupMode.Isolated, opacity = 0.5f, children = listOf(OrgChild.Drawable(DrawableId("d"))))
		val outer = partWith("outer", PartGroupMode.Grouped, opacity = 0.5f, children = listOf(OrgChild.Part(PartId("fx"))))
		val model = cascadeModel(listOf(outer, inner), listOf(drawable("d", ownOpacity = 1f)), listOf(OrgChild.Part(PartId("outer"))))
		assertEquals(1f, drawableOpacity(model, "d"), 1e-6f, "outer's composite opacity is inert; inner's rides its composite")
	}
}