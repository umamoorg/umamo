package org.umamo.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the bundled icon set parses (accessing [LocalUmamoIcons] forces every path through the SVG
 * path parser) and that the Blender-derived icons keep their two-tone structure: two non-empty layers,
 * exactly one of them the full-strength highlight.
 */
class IconTest {
	/** Every bundled icon parses to at least one non-empty layer. */
	@Test
	fun bundledIconSetParses() {
		val icons = LocalUmamoIcons
		// A representative sample across both icon families; parsing already happened for the whole set
		// the moment LocalUmamoIcons initialized.
		for (icon in listOf(icons.reset, icons.search, icons.editorModeObject, icons.uvSelectFace)) {
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
}
