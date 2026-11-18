package org.umamo.ui.theme

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the bundled icon set parses (accessing [LocalUmamoIcons] forces every path through the SVG
 * path parser), that the Blender-derived icons keep their two-tone structure: two non-empty layers,
 * exactly one of them the full-strength highlight, and that the Deform brush glyph sits inside its
 * viewport, centred, and large enough to read as a brush.
 */
class IconTest {
	/** Every bundled icon parses to at least one non-empty layer. */
	@Test
	fun bundledIconSetParses() {
		val icons = LocalUmamoIcons

		// A representative sample across both icon families; parsing already happened for the whole set
		// the moment LocalUmamoIcons initialized.
		for (icon in listOf(icons.reset, icons.search, icons.editorModeObject, icons.uvSelectFace, icons.overlays, icons.toolDeformBrush)) {
			assertTrue(icon.layers.isNotEmpty())
			assertTrue(icon.layers.all { layer -> !layer.path.isEmpty })
		}
	}

	/** The Blender two-tone icons carry a muted context layer plus exactly one full-strength highlight. */
	@Test
	fun compositeIconsAreTwoTone() {
		val icons = LocalUmamoIcons
		val compositeIcons =
			mapOf(
				"editorModeObject" to icons.editorModeObject,
				"editorModeEdit" to icons.editorModeEdit,
				"meshSelectVertex" to icons.meshSelectVertex,
				"meshSelectEdge" to icons.meshSelectEdge,
				"meshSelectFace" to icons.meshSelectFace,
				"uvSelectVertex" to icons.uvSelectVertex,
				"uvSelectEdge" to icons.uvSelectEdge,
				"uvSelectFace" to icons.uvSelectFace,
			)

		for ((name, icon) in compositeIcons) {
			assertEquals(2, icon.layers.size, "$name has two layers")
			assertTrue(icon.layers.all { layer -> !layer.path.isEmpty }, "$name layers are non-empty")
			assertTrue(icon.layers.all { layer -> layer.style == IconStyle.Filled }, "$name layers are filled")
			assertEquals(1, icon.layers.count { layer -> layer.opacity == 1f }, "$name has one highlight layer")
			assertEquals(1, icon.layers.count { layer -> layer.opacity < 1f }, "$name has one muted layer")
		}
	}

	/**
	 * The Deform brush glyph stays inside the 24-unit viewport with a unit of margin, so the stroked
	 * ink can never spill past the edge, and it stays centred and big enough to read at the tool
	 * strip's 18 dp.
	 */
	@Test
	fun deformBrushGlyphFitsTheViewport() {
		val icon = LocalUmamoIcons.toolDeformBrush
		val layer = icon.layers.single()

		assertEquals(24f, icon.viewportSize)
		assertEquals(IconStyle.Outlined(2f), layer.style)

		val bounds = layer.path.getBounds()

		assertTrue(bounds.left >= 1f, "the brush keeps a margin on the left (${bounds.left})")
		assertTrue(bounds.top >= 1f, "the brush keeps a margin on the top (${bounds.top})")
		assertTrue(bounds.right <= 23f, "the brush keeps a margin on the right (${bounds.right})")
		assertTrue(bounds.bottom <= 23f, "the brush keeps a margin on the bottom (${bounds.bottom})")
		assertTrue(abs((bounds.left + bounds.right) / 2f - 12f) <= 1f, "the brush is centred horizontally")
		assertTrue(abs((bounds.top + bounds.bottom) / 2f - 12f) <= 1f, "the brush is centred vertically")
		assertTrue(bounds.width >= 12f && bounds.height >= 12f, "the brush fills enough of the viewport to read")
	}
}