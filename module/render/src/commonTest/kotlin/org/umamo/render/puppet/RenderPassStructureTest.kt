package org.umamo.render.puppet

import org.umamo.render.FrameBackdrop
import org.umamo.render.PuppetTextures
import org.umamo.render.ViewportCamera
import org.umamo.render.device.FloatTextureUpdated
import org.umamo.render.device.LoadAction
import org.umamo.render.device.PipelineBlend
import org.umamo.render.device.RecordedBarrier
import org.umamo.render.device.RecordedCapturePass
import org.umamo.render.device.RecordedCompositeDraw
import org.umamo.render.device.RecordedGridDraw
import org.umamo.render.device.RecordedMeshDraw
import org.umamo.render.device.RecordedPass
import org.umamo.render.device.RecordedResolve
import org.umamo.render.device.RecordedTarget
import org.umamo.render.device.RecordingRenderDevice
import org.umamo.render.device.RenderTargetSpec
import org.umamo.render.device.ScissorRect
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
	 * An image capture draws through a view of its own - its camera, the supersample's line width, and
	 * no selection tint - and the viewport's next frame is the one it drew before the capture.
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
		val target = mainTarget(device)

		device.clearLog()
		renderer.render(target, viewportSize, viewportSize)
		val viewportGrid = device.passes().single().draws.filterIsInstance<RecordedGridDraw>().single()
		val viewportDraw = device.meshDraws().single()
		assertTrue(viewportDraw.highlight > 0f, "the viewport tints the selected drawable")

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

		device.clearLog()
		assertNotNull(renderer.renderSnapshot(captureCamera, 40, 30, FrameBackdrop.Transparent, tileEdge = 16))
		assertEquals(6, device.passes().size, "a 40x30 capture in 16-pixel tiles is three columns by two rows")
		assertTrue(device.meshDraws().all { draw -> draw.highlight == 0f }, "no tile tints the selected drawable")

		device.clearLog()
		renderer.render(target, viewportSize, viewportSize)
		assertEquals(viewportGrid.uniforms, device.passes().single().draws.filterIsInstance<RecordedGridDraw>().single().uniforms, "the viewport's view is as it was")
		assertEquals(viewportDraw.highlight, device.meshDraws().single().highlight, "the viewport still tints the selected drawable")
		assertEquals(viewportDraw.highlightColor, device.meshDraws().single().highlightColor, "the viewport tints toward the same color")
	}
}