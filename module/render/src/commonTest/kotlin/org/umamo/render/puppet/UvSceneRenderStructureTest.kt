package org.umamo.render.puppet

import org.umamo.render.ContentBounds
import org.umamo.render.DecodedImage
import org.umamo.render.GridColors
import org.umamo.render.LayerDrawPlan
import org.umamo.render.LayerRasterBatch
import org.umamo.render.PuppetTextures
import org.umamo.render.ViewportCamera
import org.umamo.render.device.OverlayBuffersCreated
import org.umamo.render.device.OverlayBuffersDestroyed
import org.umamo.render.device.OverlayFlagsUpdated
import org.umamo.render.device.RecordedBarrier
import org.umamo.render.device.RecordedGridDraw
import org.umamo.render.device.RecordedOverlayDraw
import org.umamo.render.device.RecordedPass
import org.umamo.render.device.RecordedQuadDraw
import org.umamo.render.device.RecordedStore
import org.umamo.render.device.RecordedTarget
import org.umamo.render.device.RecordingRenderDevice
import org.umamo.render.device.RenderTargetSpec
import org.umamo.render.device.StoreCreated
import org.umamo.render.device.StoreDestroyed
import org.umamo.render.device.StorePositionsUpdated
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
import org.umamo.runtime.model.withDerivedRenderRoot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins what a UV area's scene render records: the pass it opens, the draws in it, and the resources it
 * touches.  A UV scene draws the grid and the shown surface (an atlas page or a layer image) in one pass,
 * then any placement preview's scrims and crops, and, when the area's content carries a mesh overlay, the
 * overlay last in that same pass, from positions uploaded into the area's own store rather than captured.
 */
class UvSceneRenderStructureTest {
	private val viewportSize = 64
	private val quadIndices = intArrayOf(0, 1, 2, 1, 3, 2)
	private val quadEdges = intArrayOf(0, 1, 1, 2, 0, 2, 1, 3, 2, 3)
	private val sizes = MeshOverlaySizes(3.5f, 1f, 2.5f)
	private val baselinePass = "pass main DontCare scissor=null [grid, page]"
	private val editPass = "pass main DontCare scissor=null [grid, page, overlay OverlayFaceFill, overlay OverlayEdge, overlay OverlayVertexDot]"
	private val identitySample = listOf(1f, 0f, 0f, 0f, 1f, 0f)

	/** A UV scene with no overlay opens one pass, draws the grid and the page, and touches no resource. */
	@Test
	fun aUvSceneWithNoOverlayRecordsTheBaselinePass() {
		val (device, renderer, target) = uvRenderer()
		renderer.renderAtlasPage(target, 0, viewportSize, viewportSize)
		device.clearLog()

		renderer.renderAtlasPage(target, 0, viewportSize, viewportSize)

		assertEquals(listOf(baselinePass), describe(device, target))
		assertTrue(device.resourceEvents.isEmpty(), "a repeated page render touches no resource")

		val layer = DecodedImage(ByteArray(8 * 8 * 4), 8, 8)
		device.clearLog()
		renderer.renderUnderlayImage(target, layer, viewportSize, viewportSize)
		assertEquals(listOf(baselinePass), describe(device, target), "a layer draws the same pass")
		assertEquals(1, device.resourceEvents.filterIsInstance<TextureCreated>().size, "a layer's texture is made on first sight")
		device.clearLog()
		renderer.renderUnderlayImage(target, layer, viewportSize, viewportSize)
		assertTrue(device.resourceEvents.isEmpty(), "and only then")
	}

	/**
	 * The UV grid carries the shown surface's rectangle (the page, the layer, or the unit square with
	 * nothing shown) and the border width in framebuffer pixels; the 2D grid carries neither.
	 */
	@Test
	fun theUvGridCarriesItsSurface() {
		val (device, renderer, target) = uvRenderer()
		renderer.setRenderScale(2f)
		renderer.setGrid(GridColors.Classic.copy(frameWidthPx = 1.5f), 64f, 4)

		device.clearLog()
		renderer.renderAtlasPage(target, 0, viewportSize, viewportSize)
		val pageGrid = gridDraw(device)
		assertEquals(ContentBounds(0f, 0f, 16f, 16f), pageGrid.uniforms.surface, "a page bounds the grid by its texels")
		assertEquals(3f, pageGrid.uniforms.frameWidthPx, "the border is its display width times the render scale")

		device.clearLog()
		renderer.renderUnderlayImage(target, DecodedImage(ByteArray(8 * 12 * 4), 8, 12), viewportSize, viewportSize)
		assertEquals(ContentBounds(0f, 0f, 8f, 12f), gridDraw(device).uniforms.surface, "a layer bounds it by its own size")

		device.clearLog()
		renderer.renderAtlasPage(target, null, viewportSize, viewportSize)
		assertEquals(ContentBounds(0f, 0f, 1f, 1f), gridDraw(device).uniforms.surface, "nothing shown bounds it by the unit square")

		device.clearLog()
		renderer.render(target, viewportSize, viewportSize)
		val puppetGrid = gridDraw(device)
		assertNull(puppetGrid.uniforms.surface, "the 2D grid is unbounded")
		assertEquals(0f, puppetGrid.uniforms.frameWidthPx, "and draws no border")
	}

	/**
	 * An Edit overlay draws after the page in the same pass, from positions uploaded into a store of the
	 * area's own between frames: no capture pass, no barrier.
	 */
	@Test
	fun anEditOverlayDrawsAfterThePageInTheSamePass() {
		val (device, renderer, target) = uvRenderer()
		val positions = quadPositions(2f, 2f)
		val overlay = direct(listOf(overlayMesh("art")), mapOf("art" to positions))
		device.clearLog()

		renderer.renderAtlasPage(target, 0, viewportSize, viewportSize, "uv-1", overlay)

		assertEquals(listOf(editPass), describe(device, target), "one pass: grid, page, then the overlay")
		assertTrue(device.capturePasses().isEmpty(), "direct positions are never captured")
		assertTrue(device.steps.none { step -> step is RecordedBarrier }, "so nothing waits on a capture")
		val created = device.resourceEvents.filterIsInstance<StoreCreated>().single()
		assertEquals(4, created.store.vertexCapacity, "the store holds the overlay's vertices")
		val upload = device.resourceEvents.filterIsInstance<StorePositionsUpdated>().single()
		assertSame(created.store, upload.store, "the positions go into that store")
		assertEquals(0, upload.vertexOffset)
		assertSame(positions, upload.positions, "as the overlay's own array")
		assertTrue(device.overlayDraws().all { draw -> draw.store === created.store && draw.baseOffset == 0 }, "and every overlay draw reads them there")

		device.clearLog()
		renderer.renderUnderlayImage(target, DecodedImage(ByteArray(8 * 8 * 4), 8, 8), viewportSize, viewportSize, "uv-1", overlay)
		assertEquals(listOf(editPass), describe(device, target), "a layer carries the overlay the same way")
		assertTrue(device.resourceEvents.none { event -> event is StorePositionsUpdated || event is OverlayBuffersCreated }, "with nothing uploaded again")
	}

	/**
	 * Positions upload only for a position array the area has not uploaded: the same arrays in a new value
	 * upload nothing, one moved mesh uploads its own region alone, a selection change updates flags only,
	 * and a grown overlay re-uploads everything into a larger store.
	 */
	@Test
	fun positionsUploadOnlyWhenTheirArrayChanges() {
		val (device, renderer, target) = uvRenderer()
		val firstPositions = quadPositions(1f, 1f)
		val secondPositions = quadPositions(8f, 8f)
		val firstMesh = overlayMesh("first")
		val secondMesh = overlayMesh("second")
		device.clearLog()
		renderer.renderAtlasPage(target, 0, viewportSize, viewportSize, "uv-1", direct(listOf(firstMesh, secondMesh), mapOf("first" to firstPositions, "second" to secondPositions)))
		assertEquals(listOf(0, 4), uploads(device).map { upload -> upload.vertexOffset }, "the first sight uploads both meshes")

		device.clearLog()
		renderer.renderAtlasPage(target, 0, viewportSize, viewportSize, "uv-1", direct(listOf(firstMesh, secondMesh), mapOf("first" to firstPositions, "second" to secondPositions)))
		assertTrue(device.resourceEvents.isEmpty(), "a new value over the same arrays uploads nothing")

		val movedPositions = quadPositions(9f, 9f)
		device.clearLog()
		renderer.renderAtlasPage(target, 0, viewportSize, viewportSize, "uv-1", direct(listOf(firstMesh, secondMesh), mapOf("first" to firstPositions, "second" to movedPositions)))
		val moved = uploads(device).single()
		assertEquals(4, moved.vertexOffset, "a moved mesh uploads its own region")
		assertSame(movedPositions, moved.positions, "from its new array")
		assertTrue(device.resourceEvents.none { event -> event is OverlayBuffersCreated || event is StoreCreated }, "and nothing else")

		val selectedFlags = byteArrayOf(OVERLAY_FLAG_SELECTED, 0, 0, 0)
		device.clearLog()
		renderer.renderAtlasPage(
			target,
			0,
			viewportSize,
			viewportSize,
			"uv-1",
			direct(listOf(overlayMesh("first", selectedFlags), secondMesh), mapOf("first" to firstPositions, "second" to movedPositions)),
		)
		assertEquals(1, device.resourceEvents.filterIsInstance<OverlayFlagsUpdated>().size, "a selection change updates that mesh's flags")
		assertTrue(uploads(device).isEmpty(), "and uploads no position")

		val thirdPositions = quadPositions(4f, 12f)
		device.clearLog()
		renderer.renderAtlasPage(
			target,
			0,
			viewportSize,
			viewportSize,
			"uv-1",
			direct(listOf(firstMesh, secondMesh, overlayMesh("third")), mapOf("first" to firstPositions, "second" to movedPositions, "third" to thirdPositions)),
		)
		assertEquals(1, device.resourceEvents.filterIsInstance<StoreDestroyed>().size, "an outgrown store is freed")
		assertEquals(12, device.resourceEvents.filterIsInstance<StoreCreated>().single().store.vertexCapacity, "for one that holds every vertex")
		assertEquals(listOf(0, 4, 8), uploads(device).map { upload -> upload.vertexOffset }, "and every mesh is uploaded into it")
	}

	/** Two UV areas keep a residency each, so alternating between them uploads nothing once both are seen. */
	@Test
	fun eachAreaKeepsItsOwnResidency() {
		val (device, renderer, target) = uvRenderer()
		val leftOverlay = direct(listOf(overlayMesh("left")), mapOf("left" to quadPositions(1f, 1f)))
		val rightOverlay = direct(listOf(overlayMesh("right")), mapOf("right" to quadPositions(9f, 9f)))
		renderer.renderAtlasPage(target, 0, viewportSize, viewportSize, "uv-1", leftOverlay)
		renderer.renderAtlasPage(target, 0, viewportSize, viewportSize, "uv-2", rightOverlay)

		device.clearLog()
		renderer.renderAtlasPage(target, 0, viewportSize, viewportSize, "uv-1", leftOverlay)
		val leftStores = device.overlayDraws().map { draw -> draw.store }.distinct()
		device.clearLog()
		renderer.renderAtlasPage(target, 0, viewportSize, viewportSize, "uv-2", rightOverlay)
		val rightStores = device.overlayDraws().map { draw -> draw.store }.distinct()
		renderer.renderAtlasPage(target, 0, viewportSize, viewportSize, "uv-1", leftOverlay)

		assertTrue(device.resourceEvents.isEmpty(), "alternating areas re-upload nothing")
		assertEquals(1, leftStores.size)
		assertEquals(1, rightStores.size)
		assertNotSame(leftStores.single(), rightStores.single(), "each area draws from a store of its own")
	}

	/**
	 * Retaining frees the dropped areas' stores and buffers and leaves the kept ones alone; an area that
	 * stops showing an overlay frees its buffers and keeps its store; disposing frees every store.
	 */
	@Test
	fun retainingFreesTheDroppedAreas() {
		val (device, renderer, target) = uvRenderer()
		val keptOverlay = direct(listOf(overlayMesh("kept")), mapOf("kept" to quadPositions(1f, 1f)))
		val droppedOverlay = direct(listOf(overlayMesh("dropped")), mapOf("dropped" to quadPositions(9f, 9f)))
		device.clearLog()
		renderer.renderAtlasPage(target, 0, viewportSize, viewportSize, "uv-1", keptOverlay)
		val keptStore = storeOf(device)
		device.clearLog()
		renderer.renderAtlasPage(target, 0, viewportSize, viewportSize, "uv-2", droppedOverlay)
		val droppedStore = storeOf(device)
		val droppedBuffers = device.overlayDraws().map { draw -> draw.buffers }.distinct().single()

		device.clearLog()
		renderer.retainUvScenes { key -> key == "uv-1" }
		assertEquals(listOf(droppedStore), device.resourceEvents.filterIsInstance<StoreDestroyed>().map { event -> event.store }, "the dropped area's store is freed")
		assertEquals(listOf(droppedBuffers), device.resourceEvents.filterIsInstance<OverlayBuffersDestroyed>().map { event -> event.buffers }, "with its buffers")

		device.clearLog()
		renderer.renderAtlasPage(target, 0, viewportSize, viewportSize, "uv-1", keptOverlay)
		assertTrue(device.resourceEvents.isEmpty(), "the kept area is untouched")

		device.clearLog()
		renderer.renderAtlasPage(target, 0, viewportSize, viewportSize, "uv-1", null)
		assertEquals(listOf(baselinePass), describe(device, target), "an area showing no overlay draws the baseline")
		assertEquals(1, device.resourceEvents.filterIsInstance<OverlayBuffersDestroyed>().size, "and frees its buffers")
		assertTrue(device.resourceEvents.none { event -> event is StoreDestroyed }, "but keeps its store")

		device.clearLog()
		renderer.renderAtlasPage(target, 0, viewportSize, viewportSize, "uv-1", keptOverlay)
		assertTrue(device.resourceEvents.none { event -> event is StoreCreated }, "so the overlay's return reuses it")
		assertEquals(1, uploads(device).size, "re-uploading its positions")

		device.clearLog()
		renderer.disposeGl()
		assertTrue(device.resourceEvents.filterIsInstance<StoreDestroyed>().any { event -> event.store === keptStore }, "disposing frees every area's store")
	}

	/**
	 * The puppet's overlay and a UV area's overlay keep separate stores: rendering one never re-captures,
	 * re-uploads, or frees the other's.
	 */
	@Test
	fun theUvAndPuppetOverlaysDoNotDisturbEachOther() {
		val device = RecordingRenderDevice()
		val page = DecodedImage(ByteArray(16 * 16 * 4), 16, 16)
		val renderer = PuppetRenderer(puppetModel(), PuppetTextures(listOf(page), emptyMap(), premultipliedAlpha = false), device)
		renderer.initGl()
		renderer.setCamera(ViewportCamera(0f, 0f, 1f))
		renderer.setPose(emptyMap())
		val target = device.createRenderTarget(RenderTargetSpec(viewportSize, viewportSize, TextureFormat.Rgba8, sampled = true)) as RecordedTarget
		renderer.setMeshOverlay(MeshOverlay(MeshOverlayKind.Edit, MeshOverlaySelectMode.Vertex, listOf(overlayMesh("art")), sizes))
		val uvOverlay = direct(listOf(overlayMesh("art")), mapOf("art" to quadPositions(2f, 2f)))
		device.clearLog()
		renderer.render(target, viewportSize, viewportSize)
		val puppetStore = storeOf(device)
		device.clearLog()
		renderer.renderAtlasPage(target, 0, viewportSize, viewportSize, "uv-1", uvOverlay)
		val uvStore = storeOf(device)
		assertNotSame(puppetStore, uvStore, "the UV overlay uploads into a store of its own")
		assertTrue(device.capturePasses().isEmpty(), "and its render captures nothing")

		device.clearLog()
		renderer.render(target, viewportSize, viewportSize)
		assertTrue(device.capturePasses().isEmpty(), "a UV render leaves the puppet's capture valid")
		assertTrue(device.resourceEvents.isEmpty(), "and its buffers resident")
		assertSame(puppetStore, storeOf(device))

		device.clearLog()
		renderer.renderAtlasPage(target, 0, viewportSize, viewportSize, "uv-1", uvOverlay)
		assertTrue(device.resourceEvents.isEmpty(), "a puppet render leaves the UV area's uploads in place")
		assertSame(uvStore, storeOf(device))

		device.clearLog()
		renderer.setMeshOverlay(null)
		renderer.render(target, viewportSize, viewportSize)
		renderer.renderAtlasPage(target, 0, viewportSize, viewportSize, "uv-1", uvOverlay)
		assertTrue(device.resourceEvents.filterIsInstance<StoreDestroyed>().none { event -> event.store === uvStore }, "dropping the puppet's overlay frees nothing of the UV area's")
		assertEquals(3, device.overlayDraws().size, "which still draws")
	}

	/** A direct mesh whose arrays disagree with it is left out: alone, the frame is the baseline. */
	@Test
	fun aDirectMeshThatDisagreesIsSkipped() {
		val (device, renderer, target) = uvRenderer()
		val shortPositions = floatArrayOf(1f, 1f, 5f, 1f, 1f, 5f)
		device.clearLog()

		renderer.renderAtlasPage(target, 0, viewportSize, viewportSize, "uv-1", direct(listOf(overlayMesh("short")), mapOf("short" to shortPositions)))

		assertEquals(listOf(baselinePass), describe(device, target), "nothing of the overlay reaches the frame")
		assertTrue(
			device.resourceEvents.none { event -> event is StoreCreated || event is OverlayBuffersCreated || event is StorePositionsUpdated },
			"and nothing is uploaded for it",
		)

		device.clearLog()
		renderer.renderAtlasPage(
			target,
			0,
			viewportSize,
			viewportSize,
			"uv-1",
			direct(listOf(overlayMesh("short"), overlayMesh("whole")), mapOf("short" to shortPositions, "whole" to quadPositions(8f, 8f))),
		)
		assertEquals(listOf(editPass), describe(device, target), "a whole mesh beside it still draws")
		assertTrue(device.overlayDraws().all { draw -> draw.baseOffset == 0 }, "from the start of the store")
	}

	/** A page or a layer is the quad diag(W, H) over its whole image, sampled at the identity. */
	@Test
	fun thePageQuadIsTheDiagonalAffine() {
		val (device, renderer, target) = uvRenderer()
		device.clearLog()
		renderer.renderAtlasPage(target, 0, viewportSize, viewportSize)
		val page = quadDraws(device).single()
		assertEquals(listOf(16f, 0f, 0f, 0f, 16f, 0f), page.quadToWorld, "the page's quad")
		assertEquals(identitySample, page.uvAffine, "sampled at the identity")
		assertEquals(16, assertNotNull(page.texture).width)

		device.clearLog()
		renderer.renderUnderlayImage(target, DecodedImage(ByteArray(8 * 12 * 4), 8, 12), viewportSize, viewportSize)
		assertEquals(listOf(8f, 0f, 0f, 0f, 12f, 0f), quadDraws(device).single().quadToWorld, "a layer's")
	}

	/**
	 * An islands overlay draws island by island in its own order (the producer's back to front), each
	 * island's fill and then its edges in the colors of its roles, with no dots.
	 */
	@Test
	fun anIslandsOverlayDrawsIslandMajorBackToFront() {
		val (device, renderer, target) = uvRenderer()
		val palette = MeshOverlayPalette.Classic
		renderer.setMeshOverlayPalette(palette)
		val islands =
			islandsOf(
				"back" to IslandStyle(IslandFillRole.Idle, IslandEdgeRole.Idle),
				"middle" to IslandStyle(IslandFillRole.Selected, IslandEdgeRole.Active),
				"front" to IslandStyle(IslandFillRole.Selected, IslandEdgeRole.Warning),
			)
		device.clearLog()

		renderer.renderAtlasPage(target, 0, viewportSize, viewportSize, "uv-1", islands)

		assertEquals(
			listOf("pass main DontCare scissor=null [grid, page" + ", overlay OverlayFaceFill, overlay OverlayEdge".repeat(3) + "]"),
			describe(device, target),
			"each island fills then outlines before the next",
		)
		val draws = device.overlayDraws()
		assertEquals(listOf(0, 0, 4, 4, 8, 8), draws.map { draw -> draw.baseOffset }, "in the overlay's order")
		assertEquals(
			listOf(palette.faceIdle, palette.edgeIdle, palette.faceSelected, palette.edgeActive, palette.faceSelected, palette.warning).map(::channels),
			draws.map { draw -> draw.idleColor },
			"each in its roles' colors",
		)
		assertTrue(draws.all { draw -> draw.fillIdle }, "every face of an island fills")

		val pinned = MeshOverlayPalette.Classic.copy(pinnedPlacement = OverlayColor(0.1f, 0.2f, 0.3f, 1f))
		renderer.setMeshOverlayPalette(pinned)
		device.clearLog()
		renderer.renderAtlasPage(target, 0, viewportSize, viewportSize, "uv-1", islandsOf("only" to IslandStyle(IslandFillRole.Idle, IslandEdgeRole.Pinned)))
		assertEquals(channels(pinned.pinnedPlacement), device.overlayDraws()[1].idleColor, "the pinned role takes the palette's pinned color")
	}

	/** A placement preview draws over the page and under the islands: the scrims, the crops, then the ghost's crops. */
	@Test
	fun thePlacementPreviewDrawsUnderTheIslands() {
		val (device, renderer, target) = uvRenderer()
		val scrim = OverlayColor(0f, 0f, 0f, 0.5f)
		val oldSpot = floatArrayOf(4f, 0f, 2f, 0f, -4f, 10f)
		val newSpot = floatArrayOf(4f, 0f, 9f, 0f, -4f, 10f)
		val ghostSpot = floatArrayOf(0f, 4f, 1f, -4f, 0f, 14f)
		val placement =
			PlacementPreview(
				scrim,
				listOf(oldSpot),
				listOf(PlacementCropQuad("moving", cropImage(), newSpot, floatArrayOf(0.5f, 0f, 0.25f, 0f, 0.5f, 0.25f))),
				null,
				listOf(PlacementCropQuad("ghost", cropImage(), ghostSpot, identitySample.toFloatArray())),
			)
		device.clearLog()

		renderer.renderAtlasPage(target, 0, viewportSize, viewportSize, "uv-1", islandsOf("only" to null), placement)

		assertEquals(
			listOf("pass main DontCare scissor=null [grid, page, scrim, crop, crop, overlay OverlayFaceFill, overlay OverlayEdge]"),
			describe(device, target),
		)
		val quads = quadDraws(device)
		assertEquals(oldSpot.toList(), quads[1].quadToWorld, "the scrim covers the old spot")
		assertEquals(channels(scrim), quads[1].drawColor, "in the scrim color")
		assertNull(quads[1].texture, "flat")
		assertEquals(newSpot.toList(), quads[2].quadToWorld, "the crop draws at its new spot")
		assertEquals(identitySample, quads[2].uvAffine, "an uploaded crop is the trim itself, sampled at the identity")
		assertEquals(ghostSpot.toList(), quads[3].quadToWorld, "the ghost at its committed spot")
		assertEquals(2, device.resourceEvents.filterIsInstance<TextureCreated>().size, "each crop uploaded once")
	}

	/** A crop whose tile has a resident layer texture samples the tile through it and uploads nothing. */
	@Test
	fun aResidentLayerTextureServesTheCrop() {
		val (device, renderer, target) = uvRenderer()
		val tile = DecodedImage(ByteArray(16 * 16 * 4), 16, 16)
		renderer.setSourceLayerPlan(LayerDrawPlan(emptyMap(), mapOf("tileA" to 16L * 16L * 4L)))
		renderer.deliverSourceLayerRasters(LayerRasterBatch(mapOf("tileA" to tile)))
		val layerTexture = device.resourceEvents.filterIsInstance<TextureCreated>().last().texture
		val sampleAffine = floatArrayOf(0.5f, 0f, 0.25f, 0f, 0.5f, 0.25f)
		val placement = PlacementPreview(OverlayColor(0f, 0f, 0f, 0.5f), emptyList(), listOf(PlacementCropQuad("tileA", cropImage(), floatArrayOf(8f, 0f, 2f, 0f, 8f, 2f), sampleAffine)), null, emptyList())
		device.clearLog()

		renderer.renderAtlasPage(target, 0, viewportSize, viewportSize, "uv-1", null, placement)

		val crop = quadDraws(device)[1]
		assertSame(layerTexture, crop.texture, "the crop samples the tile's layer texture")
		assertEquals(sampleAffine.toList(), crop.uvAffine, "through the trim's place in the tile")
		assertTrue(device.resourceEvents.none { event -> event is TextureCreated }, "and uploads nothing")
	}

	/** An uploaded crop is made once per image and freed on the first render that no longer shows it, or with the scene. */
	@Test
	fun anUploadedCropLivesAsLongAsItIsShown() {
		val (device, renderer, target) = uvRenderer()
		val first = cropImage()
		device.clearLog()
		renderer.renderAtlasPage(target, 0, viewportSize, viewportSize, "uv-1", null, cropPreview(first))
		val firstTexture = device.resourceEvents.filterIsInstance<TextureCreated>().single().texture

		device.clearLog()
		renderer.renderAtlasPage(target, 0, viewportSize, viewportSize, "uv-1", null, cropPreview(first))
		assertTrue(device.resourceEvents.isEmpty(), "a new preview over the same crop image uploads nothing")

		val second = cropImage()
		device.clearLog()
		renderer.renderAtlasPage(target, 0, viewportSize, viewportSize, "uv-1", null, cropPreview(second))
		assertEquals(1, device.resourceEvents.filterIsInstance<TextureCreated>().size, "a new crop image uploads")
		assertEquals(listOf(firstTexture), device.resourceEvents.filterIsInstance<TextureDestroyed>().map { event -> event.texture }, "and the one no longer shown is freed")
		val secondTexture = device.resourceEvents.filterIsInstance<TextureCreated>().single().texture

		device.clearLog()
		renderer.renderAtlasPage(target, 0, viewportSize, viewportSize, "uv-1")
		assertEquals(listOf(secondTexture), device.resourceEvents.filterIsInstance<TextureDestroyed>().map { event -> event.texture }, "no preview frees it")
		assertEquals(listOf(baselinePass), describe(device, target), "and draws nothing of it")

		renderer.renderAtlasPage(target, 0, viewportSize, viewportSize, "uv-1", null, cropPreview(first))
		device.clearLog()
		renderer.retainUvScenes { false }
		assertEquals(1, device.resourceEvents.filterIsInstance<TextureDestroyed>().size, "a dropped area frees its crops")
	}

	/** A new islands value over the same arrays (a selection change re-styles islands) uploads nothing. */
	@Test
	fun anIslandSelectionChangeUploadsNothing() {
		val (device, renderer, target) = uvRenderer()
		val positions = mapOf("left" to quadPositions(1f, 1f), "right" to quadPositions(8f, 8f))
		renderer.renderAtlasPage(target, 0, viewportSize, viewportSize, "uv-1", islandsOver(positions, mapOf("left" to null, "right" to null)))
		device.clearLog()

		val restyled = IslandStyle(IslandFillRole.Selected, IslandEdgeRole.Active)
		renderer.renderAtlasPage(target, 0, viewportSize, viewportSize, "uv-1", islandsOver(positions, mapOf("left" to restyled, "right" to null)))

		assertTrue(device.resourceEvents.isEmpty(), "no flags, buffers, or positions upload for a re-style")
		assertEquals(channels(MeshOverlayPalette.Classic.edgeActive), device.overlayDraws()[1].idleColor, "but the island draws in its new role")
	}

	/**
	 * A recording renderer over an empty model with one 16x16 page, behind a camera centered on the page at
	 * zoom 2, and its target.
	 *
	 * @return Triple<RecordingRenderDevice, PuppetRenderer, RecordedTarget> The device, the renderer, and
	 *   the target.
	 */
	private fun uvRenderer(): Triple<RecordingRenderDevice, PuppetRenderer, RecordedTarget> {
		val device = RecordingRenderDevice()
		val page = DecodedImage(ByteArray(16 * 16 * 4), 16, 16)
		val renderer = PuppetRenderer(emptyModel(), PuppetTextures(listOf(page), emptyMap(), premultipliedAlpha = false), device)
		renderer.initGl()
		renderer.setCamera(ViewportCamera(8f, 8f, 2f))
		val target = device.createRenderTarget(RenderTargetSpec(viewportSize, viewportSize, TextureFormat.Rgba8, sampled = true)) as RecordedTarget
		return Triple(device, renderer, target)
	}

	/**
	 * A model with nothing in it: a UV scene draws no drawable.
	 *
	 * @return PuppetModel The model.
	 */
	private fun emptyModel(): PuppetModel = PuppetModel(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null)

	/**
	 * A model with one flat quad, "art", at its rest positions, for the puppet's own overlay.
	 *
	 * @return PuppetModel The model.
	 */
	private fun puppetModel(): PuppetModel {
		val parameterId = ParameterId("A")
		val positions = floatArrayOf(-16f, 8f, 16f, 8f, -16f, 30f, 16f, 30f)
		val art =
			Drawable(
				id = DrawableId("art"),
				name = "art",
				parentDeformerId = null,
				blendMode = BlendMode.Normal,
				maskedBy = emptyList(),
				mesh = DrawableMesh.withLocalEqualToCanvas(positions, FloatArray(positions.size), quadIndices),
				geometryGrid =
					KeyformGrid(
						listOf(KeyformAxis(parameterId, floatArrayOf(0f))),
						listOf(KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(positions.size)))),
					),
			)
		return PuppetModel(
			parameters = listOf(Parameter(parameterId, "A", -1f, 1f, 0f)),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = listOf(art),
			rootChildren = listOf(OrgChild.Drawable(art.id)),
			rootPartId = null,
		).withDerivedRenderRoot()
	}

	/**
	 * A 4x4 quad's display positions with its bottom-left corner at ([left], [bottom]).
	 *
	 * @param Float left The left edge in surface texels.
	 * @param Float bottom The bottom edge in surface texels, y up.
	 * @return FloatArray The positions, a new array each call so a mesh can be told apart by reference.
	 */
	private fun quadPositions(left: Float, bottom: Float): FloatArray = floatArrayOf(left, bottom, left + 4f, bottom, left, bottom + 4f, left + 4f, bottom + 4f)

	/**
	 * An overlay entry over a quad, idle unless [vertexFlags] says otherwise.
	 *
	 * @param String id The drawable id.
	 * @param ByteArray vertexFlags The vertex flags.
	 * @return MeshOverlayMesh The entry.
	 */
	private fun overlayMesh(id: String, vertexFlags: ByteArray = ByteArray(4)): MeshOverlayMesh =
		MeshOverlayMesh(DrawableId(id), 4, quadEdges, vertexFlags, ByteArray(quadEdges.size / 2), ByteArray(2), null, null, null)

	/**
	 * An Edit overlay over direct positions, every mesh triangulated as the quad.
	 *
	 * @param List<MeshOverlayMesh> meshes The entries, in layout order.
	 * @param Map<String, FloatArray> positions Each entry's display positions, by drawable id.
	 * @return DirectMeshOverlay The overlay.
	 */
	private fun direct(meshes: List<MeshOverlayMesh>, positions: Map<String, FloatArray>): DirectMeshOverlay =
		DirectMeshOverlay(
			MeshOverlay(MeshOverlayKind.Edit, MeshOverlaySelectMode.Vertex, meshes, sizes),
			positions.mapKeys { (id, _) -> DrawableId(id) },
			meshes.associate { mesh -> mesh.drawableId to quadIndices },
		)

	/**
	 * An islands overlay of 4x4 quads side by side, in the order given, each with its style.
	 *
	 * @param Pair<String, IslandStyle?> islands Each island's id and style, back to front.
	 * @return DirectMeshOverlay The overlay.
	 */
	private fun islandsOf(vararg islands: Pair<String, IslandStyle?>): DirectMeshOverlay =
		islandsOver(islands.withIndex().associate { (islandIndex, island) -> island.first to quadPositions(islandIndex * 4f, 2f) }, islands.toMap())

	/**
	 * An islands overlay over given positions, in the styles' order.
	 *
	 * @param Map<String, FloatArray> positions Each island's display positions.
	 * @param Map<String, IslandStyle?> styles Each island's style, in layout order.
	 * @return DirectMeshOverlay The overlay.
	 */
	private fun islandsOver(positions: Map<String, FloatArray>, styles: Map<String, IslandStyle?>): DirectMeshOverlay {
		val meshes =
			styles.map { (id, style) ->
				MeshOverlayMesh(DrawableId(id), 4, quadEdges, ByteArray(0), ByteArray(0), ByteArray(0), null, null, null, style)
			}
		return DirectMeshOverlay(
			MeshOverlay(MeshOverlayKind.Islands, MeshOverlaySelectMode.Vertex, meshes, sizes),
			positions.mapKeys { (id, _) -> DrawableId(id) },
			meshes.associate { mesh -> mesh.drawableId to quadIndices },
		)
	}

	/**
	 * A 4x4 crop image, a new instance each call so crops tell apart by identity.
	 *
	 * @return DecodedImage The image.
	 */
	private fun cropImage(): DecodedImage = DecodedImage(ByteArray(4 * 4 * 4), 4, 4)

	/**
	 * A preview of one mover's crop and nothing else.
	 *
	 * @param DecodedImage crop The crop.
	 * @return PlacementPreview The preview.
	 */
	private fun cropPreview(crop: DecodedImage): PlacementPreview =
		PlacementPreview(OverlayColor(0f, 0f, 0f, 0.5f), emptyList(), listOf(PlacementCropQuad("moving", crop, floatArrayOf(4f, 0f, 2f, 0f, 4f, 2f), identitySample.toFloatArray())), null, emptyList())

	/**
	 * The image quads the device recorded, in issue order.
	 *
	 * @param RecordingRenderDevice device The device.
	 * @return List<RecordedQuadDraw> The quads.
	 */
	private fun quadDraws(device: RecordingRenderDevice): List<RecordedQuadDraw> = device.passes().flatMap { pass -> pass.draws.filterIsInstance<RecordedQuadDraw>() }

	/**
	 * A color's four channels as the recorder holds them.
	 *
	 * @param OverlayColor color The color.
	 * @return List<Float> Red, green, blue, alpha.
	 */
	private fun channels(color: OverlayColor): List<Float> = listOf(color.red, color.green, color.blue, color.alpha)

	/**
	 * What an image quad is, for the frame's description: a flat scrim, the surface's page (its quad the
	 * diagonal over the origin), or a crop.
	 *
	 * @param RecordedQuadDraw draw The quad.
	 * @return String The role.
	 */
	private fun quadRole(draw: RecordedQuadDraw): String {
		val quad = draw.quadToWorld
		return when {
			draw.texture == null -> "scrim"
			quad[1] == 0f && quad[2] == 0f && quad[3] == 0f && quad[5] == 0f -> "page"
			else -> "crop"
		}
	}

	/**
	 * The one grid draw the device recorded.
	 *
	 * @param RecordingRenderDevice device The device.
	 * @return RecordedGridDraw The draw.
	 */
	private fun gridDraw(device: RecordingRenderDevice): RecordedGridDraw = device.passes().flatMap { pass -> pass.draws.filterIsInstance<RecordedGridDraw>() }.single()

	/**
	 * The position uploads the device recorded, in issue order.
	 *
	 * @param RecordingRenderDevice device The device.
	 * @return List<StorePositionsUpdated> The uploads.
	 */
	private fun uploads(device: RecordingRenderDevice): List<StorePositionsUpdated> = device.resourceEvents.filterIsInstance<StorePositionsUpdated>()

	/**
	 * The one store the recorded overlay draws read.
	 *
	 * @param RecordingRenderDevice device The device.
	 * @return RecordedStore The store.
	 */
	private fun storeOf(device: RecordingRenderDevice): RecordedStore = device.overlayDraws().map { draw -> draw.store }.distinct().single()

	/**
	 * A frame's structure as comparable lines.
	 *
	 * @param RecordingRenderDevice device The device that recorded the frame.
	 * @param RecordedTarget target The target the frame rendered into.
	 * @return List<String> One line per recorded step.
	 */
	private fun describe(device: RecordingRenderDevice, target: RecordedTarget): List<String> =
		device.steps.map { step ->
			if (step is RecordedPass) {
				val role = if (step.target === target) "main" else "side"
				val draws =
					step.draws.joinToString(", ") { draw ->
						when (draw) {
							is RecordedGridDraw -> "grid"
							is RecordedQuadDraw -> quadRole(draw)
							is RecordedOverlayDraw -> "overlay ${draw.purpose}${if (draw.activeDraw) " active" else ""}"
							else -> "other"
						}
					}
				"pass $role ${step.spec.loadAction} scissor=${step.spec.scissor} [$draws]"
			} else {
				step::class.simpleName ?: "step"
			}
		}
}