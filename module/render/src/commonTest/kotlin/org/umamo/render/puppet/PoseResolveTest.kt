package org.umamo.render.puppet

import org.umamo.render.eval.RenderPlanDrawable
import org.umamo.render.eval.RenderPlanOffscreen
import org.umamo.render.eval.flattenRenderPlan
import org.umamo.render.eval.preparePose
import org.umamo.render.glsl.MAX_GLUES
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.CUBISM_DEFAULT_PART_DRAW_ORDER
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
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PartOffscreen
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RenderDrawable
import org.umamo.runtime.model.RenderGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins [resolvePose]: the posed-vs-drawn distinction, the drawn filter, and the glue both-posed gate.
 *
 * The gate had no coverage anywhere before this - not CPU, not GPU. It is the rule that stops a weld
 * reading a partner's uninitialised region of the shared position store, which on a live GPU shows up as
 * random geometry spikes while a parameter moves, so it is worth pinning precisely.
 */
class PoseResolveTest {
	private val paramA = ParameterId("A")

	private fun drawable(id: String, indices: IntArray = intArrayOf(0, 1, 2)): Drawable {
		val positions = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f)
		return Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh(positions, FloatArray(positions.size), indices),
			keyforms =
				KeyformGrid(
					listOf(KeyformAxis(paramA, floatArrayOf(0f))),
					listOf(KeyformCell(intArrayOf(0), MeshForm(FloatArray(positions.size)))),
				),
		)
	}

	private fun model(drawables: List<Drawable>, glues: List<Glue> = emptyList()): PuppetModel =
		PuppetModel(
			parameters = listOf(Parameter(paramA, "A", -1f, 1f, 0f)),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = drawables,
			rootChildren = drawables.map { OrgChild.Drawable(it.id) },
			rootPartId = null,
			glues = glues,
			canvasWidth = 0f,
			canvasHeight = 0f,
			worldOriginX = 0f,
			worldOriginY = 0f,
		)

	private fun resolve(
		source: PuppetModel,
		renderable: Map<DrawableId, Boolean>,
		shown: Set<DrawableId> = source.drawables.map { it.id }.toSet(),
		renderRoot: RenderGroup = RenderGroup(null, CUBISM_DEFAULT_PART_DRAW_ORDER, emptyList()),
	) = resolvePose(
		inputs = preparePose(source, emptyMap()),
		renderableById = renderable,
		shownIds = shown,
		baseOrder = source.drawables.map { it.id },
		renderRoot = renderRoot,
		glueIntensities = FloatArray(MAX_GLUES) { 1f },
	)

	@Test
	fun resolvePoseDrawsOnlyResidentRenderableShownDrawables() {
		val source = model(listOf(drawable("a"), drawable("b"), drawable("hidden")))
		val resolved =
			resolve(
				source,
				renderable = mapOf(DrawableId("a") to true, DrawableId("b") to true, DrawableId("hidden") to true),
				shown = setOf(DrawableId("a"), DrawableId("b")),
			)
		assertEquals(listOf(DrawableId("a"), DrawableId("b")), resolved.drawOrder, "the hidden drawable is not drawn")
		assertTrue(DrawableId("hidden") in resolved.posed, "but it still POSES - it may be a mask source")
	}

	@Test
	fun resolvePosePosesAnIndexLessAnchorButDoesNotDrawIt() {
		// The posed-vs-drawn distinction. An index-less glue anchor must deform (pass 1 writes its positions
		// for a partner to weld against) while drawing nothing. Conflating the two breaks glue silently.
		val source = model(listOf(drawable("anchor", indices = IntArray(0)), drawable("b")))
		val resolved = resolve(source, renderable = mapOf(DrawableId("anchor") to false, DrawableId("b") to true))
		assertTrue(DrawableId("anchor") in resolved.posed, "the anchor poses: its deformed positions are a weld partner")
		assertEquals(listOf(DrawableId("b")), resolved.drawOrder, "but it draws nothing")
	}

	@Test
	fun resolvePoseSkipsADrawableTheBackendNeverUploaded() {
		val source = model(listOf(drawable("a"), drawable("absent")))
		val resolved = resolve(source, renderable = mapOf(DrawableId("a") to true))
		assertTrue(DrawableId("absent") !in resolved.posed, "a non-resident drawable cannot pose")
		assertEquals(listOf(DrawableId("a")), resolved.drawOrder)
	}

	@Test
	fun resolvePoseGlueIntensityHoldsWhenBothMeshesPose() {
		val source =
			model(
				listOf(drawable("a"), drawable("b")),
				listOf(Glue(DrawableId("a"), DrawableId("b"), listOf(GluePair(0, 0, 0.5f, 0.5f)), null)),
			)
		val resolved = resolve(source, renderable = mapOf(DrawableId("a") to true, DrawableId("b") to true))
		assertEquals(1f, resolved.glueIntensities[0], "both posed, so the weld runs at full intensity")
	}

	@Test
	fun resolvePoseZeroesGlueIntensityWhenAPartnerIsUnposed() {
		// The gate. "b" is not resident, so pass 1 never writes its region of the shared store; welding
		// toward it would read uninitialised memory. Zeroing makes the shader skip the partner read entirely.
		val source =
			model(
				listOf(drawable("a"), drawable("b")),
				listOf(Glue(DrawableId("a"), DrawableId("b"), listOf(GluePair(0, 0, 0.5f, 0.5f)), null)),
			)
		val resolved = resolve(source, renderable = mapOf(DrawableId("a") to true))
		assertEquals(0f, resolved.glueIntensities[0], "an unposed partner disables the weld")
	}

	@Test
	fun resolvePoseGateAsksWhetherThePartnerPosedNotWhetherItDraws() {
		// An index-less anchor draws nothing yet is a perfectly good partner: it poses, so the weld holds.
		// Gating on "draws" instead of "poses" would silently disable every anchor-based glue.
		val source =
			model(
				listOf(drawable("anchor", indices = IntArray(0)), drawable("b")),
				listOf(Glue(DrawableId("anchor"), DrawableId("b"), listOf(GluePair(0, 0, 0f, 1f)), null)),
			)
		val resolved = resolve(source, renderable = mapOf(DrawableId("anchor") to false, DrawableId("b") to true))
		assertEquals(1f, resolved.glueIntensities[0], "a non-drawing anchor still counts as posed")
	}

	@Test
	fun resolvePoseKeepsOffscreenBoundariesInThePlanAndFlattensToDrawOrder() {
		val source = model(listOf(drawable("a"), drawable("b")))
		val offscreenRoot =
			RenderGroup(
				partId = null,
				drawOrder = CUBISM_DEFAULT_PART_DRAW_ORDER,
				children =
					listOf(
						RenderDrawable(DrawableId("b")),
						RenderGroup(PartId("fx"), 600, listOf(RenderDrawable(DrawableId("a"))), null, PartOffscreen()),
					),
			)
		val resolved =
			resolve(
				source,
				renderable = mapOf(DrawableId("a") to true, DrawableId("b") to true),
				renderRoot = offscreenRoot,
			)
		assertEquals(2, resolved.renderPlan.size)
		assertEquals(DrawableId("b"), assertIs<RenderPlanDrawable>(resolved.renderPlan[0]).id)
		val offscreenNode = assertIs<RenderPlanOffscreen>(resolved.renderPlan[1])
		assertEquals(PartId("fx"), offscreenNode.partId)
		assertEquals(flattenRenderPlan(resolved.renderPlan), resolved.drawOrder, "the flat order IS the plan flattened")
		assertEquals(listOf(DrawableId("b"), DrawableId("a")), resolved.drawOrder)
	}

	@Test
	fun resolvePoseDropsAnOffscreenNodeWhoseSubtreeFilteredAway() {
		// The offscreen part's only drawable is hidden → its whole composite disappears (an empty
		// buffer composite would be a wasted pass); the flat order agrees.
		val source = model(listOf(drawable("a"), drawable("b")))
		val offscreenRoot =
			RenderGroup(
				partId = null,
				drawOrder = CUBISM_DEFAULT_PART_DRAW_ORDER,
				children =
					listOf(
						RenderDrawable(DrawableId("b")),
						RenderGroup(PartId("fx"), 600, listOf(RenderDrawable(DrawableId("a"))), null, PartOffscreen()),
					),
			)
		val resolved =
			resolve(
				source,
				renderable = mapOf(DrawableId("a") to true, DrawableId("b") to true),
				shown = setOf(DrawableId("b")),
				renderRoot = offscreenRoot,
			)
		assertEquals(1, resolved.renderPlan.size, "the empty offscreen node is dropped")
		assertEquals(DrawableId("b"), assertIs<RenderPlanDrawable>(resolved.renderPlan.single()).id)
		assertEquals(listOf(DrawableId("b")), resolved.drawOrder)
	}

	@Test
	fun resolvePoseGlueIntensitiesBeyondTheShaderArrayAreDropped() {
		// MAX_GLUES is a shader contract (uniform float glueIntensity[MAX_GLUES]), not a tuning knob: glue
		// #65+ renders UNWELDED rather than overrunning the array. Pinned so the ceiling is not "fixed" by
		// growing the Kotlin side alone, which would silently disagree with the GLSL.
		val drawables = listOf(drawable("a"), drawable("b"))
		val glues = List(70) { Glue(DrawableId("a"), DrawableId("b"), listOf(GluePair(0, 0, 0.5f, 0.5f)), null) }
		val resolved =
			resolve(model(drawables, glues), renderable = mapOf(DrawableId("a") to true, DrawableId("b") to true))
		assertEquals(64, resolved.glueIntensities.size, "the array is exactly the shader's")
		assertEquals(1f, resolved.glueIntensities[63], "the last addressable glue resolves")
	}
}
