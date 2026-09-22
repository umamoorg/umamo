package org.umamo.editor.desktop.viewport

import org.umamo.render.ContentBounds
import org.umamo.render.DecodedImage
import org.umamo.render.ViewportCamera
import org.umamo.ui.viewport.AreaCameraKey
import org.umamo.ui.viewport.CameraSurface
import org.umamo.ui.viewport.UvSceneContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins how an area's scene routing is published, which decides what the render loop draws it as and
 * which content bounds its camera fits.
 *
 * These are pure state assertions - no GL, no render thread - because the routing is pure state.  The
 * engine reads exactly these fields to choose between drawing the posed puppet and drawing a flat UV
 * surface, so a slot that reads as the wrong kind sends an area to the wrong renderer and fits it to
 * the wrong bounds, which shows up as artwork that is simply in the wrong place.
 */
class ViewportAreaRegistryTest {
	private fun image(width: Int = 4, height: Int = 4): DecodedImage =
		DecodedImage(ByteArray(width * height * 4), width, height)

	/**
	 * A registered UV area reads as a UV scene, not as a puppet.
	 *
	 * The regression this exists for: the kind and the content were once published as separate fields,
	 * and collapsing them into one value dropped the kind write.  Every UV area then still read as
	 * Puppet2D, so the engine fit each one to the PUPPET's rest bounds instead of the page rectangle and
	 * the atlas landed far off origin - while every existing test stayed green, because nothing covered
	 * this routing at all.
	 */
	@Test
	fun registeringAUvSceneMarksTheAreaAsUv() {
		val registry = ViewportAreaRegistry()
		registry.registerUvScene("uv", UvSceneContent.AtlasPage(0), null)
		val slot = registry.areas.getValue("uv")
		assertEquals(RenderScene.UvScene, slot.scene, "a UV area must not read as a puppet area")
		assertEquals(UvSceneContent.AtlasPage(0), slot.uvContent, "and it carries the content it registered with")
	}

	/** A puppet area stays a puppet area, and carries no UV surface. */
	@Test
	fun registeringAPuppetAreaLeavesItAPuppet() {
		val registry = ViewportAreaRegistry()
		registry.register("puppet")
		val slot = registry.areas.getValue("puppet")
		assertEquals(RenderScene.Puppet2D, slot.scene, "the puppet area keeps the puppet scene")
		assertNull(slot.uvContent, "and has no UV surface to draw")
	}

	/**
	 * Retargeting a UV area replaces its whole surface, kind and payload together.
	 *
	 * A page and a layer are addressed differently - one by index into the document's uploaded pages,
	 * one by pixels the engine has never seen - so a switch that carried over half of the old choice
	 * would draw one surface while the freshness test spoke for the other.
	 */
	@Test
	fun retargetingReplacesTheWholeSurface() {
		val registry = ViewportAreaRegistry()
		registry.registerUvScene("uv", UvSceneContent.AtlasPage(0), null)
		val slot = registry.areas.getValue("uv")

		val layer = image()
		registry.setUvSceneContent("uv", UvSceneContent.SourceLayer("layer", layer), null)
		assertEquals(UvSceneContent.SourceLayer("layer", layer), slot.uvContent, "the layer view replaces the page view whole")
		assertEquals(RenderScene.UvScene, slot.scene, "and it is still a UV area")

		registry.setUvSceneContent("uv", UvSceneContent.AtlasPage(2), null)
		assertEquals(UvSceneContent.AtlasPage(2), slot.uvContent, "and switching back replaces it whole again")
	}

	/**
	 * Retargeting never turns a puppet area into a UV one.
	 *
	 * The puppet / UV split is fixed at registration; only the surface within the UV family moves.
	 */
	@Test
	fun retargetingIgnoresAPuppetArea() {
		val registry = ViewportAreaRegistry()
		registry.register("puppet")
		registry.setUvSceneContent("puppet", UvSceneContent.AtlasPage(1), null)
		val slot = registry.areas.getValue("puppet")
		assertEquals(RenderScene.Puppet2D, slot.scene, "a puppet area is not retargetable into a UV scene")
		assertNull(slot.uvContent, "and takes on no UV surface")
	}

	/** An unregistered area is a no-op rather than a crash, since requests can outlive their area. */
	@Test
	fun retargetingAnUnknownAreaDoesNothing() {
		val registry = ViewportAreaRegistry()
		registry.setUvSceneContent("gone", UvSceneContent.AtlasPage(0), null)
		assertTrue(registry.areas.isEmpty(), "no slot is conjured for an area that never registered")
	}

	/**
	 * A UV area survives the register-before-unregister overlap a tree collapse produces.
	 *
	 * Closing a split sibling rebuilds the surviving area's leaf under a fresh composition node, so the
	 * new register runs BEFORE the old unregister.  The slot must still be a UV area afterwards.
	 */
	@Test
	fun aReRegisteredUvAreaSurvivesTheOverlap() {
		val registry = ViewportAreaRegistry()
		registry.registerUvScene("uv", UvSceneContent.AtlasPage(0), null)
		registry.registerUvScene("uv", UvSceneContent.AtlasPage(1), null)
		registry.unregister("uv")
		val slot = registry.areas.getValue("uv")
		assertEquals(RenderScene.UvScene, slot.scene, "the surviving hold keeps the area a UV scene")
		assertEquals(UvSceneContent.AtlasPage(1), slot.uvContent, "with the content the rebuilt leaf registered")
	}

	/**
	 * Switching an area's editor type in place - the UV editor to the 2D viewport - hands the slot to the new space.
	 *
	 * The new space registers BEFORE the old one releases (it composes first), so the slot never drops to zero and
	 * is reused as it stands: still a UV scene, still holding the page, still on the page's camera.  Left that way
	 * the engine keeps drawing the atlas page under the 2D viewport's gizmos, framed by a pan and zoom that mean
	 * nothing in the puppet's world.  A registration claims all three.
	 */
	@Test
	fun switchingAUvEditorToAViewportInPlaceRetargetsTheSlot() {
		val registry = ViewportAreaRegistry()
		val pageBounds = ContentBounds(0f, 0f, 4096f, 4096f)
		val puppetBounds = ContentBounds(-600f, -900f, 1200f, 1800f)
		registry.registerUvScene("area", UvSceneContent.AtlasPage(0), null)
		val slot = registry.areas.getValue("area")
		val pageCamera = registry.establishCamera(slot, "area", 800, 600) { _, _ -> pageBounds }

		// The space switch: the viewport registers while the UV editor still holds the area, then the editor lets go.
		registry.register("area")
		registry.unregister("area")

		assertEquals(slot, registry.areas["area"], "the slot survives the switch - the area never went unheld")
		assertEquals(RenderScene.Puppet2D, slot.scene, "and now reads as a puppet area")
		assertNull(slot.uvContent, "with no UV surface left to draw")
		assertNull(slot.camera, "and no camera, so the engine establishes the puppet's view")
		assertNull(slot.imageState.value, "and no last frame of the page to show under the viewport's overlays")
		val puppetCamera = registry.establishCamera(slot, "area", 800, 600) { _, _ -> puppetBounds }
		assertEquals(ViewportCamera.fit(puppetBounds, 800, 600), puppetCamera, "the puppet is fitted, not framed by the page's camera")

		// And back: each surface kept its own view for the area.
		registry.registerUvScene("area", UvSceneContent.AtlasPage(0), null)
		registry.unregister("area")
		assertEquals(RenderScene.UvScene, slot.scene)
		assertEquals(pageCamera, registry.establishCamera(slot, "area", 800, 600) { _, _ -> error("the page's view is remembered, not refitted") })
	}

	/**
	 * A leaf rebuilt for the SAME space - a tree collapse registers the survivor again before its old leaf releases -
	 * keeps its camera and its frame: only a change of kind is a claim.
	 */
	@Test
	fun reRegisteringTheSameSpaceKeepsTheView() {
		val registry = ViewportAreaRegistry()
		registry.register("area")
		val slot = registry.areas.getValue("area")
		val camera = registry.establishCamera(slot, "area", 800, 600) { _, _ -> ContentBounds(0f, -100f, 100f, 100f) }

		registry.register("area")
		registry.unregister("area")

		assertEquals(camera, slot.camera)
		assertEquals(camera, slot.cameraState.value)
	}

	/**
	 * A camera seeded before an area registers is the one the area opens on, in place of a fit - how a saved
	 * document reopens each area on the view it was left with (docs/format/UMA.md § 7.3).  A seed is for one
	 * surface: the same area showing the other surface fits instead.
	 */
	@Test
	fun aSeededCameraIsWhatTheAreaOpensOn() {
		val registry = ViewportAreaRegistry()
		val saved = ViewportCamera(120f, -340f, 2.5f)
		registry.seedCameras(
			mapOf(
				AreaCameraKey("viewport", CameraSurface.Viewport) to saved,
				AreaCameraKey("never-shown", CameraSurface.Viewport) to ViewportCamera(1f, 1f, 1f),
				AreaCameraKey("now-a-uv-editor", CameraSurface.Viewport) to ViewportCamera(2f, 2f, 2f),
			),
		)
		registry.register("viewport")
		registry.register("fresh")
		registry.registerUvScene("now-a-uv-editor", UvSceneContent.AtlasPage(0), null)
		val fitBounds = ContentBounds(0f, -100f, 100f, 100f)

		val restored = registry.establishCamera(registry.areas.getValue("viewport"), "viewport", 800, 600) { _, _ -> error("a seeded area must not fit") }
		val fitted = registry.establishCamera(registry.areas.getValue("fresh"), "fresh", 800, 600) { _, _ -> fitBounds }
		val otherSurface = registry.establishCamera(registry.areas.getValue("now-a-uv-editor"), "now-a-uv-editor", 800, 600) { _, _ -> fitBounds }

		assertEquals(saved, restored)
		assertEquals(ViewportCamera.fit(fitBounds, 800, 600), fitted, "an area with nothing saved fits its content, as before")
		assertEquals(ViewportCamera.fit(fitBounds, 800, 600), otherSurface, "a view saved for the puppet is not applied to a texture")
		assertEquals(ViewportCamera(2f, 2f, 2f), registry.cameras()[AreaCameraKey("now-a-uv-editor", CameraSurface.Viewport)], "and stays remembered for the day the area shows a viewport again")
		assertTrue(AreaCameraKey("never-shown", CameraSurface.Viewport) in registry.cameras(), "every remembered camera is readable for the next save")
	}

	/** Seeding never moves an area that already has a camera: it is for the moment the engine is built. */
	@Test
	fun seedingLeavesAnEstablishedCameraAlone() {
		val registry = ViewportAreaRegistry()
		registry.register("viewport")
		val established = registry.establishCamera(registry.areas.getValue("viewport"), "viewport", 800, 600) { _, _ -> ContentBounds(0f, -100f, 100f, 100f) }

		registry.seedCameras(mapOf(AreaCameraKey("viewport", CameraSurface.Viewport) to ViewportCamera(9f, 9f, 9f)))

		assertEquals(established, registry.cameras()[AreaCameraKey("viewport", CameraSurface.Viewport)])
	}

	/**
	 * A UV editor keeps a view per page and layer.  Switching to one it has never shown fits that surface rather
	 * than carrying the page's pan and zoom over it - a layer a fraction of the page's size would land tiny and in a
	 * corner - and switching back brings back the view the first one was left with.
	 */
	@Test
	fun eachSurfaceKeepsItsOwnView() {
		val registry = ViewportAreaRegistry()
		val pageBounds = ContentBounds(0f, 0f, 4096f, 4096f)
		val layerBounds = ContentBounds(0f, 0f, 300f, 200f)
		registry.registerUvScene("uv", UvSceneContent.AtlasPage(0), null)
		val slot = registry.areas.getValue("uv")
		registry.establishCamera(slot, "uv", 800, 600) { _, _ -> pageBounds }
		registry.pan("uv", 40f, -25f)
		val pannedPage = slot.camera

		val layerContent = UvSceneContent.SourceLayer("layer-1", image(300, 200))
		registry.setUvSceneContent("uv", layerContent, null)
		assertEquals(pannedPage, slot.camera, "a retarget moves no camera by itself - the render thread swaps it with the next frame")
		val layerView =
			registry.establishCamera(slot, "uv", 800, 600) { _, content ->
				assertEquals(layerContent, content, "the fit is of the content the swap files the view under")
				layerBounds
			}
		assertEquals(ViewportCamera.fit(layerBounds, 800, 600), layerView, "a layer the area has never shown is fitted")

		registry.setUvSceneContent("uv", UvSceneContent.AtlasPage(0), null)
		assertEquals(pannedPage, registry.establishCamera(slot, "uv", 800, 600) { _, _ -> error("the page's view is remembered, not refitted") })

		registry.setUvSceneContent("uv", UvSceneContent.SourceLayer("layer-1", image(300, 200)), null)
		assertEquals(layerView, registry.establishCamera(slot, "uv", 800, 600) { _, _ -> error("a layer is known by its key, not its pixels") })
	}

	/** Two UV editors keep their own views: one's view of a page is not what the other opens that page on. */
	@Test
	fun twoAreasKeepSeparateViewsOfOnePage() {
		val registry = ViewportAreaRegistry()
		val pageBounds = ContentBounds(0f, 0f, 1024f, 1024f)
		registry.registerUvScene("top", UvSceneContent.AtlasPage(1), null)
		registry.registerUvScene("bottom", UvSceneContent.AtlasPage(0), null)
		val top = registry.areas.getValue("top")
		val bottom = registry.areas.getValue("bottom")
		registry.establishCamera(top, "top", 800, 300) { _, _ -> pageBounds }
		registry.zoomCentered("top", zoomIn = true, coarse = true)
		registry.establishCamera(bottom, "bottom", 800, 300) { _, _ -> pageBounds }

		registry.setUvSceneContent("bottom", UvSceneContent.AtlasPage(1), null)

		assertEquals(ViewportCamera.fit(pageBounds, 800, 300), registry.establishCamera(bottom, "bottom", 800, 300) { _, _ -> pageBounds })
		assertNotEquals(top.camera, bottom.camera, "and the top area's zoom stays its own")
	}

	/**
	 * An area put away by a workspace tab switch and brought back showing another surface - its selection moved
	 * while it was away - opens on that surface's view, or a fit, never on the last view it had of the old one.
	 */
	@Test
	fun aReturningAreaOnAnotherSurfaceTakesThatSurfacesView() {
		val registry = ViewportAreaRegistry()
		val pageBounds = ContentBounds(0f, 0f, 2048f, 2048f)
		registry.registerUvScene("uv", UvSceneContent.AtlasPage(0), null)
		registry.establishCamera(registry.areas.getValue("uv"), "uv", 800, 600) { _, _ -> pageBounds }
		registry.setUvSceneContent("uv", UvSceneContent.AtlasPage(1), null)
		registry.establishCamera(registry.areas.getValue("uv"), "uv", 800, 600) { _, _ -> pageBounds }
		registry.zoomCentered("uv", zoomIn = true, coarse = true)
		val secondPageView = registry.areas.getValue("uv").camera
		registry.setUvSceneContent("uv", UvSceneContent.AtlasPage(0), null)
		registry.establishCamera(registry.areas.getValue("uv"), "uv", 800, 600) { _, _ -> error("the first page's view is remembered") }
		registry.pan("uv", 120f, 0f)

		// The tab switch drops the slot; the area comes back following a selection now on the second page.
		registry.unregister("uv")
		assertNull(registry.areas["uv"], "the slot is gone while its workspace is away")
		registry.registerUvScene("uv", UvSceneContent.AtlasPage(1), null)
		val returned = registry.establishCamera(registry.areas.getValue("uv"), "uv", 800, 600) { _, _ -> error("the second page's view is remembered") }

		assertEquals(secondPageView, returned)
		assertEquals(secondPageView, registry.cameras()[AreaCameraKey("uv", CameraSurface.Uv)], "and it is the view a save now writes")

		registry.unregister("uv")
		registry.registerUvScene("uv", UvSceneContent.SourceLayer("never-shown", image(64, 64)), null)
		val layerBounds = ContentBounds(0f, 0f, 64f, 64f)
		assertEquals(ViewportCamera.fit(layerBounds, 800, 600), registry.establishCamera(registry.areas.getValue("uv"), "uv", 800, 600) { _, _ -> layerBounds })
	}

	/**
	 * A saved document's UV view belongs to the surface the area opens on.  Once the area has shown a surface this
	 * session, a surface it has not shown fits instead of inheriting the saved view.
	 */
	@Test
	fun aSeededUvViewBelongsToTheFirstSurfaceShown() {
		val registry = ViewportAreaRegistry()
		val saved = ViewportCamera(300f, 200f, 0.5f)
		val pageBounds = ContentBounds(0f, 0f, 1024f, 1024f)
		registry.seedCameras(mapOf(AreaCameraKey("uv", CameraSurface.Uv) to saved))
		registry.registerUvScene("uv", UvSceneContent.AtlasPage(3), null)
		val slot = registry.areas.getValue("uv")

		assertEquals(saved, registry.establishCamera(slot, "uv", 800, 600) { _, _ -> error("the saved view is what the area opens on") })

		registry.setUvSceneContent("uv", UvSceneContent.AtlasPage(0), null)
		assertEquals(ViewportCamera.fit(pageBounds, 800, 600), registry.establishCamera(slot, "uv", 800, 600) { _, _ -> pageBounds })

		registry.setUvSceneContent("uv", UvSceneContent.AtlasPage(3), null)
		assertEquals(saved, registry.establishCamera(slot, "uv", 800, 600) { _, _ -> error("and the opening page keeps it") })
	}

	/** A save sees one UV view per area, the shown surface's: the views kept for the other surfaces are session state. */
	@Test
	fun aSaveSeesOnlyTheShownSurfacesView() {
		val registry = ViewportAreaRegistry()
		val layerBounds = ContentBounds(0f, 0f, 300f, 200f)
		registry.registerUvScene("uv", UvSceneContent.AtlasPage(0), null)
		val slot = registry.areas.getValue("uv")
		registry.establishCamera(slot, "uv", 800, 600) { _, _ -> ContentBounds(0f, 0f, 4096f, 4096f) }
		registry.pan("uv", 10f, 10f)

		registry.setUvSceneContent("uv", UvSceneContent.SourceLayer("layer-1", image(300, 200)), null)
		val layerView = registry.establishCamera(slot, "uv", 800, 600) { _, _ -> layerBounds }

		assertEquals(mapOf(AreaCameraKey("uv", CameraSurface.Uv) to layerView), registry.cameras())
	}

	/**
	 * A UV editor's fit frames the page, widened to every shown mesh that reaches past it: a placement moved off the
	 * page carries its islands with it, and a fit of the page alone would frame everything but what was just moved.
	 */
	@Test
	fun aFitTakesInMeshesPastThePageEdge() {
		val pageBounds = ContentBounds(0f, 0f, 1024f, 1024f)
		val spilled = ContentBounds(900f, -300f, 600f, 400f)
		val widened = ContentBounds(0f, -300f, 1500f, 1324f)
		assertEquals(widened, uvFitBounds(pageBounds, spilled))
		assertEquals(pageBounds, uvFitBounds(pageBounds, ContentBounds(10f, 10f, 20f, 20f)), "meshes inside the page leave the page fit alone")
		assertEquals(pageBounds, uvFitBounds(pageBounds, null), "as does a surface with no meshes")

		val registry = ViewportAreaRegistry()
		registry.registerUvScene("uv", UvSceneContent.AtlasPage(0), spilled)
		val slot = registry.areas.getValue("uv")
		assertEquals(ViewportCamera.fit(widened, 800, 600), registry.establishCamera(slot, "uv", 800, 600) { _, _ -> pageBounds }, "the first fit takes the spill in")

		registry.setUvSceneContent("uv", UvSceneContent.AtlasPage(0), ContentBounds(10f, 10f, 20f, 20f))
		registry.fit("uv")
		assertEquals(ViewportCamera.fit(pageBounds, 800, 600), registry.establishCamera(slot, "uv", 800, 600) { _, _ -> pageBounds }, "and Fit View follows the meshes back inside")
	}
}