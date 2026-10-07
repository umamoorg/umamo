package org.umamo.editor.desktop.viewport

import org.umamo.render.ContentBounds
import org.umamo.render.DecodedImage
import org.umamo.render.ViewportCamera
import org.umamo.render.puppet.DirectMeshOverlay
import org.umamo.render.puppet.MeshOverlay
import org.umamo.render.puppet.MeshOverlayKind
import org.umamo.render.puppet.MeshOverlaySelectMode
import org.umamo.render.puppet.MeshOverlaySizes
import org.umamo.render.puppet.OverlayColor
import org.umamo.render.puppet.PlacementPreview
import org.umamo.ui.viewport.UvSceneContent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the per-area scheduling matrix: every stamp matching is fresh; a size-only staleness waits on the
 * resize throttle while the size is in motion and renders at the interactive scale once the throttle
 * elapses or at the settle scale once the size holds still; any other staleness renders at once at the
 * scale the motion allows; an interactive frame stays size-stale so the settle pass restores quality;
 * a puppet area and a UV area each watch only their own versions; the passCamera compares by identity; and
 * the first size observation never stamps.  Pure over a hand-built slot, so no render thread is needed.
 */
class AreaFreshnessTest {
	private val passCamera = ViewportCamera.fit(ContentBounds(0f, 0f, 100f, 100f), 200, 100)

	// A timestamp with room below it, so "a window ago" never goes negative.
	private val now = RESIZE_SETTLE_NANOS * 100
	private val tick = RenderTick(paramsVersion = 7, settleScale = 2, interactiveScale = 1, nowNanos = now)
	private val puppetBump = 3L
	private val atlasBump = 5L

	/** A puppet area whose last render matches every input at 200 x 100, settled long ago. */
	private fun freshPuppetSlot(): AreaSlot =
		AreaSlot().apply {
			scene = RenderScene.Puppet2D
			renderedWidth = 200
			renderedHeight = 100
			renderedScale = 2
			renderedParamsVersion = 7
			renderedCamera = passCamera
			puppetRenderBumpDone = puppetBump
			atlasRenderBumpDone = -1
		}

	/** A UV area showing page 0 whose last render matches every input it watches, settled long ago. */
	private fun freshUvSlot(): AreaSlot =
		AreaSlot().apply {
			scene = RenderScene.UvScene
			uvContent = UvSceneContent.AtlasPage(0)
			renderedUvContent = UvSceneContent.AtlasPage(0)
			renderedWidth = 200
			renderedHeight = 100
			renderedScale = 2
			renderedParamsVersion = -1
			renderedCamera = passCamera
			puppetRenderBumpDone = -1
			atlasRenderBumpDone = atlasBump
		}

	private fun decide(slot: AreaSlot, width: Int = 200, height: Int = 100): AreaRenderDecision =
		decideAreaRender(slot, width, height, passCamera, tick, puppetBump, atlasBump)

	@Test
	fun everyStampMatchingIsFresh() {
		assertEquals(AreaRenderDecision.Fresh, decide(freshPuppetSlot()))
		assertEquals(AreaRenderDecision.Fresh, decide(freshUvSlot()))
	}

	@Test
	fun aSizeChangeInMotionUnderTheThrottleIsDeferred() {
		val slot =
			freshPuppetSlot().apply {
				sizeChangedNanos = now - 1
				resizeRenderNanos = now - 1
			}
		assertEquals(AreaRenderDecision.Deferred, decide(slot, width = 300))
	}

	@Test
	fun aSizeChangeInMotionPastTheThrottleRendersAtTheInteractiveScale() {
		val slot =
			freshPuppetSlot().apply {
				sizeChangedNanos = now - 1
				resizeRenderNanos = now - RESIZE_THROTTLE_NANOS
			}
		assertEquals(AreaRenderDecision.Render(1), decide(slot, width = 300))
	}

	@Test
	fun aSettledSizeChangeRendersAtTheSettleScaleWhateverTheThrottle() {
		val slot =
			freshPuppetSlot().apply {
				sizeChangedNanos = now - RESIZE_SETTLE_NANOS
				resizeRenderNanos = now - 1
			}
		assertEquals(AreaRenderDecision.Render(2), decide(slot, width = 300))
	}

	@Test
	fun aStaleRestDuringMotionRendersAtOnceAtTheInteractiveScale() {
		val slot =
			freshPuppetSlot().apply {
				renderedParamsVersion = 6
				sizeChangedNanos = now - 1
				resizeRenderNanos = now - 1
			}
		assertEquals(AreaRenderDecision.Render(1), decide(slot), "a pose change is never throttled")
	}

	@Test
	fun anInteractiveFrameStaysSizeStaleUntilTheSettlePass() {
		val slot = freshPuppetSlot().apply { renderedScale = 1 }
		assertEquals(AreaRenderDecision.Render(2), decide(slot), "the settled size re-renders at full quality")
	}

	@Test
	fun aPuppetAreaWatchesThePoseAndPuppetVersionsOnly() {
		assertEquals(AreaRenderDecision.Fresh, decide(freshPuppetSlot().apply { atlasRenderBumpDone = 999 }), "the atlas version is not its concern")
		assertEquals(AreaRenderDecision.Render(2), decide(freshPuppetSlot().apply { puppetRenderBumpDone = puppetBump - 1 }))
		assertEquals(AreaRenderDecision.Render(2), decide(freshPuppetSlot().apply { renderedParamsVersion = 6 }))
	}

	@Test
	fun aUvAreaWatchesItsSurfaceAndTheAtlasVersionOnly() {
		assertEquals(AreaRenderDecision.Fresh, decide(freshUvSlot().apply { puppetRenderBumpDone = 999 }), "the puppet version is not its concern")
		assertEquals(AreaRenderDecision.Render(2), decide(freshUvSlot().apply { atlasRenderBumpDone = atlasBump - 1 }))
		assertEquals(AreaRenderDecision.Render(2), decide(freshUvSlot().apply { uvContent = UvSceneContent.AtlasPage(1) }), "a page switch")
		val image = DecodedImage(ByteArray(4), 1, 1)
		val sameLayer =
			freshUvSlot().apply {
				uvContent = UvSceneContent.SourceLayer("layer", image)
				renderedUvContent = UvSceneContent.SourceLayer("layer", image)
			}
		assertEquals(AreaRenderDecision.Fresh, decide(sameLayer), "the same raster instance is the same surface")
		val redecoded = sameLayer.apply { uvContent = UvSceneContent.SourceLayer("layer", DecodedImage(ByteArray(4), 1, 1)) }
		assertEquals(AreaRenderDecision.Render(2), decide(redecoded), "a re-decoded raster is new pixels")
	}

	/**
	 * A UV area's overlay rides its content, so it is watched by the content's equality: the same overlay
	 * instance is fresh, an overlay appearing or a newly published one renders, and the surface the camera
	 * keys ignores it.
	 */
	@Test
	fun aUvAreaWatchesItsOverlayByIdentity() {
		val overlay = directOverlay()
		val shown =
			freshUvSlot().apply {
				uvContent = UvSceneContent.AtlasPage(0, overlay)
				renderedUvContent = UvSceneContent.AtlasPage(0, overlay)
			}
		assertEquals(AreaRenderDecision.Fresh, decide(shown), "the same overlay instance is fresh")
		assertEquals(AreaRenderDecision.Render(2), decide(freshUvSlot().apply { uvContent = UvSceneContent.AtlasPage(0, overlay) }), "an overlay appearing")
		assertEquals(AreaRenderDecision.Render(2), decide(shown.apply { uvContent = UvSceneContent.AtlasPage(0, directOverlay()) }), "a newly published overlay, however alike")
		assertEquals(UvSceneContent.AtlasPage(0).surfaceId, UvSceneContent.AtlasPage(0, overlay).surfaceId, "the camera keys the surface alone")
		assertEquals(
			UvSceneContent.SourceLayer("layer", null).surfaceId,
			UvSceneContent.SourceLayer("layer", null, overlay).surfaceId,
			"a layer's too",
		)
	}

	/** A page's placement preview rides its content the same way: the same instance is fresh, a new one renders. */
	@Test
	fun aUvAreaWatchesItsPlacementByIdentity() {
		val placement = placementPreview()
		val shown =
			freshUvSlot().apply {
				uvContent = UvSceneContent.AtlasPage(0, placement = placement)
				renderedUvContent = UvSceneContent.AtlasPage(0, placement = placement)
			}
		assertEquals(AreaRenderDecision.Fresh, decide(shown), "the same preview instance is fresh")
		assertEquals(AreaRenderDecision.Render(2), decide(shown.apply { uvContent = UvSceneContent.AtlasPage(0, placement = placementPreview()) }), "a new drive's preview renders")
		assertEquals(UvSceneContent.AtlasPage(0).surfaceId, UvSceneContent.AtlasPage(0, placement = placement).surfaceId, "the camera keys the surface alone")
	}

	/**
	 * An empty placement preview, a new instance each call.
	 *
	 * @return PlacementPreview The preview.
	 */
	private fun placementPreview(): PlacementPreview = PlacementPreview(OverlayColor(0f, 0f, 0f, 0.5f), emptyList(), emptyList(), null, emptyList())

	/**
	 * An empty direct overlay, a new instance each call.
	 *
	 * @return DirectMeshOverlay The overlay.
	 */
	private fun directOverlay(): DirectMeshOverlay =
		DirectMeshOverlay(MeshOverlay(MeshOverlayKind.Edit, MeshOverlaySelectMode.Vertex, emptyList(), MeshOverlaySizes(3.5f, 1f, 2.5f)), emptyMap(), emptyMap())

	@Test
	fun theCameraIsComparedByIdentity() {
		val slot = freshPuppetSlot().apply { renderedCamera = passCamera.copy() }
		assertEquals(AreaRenderDecision.Render(2), decide(slot))
	}

	@Test
	fun theFirstObservationDoesNotStampAndALaterChangeDoes() {
		val slot = AreaSlot()
		observeAreaSize(slot, 200, 100, now)
		assertEquals(listOf(200, 100), listOf(slot.observedWidth, slot.observedHeight))
		assertEquals(0L, slot.sizeChangedNanos, "a newly opened area counts as settled")
		observeAreaSize(slot, 300, 100, now + 1)
		assertEquals(now + 1, slot.sizeChangedNanos, "a real change restarts the settle window")
		observeAreaSize(slot, 300, 100, now + 2)
		assertEquals(now + 1, slot.sizeChangedNanos, "an unchanged size leaves the stamp alone")
	}
}