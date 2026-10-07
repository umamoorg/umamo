package org.umamo.render.puppet

import org.umamo.render.DecodedImage
import org.umamo.render.DrawableLayerDraw
import org.umamo.render.LayerDrawPlan
import org.umamo.render.LayerRasterBatch
import org.umamo.render.PuppetTextures
import org.umamo.render.ViewportCamera
import org.umamo.render.device.MeshCreated
import org.umamo.render.device.MeshDestroyed
import org.umamo.render.device.MeshPositionsUpdated
import org.umamo.render.device.MeshUvsUpdated
import org.umamo.render.device.RecordedMesh
import org.umamo.render.device.RecordedTarget
import org.umamo.render.device.RecordedTextureKind
import org.umamo.render.device.RecordingRenderDevice
import org.umamo.render.device.RenderTargetSpec
import org.umamo.render.device.ResourceEvent
import org.umamo.render.device.TextureCreated
import org.umamo.render.device.TextureDestroyed
import org.umamo.render.device.TextureFormat
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
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins what [PuppetRenderer.updateModel] asks of the device for each kind of edit, and that a drawable
 * rebuilt by an edit keeps displaying from its source artwork.
 *
 * Each reconcile tier exists to avoid the work of the tier above it, and the GL tests cannot tell them
 * apart: an edit that re-uploaded a whole mesh where a position update would do renders the same
 * pixels.  These run on a [RecordingRenderDevice], with no GPU, so they run everywhere.
 *
 * Nothing here asserts the order resources are created or freed in, only which ones are.
 */
class ResidencyDeviceCallsTest {
	private val viewportSize = 64
	private val paramA = ParameterId("A")
	private val probeId = DrawableId("probe")

	// The affine the artwork plan carries.  Off identity on purpose, so a draw that lost it is told apart
	// from one that kept it.
	private val artworkAffine = floatArrayOf(0.5f, 0f, 0.25f, 0f, 0.5f, 0.25f)

	/**
	 * The one-quad model every test here edits.
	 *
	 * @return PuppetModel The model.
	 */
	private fun probeModel(): PuppetModel {
		val positions = floatArrayOf(-16f, -16f, 16f, -16f, -16f, 16f, 16f, 16f)
		val probe =
			Drawable(
				id = probeId,
				name = probeId.raw,
				parentDeformerId = null,
				blendMode = BlendMode.Normal,
				maskedBy = emptyList(),
				mesh = DrawableMesh.withLocalEqualToCanvas(positions, floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f), intArrayOf(0, 1, 2, 1, 3, 2)),
				geometryGrid =
					KeyformGrid(
						listOf(KeyformAxis(paramA, floatArrayOf(0f))),
						listOf(KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(positions.size)))),
					),
			)
		return PuppetModel(
			parameters = listOf(Parameter(paramA, "A", -1f, 1f, 0f)),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = listOf(probe),
			rootChildren = listOf(OrgChild.Drawable(probeId)),
			rootPartId = null,
		)
	}

	/**
	 * The model with its one drawable replaced by an edited copy.
	 *
	 * @param PuppetModel source The model to edit.
	 * @param Function    edit   Builds the edited drawable from the current one.
	 * @return PuppetModel The edited model.
	 */
	private fun edited(source: PuppetModel, edit: (Drawable) -> Drawable): PuppetModel = source.copy(drawables = listOf(edit(source.drawables.single())))

	/**
	 * The drawable over a replaced mesh, every array it does not replace shared by reference, as a
	 * copy-on-write edit leaves them.
	 *
	 * @param Drawable    drawable  The drawable to edit.
	 * @param FloatArray? positions The new positions, or null to keep the current array.
	 * @param FloatArray? uvs       The new UVs, or null to keep the current array.
	 * @param IntArray?   indices   The new indices, or null to keep the current array.
	 * @return Drawable The edited drawable.
	 */
	private fun withMesh(
		drawable: Drawable,
		positions: FloatArray? = null,
		uvs: FloatArray? = null,
		indices: IntArray? = null,
	): Drawable {
		val mesh = drawable.mesh ?: error("the probe carries a mesh")
		return drawable.copy(mesh = DrawableMesh.withLocalEqualToCanvas(positions ?: mesh.positions, uvs ?: mesh.uvs, indices ?: mesh.indices))
	}

	/**
	 * A solid-color image.
	 *
	 * @param Int red   The red channel, 0..255.
	 * @param Int green The green channel, 0..255.
	 * @return DecodedImage The image.
	 */
	private fun solidImage(red: Int, green: Int): DecodedImage {
		val edge = 8
		val rgba = ByteArray(edge * edge * 4)
		for (pixel in rgba.indices step 4) {
			rgba[pixel] = red.toByte()
			rgba[pixel + 1] = green.toByte()
			rgba[pixel + 3] = 0xFF.toByte()
		}
		return DecodedImage(rgba, edge, edge)
	}

	/**
	 * A renderer over [source] with nothing in the device's log, and the resident mesh it uploaded.
	 *
	 * @param PuppetModel           source The model.
	 * @param RecordingRenderDevice device The device to record on.
	 * @return Pair<PuppetRenderer, RecordedMesh> The renderer and the probe's resident mesh.
	 */
	private fun uploadedRenderer(source: PuppetModel, device: RecordingRenderDevice): Pair<PuppetRenderer, RecordedMesh> {
		val renderer = PuppetRenderer(source, PuppetTextures(emptyList(), emptyMap(), premultipliedAlpha = false), device)
		renderer.initGl()
		val resident = device.resourceEvents.filterIsInstance<MeshCreated>().single().mesh
		device.clearLog()
		return renderer to resident
	}

	/**
	 * The events' class names, for a failure message.
	 *
	 * @param List<ResourceEvent> events The events.
	 * @return List<String?> Their class names.
	 */
	private fun namesOf(events: List<ResourceEvent>): List<String?> = events.map { event -> event::class.simpleName }

	/** An edit to a drawable's composite state touches no resource at all, yet it is structural: the pose stamps it. */
	@Test
	fun aCompositeOnlyEditIssuesNoDeviceCall() {
		val source = probeModel()
		val device = RecordingRenderDevice()
		val (renderer, _) = uploadedRenderer(source, device)
		renderer.setPose(emptyMap())
		device.clearLog()

		val kind = renderer.updateModel(edited(source) { drawable -> drawable.copy(culling = true, blendMode = BlendMode.AdditivePremultiplied) })

		assertTrue(device.resourceEvents.isEmpty(), "a composite-only edit is no buffer work, got ${namesOf(device.resourceEvents)}")
		assertEquals(ModelUpdateKind.Structural, kind, "a composite edit changes what the pose stamps, so the pose must rebuild")
	}

	/** Moving the base mesh re-uploads its positions in place and nothing else, and keeps the pose. */
	@Test
	fun aPositionEditUpdatesPositionsInPlace() {
		val source = probeModel()
		val device = RecordingRenderDevice()
		val (renderer, resident) = uploadedRenderer(source, device)
		renderer.setPose(emptyMap())
		device.clearLog()
		val movedPositions = floatArrayOf(-8f, -8f, 24f, -8f, -8f, 24f, 24f, 24f)

		val kind = renderer.updateModel(edited(source) { drawable -> withMesh(drawable, positions = movedPositions) })

		val update = device.resourceEvents.single() as MeshPositionsUpdated
		assertSame(resident, update.mesh, "the resident mesh is updated, not replaced")
		assertSame(movedPositions, update.positions, "the edited positions are what is uploaded")
		assertEquals(ModelUpdateKind.PositionsOnly, kind, "a moved base mesh keeps the pose")
	}

	/** Before any pose there is nothing to keep, so even a pure move is reported structural. */
	@Test
	fun aPositionsOnlyPushBeforeAnyPoseIsStructural() {
		val source = probeModel()
		val device = RecordingRenderDevice()
		val (renderer, _) = uploadedRenderer(source, device)
		val movedPositions = floatArrayOf(-8f, -8f, 24f, -8f, -8f, 24f, 24f, 24f)

		val kind = renderer.updateModel(edited(source) { drawable -> withMesh(drawable, positions = movedPositions) })

		assertEquals(ModelUpdateKind.Structural, kind, "no pose exists yet, so the caller must pose as for any edit")
	}

	/** Editing the texture coordinates re-uploads them in place and nothing else. */
	@Test
	fun aUvEditUpdatesUvsInPlace() {
		val source = probeModel()
		val device = RecordingRenderDevice()
		val (renderer, resident) = uploadedRenderer(source, device)
		val movedUvs = floatArrayOf(0.1f, 0.9f, 0.9f, 0.9f, 0.1f, 0.1f, 0.9f, 0.1f)

		renderer.updateModel(edited(source) { drawable -> withMesh(drawable, uvs = movedUvs) })

		val update = device.resourceEvents.single() as MeshUvsUpdated
		assertSame(resident, update.mesh, "the resident mesh is updated, not replaced")
		assertSame(movedUvs, update.uvs, "the edited coordinates are what is uploaded")
	}

	/** A topology change frees the resident's mesh and delta texture and uploads both again. */
	@Test
	fun aTopologyEditFreesTheResidentAndUploadsItWhole() {
		val source = probeModel()
		val device = RecordingRenderDevice()
		val (renderer, resident) = uploadedRenderer(source, device)

		renderer.updateModel(edited(source) { drawable -> withMesh(drawable, indices = intArrayOf(0, 2, 1, 1, 2, 3)) })

		val events = device.resourceEvents
		assertSame(resident, events.filterIsInstance<MeshDestroyed>().single().mesh, "the resident mesh is freed")
		assertTrue(events.filterIsInstance<MeshCreated>().single().mesh !== resident, "a new mesh is uploaded")
		assertEquals(RecordedTextureKind.Float, events.filterIsInstance<TextureDestroyed>().single().texture.kind, "the resident's delta texture is freed")
		assertEquals(RecordedTextureKind.Float, events.filterIsInstance<TextureCreated>().single().texture.kind, "a new delta texture is uploaded")
		assertEquals(4, events.size, "a re-upload is two frees and two uploads, got ${namesOf(events)}")
	}

	/** Deleting a drawable frees its mesh and delta texture and uploads nothing. */
	@Test
	fun aRemovedDrawableFreesItsResidency() {
		val source = probeModel()
		val device = RecordingRenderDevice()
		val (renderer, resident) = uploadedRenderer(source, device)

		renderer.updateModel(source.copy(drawables = emptyList(), rootChildren = emptyList()))

		val events = device.resourceEvents
		assertSame(resident, events.filterIsInstance<MeshDestroyed>().single().mesh, "the resident mesh is freed")
		assertEquals(RecordedTextureKind.Float, events.filterIsInstance<TextureDestroyed>().single().texture.kind, "the resident's delta texture is freed")
		assertEquals(2, events.size, "a removal is two frees, got ${namesOf(events)}")
	}

	/**
	 * A puppet displays from its atlas until every mapped layer has landed, then from its artwork, and a
	 * drawable an edit rebuilt is pointed at that artwork again.
	 */
	@Test
	fun aReuploadedResidentStillSamplesItsArtwork() {
		val source = probeModel()
		val atlasPage = solidImage(red = 0xFF, green = 0x00)
		val artwork = solidImage(red = 0x00, green = 0xFF)
		val device = RecordingRenderDevice()
		val renderer = PuppetRenderer(source, PuppetTextures(listOf(atlasPage), mapOf(probeId.raw to 0), premultipliedAlpha = false), device)
		renderer.initGl()
		renderer.setCamera(ViewportCamera(0f, 0f, 1f))
		renderer.setPose(emptyMap())
		val target = device.createRenderTarget(RenderTargetSpec(viewportSize, viewportSize, TextureFormat.Rgba8, sampled = true)) as RecordedTarget

		renderer.setSourceLayerPlan(
			LayerDrawPlan(
				drawsByDrawableId = mapOf(probeId.raw to DrawableLayerDraw("layer", artworkAffine)),
				layerByteCostByKey = mapOf("layer" to 256L),
			),
		)
		device.clearLog()
		renderer.render(target, viewportSize, viewportSize)
		val beforeDelivery = device.meshDraws().single()
		assertSame(atlasPage.rgba, assertNotNull(beforeDelivery.art).pixels, "with its artwork still to land, the puppet displays from the atlas")
		assertEquals(listOf(1f, 0f, 0f, 0f, 1f, 0f), beforeDelivery.uvAffine, "an atlas draw samples through the identity affine")

		renderer.deliverSourceLayerRasters(LayerRasterBatch(rastersByLayerKey = mapOf("layer" to artwork)))
		device.clearLog()
		renderer.render(target, viewportSize, viewportSize)
		val fromArtwork = device.meshDraws().single()
		assertSame(artwork.rgba, assertNotNull(fromArtwork.art).pixels, "with its artwork landed, the puppet displays from it")
		assertEquals(artworkAffine.toList(), fromArtwork.uvAffine, "an artwork draw samples through the plan's affine")

		renderer.updateModel(edited(source) { drawable -> withMesh(drawable, indices = intArrayOf(0, 2, 1, 1, 2, 3)) })
		renderer.setPose(emptyMap())
		device.clearLog()
		renderer.render(target, viewportSize, viewportSize)
		val afterReupload = device.meshDraws().single()
		assertTrue(afterReupload.mesh !== fromArtwork.mesh, "the edit rebuilt the resident")
		assertSame(artwork.rgba, assertNotNull(afterReupload.art).pixels, "the rebuilt resident displays from its artwork")
		assertEquals(artworkAffine.toList(), afterReupload.uvAffine, "the rebuilt resident samples through the plan's affine")
	}
}