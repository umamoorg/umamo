package org.umamo.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * The flat, explicit color palette for the custom design system - every role is a value we set, not a tone
 * derived from a seed. A dense, Blender-ish dark scheme (flat greys + the brand purple accent) with a
 * deliberately designed light scheme; re-tinting is local to this file. Kit widgets and migrated panels
 * read these via [LocalUmamoColors].
 *
 * The light scheme is a system, not an inversion of the dark values:
 *  - The surface ladder keeps the dark scheme's elevation sign (raised surfaces are lighter in both
 *    themes): window backdrop < tab < row stripe < panel < header < control fill.
 *  - One deliberate flip: transient hover washes go darker in light mode, because a lighter-than-near-white
 *    hover is imperceptible. Dark keeps its brighten-on-hover values.
 *  - The accent deepens in light mode so white on-accent text and accent-as-text stay legible.
 *  - Selection decouples from the accent in light mode: a soft lavender row tint with deep plum marks, so
 *    selected rows read as selection rather than as primary buttons.
 *
 * 独自デザイン系の平坦なパレット。Blender 風のダーク（灰＋紫アクセント）と、独立して設計したライト。
 */

/**
 * The role palette. Lean and explicit - no seed/tonal derivation. Roles map onto the editor's surfaces,
 * line-work, text, the brand accent, selection, controls, transient state washes, and the viewport overlay.
 *
 * @property Color windowBackground   The outermost editor background and the native caption tint.
 * @property Color menuBackground     Menu and popup surface fill (menus, command palette, hover popups).
 * @property Color panelBackground    Default panel / surface / dialog fill; space-picker chip hover fill.
 * @property Color tabBackground      Unselected tabs and the space-picker chip at rest.
 * @property Color headerBackground   Panel headers, the status bar, the selected workspace tab, and the
 *   tab rename editor.
 * @property Color rowHover           Hover wash for rows, tabs, menu items, and secondary buttons.
 * @property Color rowStripe          The tint of every other row in dense lists (outliner zebra striping).
 * @property Color divider            The deepest structural line: splitters, hairline rules, and the Pad2D
 *   center crosshair.
 * @property Color panelBorder        Hairline border around panels, dialogs, and popups.
 * @property Color panelBorderHover   Border pop on hover (area chrome, select fields).
 * @property Color controlBorder      Inset border around controls; the Pad2D frame.
 * @property Color guideLine          Mid-strength drawn line-work: the Pad2D grid, slider ticks, in-menu
 *   dividers, and the area drag-indicator strip.
 * @property Color treeGuideLine      Outliner ancestry guide lines.
 * @property Color text               Primary text and text cursors.
 * @property Color textMuted          Secondary / dimmed text, placeholders, and muted glyphs.
 * @property Color textDisabled       Disabled text and glyphs.
 * @property Color accent             The brand accent fill: primary buttons, checkboxes, the slider fill,
 *   armed/recording states, and accent borders/marks.
 * @property Color accentHover        Accent hover state.
 * @property Color accentText         Text/icon on top of accent fills.
 * @property Color selection          Selected-item row background.
 * @property Color selectionText      Text and marks on selection rows.
 * @property Color controlBackground  Neutral control fill (text fields, secondary buttons, segments, key
 *   chips).
 * @property Color controlGlyph       Icon glyphs on neutral control and chip fills.
 * @property Color buttonHover        Hover fill for unselected ButtonGroup segments (stronger than
 *   rowHover in dark).
 * @property Color sliderTrack        Recessed slider track / pad groove (unfilled).
 * @property Color sliderThumb        Slider and 2D-pad handle.
 * @property Color dropZoneFill       Faint wash over a candidate area drop zone while dragging.
 * @property Color dropZoneEmphasis   Stronger wash over the consumed half of an area drop zone.
 * @property Color dropTargetBackground        Outliner nest-into drop-target row fill.
 * @property Color selectionAncestorBackground Outliner ancestor-of-selection row wash.
 * @property Color searchMatchBackground       Outliner search-hit row band.
 * @property Color scrollbarThumb      Scrollbar thumb at rest.
 * @property Color scrollbarThumbHover Scrollbar thumb while hovered.
 * @property Color overlayScrim       Dim behind modal overlays (the command palette, dialogs).
 * @property Color viewportBadgeText  Text on viewport overlay badges (zoom readout, edit-target badge).
 *   Badges overlay artwork rather than chrome, so they stay dark pills in both themes.
 * @property Color viewportBadgeBackground The viewport overlay badge pill fill.
 * @property Color viewportMarquee    The box-select rubber-band stroke in the viewport.
 * @property Color viewportMarqueeContrast The opposite-luminance tone paired with the marquee for the
 *   two-tone marching-ants selection affordances (crosshair guides, box, circle), so the dashed outline
 *   stays legible over any viewport background where a single-color dash would wash out.
 * @property Color transparencyCheckerLight The lighter of the two alternating cells of the transparency
 *   checker drawn behind thumbnails to reveal alpha (themed so a dark scheme gets a dark checker).
 * @property Color transparencyCheckerDark  The darker of the two alternating transparency-checker cells.
 * @property Color viewportGridBackground The flat canvas fill behind the GL viewport's world-aligned grid.
 * @property Color viewportGridLineMajor The major (whole-scale) grid line color.
 * @property Color viewportGridLineMinor The minor (subdivision) grid line color.
 * @property Color outlinerObjectTint  Signature tint for puppet-root / part / art-mesh outliner icons.
 * @property Color outlinerDeformTint  Signature tint for armature / deformer outliner icons.
 * @property Color outlinerObjectTintDimmed Dimmed variant of the object tint for muted rows.
 * @property Color outlinerDeformTintDimmed Dimmed variant of the deformer tint for muted rows.
 */
data class UmamoColors(
	val windowBackground: Color,
	val menuBackground: Color,
	val panelBackground: Color,
	val panelThirdElevation: Color,
	val panelThirdElevationBorder: Color,
	val tabBackground: Color,
	val headerBackground: Color,
	val rowHover: Color,
	val rowStripe: Color,
	val divider: Color,
	val panelBorder: Color,
	val panelBorderHover: Color,
	val controlBorder: Color,
	val guideLine: Color,
	val treeGuideLine: Color,
	val text: Color,
	val textMuted: Color,
	val textDisabled: Color,
	val accent: Color,
	val accentHover: Color,
	val accentText: Color,
	val selection: Color,
	val selectionText: Color,
	val controlBackground: Color,
	val controlGlyph: Color,
	val buttonHover: Color,
	val sliderTrack: Color,
	val sliderThumb: Color,
	val dropZoneFill: Color,
	val dropZoneEmphasis: Color,
	val dropTargetBackground: Color,
	val selectionAncestorBackground: Color,
	val searchMatchBackground: Color,
	val scrollbarThumb: Color,
	val scrollbarThumbHover: Color,
	val overlayScrim: Color,
	val viewportBadgeText: Color,
	val viewportBadgeBackground: Color,
	val viewportMarquee: Color,
	val viewportMarqueeContrast: Color,
	val transparencyCheckerLight: Color,
	val transparencyCheckerDark: Color,
	val viewportGridBackground: Color,
	val viewportGridLineMajor: Color,
	val viewportGridLineMinor: Color,
	val outlinerObjectTint: Color,
	val outlinerDeformTint: Color,
	val outlinerObjectTintDimmed: Color,
	val outlinerDeformTintDimmed: Color,
)

// Alpha-carrying tokens below derive via .copy(alpha = ...) on these bases, never re-baked hex: values
// like 0.7f have no exact 8-bit representation, and the derivation keeps the rendered floats bit-identical
// to what the call sites historically computed inline.
private val brandPurple = Color(0xFFBF86D7)
private val brandPurpleBright = Color(0xFFD394ED)

// The light scheme deepens the accent so white on-accent text reads at ~5.6:1 (the dark purple family
// value is too pale against light surfaces).
private val brandPurpleDeep = Color(0xFF8A4FA8)
private val brandPurpleDeepBright = Color(0xFF9B63B9)
private val mutedGreyDark = Color(0xFF9A9A9A)
private val mutedGreyLight = Color(0xFF6B6B6B)

// Blender's armature object / data signature colours, per outliner node family.
private val outlinerTanDark = Color(0xFFE19658)
private val outlinerTanLight = Color(0xFFE19658)
private val outlinerTealDark = Color(0xFF00D4A3)
private val outlinerTealLight = Color(0xFF00D4A3)
private val chromeDeep = Color(0xFF181818)
private val panelEmphasis = Color(0xFF3A3A3A)
private val hoverEmphasis = Color(0xFF414141)
// Creme: FFF3EA (Text or otherwise)  Muted Creme: C4B0A1 - Honestly, creme for the text looks like the weird Android Material 3 color tinting and I hate it.
// Primary Purple: BF86D7
// Secondary Yellow: FCDD83

/** The dark scheme: flat greys with the brand purple accent (the default; Blender is dark-first). */
val umamoDarkColors =
	UmamoColors(
		windowBackground = chromeDeep,
		menuBackground = chromeDeep,
		panelBackground = Color(0xFF303030),
		panelThirdElevation = Color(0xFF454545),
		panelThirdElevationBorder = Color(0xFF545454),
		tabBackground = Color(0xFF262626),
		headerBackground = panelEmphasis,
		rowHover = hoverEmphasis,
		rowStripe = Color(0xFF2B2B2B),
		divider = chromeDeep,
		panelBorder = Color(0xFF454545),
		panelBorderHover = Color(0xFFA1A1A1),
		controlBorder = Color(0xFF1A1A1A),
		guideLine = mutedGreyDark,
		treeGuideLine = mutedGreyDark.copy(alpha = 0.4f),
		text = Color(0xFFE3E3E3),
		textMuted = mutedGreyDark,
		textDisabled = mutedGreyDark.copy(alpha = 0.3f),
		accent = brandPurple,
		accentHover = brandPurpleBright,
		accentText = Color(0xFFFFFFFF),
		selection = brandPurple,
		selectionText = Color(0xFFFFFFFF),
		controlBackground = Color(0xFF545454),
		controlGlyph = Color(0xFFFFFFFF),
		buttonHover = Color(0xFF656565),
		sliderTrack = Color(0xFF252525),
		sliderThumb = Color(0xFFD2D2D2),
		dropZoneFill = brandPurple.copy(alpha = 0.16f),
		dropZoneEmphasis = brandPurple.copy(alpha = 0.28f),
		dropTargetBackground = brandPurple.copy(alpha = 0.4f),
		selectionAncestorBackground = brandPurple.copy(alpha = 0.5f),
		searchMatchBackground = brandPurple.copy(alpha = 0.25f),
		scrollbarThumb = mutedGreyDark.copy(alpha = 0.4f),
		scrollbarThumbHover = mutedGreyDark.copy(alpha = 0.7f),
		overlayScrim = Color(0x99000000),
		viewportBadgeText = Color(0xFFE0E0E0),
		viewportBadgeBackground = Color(0x99000000),
		viewportMarquee = Color(0xCCFFFFFF),
		// The dark tone that alternates with the white marquee so the marching-ants dash reads on light art.
		viewportMarqueeContrast = Color(0xCC000000),
		transparencyCheckerLight = Color(0xFF3C3C3C),
		transparencyCheckerDark = Color(0xFF323232),
		viewportGridBackground = Color(0xFF343434),
		viewportGridLineMajor = Color(0xFF515151),
		viewportGridLineMinor = Color(0xFF454545),
		outlinerObjectTint = outlinerTanDark,
		outlinerDeformTint = outlinerTealDark,
		outlinerObjectTintDimmed = outlinerTanDark.copy(alpha = 0.2f),
		outlinerDeformTintDimmed = outlinerTealDark.copy(alpha = 0.2f),
	)

/** The light scheme: a designed grey ladder with the deepened purple accent (see the file comment). */
val umamoLightColors =
	UmamoColors(
		windowBackground = Color(0xFFD4D4D4),
		menuBackground = Color(0xFFFAFAFA),
		panelBackground = Color(0xFFF4F4F4),
		panelThirdElevation = Color(0xFFFFFFFF),
		panelThirdElevationBorder = Color(0xFF6C6C6C),
		tabBackground = Color(0xFFDDDDDD),
		headerBackground = Color(0xFFFAFAFA),
		rowHover = Color(0xFFE4E4E4),
		rowStripe = Color(0xFFECECEC),
		divider = Color(0xFFD4D4D4),
		panelBorder = Color(0xFFB0B0B0),
		panelBorderHover = Color(0xFF616161),
		controlBorder = Color(0xFF9E9E9E),
		guideLine = Color(0xFF8F8F8F),
		treeGuideLine = mutedGreyLight.copy(alpha = 0.4f),
		text = Color(0xFF202020),
		textMuted = mutedGreyLight,
		textDisabled = mutedGreyLight.copy(alpha = 0.3f),
		accent = brandPurpleDeep,
		accentHover = brandPurpleDeepBright,
		accentText = Color(0xFFFFFFFF),
		selection = Color(0xFFDCC3EA),
		selectionText = Color(0xFF3B2144),
		controlBackground = Color(0xFFDFDFDF),
		controlGlyph = Color(0xFF2E2E2E),
		buttonHover = Color(0xFFEDEDED),
		sliderTrack = Color(0xFFDCDCDC),
		sliderThumb = Color(0xFF505050),
		dropZoneFill = brandPurpleDeep.copy(alpha = 0.16f),
		dropZoneEmphasis = brandPurpleDeep.copy(alpha = 0.28f),
		dropTargetBackground = brandPurpleDeep.copy(alpha = 0.45f),
		// Kept subordinate to the solid lavender selection; dark's half-alpha-over-dark is dimmer than
		// the full accent, and light preserves that ordering.
		selectionAncestorBackground = brandPurpleDeep.copy(alpha = 0.28f),
		searchMatchBackground = brandPurpleDeep.copy(alpha = 0.14f),
		scrollbarThumb = mutedGreyLight.copy(alpha = 0.4f),
		scrollbarThumbHover = mutedGreyLight.copy(alpha = 0.7f),
		overlayScrim = Color(0x55000000),
		viewportBadgeText = Color(0xFFE0E0E0),
		viewportBadgeBackground = Color(0x99000000),
		// A white rubber band disappears on the light grid, so the marquee inverts with the theme.
		viewportMarquee = Color(0xCC333333),
		// The light tone that alternates with the dark marquee so the marching-ants dash reads on dark art.
		viewportMarqueeContrast = Color(0xCCFFFFFF),
		// The classic Photoshop transparency checker (white / #CCCCCC), kept for the thumbnail alpha reveal.
		transparencyCheckerLight = Color(0xFFFFFFFF),
		transparencyCheckerDark = Color(0xFFCCCCCC),
		viewportGridBackground = Color(0xFFCFCFCF),
		viewportGridLineMajor = Color(0xFFA8A8A8),
		viewportGridLineMinor = Color(0xFFBEBEBE),
		outlinerObjectTint = outlinerTanLight,
		outlinerDeformTint = outlinerTealLight,
		outlinerObjectTintDimmed = outlinerTanLight.copy(alpha = 0.2f),
		outlinerDeformTintDimmed = outlinerTealLight.copy(alpha = 0.2f),
	)

/** The active palette for the composition (defaults to dark; [UmamoTheme] provides the resolved scheme). */
val LocalUmamoColors = staticCompositionLocalOf { umamoDarkColors }
