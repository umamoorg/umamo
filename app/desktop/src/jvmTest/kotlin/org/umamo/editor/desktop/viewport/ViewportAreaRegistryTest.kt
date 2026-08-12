package org.umamo.editor.desktop.viewport

import org.umamo.render.DecodedImage
import org.umamo.ui.viewport.UvSceneContent
import kotlin.test.Test
import kotlin.test.assertEquals
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
		registry.registerUvScene("uv", UvSceneContent.AtlasPage(0))
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
		registry.registerUvScene("uv", UvSceneContent.AtlasPage(0))
		val slot = registry.areas.getValue("uv")

		val layer = image()
		registry.setUvSceneContent("uv", UvSceneContent.SourceLayer(layer))
		assertEquals(UvSceneContent.SourceLayer(layer), slot.uvContent, "the layer view replaces the page view whole")
		assertEquals(RenderScene.UvScene, slot.scene, "and it is still a UV area")

		registry.setUvSceneContent("uv", UvSceneContent.AtlasPage(2))
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
		registry.setUvSceneContent("puppet", UvSceneContent.AtlasPage(1))
		val slot = registry.areas.getValue("puppet")
		assertEquals(RenderScene.Puppet2D, slot.scene, "a puppet area is not retargetable into a UV scene")
		assertNull(slot.uvContent, "and takes on no UV surface")
	}

	/** An unregistered area is a no-op rather than a crash, since requests can outlive their area. */
	@Test
	fun retargetingAnUnknownAreaDoesNothing() {
		val registry = ViewportAreaRegistry()
		registry.setUvSceneContent("gone", UvSceneContent.AtlasPage(0))
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
		registry.registerUvScene("uv", UvSceneContent.AtlasPage(0))
		registry.registerUvScene("uv", UvSceneContent.AtlasPage(1))
		registry.unregister("uv")
		val slot = registry.areas.getValue("uv")
		assertEquals(RenderScene.UvScene, slot.scene, "the surviving hold keeps the area a UV scene")
		assertEquals(UvSceneContent.AtlasPage(1), slot.uvContent, "with the content the rebuilt leaf registered")
	}
}