package org.umamo.render.puppet

import org.umamo.render.FrameBackdrop
import org.umamo.render.FrameOverlays
import org.umamo.render.PuppetTextures
import org.umamo.render.ViewportCamera
import org.umamo.render.device.FloatTextureUpdated
import org.umamo.render.device.LoadAction
import org.umamo.render.device.OverlayBuffersCreated
import org.umamo.render.device.OverlayBuffersDestroyed
import org.umamo.render.device.OverlayFlagsUpdated
import org.umamo.render.device.PipelineBlend
import org.umamo.render.device.PipelinePurpose
import org.umamo.render.device.RecordedAxisDraw
import org.umamo.render.device.RecordedBarrier
import org.umamo.render.device.RecordedCapturePass
import org.umamo.render.device.RecordedCompositeDraw
import org.umamo.render.device.RecordedGridDraw
import org.umamo.render.device.RecordedMeshDraw
import org.umamo.render.device.RecordedOverlayDraw
import org.umamo.render.device.RecordedPass
import org.umamo.render.device.RecordedResolve
import org.umamo.render.device.RecordedTarget
import org.umamo.render.device.RecordingRenderDevice
import org.umamo.render.device.RenderTargetSpec
import org.umamo.render.device.ScissorRect
import org.umamo.render.device.StoreCreated
import org.umamo.render.device.StoreDestroyed
import org.umamo.render.device.TargetCreated
import org.umamo.render.device.TextureFormat
import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.Glue
import org.umamo.runtime.model.GluePair
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartComposite
import org.umamo.runtime.model.PartGroupMode
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.WarpLatticeForm
import org.umamo.runtime.model.withDerivedRenderRoot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the pass structure [PuppetRenderer] records for each kind of draw: which passes it opens, what
 * each does with its target's contents, where it scissors, and what it copies between targets.
 *
 * The GL tests read pixels, and pixels cannot see any of this.  A composite drawn inline and one drawn
 * through a layer land on the same pixels by design, so a render that stopped flattening, stopped
 * skipping an empty layer, or re-captured the glue store every frame would still pass every one of them.
 * These run on a [RecordingRenderDevice], with no GPU, so they run everywhere.
 *
 * Targets are found by relation (the target whose contents a draw samples) and never by the order they
 * were created in, and nothing here asserts the order resources are created or freed in.
 */
class RenderPassStructureTest {
	private val viewportSize = 64
	private val paramA = ParameterId("A")
	private val quadIndices = intArrayOf(0, 1, 2, 1, 3, 2)
	private val quadEdges = intArrayOf(0, 1, 1, 2, 0, 2, 1, 3, 2, 3)

	/**
	 * A quad covering the whole viewport at the fixed 1:1 camera.
	 *
	 * @return FloatArray The positions, a new array each call so a mesh can be told apart by reference.
	 */
	private fun fullQuad(): FloatArray = floatArrayOf(-32f, -32f, 32f, -32f, -32f, 32f, 32f, 32f)

	/**
	 * A quad covering one band of the viewport: mesh x in [-16, 16] and y in [8, 30].  The renderer
	 * negates y, so it lands on pixel columns 16 to 48 and top-first rows 40 to 62.
	 *
	 * @return FloatArray The positions, a new array each call.
	 */
	private fun bandQuad(): FloatArray = floatArrayOf(-16f, 8f, 16f, 8f, -16f, 30f, 16f, 30f)

	/**
	 * A single-cell keyform grid with zero deltas, so a mesh sits at its rest positions.
	 *
	 * @param Int coordinateCount The mesh's position array length.
	 * @return KeyformGrid<MeshDeltaForm> The grid.
	 */
	private fun restGrid(coordinateCount: Int): KeyformGrid<MeshDeltaForm> =
		KeyformGrid(
			listOf(KeyformAxis(paramA, floatArrayOf(0f))),
			listOf(KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(coordinateCount)))),
		)

	/**
	 * A flat-color drawable over [positions].
	 *
	 * @param String           id               The drawable id.
	 * @param FloatArray       positions        The rest positions.
	 * @param IntArray         indices          The triangle indices; empty for a weld anchor.
	 * @param BlendMode        blendMode        The color blend.
	 * @param List<DrawableId> maskedBy         The clip mask sources.
	 * @param DeformerId?      parentDeformerId The parent deformer, or null for a direct mesh.
	 * @return Drawable The drawable.
	 */
	private fun drawable(
		id: String,
		positions: FloatArray,
		indices: IntArray = quadIndices,
		blendMode: BlendMode = BlendMode.Normal,
		maskedBy: List<DrawableId> = emptyList(),
		parentDeformerId: DeformerId? = null,
	): Drawable =
		Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = parentDeformerId,
			blendMode = blendMode,
			maskedBy = maskedBy,
			mesh = DrawableMesh.withLocalEqualToCanvas(positions, FloatArray(positions.size), indices),
			geometryGrid = restGrid(positions.size),
		)

	/**
	 * An isolated part wrapping one drawable.
	 *
	 * @param String        partId    The part id.
	 * @param String        childId   The wrapped drawable's id.
	 * @param PartComposite composite How the part's layer composites back.
	 * @return Part The part.
	 */
	private fun isolatedPart(partId: String, childId: String, composite: PartComposite): Part =
		Part(
			id = PartId(partId),
			name = partId,
			children = listOf(OrgChild.Drawable(DrawableId(childId))),
			groupMode = PartGroupMode.Isolated,
			composite = composite,
		)

	/**
	 * A model over the given drawables, drawn in [backToFront] order.
	 *
	 * The organization tree lists its children front first, as the Parts panel shows them, so the draw
	 * order handed in here is reversed into it.
	 *
	 * @param List<Drawable> drawables   The drawables.
	 * @param List<OrgChild> backToFront The root's children in the order they draw.
	 * @param List<Part>     parts       The parts.
	 * @param List<Deformer> deformers   The deformers.
	 * @param List<Glue>     glues       The glue pairs.
	 * @return PuppetModel The model.
	 */
	private fun model(
		drawables: List<Drawable>,
		backToFront: List<OrgChild>,
		parts: List<Part> = emptyList(),
		deformers: List<Deformer> = emptyList(),
		glues: List<Glue> = emptyList(),
	): PuppetModel =
		PuppetModel(
			parameters = listOf(Parameter(paramA, "A", -1f, 1f, 0f)),
			parts = parts,
			deformers = deformers,
			drawables = drawables,
			rootChildren = backToFront.asReversed(),
			rootPartId = null,
			glues = glues,
		).withDerivedRenderRoot()

	/**
	 * A renderer over [source], uploaded and posed at rest behind the fixed 1:1 camera.
	 *
	 * @param PuppetModel           source The model.
	 * @param RecordingRenderDevice device The device to record on.
	 * @return PuppetRenderer The renderer.
	 */
	private fun posedRenderer(source: PuppetModel, device: RecordingRenderDevice): PuppetRenderer {
		val renderer = PuppetRenderer(source, PuppetTextures(emptyList(), emptyMap(), premultipliedAlpha = false), device)
		renderer.initGl()
		renderer.setCamera(ViewportCamera(0f, 0f, 1f))
		renderer.setPose(emptyMap())
		return renderer
	}

	/**
	 * The surface a test renders into.
	 *
	 * @param RecordingRenderDevice device The device to allocate on.
	 * @return RecordedTarget The target.
	 */
	private fun mainTarget(device: RecordingRenderDevice): RecordedTarget =
		device.createRenderTarget(RenderTargetSpec(viewportSize, viewportSize, TextureFormat.Rgba8, sampled = true)) as RecordedTarget

	/**
	 * Renders [source] once and returns the device that recorded it, with the log holding that one frame.
	 *
	 * @param PuppetModel source The model.
	 * @return Pair<RecordingRenderDevice, RecordedTarget> The device and the target rendered into.
	 */
	private fun recordFrame(source: PuppetModel): Pair<RecordingRenderDevice, RecordedTarget> {
		val device = RecordingRenderDevice()
		val renderer = posedRenderer(source, device)
		val target = mainTarget(device)
		device.clearLog()
		renderer.render(target, viewportSize, viewportSize)
		return device to target
	}

	/**
	 * A frame's structure as comparable lines, naming each target by its role and each mesh by its
	 * vertex count, so two recordings of different models can be held against each other.
	 *
	 * @param RecordingRenderDevice device The device that recorded the frame.
	 * @param RecordedTarget        target The target the frame rendered into.
	 * @return List<String> One line per recorded step.
	 */
	private fun describe(device: RecordingRenderDevice, target: RecordedTarget): List<String> =
		device.steps.map { step ->
			when (step) {
				is RecordedPass -> {
					val role = if (step.target === target) "main" else "side"
					val draws =
						step.draws.joinToString(", ") { draw ->
							when (draw) {
								is RecordedMeshDraw -> "mesh(${draw.mesh.restPositions.size / 2} vertices, ${draw.pipeline.blend}, opacity ${draw.opacity})"
								is RecordedCompositeDraw -> "composite"
								is RecordedGridDraw -> "grid"
								is RecordedAxisDraw -> "axis"
								is RecordedOverlayDraw -> "overlay ${draw.purpose}${if (draw.activeDraw) " active" else ""}"
								else -> "other"
							}
						}
					"pass $role ${step.spec.loadAction} scissor=${step.spec.scissor} [$draws]"
				}

				is RecordedResolve -> "copy region=${step.region}"
				is RecordedCapturePass -> "capture ${step.captures.size}"
				is RecordedBarrier -> "barrier"
			}
		}

	/**
	 * Whether a scissor rectangle holds a pixel rectangle entirely.
	 *
	 * @param ScissorRect rect   The scissor rectangle.
	 * @param Int         left   The pixel rectangle's left edge.
	 * @param Int         top    The pixel rectangle's top edge.
	 * @param Int         right  The pixel rectangle's right edge, exclusive.
	 * @param Int         bottom The pixel rectangle's bottom edge, exclusive.
	 * @return Boolean True when the pixel rectangle lies inside the scissor rectangle.
	 */
	private fun holds(rect: ScissorRect, left: Int, top: Int, right: Int, bottom: Int): Boolean =
		rect.x <= left && rect.y <= top && rect.x + rect.width >= right && rect.y + rect.height >= bottom

	/**
	 * The model with one drawable's positions replaced, its uvs and indices shared: the shape of a
	 * preview push.
	 *
	 * @param PuppetModel source    The model to edit.
	 * @param String      id        The drawable to move.
	 * @param FloatArray  positions Its new rest positions.
	 * @return PuppetModel The edited model.
	 */
	private fun withPositions(source: PuppetModel, id: String, positions: FloatArray): PuppetModel =
		source.copy(
			drawables =
				source.drawables.map { drawable ->
					if (drawable.id == DrawableId(id)) {
						val mesh = drawable.mesh ?: error("the fixture carries meshes")
						drawable.copy(mesh = DrawableMesh.withLocalEqualToCanvas(positions, mesh.uvs, mesh.indices))
					} else {
						drawable
					}
				},
		)

	/**
	 * The isolated-composite fixture: a base quad, a band inside an isolated part, and a following quad.
	 *
	 * @return PuppetModel The model.
	 */
	private fun compositeModel(): PuppetModel =
		model(
			drawables = listOf(drawable("base", fullQuad()), drawable("layered", bandQuad()), drawable("following", fullQuad())),
			parts = listOf(isolatedPart("fx", "layered", PartComposite(opacity = 0.5f))),
			backToFront = listOf(OrgChild.Drawable(DrawableId("base")), OrgChild.Part(PartId("fx")), OrgChild.Drawable(DrawableId("following"))),
		)

	/** Plain drawables draw into one pass that paints the backdrop itself, with no copy between targets. */
	@Test
	fun aFlatPlanIsOnePassWithNoCopies() {
		val backPositions = fullQuad()
		val frontPositions = bandQuad()
		val source =
			model(
				drawables = listOf(drawable("back", backPositions), drawable("front", frontPositions)),
				backToFront = listOf(OrgChild.Drawable(DrawableId("back")), OrgChild.Drawable(DrawableId("front"))),
			)
		val (device, target) = recordFrame(source)

		val pass = device.passes().single()
		assertSame(target, pass.target, "the one pass writes the target it was given")
		assertEquals(LoadAction.DontCare, pass.spec.loadAction, "the grid paints every pixel, so nothing is fetched")
		assertNull(pass.spec.scissor, "the top-level pass is not scissored")
		assertTrue(pass.ended, "the pass is ended")
		assertTrue(pass.draws.first() is RecordedGridDraw, "the backdrop is drawn first")
		assertEquals(
			listOf(backPositions, frontPositions),
			pass.draws.filterIsInstance<RecordedMeshDraw>().map { draw -> draw.mesh.restPositions },
			"both drawables draw, back to front",
		)
		assertTrue(device.resolves().isEmpty(), "a flat plan copies nothing between targets")
		assertTrue(device.capturePasses().isEmpty(), "a model with no glue captures nothing")
		assertTrue(device.compositeDraws().isEmpty(), "a flat plan composites nothing")
	}

	/** A masked drawable ends the pass, renders its mask's coverage, and resumes on what was already drawn. */
	@Test
	fun aMaskedDrawableFragmentsThePassAroundACoveragePass() {
		val maskPositions = bandQuad()
		val artPositions = fullQuad()
		val source =
			model(
				drawables =
					listOf(
						drawable("mask", maskPositions, blendMode = BlendMode.MultiplyPremultiplied),
						drawable("art", artPositions, maskedBy = listOf(DrawableId("mask"))),
					),
				backToFront = listOf(OrgChild.Drawable(DrawableId("mask")), OrgChild.Drawable(DrawableId("art"))),
			)
		val (device, target) = recordFrame(source)

		val maskedDraw = device.meshDrawsOf(artPositions).single()
		assertTrue(maskedDraw.useMask, "the masked drawable multiplies by its coverage")
		val coverage = assertNotNull(maskedDraw.maskCoverage, "the masked drawable samples a coverage texture")
		val coverageTarget = assertNotNull(device.targetSampledAs(coverage), "the coverage is a target a pass rendered")
		assertTrue(coverageTarget !== target, "coverage renders into its own target")

		val coveragePass = device.passes().single { pass -> pass.target === coverageTarget }
		assertEquals(LoadAction.Clear, coveragePass.spec.loadAction, "coverage starts from nothing")
		val coverageDraw = coveragePass.draws.single() as RecordedMeshDraw
		assertSame(maskPositions, coverageDraw.mesh.restPositions, "the coverage pass draws the mask source")
		assertEquals(1f, coverageDraw.opacity, "coverage is the mask's shape at full intensity")
		assertEquals(PipelineBlend.Normal, coverageDraw.pipeline.blend, "coverage ignores the mask source's own blend")
		assertTrue(!coverageDraw.useMask, "coverage is itself unmasked")

		val resumedPass = device.passOf(maskedDraw)
		assertSame(target, resumedPass.target, "the masked drawable draws into the frame's target")
		assertEquals(LoadAction.Load, resumedPass.spec.loadAction, "resuming keeps what was drawn before the mask")

		val passes = device.passes()
		val firstPass = passes.first()
		assertSame(target, firstPass.target, "the frame opens on its target")
		assertTrue(firstPass.ended, "the pass is ended before the coverage pass begins")
		assertTrue(
			passes.indexOf(firstPass) < passes.indexOf(coveragePass) && passes.indexOf(coveragePass) < passes.indexOf(resumedPass),
			"the coverage pass sits between the two halves of the frame",
		)
		assertEquals(
			coverageTarget.spec.width to coverageTarget.spec.height,
			maskedDraw.screenTexWidth to maskedDraw.screenTexHeight,
			"the screen-space divisor is the coverage texture's allocated size",
		)
	}

	/**
	 * An isolated part's layer clear, destination copy, and composite draw are all confined to the part's
	 * bounds, and what is drawn after the composite is not.
	 */
	@Test
	fun anIsolatedCompositeScissorsItsLayerWorkAndNotWhatFollows() {
		val basePositions = fullQuad()
		val layeredPositions = bandQuad()
		val followingPositions = fullQuad()
		val source =
			model(
				drawables = listOf(drawable("base", basePositions), drawable("layered", layeredPositions), drawable("following", followingPositions)),
				parts = listOf(isolatedPart("fx", "layered", PartComposite(opacity = 0.5f))),
				backToFront = listOf(OrgChild.Drawable(DrawableId("base")), OrgChild.Part(PartId("fx")), OrgChild.Drawable(DrawableId("following"))),
			)
		val (device, target) = recordFrame(source)

		val composite = device.compositeDraws().single()
		val compositePass = device.passOf(composite)
		assertSame(target, compositePass.target, "the layer composites back into the frame's target")
		assertEquals(LoadAction.Load, compositePass.spec.loadAction, "the composite blends over what is there")
		val bounds = assertNotNull(compositePass.spec.scissor, "the composite draw is scissored to the part's bounds")
		assertTrue(holds(bounds, left = 16, top = 40, right = 48, bottom = 62), "the scissor holds the part's drawable, got $bounds")
		assertTrue(bounds.width < viewportSize && bounds.height < viewportSize, "the scissor is smaller than the viewport, got $bounds")
		assertEquals(0.5f, composite.opacity, "the composite carries the part's opacity")

		val layerTarget = assertNotNull(device.targetSampledAs(composite.layer), "the composited layer is a target a pass rendered")
		val layerPass = device.passes().single { pass -> pass.target === layerTarget }
		assertEquals(LoadAction.Clear, layerPass.spec.loadAction, "the layer starts transparent")
		assertEquals(bounds, layerPass.spec.scissor, "the layer's clear and draws are confined to the same bounds")
		assertSame(layeredPositions, (layerPass.draws.single() as RecordedMeshDraw).mesh.restPositions, "the layer holds the part's drawable alone")
		assertTrue(device.meshDrawsOf(layeredPositions).size == 1, "the part's drawable is drawn into its layer only")

		val copy = device.resolves().single()
		assertSame(target, copy.source, "the destination copy reads the frame's target")
		assertSame(composite.destinationSnapshot, copy.destination.sampledTexture, "the composite samples the copy that was just made")
		assertEquals(bounds, copy.region, "the destination copy is confined to the same bounds")
		assertEquals(viewportSize to viewportSize, copy.sourceUsedWidth to copy.sourceUsedHeight, "the copy covers the rendered extent")

		val steps = device.steps
		assertTrue(
			steps.indexOf(layerPass) < steps.indexOf(copy) && steps.indexOf(copy) < steps.indexOf(compositePass),
			"the layer renders, then the destination is copied, then the composite draws",
		)

		val followingPass = device.passOf(device.meshDrawsOf(followingPositions).single())
		assertSame(target, followingPass.target, "what follows the composite draws into the frame's target")
		assertEquals(LoadAction.Load, followingPass.spec.loadAction, "what follows keeps the composite's result")
		assertNull(followingPass.spec.scissor, "what follows is not confined to the composite's bounds")
		assertTrue(steps.indexOf(compositePass) < steps.indexOf(followingPass), "what follows draws after the composite")
		assertEquals(
			layerTarget.spec.width to layerTarget.spec.height,
			composite.screenTexWidth to composite.screenTexHeight,
			"the screen-space divisor is the layer's allocated size",
		)
	}

	/** A drawable whose blend is not fixed-function draws alone into a layer and composites with its modes. */
	@Test
	fun anExtendedBlendDrawableCompositesAsALayerOfItsOwn() {
		val basePositions = fullQuad()
		val blendedPositions = bandQuad()
		val source =
			model(
				drawables = listOf(drawable("base", basePositions), drawable("blended", blendedPositions, blendMode = BlendMode.Screen)),
				backToFront = listOf(OrgChild.Drawable(DrawableId("base")), OrgChild.Drawable(DrawableId("blended"))),
			)
		val (device, target) = recordFrame(source)

		val composite = device.compositeDraws().single()
		assertEquals(packedColorModeOf(BlendMode.Screen), composite.colorMode, "the composite computes the drawable's color blend")
		assertEquals(packedAlphaModeOf(AlphaBlendMode.Over), composite.alphaMode, "the composite computes the drawable's alpha blend")
		assertEquals(1f, composite.opacity, "the drawable's opacity is applied in its layer, not again in the composite")
		assertEquals(listOf(1f, 1f, 1f), composite.multiplyColor, "the composite's multiply color is identity")
		assertEquals(listOf(0f, 0f, 0f), composite.screenColor, "the composite's screen color is identity")
		assertSame(target, device.passOf(composite).target, "the layer composites back into the frame's target")

		val layerTarget = assertNotNull(device.targetSampledAs(composite.layer), "the composited layer is a target a pass rendered")
		val blendedDraw = device.meshDrawsOf(blendedPositions).single()
		assertSame(layerTarget, device.passOf(blendedDraw).target, "the drawable is drawn into its layer only")
		assertEquals(PipelineBlend.Normal, blendedDraw.pipeline.blend, "the layer draw writes plain premultiplied pixels")
		assertEquals(LoadAction.Clear, device.passOf(blendedDraw).spec.loadAction, "the layer starts transparent")
	}

	/** An identity Normal/Over isolated part draws inline: the frame is the one the same drawable alone records. */
	@Test
	fun anIdentityCompositeRecordsTheSameFrameAsNoComposite() {
		val plain =
			model(
				drawables = listOf(drawable("art", bandQuad())),
				backToFront = listOf(OrgChild.Drawable(DrawableId("art"))),
			)
		val wrapped =
			model(
				drawables = listOf(drawable("art", bandQuad())),
				parts = listOf(isolatedPart("fx", "art", PartComposite())),
				backToFront = listOf(OrgChild.Part(PartId("fx"))),
			)
		val (plainDevice, plainTarget) = recordFrame(plain)
		val (wrappedDevice, wrappedTarget) = recordFrame(wrapped)

		assertEquals(describe(plainDevice, plainTarget), describe(wrappedDevice, wrappedTarget), "the identity part adds nothing to the frame")
		assertTrue(wrappedDevice.compositeDraws().isEmpty(), "the identity part issues no composite draw")
		assertTrue(wrappedDevice.resolves().isEmpty(), "the identity part copies no destination")
		assertEquals(1, wrappedDevice.passes().size, "the identity part opens no pass of its own")
	}

	/** A part faded to nothing is skipped: the frame is the one the model records without it. */
	@Test
	fun aFullyFadedCompositeRecordsNothing() {
		val fadedPositions = bandQuad()
		val without =
			model(
				drawables = listOf(drawable("base", fullQuad())),
				backToFront = listOf(OrgChild.Drawable(DrawableId("base"))),
			)
		val faded =
			model(
				drawables = listOf(drawable("base", fullQuad()), drawable("faded", fadedPositions)),
				parts = listOf(isolatedPart("fx", "faded", PartComposite(opacity = 0f))),
				backToFront = listOf(OrgChild.Drawable(DrawableId("base")), OrgChild.Part(PartId("fx"))),
			)
		val (withoutDevice, withoutTarget) = recordFrame(without)
		val (fadedDevice, fadedTarget) = recordFrame(faded)

		assertEquals(describe(withoutDevice, withoutTarget), describe(fadedDevice, fadedTarget), "the faded part adds nothing to the frame")
		assertTrue(fadedDevice.meshDrawsOf(fadedPositions).isEmpty(), "the faded part's drawable is never drawn")
		assertTrue(fadedDevice.compositeDraws().isEmpty(), "the faded part issues no composite draw")
	}

	/** The glue store is captured when the pose changes and read, not re-captured, by every render of that pose. */
	@Test
	fun glueCaptureRunsOncePerPoseNotOncePerRender() {
		// The anchor carries no triangles: it draws nothing and exists only as a weld partner.
		val anchorPositions = floatArrayOf(-30f, -10f, -10f, -10f, -30f, 10f, -10f, 10f)
		val weldedPositions = floatArrayOf(10f, -10f, 30f, -10f, 10f, 10f, 30f, 10f)
		val source =
			model(
				drawables = listOf(drawable("anchor", anchorPositions, indices = IntArray(0)), drawable("welded", weldedPositions)),
				backToFront = listOf(OrgChild.Drawable(DrawableId("anchor")), OrgChild.Drawable(DrawableId("welded"))),
				glues = listOf(Glue(DrawableId("anchor"), DrawableId("welded"), listOf(GluePair(1, 0, 0f, 1f), GluePair(3, 2, 0f, 1f)))),
			)
		val device = RecordingRenderDevice()
		val renderer = posedRenderer(source, device)
		val target = mainTarget(device)
		device.clearLog()

		renderer.render(target, viewportSize, viewportSize)
		renderer.render(target, viewportSize, viewportSize)

		val capture = device.capturePasses().single()
		assertEquals(
			listOf(anchorPositions to 0, weldedPositions to 4),
			capture.captures.map { captured -> captured.mesh.restPositions to captured.destinationVertexOffset },
			"both glue meshes are captured, each into its own region of the store",
		)
		assertEquals(1, device.steps.count { step -> step is RecordedBarrier }, "the store is ordered against its readers once")
		val steps = device.steps
		val barrier = steps.first { step -> step is RecordedBarrier }
		assertTrue(
			steps.indexOf(capture) < steps.indexOf(barrier) && steps.indexOf(barrier) < steps.indexOf(device.passes().first()),
			"the capture and its barrier come before the first pass that reads the store",
		)
		val weldedDraws = device.meshDrawsOf(weldedPositions)
		assertEquals(2, weldedDraws.size, "the welded mesh draws in both renders")
		assertTrue(weldedDraws.all { draw -> draw.isGlue }, "a glue mesh draws through the glue pipeline")
		assertTrue(device.meshDrawsOf(anchorPositions).isEmpty(), "the triangle-less anchor draws nothing")

		renderer.setPose(emptyMap())
		renderer.render(target, viewportSize, viewportSize)
		assertEquals(2, device.capturePasses().size, "a new pose captures the store again")
	}

	/** A warp parent's control points are uploaded when the pose changes and never while rendering. */
	@Test
	fun warpControlPointsUploadOncePerPoseAndNeverInRender() {
		// A one-cell lattice spanning the band the mesh, in lattice-local [0, 1] coordinates, is carried into.
		val controlPoints = floatArrayOf(-16f, 8f, 16f, 8f, -16f, 30f, 16f, 30f)
		val warp =
			Deformer.Warp(
				id = DeformerId("warp"),
				name = "warp",
				parent = null,
				partId = null,
				rows = 1,
				columns = 1,
				isQuadTransform = true,
				geometryGrid =
					KeyformGrid(
						listOf(KeyformAxis(paramA, floatArrayOf(0f))),
						listOf(KeyformCell(intArrayOf(0), WarpLatticeForm(controlPoints))),
					),
			)
		val warpedPositions = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
		val source =
			model(
				drawables = listOf(drawable("warped", warpedPositions, parentDeformerId = DeformerId("warp"))),
				backToFront = listOf(OrgChild.Drawable(DrawableId("warped"))),
				deformers = listOf(warp),
			)
		val device = RecordingRenderDevice()
		val renderer = PuppetRenderer(source, PuppetTextures(emptyList(), emptyMap(), premultipliedAlpha = false), device)
		renderer.initGl()
		renderer.setCamera(ViewportCamera(0f, 0f, 1f))
		val target = mainTarget(device)
		device.clearLog()

		renderer.setPose(emptyMap())
		val upload = device.resourceEvents.filterIsInstance<FloatTextureUpdated>().single()
		assertEquals(2 to 2, upload.width to upload.height, "a one-cell lattice uploads its four control points")

		device.clearLog()
		renderer.render(target, viewportSize, viewportSize)
		renderer.render(target, viewportSize, viewportSize)
		assertTrue(
			device.resourceEvents.all { event -> event is TargetCreated },
			"rendering allocates its side targets and uploads nothing, got ${device.resourceEvents.map { event -> event::class.simpleName }}",
		)
		val draws = device.meshDrawsOf(warpedPositions)
		assertEquals(2, draws.size, "the warped mesh draws in both renders")
		assertTrue(draws.all { draw -> draw.warpControlPoints === upload.texture }, "each draw samples the control points the pose uploaded")
		assertTrue(draws.all { draw -> draw.parentType == 2 }, "each draw deforms through its warp parent")

		device.clearLog()
		renderer.setPose(emptyMap())
		assertEquals(1, device.resourceEvents.filterIsInstance<FloatTextureUpdated>().size, "a new pose uploads the control points again, once")
	}

	/**
	 * An image capture draws through a view of its own - its camera, the supersample's line width, no
	 * selection tint, and no mesh overlay - and the viewport's next frame is the one it drew before the
	 * capture.
	 */
	@Test
	fun aCaptureDrawsWithItsOwnViewAndLeavesTheViewportsAlone() {
		val artPositions = bandQuad()
		val source =
			model(
				drawables = listOf(drawable("art", artPositions)),
				backToFront = listOf(OrgChild.Drawable(DrawableId("art"))),
			)
		val device = RecordingRenderDevice()
		val renderer = posedRenderer(source, device)
		renderer.setSelection(setOf(DrawableId("art")))
		renderer.setActiveSelection(DrawableId("art"))
		renderer.setMeshOverlay(overlayOver(MeshOverlayKind.Edit, MeshOverlaySelectMode.Vertex, listOf("art")))
		val target = mainTarget(device)

		device.clearLog()
		renderer.render(target, viewportSize, viewportSize)
		val viewportGrid = device.passes().single().draws.filterIsInstance<RecordedGridDraw>().single()
		val viewportDraw = device.meshDraws().single()
		assertTrue(viewportDraw.highlight > 0f, "the viewport tints the selected drawable")
		assertEquals(3, device.overlayDraws().size, "the viewport draws the overlay over the art")
		assertEquals(1, device.resourceEvents.filterIsInstance<OverlayBuffersCreated>().size, "the overlay's buffers are uploaded for the viewport")

		// A pose between the viewport's frame and the capture leaves the overlay store stale; the capture
		// still leaves it alone, since nothing of the overlay is a capture's business.
		renderer.setPose(emptyMap())
		device.clearLog()
		val captureCamera = ViewportCamera(10f, 5f, 3f)
		val image = assertNotNull(renderer.renderSnapshot(captureCamera, 40, 30, FrameBackdrop.Grid))
		assertEquals(40 to 30, image.width to image.height)
		val captureGrid = device.passes().single().draws.filterIsInstance<RecordedGridDraw>().single()
		assertEquals(SNAPSHOT_SUPERSAMPLE.toFloat(), captureGrid.uniforms.lineWidthPx, "the capture's grid lines are drawn at the supersample's width")
		assertEquals(
			40 * SNAPSHOT_SUPERSAMPLE to 30 * SNAPSHOT_SUPERSAMPLE,
			captureGrid.uniforms.viewportWidth to captureGrid.uniforms.viewportHeight,
			"the capture renders at the supersampled size",
		)
		val captureTransform = ViewportCamera(10f, 5f, 3f * SNAPSHOT_SUPERSAMPLE).worldToNdc(40 * SNAPSHOT_SUPERSAMPLE, 30 * SNAPSHOT_SUPERSAMPLE)
		assertEquals(
			captureTransform.toList(),
			with(captureGrid.uniforms.worldToNdc) { listOf(scaleX, scaleY, offsetX, offsetY) },
			"the capture projects through its own camera",
		)
		assertEquals(0f, device.meshDraws().single().highlight, "the capture draws the selected drawable untinted")
		assertTrue(device.overlayDraws().isEmpty(), "the capture draws no overlay")
		assertTrue(device.capturePasses().isEmpty(), "and captures no overlay positions, stale as the store is")

		device.clearLog()
		assertNotNull(renderer.renderSnapshot(captureCamera, 40, 30, FrameBackdrop.Transparent, tileEdge = 16))
		assertEquals(6, device.passes().size, "a 40x30 capture in 16-pixel tiles is three columns by two rows")
		assertTrue(device.meshDraws().all { draw -> draw.highlight == 0f }, "no tile tints the selected drawable")
		assertTrue(device.overlayDraws().isEmpty(), "no tile draws the overlay")

		device.clearLog()
		renderer.render(target, viewportSize, viewportSize)
		assertEquals(viewportGrid.uniforms, device.passes().single().draws.filterIsInstance<RecordedGridDraw>().single().uniforms, "the viewport's view is as it was")
		assertEquals(viewportDraw.highlight, device.meshDraws().single().highlight, "the viewport still tints the selected drawable")
		assertEquals(viewportDraw.highlightColor, device.meshDraws().single().highlightColor, "the viewport tints toward the same color")
		assertEquals(3, device.overlayDraws().size, "the viewport draws the overlay again")
		assertTrue(device.resourceEvents.filterIsInstance<OverlayBuffersCreated>().isEmpty(), "over the buffers it kept through the capture")
	}

	/** A push that moved a glue mesh keeps the pose but re-captures the store the weld reads. */
	@Test
	fun aPositionsOnlyPushRecapturesTheGlueStoreWithoutAPose() {
		val anchorPositions = floatArrayOf(-30f, -10f, -10f, -10f, -30f, 10f, -10f, 10f)
		val weldedPositions = floatArrayOf(10f, -10f, 30f, -10f, 10f, 10f, 30f, 10f)
		val source =
			model(
				drawables = listOf(drawable("anchor", anchorPositions, indices = IntArray(0)), drawable("welded", weldedPositions)),
				backToFront = listOf(OrgChild.Drawable(DrawableId("anchor")), OrgChild.Drawable(DrawableId("welded"))),
				glues = listOf(Glue(DrawableId("anchor"), DrawableId("welded"), listOf(GluePair(1, 0, 0f, 1f), GluePair(3, 2, 0f, 1f)))),
			)
		val device = RecordingRenderDevice()
		val renderer = posedRenderer(source, device)
		val target = mainTarget(device)
		device.clearLog()
		renderer.render(target, viewportSize, viewportSize)
		assertEquals(1, device.capturePasses().size, "precondition: the first render captures the store once")

		val movedPositions = floatArrayOf(14f, -10f, 34f, -10f, 14f, 10f, 34f, 10f)
		assertEquals(ModelUpdateKind.PositionsOnly, renderer.updateModel(withPositions(source, "welded", movedPositions)), "a moved glue mesh is a positions-only push")
		renderer.render(target, viewportSize, viewportSize)

		val captures = device.capturePasses()
		assertEquals(2, captures.size, "the moved mesh re-captures the store without a pose")
		assertTrue(captures.last().captures.any { captured -> captured.mesh.restPositions === movedPositions }, "the second capture deforms the moved positions")
	}

	/** A push that moved an isolated part's drawable keeps the pose but re-plans the composite scissor. */
	@Test
	fun aPositionsOnlyPushMovesTheCompositeScissorWithoutAPose() {
		val source = compositeModel()
		val device = RecordingRenderDevice()
		val renderer = posedRenderer(source, device)
		val target = mainTarget(device)
		device.clearLog()
		renderer.render(target, viewportSize, viewportSize)
		val firstBounds = assertNotNull(device.passOf(device.compositeDraws().single()).spec.scissor, "precondition: the composite is scissored")
		device.clearLog()

		// The band slides 12 world units left: pixels 16..48 become 4..36, which the first scissor cannot hold.
		val movedBand = floatArrayOf(-28f, 8f, 4f, 8f, -28f, 30f, 4f, 30f)
		assertEquals(ModelUpdateKind.PositionsOnly, renderer.updateModel(withPositions(source, "layered", movedBand)))
		renderer.render(target, viewportSize, viewportSize)

		val movedBounds = assertNotNull(device.passOf(device.compositeDraws().single()).spec.scissor, "the composite is still scissored")
		assertTrue(holds(movedBounds, left = 4, top = 40, right = 36, bottom = 62), "the scissor follows the moved band, got $movedBounds")
		assertNotEquals(firstBounds, movedBounds, "the scissor moved with the band")
	}

	/** The frame a positions-only push draws without a pose is the frame a fresh pose over the moved model draws. */
	@Test
	fun aPositionsOnlyPushDrawsTheSameFrameAsAFreshPose() {
		val source = compositeModel()
		val moved = withPositions(source, "layered", floatArrayOf(-28f, 8f, 4f, 8f, -28f, 30f, 4f, 30f))
		val device = RecordingRenderDevice()
		val renderer = posedRenderer(source, device)
		val target = mainTarget(device)
		device.clearLog()
		assertEquals(ModelUpdateKind.PositionsOnly, renderer.updateModel(moved))
		renderer.render(target, viewportSize, viewportSize)
		val keptPose = describe(device, target)

		val (freshDevice, freshTarget) = recordFrame(moved)
		assertEquals(describe(freshDevice, freshTarget), keptPose, "the kept pose records the same passes, draws, and scissors as a fresh one")
	}

	/**
	 * The step sequence the flat, composite, and glue fixtures record with NO overlay set, as literals: the
	 * always-on guard that the overlay pass adds nothing to a frame that shows none.
	 */
	@Test
	fun aFrameWithNoOverlayRecordsTheBaselineSteps() {
		val flat =
			model(
				drawables = listOf(drawable("back", fullQuad()), drawable("front", bandQuad())),
				backToFront = listOf(OrgChild.Drawable(DrawableId("back")), OrgChild.Drawable(DrawableId("front"))),
			)
		val (flatDevice, flatTarget) = recordFrame(flat)
		val (compositeDevice, compositeTarget) = recordFrame(compositeModel())

		val anchorPositions = floatArrayOf(-30f, -10f, -10f, -10f, -30f, 10f, -10f, 10f)
		val weldedPositions = floatArrayOf(10f, -10f, 30f, -10f, 10f, 10f, 30f, 10f)
		val glued =
			model(
				drawables = listOf(drawable("anchor", anchorPositions, indices = IntArray(0)), drawable("welded", weldedPositions)),
				backToFront = listOf(OrgChild.Drawable(DrawableId("anchor")), OrgChild.Drawable(DrawableId("welded"))),
				glues = listOf(Glue(DrawableId("anchor"), DrawableId("welded"), listOf(GluePair(1, 0, 0f, 1f), GluePair(3, 2, 0f, 1f)))),
			)
		val (glueDevice, glueTarget) = recordFrame(glued)

		val expected =
			mapOf(
				"flat" to listOf("pass main DontCare scissor=null [grid, mesh(4 vertices, Normal, opacity 1.0), mesh(4 vertices, Normal, opacity 1.0)]"),
				"composite" to
					listOf(
						"pass main DontCare scissor=null [grid, mesh(4 vertices, Normal, opacity 1.0)]",
						"pass side Clear scissor=ScissorRect(x=14, y=38, width=36, height=26) [mesh(4 vertices, Normal, opacity 1.0)]",
						"copy region=ScissorRect(x=14, y=38, width=36, height=26)",
						"pass main Load scissor=ScissorRect(x=14, y=38, width=36, height=26) [composite]",
						"pass main Load scissor=null [mesh(4 vertices, Normal, opacity 1.0)]",
					),
				"glue" to listOf("capture 2", "barrier", "pass main DontCare scissor=null [grid, mesh(4 vertices, Normal, opacity 1.0)]"),
			)
		val recorded =
			mapOf(
				"flat" to describe(flatDevice, flatTarget),
				"composite" to describe(compositeDevice, compositeTarget),
				"glue" to describe(glueDevice, glueTarget),
			)
		assertEquals(expected, recorded, "a frame with no overlay records exactly the steps it always did")
	}

	/** Disposing the renderer frees the glue store along with the residents. */
	@Test
	fun disposeGlFreesTheGlueStore() {
		val anchorPositions = floatArrayOf(-30f, -10f, -10f, -10f, -30f, 10f, -10f, 10f)
		val weldedPositions = floatArrayOf(10f, -10f, 30f, -10f, 10f, 10f, 30f, 10f)
		val source =
			model(
				drawables = listOf(drawable("anchor", anchorPositions, indices = IntArray(0)), drawable("welded", weldedPositions)),
				backToFront = listOf(OrgChild.Drawable(DrawableId("anchor")), OrgChild.Drawable(DrawableId("welded"))),
				glues = listOf(Glue(DrawableId("anchor"), DrawableId("welded"), listOf(GluePair(1, 0, 0f, 1f), GluePair(3, 2, 0f, 1f)))),
			)
		val device = RecordingRenderDevice()
		val renderer = posedRenderer(source, device)
		device.clearLog()

		renderer.disposeGl()

		assertEquals(1, device.resourceEvents.filterIsInstance<StoreDestroyed>().size, "the glue store is freed with the residents")
	}

	/** An Edit overlay captures its store once per pose and draws, domain-major, after the art. */
	@Test
	fun anEditOverlayCapturesOncePerPoseAndDrawsAfterThePlan() {
		val source =
			model(
				drawables = listOf(drawable("art", bandQuad())),
				backToFront = listOf(OrgChild.Drawable(DrawableId("art"))),
			)
		val device = RecordingRenderDevice()
		val renderer = posedRenderer(source, device)
		val target = mainTarget(device)
		renderer.setMeshOverlay(overlayOver(MeshOverlayKind.Edit, MeshOverlaySelectMode.Vertex, listOf("art"), activeVertex = 3, activeEdge = 4))
		device.clearLog()

		renderer.render(target, viewportSize, viewportSize)
		assertEquals(
			listOf(
				"capture 1",
				"barrier",
				"pass main DontCare scissor=null [grid, mesh(4 vertices, Normal, opacity 1.0), overlay OverlayFaceFill, overlay OverlayEdge, overlay OverlayEdge active, overlay OverlayVertexDot, overlay OverlayVertexDot active]",
			),
			describe(device, target),
			"the first frame captures the overlay's positions and draws it over the art, actives last",
		)
		assertEquals(1, device.resourceEvents.filterIsInstance<StoreCreated>().size, "the overlay store is allocated")
		assertEquals(1, device.resourceEvents.filterIsInstance<OverlayBuffersCreated>().size, "and the mesh's buffers uploaded")

		device.clearLog()
		renderer.render(target, viewportSize, viewportSize)
		assertTrue(device.capturePasses().isEmpty(), "a still frame re-captures nothing")
		assertTrue(device.resourceEvents.isEmpty(), "and uploads nothing")
		assertEquals(5, device.overlayDraws().size, "but still draws the overlay")

		device.clearLog()
		renderer.setPose(emptyMap())
		renderer.render(target, viewportSize, viewportSize)
		assertEquals(1, device.capturePasses().size, "a new pose captures the overlay again")

		device.clearLog()
		assertEquals(ModelUpdateKind.PositionsOnly, renderer.updateModel(withPositions(source, "art", fullQuad())))
		renderer.render(target, viewportSize, viewportSize)
		assertEquals(1, device.capturePasses().size, "a preview push captures the moved positions")
		assertTrue(
			device.resourceEvents.none { event -> event is OverlayBuffersCreated || event is OverlayFlagsUpdated },
			"and uploads no overlay data",
		)
	}

	/** The select mode decides which domains draw and how the fills apply. */
	@Test
	fun aSelectModeDecidesTheOverlayDraws() {
		val source =
			model(
				drawables = listOf(drawable("art", bandQuad())),
				backToFront = listOf(OrgChild.Drawable(DrawableId("art"))),
			)
		val device = RecordingRenderDevice()
		val renderer = posedRenderer(source, device)
		val target = mainTarget(device)

		val vertexDraws = overlayDrawsFor(renderer, device, target, overlayOver(MeshOverlayKind.Edit, MeshOverlaySelectMode.Vertex, listOf("art")))
		assertEquals(listOf(PipelinePurpose.OverlayFaceFill, PipelinePurpose.OverlayEdge, PipelinePurpose.OverlayVertexDot), vertexDraws.map { draw -> draw.purpose })
		assertFalse(vertexDraws[0].fillIdle, "outside Face mode only the selected faces fill")

		val faceDraws = overlayDrawsFor(renderer, device, target, overlayOver(MeshOverlayKind.Edit, MeshOverlaySelectMode.Face, listOf("art")))
		assertEquals(listOf(PipelinePurpose.OverlayFaceFill, PipelinePurpose.OverlayEdge, PipelinePurpose.OverlayFaceDot), faceDraws.map { draw -> draw.purpose })
		assertTrue(faceDraws[0].fillIdle, "Face mode fills every face")
		assertEquals(1f, faceDraws[2].idleColor[3], "the face dots render opaque whatever the fill alpha")

		val edgeDraws = overlayDrawsFor(renderer, device, target, overlayOver(MeshOverlayKind.Edit, MeshOverlaySelectMode.Edge, listOf("art")))
		assertEquals(listOf(PipelinePurpose.OverlayFaceFill, PipelinePurpose.OverlayEdge), edgeDraws.map { draw -> draw.purpose })
		assertFalse(edgeDraws[0].fillIdle, "Edge mode draws no dots and fills only the selected faces")
	}

	/** Grid lines off is the grid pass with its lines in the background color: the same opaque fill, still what clears the frame. */
	@Test
	fun gridLinesOffPaintsAFlatBackdropInTheSamePass() {
		val source =
			model(
				drawables = listOf(drawable("art", bandQuad())),
				backToFront = listOf(OrgChild.Drawable(DrawableId("art"))),
			)
		val device = RecordingRenderDevice()
		val renderer = posedRenderer(source, device)
		val target = mainTarget(device)
		device.clearLog()
		renderer.render(target, viewportSize, viewportSize)
		val linedColors = device.passes().single().draws.filterIsInstance<RecordedGridDraw>().single().uniforms.colors
		assertNotEquals(linedColors.backgroundRed, linedColors.majorRed, "the default frame draws its lines")

		device.clearLog()
		renderer.render(target, viewportSize, viewportSize, overlays = FrameOverlays(gridLines = false))

		assertEquals(listOf("pass main DontCare scissor=null [grid, mesh(4 vertices, Normal, opacity 1.0)]"), describe(device, target), "the pass is as with lines: the grid fill still clears")
		val flatColors = device.passes().single().draws.filterIsInstance<RecordedGridDraw>().single().uniforms.colors
		val background = listOf(flatColors.backgroundRed, flatColors.backgroundGreen, flatColors.backgroundBlue)
		assertEquals(background, listOf(flatColors.majorRed, flatColors.majorGreen, flatColors.majorBlue), "the major lines take the background color")
		assertEquals(background, listOf(flatColors.minorRed, flatColors.minorGreen, flatColors.minorBlue), "and so do the minor lines")
	}

	/** The world axes draw right after the grid only when the frame asks, with or without the grid's lines. */
	@Test
	fun axesDrawAfterTheGridWhenAsked() {
		val source =
			model(
				drawables = listOf(drawable("art", bandQuad())),
				backToFront = listOf(OrgChild.Drawable(DrawableId("art"))),
			)
		val device = RecordingRenderDevice()
		val renderer = posedRenderer(source, device)
		val target = mainTarget(device)
		val withAxes = "pass main DontCare scissor=null [grid, axis, axis, mesh(4 vertices, Normal, opacity 1.0)]"

		device.clearLog()
		renderer.render(target, viewportSize, viewportSize)
		assertEquals(listOf("pass main DontCare scissor=null [grid, mesh(4 vertices, Normal, opacity 1.0)]"), describe(device, target), "a frame draws no axes unless asked")

		device.clearLog()
		renderer.render(target, viewportSize, viewportSize, overlays = FrameOverlays(axes = true))
		assertEquals(listOf(withAxes), describe(device, target), "asked, the two axis lines follow the grid")

		device.clearLog()
		renderer.render(target, viewportSize, viewportSize, overlays = FrameOverlays(gridLines = false, axes = true))
		assertEquals(listOf(withAxes), describe(device, target), "over a flat fill too")
	}

	/**
	 * A frame that hides the mesh overlay neither captures nor draws it, while the residency still follows the
	 * held overlay: the buffers are uploaded all the same, so the first frame to show it draws over them with
	 * no upload, capturing the positions the hidden frames skipped.
	 */
	@Test
	fun aHiddenMeshOverlayNeitherCapturesNorDrawsAndKeepsItsBuffers() {
		val source =
			model(
				drawables = listOf(drawable("art", bandQuad())),
				backToFront = listOf(OrgChild.Drawable(DrawableId("art"))),
			)
		val device = RecordingRenderDevice()
		val renderer = posedRenderer(source, device)
		val target = mainTarget(device)
		renderer.setMeshOverlay(overlayOver(MeshOverlayKind.Edit, MeshOverlaySelectMode.Vertex, listOf("art")))
		device.clearLog()

		renderer.render(target, viewportSize, viewportSize, overlays = FrameOverlays(meshOverlay = false))

		assertEquals(listOf("pass main DontCare scissor=null [grid, mesh(4 vertices, Normal, opacity 1.0)]"), describe(device, target), "a hidden overlay neither captures nor draws")
		assertEquals(1, device.resourceEvents.filterIsInstance<OverlayBuffersCreated>().size, "its buffers are uploaded all the same")
		assertTrue(device.resourceEvents.filterIsInstance<OverlayBuffersDestroyed>().isEmpty(), "and kept")

		device.clearLog()
		renderer.render(target, viewportSize, viewportSize)
		assertEquals(
			listOf(
				"capture 1",
				"barrier",
				"pass main DontCare scissor=null [grid, mesh(4 vertices, Normal, opacity 1.0), overlay OverlayFaceFill, overlay OverlayEdge, overlay OverlayVertexDot]",
			),
			describe(device, target),
			"the first shown frame captures the positions the hidden one skipped and draws",
		)
		assertTrue(device.resourceEvents.filterIsInstance<OverlayBuffersCreated>().isEmpty(), "over the buffers it kept")

		device.clearLog()
		renderer.render(target, viewportSize, viewportSize, overlays = FrameOverlays(meshOverlay = false))
		assertTrue(device.resourceEvents.isEmpty(), "hiding it again frees nothing")
	}

	/** The object wireframe draws every listed mesh's edges and nothing else. */
	@Test
	fun anObjectWireframeDrawsEdgesOnly() {
		val source =
			model(
				drawables = listOf(drawable("back", fullQuad()), drawable("front", bandQuad())),
				backToFront = listOf(OrgChild.Drawable(DrawableId("back")), OrgChild.Drawable(DrawableId("front"))),
			)
		val device = RecordingRenderDevice()
		val renderer = posedRenderer(source, device)
		val target = mainTarget(device)

		val draws = overlayDrawsFor(renderer, device, target, overlayOver(MeshOverlayKind.ObjectWireframe, MeshOverlaySelectMode.Vertex, listOf("back", "front")))

		assertEquals(listOf(PipelinePurpose.OverlayEdge, PipelinePurpose.OverlayEdge), draws.map { draw -> draw.purpose }, "one edge batch per mesh, no fills, no dots")
		assertEquals(listOf(0, 4), draws.map { draw -> draw.baseOffset }, "each mesh reads its own store region")
		assertEquals(1, device.capturePasses().size, "the wireframe still needs the positions captured")
	}

	/** An overlay mesh whose resident disagrees with it is left out of the frame, and the frame is as without it. */
	@Test
	fun anOverlayMeshWhoseResidentDisagreesIsSkipped() {
		val source =
			model(
				drawables = listOf(drawable("art", bandQuad())),
				backToFront = listOf(OrgChild.Drawable(DrawableId("art"))),
			)
		val device = RecordingRenderDevice()
		val renderer = posedRenderer(source, device)
		val target = mainTarget(device)
		renderer.setMeshOverlay(overlayOver(MeshOverlayKind.Edit, MeshOverlaySelectMode.Vertex, listOf("art"), vertexCount = 5))
		device.clearLog()

		renderer.render(target, viewportSize, viewportSize)

		assertEquals(listOf("pass main DontCare scissor=null [grid, mesh(4 vertices, Normal, opacity 1.0)]"), describe(device, target), "nothing of the overlay reaches the frame")
		assertTrue(device.resourceEvents.none { event -> event is StoreCreated || event is OverlayBuffersCreated }, "and nothing is uploaded for it")
	}

	/** A topology edit replaces the resident; the next frame rebuilds that mesh's buffers and re-captures. */
	@Test
	fun aStructuralPushRepairsTheOverlay() {
		val source =
			model(
				drawables = listOf(drawable("art", bandQuad())),
				backToFront = listOf(OrgChild.Drawable(DrawableId("art"))),
			)
		val device = RecordingRenderDevice()
		val renderer = posedRenderer(source, device)
		val target = mainTarget(device)
		renderer.setMeshOverlay(overlayOver(MeshOverlayKind.Edit, MeshOverlaySelectMode.Vertex, listOf("art")))
		renderer.render(target, viewportSize, viewportSize)
		val remeshed =
			source.copy(
				drawables =
					source.drawables.map { drawable ->
						val mesh = drawable.mesh ?: error("the fixture carries meshes")
						drawable.copy(mesh = DrawableMesh.withLocalEqualToCanvas(mesh.positions, mesh.uvs, quadIndices.copyOf()))
					},
			)
		device.clearLog()

		assertEquals(ModelUpdateKind.Structural, renderer.updateModel(remeshed))
		// The engine poses after every structural push; the new resident has no pose until then.
		renderer.setPose(emptyMap())
		renderer.render(target, viewportSize, viewportSize)

		assertEquals(1, device.resourceEvents.filterIsInstance<OverlayBuffersDestroyed>().size, "the replaced resident's buffers are freed")
		assertEquals(1, device.resourceEvents.filterIsInstance<OverlayBuffersCreated>().size, "and rebuilt over the new one")
		assertEquals(1, device.capturePasses().size, "and the positions are captured again")
		assertEquals(3, device.overlayDraws().size, "the overlay draws as before")
	}

	/**
	 * An overlay over the fixture's quads, every flag idle except the given actives.
	 *
	 * @param MeshOverlayKind kind The overlay kind.
	 * @param MeshOverlaySelectMode selectMode The select mode.
	 * @param List<String> ids The quads, in layout order.
	 * @param Int vertexCount The vertex count each entry claims; 4 pairs with a quad.
	 * @param Int? activeVertex The active vertex on every entry, or null.
	 * @param Int? activeEdge The active edge ordinal on every entry, or null.
	 * @return MeshOverlay The overlay.
	 */
	private fun overlayOver(
		kind: MeshOverlayKind,
		selectMode: MeshOverlaySelectMode,
		ids: List<String>,
		vertexCount: Int = 4,
		activeVertex: Int? = null,
		activeEdge: Int? = null,
	): MeshOverlay =
		MeshOverlay(
			kind,
			selectMode,
			ids.map { id ->
				val vertexFlags = ByteArray(vertexCount)
				if (activeVertex != null) {
					vertexFlags[activeVertex] = OVERLAY_FLAG_ACTIVE
				}
				val edgeFlags = ByteArray(quadEdges.size / 2)
				if (activeEdge != null) {
					edgeFlags[activeEdge] = OVERLAY_FLAG_ACTIVE
				}
				MeshOverlayMesh(DrawableId(id), vertexCount, quadEdges, vertexFlags, edgeFlags, ByteArray(2), activeVertex, activeEdge, null)
			},
			MeshOverlaySizes(3.5f, 1f, 2.5f),
		)

	/**
	 * A mesh the current pose leaves unposed (its own keyform grid out of range) is neither captured nor
	 * drawn, even after an earlier pose posed it: posing at rest clears it from this pose's set, and the
	 * overlay follows that, not a stale shape from the earlier pose.
	 */
	@Test
	fun anOverlayMeshUnposedAtThisPoseIsNeitherCapturedNorDrawn() {
		// Steady is keyed over A's whole range, so every pose poses it; ranged only over [0.5, 1], so rest
		// leaves it unposed.
		val steady = drawable("steady", bandQuad()).copy(geometryGrid = keyedOver(-1f, 1f))
		val ranged = drawable("ranged", bandQuad()).copy(geometryGrid = keyedOver(0.5f, 1f))
		val source =
			model(
				drawables = listOf(steady, ranged),
				backToFront = listOf(OrgChild.Drawable(DrawableId("steady")), OrgChild.Drawable(DrawableId("ranged"))),
			)
		val device = RecordingRenderDevice()
		val renderer = posedRenderer(source, device)
		val target = mainTarget(device)
		val overlay = overlayOver(MeshOverlayKind.Edit, MeshOverlaySelectMode.Vertex, listOf("steady", "ranged"))

		renderer.setPose(mapOf(paramA to 0.75f))
		val posedDraws = overlayDrawsFor(renderer, device, target, overlay)
		assertEquals(listOf(0, 4), device.capturePasses().single().captures.map { capture -> capture.destinationVertexOffset }, "inside its range both meshes are captured")
		assertEquals(setOf(0, 4), posedDraws.map { draw -> draw.baseOffset }.toSet(), "and both draw")

		renderer.setPose(emptyMap())
		device.clearLog()
		renderer.render(target, viewportSize, viewportSize)
		assertEquals(listOf(0), device.capturePasses().single().captures.map { capture -> capture.destinationVertexOffset }, "at rest only the steady mesh is captured")
		assertEquals(setOf(0), device.overlayDraws().map { draw -> draw.baseOffset }.toSet(), "and only it draws")
	}

	/**
	 * A two-key zero-delta grid over A from [low] to [high], so a quad is posed exactly while A is in range.
	 *
	 * @param Float low The lower key.
	 * @param Float high The upper key.
	 * @return KeyformGrid<MeshDeltaForm> The grid.
	 */
	private fun keyedOver(low: Float, high: Float): KeyformGrid<MeshDeltaForm> =
		KeyformGrid(
			listOf(KeyformAxis(paramA, floatArrayOf(low, high))),
			listOf(
				KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(8))),
				KeyformCell(intArrayOf(1), MeshDeltaForm(FloatArray(8))),
			),
		)

	/**
	 * The overlay draws one frame records for [overlay].
	 *
	 * @param PuppetRenderer renderer The posed renderer.
	 * @param RecordingRenderDevice device Its device.
	 * @param RecordedTarget target The frame target.
	 * @param MeshOverlay overlay The overlay to draw.
	 * @return List<RecordedOverlayDraw> The overlay draws, in issue order.
	 */
	private fun overlayDrawsFor(renderer: PuppetRenderer, device: RecordingRenderDevice, target: RecordedTarget, overlay: MeshOverlay): List<RecordedOverlayDraw> {
		renderer.setMeshOverlay(overlay)
		device.clearLog()
		renderer.render(target, viewportSize, viewportSize)
		return device.overlayDraws()
	}
}