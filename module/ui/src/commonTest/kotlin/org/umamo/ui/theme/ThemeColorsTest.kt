package org.umamo.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins every umamoDarkColors value to its expected constant.  The dark scheme is the shipped look and its
 * rendered pixels are a contract: this test keeps a later refactor of the color table from silently
 * shifting any token's value. Alpha-carrying tokens are asserted via .copy(alpha = ...) on the same base
 * value for float bit-identity (values like 0.7f have no exact 8-bit hex form).
 */
class ThemeColorsTest {
	/**
	 * Asserts every dark-scheme token against its pinned constant.
	 */
	@Test
	fun darkSchemeValuesArePinned() {
		val brandPurple = Color(0xFFBF86D7)
		val mutedGrey = Color(0xFF9A9A9A)
		val outlinerTan = Color(0xFFE19658)
		val outlinerTeal = Color(0xFF00D4A3)
		val colors = umamoDarkColors
		assertEquals(Color(0xFF181818), colors.windowBackground, "windowBackground")
		assertEquals(Color(0xFF181818), colors.menuBackground, "menuBackground")
		assertEquals(Color(0xFF303030), colors.panelBackground, "panelBackground")
		assertEquals(Color(0xFF262626), colors.tabBackground, "tabBackground")
		assertEquals(Color(0xFF3A3A3A), colors.headerBackground, "headerBackground")
		assertEquals(Color(0xFF414141), colors.rowHover, "rowHover")
		assertEquals(Color(0xFF2B2B2B), colors.rowStripe, "rowStripe")
		assertEquals(Color(0xFF181818), colors.divider, "divider")
		assertEquals(Color(0xFF454545), colors.panelBorder, "panelBorder")
		assertEquals(Color(0xFFA1A1A1), colors.panelBorderHover, "panelBorderHover")
		assertEquals(Color(0xFF1A1A1A), colors.controlBorder, "controlBorder")
		assertEquals(mutedGrey, colors.guideLine, "guideLine")
		assertEquals(mutedGrey.copy(alpha = 0.4f), colors.treeGuideLine, "treeGuideLine")
		assertEquals(Color(0xFFE3E3E3), colors.text, "text")
		assertEquals(mutedGrey, colors.textMuted, "textMuted")
		assertEquals(mutedGrey.copy(alpha = 0.3f), colors.textDisabled, "textDisabled")
		assertEquals(brandPurple, colors.accent, "accent")
		assertEquals(Color(0xFFD394ED), colors.accentHover, "accentHover")
		assertEquals(Color(0xFFFFFFFF), colors.accentText, "accentText")
		assertEquals(brandPurple, colors.selection, "selection")
		assertEquals(Color(0xFFFFFFFF), colors.selectionText, "selectionText")
		assertEquals(Color(0xFF545454), colors.controlBackground, "controlBackground")
		assertEquals(Color(0xFFFFFFFF), colors.controlGlyph, "controlGlyph")
		assertEquals(Color(0xFF656565), colors.buttonHover, "buttonHover")
		assertEquals(Color(0xFF252525), colors.sliderTrack, "sliderTrack")
		assertEquals(Color(0xFFD2D2D2), colors.sliderThumb, "sliderThumb")
		assertEquals(brandPurple.copy(alpha = 0.16f), colors.dropZoneFill, "dropZoneFill")
		assertEquals(brandPurple.copy(alpha = 0.28f), colors.dropZoneEmphasis, "dropZoneEmphasis")
		assertEquals(brandPurple.copy(alpha = 0.4f), colors.dropTargetBackground, "dropTargetBackground")
		assertEquals(brandPurple.copy(alpha = 0.5f), colors.selectionAncestorBackground, "selectionAncestorBackground")
		assertEquals(brandPurple.copy(alpha = 0.25f), colors.searchMatchBackground, "searchMatchBackground")
		assertEquals(mutedGrey.copy(alpha = 0.4f), colors.scrollbarThumb, "scrollbarThumb")
		assertEquals(mutedGrey.copy(alpha = 0.7f), colors.scrollbarThumbHover, "scrollbarThumbHover")
		assertEquals(Color(0x99000000), colors.overlayScrim, "overlayScrim")
		assertEquals(Color(0xFFE0E0E0), colors.viewportBadgeText, "viewportBadgeText")
		assertEquals(Color(0x99000000), colors.viewportBadgeBackground, "viewportBadgeBackground")
		assertEquals(Color(0xCCFFFFFF), colors.viewportMarquee, "viewportMarquee")
		assertEquals(Color(0xCC000000), colors.viewportMarqueeContrast, "viewportMarqueeContrast")
		assertEquals(Color(0xFF3C3C3C), colors.transparencyCheckerLight, "transparencyCheckerLight")
		assertEquals(Color(0xFF323232), colors.transparencyCheckerDark, "transparencyCheckerDark")
		assertEquals(Color(0xFF343434), colors.viewportGridBackground, "viewportGridBackground")
		assertEquals(Color(0xFF515151), colors.viewportGridLineMajor, "viewportGridLineMajor")
		assertEquals(Color(0xFF454545), colors.viewportGridLineMinor, "viewportGridLineMinor")
		assertEquals(outlinerTan, colors.outlinerObjectTint, "outlinerObjectTint")
		assertEquals(outlinerTeal, colors.outlinerDeformTint, "outlinerDeformTint")
		assertEquals(outlinerTan.copy(alpha = 0.2f), colors.outlinerObjectTintDimmed, "outlinerObjectTintDimmed")
		assertEquals(outlinerTeal.copy(alpha = 0.2f), colors.outlinerDeformTintDimmed, "outlinerDeformTintDimmed")
	}
}
