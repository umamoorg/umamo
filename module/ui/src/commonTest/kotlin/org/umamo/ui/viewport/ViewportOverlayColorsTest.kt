package org.umamo.ui.viewport

import org.umamo.render.puppet.MeshOverlayPalette
import org.umamo.ui.graphics.parseHexColor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The selection-highlight parser: both hex widths must land on the same RGB because a hand-typed or
 * older user setting may hold #RRGGBB while the preferences HexColorField commits canonical
 * #AARRGGBB - a 6-digit-only parser would silently ignore every edit made in the window.
 */
class ViewportOverlayColorsTest {
	private val defaultComponents = parseSelectionHighlightColor(ViewportColorSettings.SELECTION_HIGHLIGHT_DEFAULT)

	@Test
	fun sixDigitHexParses() {
		val (red, green, blue) = parseSelectionHighlightColor("#00FF00")
		assertEquals(0f, red)
		assertEquals(1f, green)
		assertEquals(0f, blue)
	}

	@Test
	fun eightDigitHexParsesWithAlphaIgnored() {
		assertEquals(
			parseSelectionHighlightColor("#00FF00"),
			parseSelectionHighlightColor("#8000FF00"),
			"the alpha byte is masked off",
		)
		assertEquals(defaultComponents, parseSelectionHighlightColor("#FF338CFF"), "the canonical form of the default")
	}

	@Test
	fun leadingHashIsOptional() {
		assertEquals(parseSelectionHighlightColor("#00FF00"), parseSelectionHighlightColor("00FF00"))
	}

	@Test
	fun malformedOrAbsentValuesFallBackToTheDefault() {
		assertEquals(defaultComponents, parseSelectionHighlightColor(null))
		assertEquals(defaultComponents, parseSelectionHighlightColor(""))
		assertEquals(defaultComponents, parseSelectionHighlightColor("#12345"), "wrong digit count")
		assertEquals(defaultComponents, parseSelectionHighlightColor("#GGGGGG"), "non-hex digits")
	}

	/**
	 * The bundled defaults map onto the renderer's Classic palette exactly: both sides divide each byte by
	 * 255, and the alpha travels straight.
	 */
	@Test
	fun theDefaultColorsMapToTheClassicPalette() {
		val defaults =
			ViewportOverlayColors(
				vertexIdle = parseHexColor(ViewportColorSettings.VERTEX_IDLE_DEFAULT)!!,
				vertexSelected = parseHexColor(ViewportColorSettings.VERTEX_SELECTED_DEFAULT)!!,
				vertexActive = parseHexColor(ViewportColorSettings.VERTEX_ACTIVE_DEFAULT)!!,
				vertexOffKey = parseHexColor(ViewportColorSettings.VERTEX_OFFKEY_DEFAULT)!!,
				edgeIdle = parseHexColor(ViewportColorSettings.EDGE_IDLE_DEFAULT)!!,
				edgeSelected = parseHexColor(ViewportColorSettings.EDGE_SELECTED_DEFAULT)!!,
				edgeActive = parseHexColor(ViewportColorSettings.EDGE_ACTIVE_DEFAULT)!!,
				edgeOffKey = parseHexColor(ViewportColorSettings.EDGE_OFFKEY_DEFAULT)!!,
				faceIdle = parseHexColor(ViewportColorSettings.FACE_IDLE_DEFAULT)!!,
				faceSelected = parseHexColor(ViewportColorSettings.FACE_SELECTED_DEFAULT)!!,
				faceActive = parseHexColor(ViewportColorSettings.FACE_ACTIVE_DEFAULT)!!,
				faceOffKey = parseHexColor(ViewportColorSettings.FACE_OFFKEY_DEFAULT)!!,
				warning = parseHexColor(ViewportColorSettings.WARNING_COLOR_DEFAULT)!!,
				pinnedPlacement = parseHexColor(ViewportColorSettings.PINNED_PLACEMENT_COLOR_DEFAULT)!!,
				selectionHighlight = parseHexColor(ViewportColorSettings.SELECTION_HIGHLIGHT_DEFAULT)!!,
				activeSelectionHighlight = parseHexColor(ViewportColorSettings.ACTIVE_SELECTION_HIGHLIGHT_DEFAULT)!!,
			)
		assertEquals(MeshOverlayPalette.Classic, defaults.toMeshOverlayPalette())
	}
}