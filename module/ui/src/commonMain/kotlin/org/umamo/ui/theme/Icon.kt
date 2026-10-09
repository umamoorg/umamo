package org.umamo.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser

/**
 * How an icon's path is painted.  Bundling this with the path lets call sites draw an icon without
 * restating fill-versus-stroke or the stroke weight every time.
 */
sealed interface IconStyle {
	/** Paint the path as a solid filled shape (glyph-style icons). */
	data object Filled : IconStyle

	/**
	 * Paint the path as a stroked outline (Tabler line icons).  Caps and joins are always rounded to
	 * match the Tabler source, so only the line weight varies.
	 *
	 * @param Float strokeWidth Line weight in the icon's own viewport units.
	 */
	data class Outlined(val strokeWidth: Float = 2f) : IconStyle
}

/**
 * One paint layer of an icon: a parsed path, how it is painted, and its opacity relative to the tint
 * color the caller draws with.  Opacity is what carries a two-tone icon's contrast (Blender's icon set
 * encodes its dark/light pairing as a full-strength layer over a muted one), so both tones derive from
 * the single caller color and follow the theme automatically.  A layer may instead carry its OWN
 * color for genuinely multi-color art (the 2D cursor's red / blue axis stubs), overriding the caller
 * tint for that layer only; theme-following icons leave it null.
 *
 * @param Path      path    The parsed vector outline.
 * @param IconStyle style   Fill-versus-stroke descriptor used when drawing.
 * @param Float     opacity The layer's opacity multiplier over the paint color (1 = full strength).
 * @param Color?    color   The layer's own paint color, or null to take the caller's tint.
 */
data class IconLayer(
	val path: Path,
	val style: IconStyle = IconStyle.Outlined(),
	val opacity: Float = 1f,
	val color: Color? = null,
)

/**
 * A single themed vector icon: its paint layers in draw order and the square viewport their coordinates
 * are authored in.  Single-color icons (the Tabler set) are one layer; two-tone icons (the Blender set)
 * are a muted context layer plus a full-strength highlight layer.  Carrying the styles per layer keeps
 * draw call sites free of stroke and scale setup.
 *
 * @param List<IconLayer> layers       The paint layers, drawn first to last.
 * @param Float           viewportSize Edge length of the square coordinate box the paths are authored in.
 */
data class UmamoIcon(
	val layers: List<IconLayer>,
	val viewportSize: Float = 24f,
)

/** The bundled set of editor icons, each a fully described [UmamoIcon]. */
data class UmamoIcons(
	val reset: UmamoIcon,
	val resetAll: UmamoIcon,
	val groupAdd: UmamoIcon,
	val parameterAdd: UmamoIcon,
	val part: UmamoIcon,
	val mesh: UmamoIcon,
	val warpDeformer: UmamoIcon,
	val armature: UmamoIcon,
	val rotationDeformer: UmamoIcon,
	val search: UmamoIcon,
	val close: UmamoIcon,
	val plus: UmamoIcon,
	val eyedropper: UmamoIcon,
	val puppetRoot: UmamoIcon,
	val filterUnfiltered: UmamoIcon,
	val filterFiltered: UmamoIcon,
	val eyeVisible: UmamoIcon,
	val eyeHidden: UmamoIcon,
	val linked: UmamoIcon,
	val unlinked: UmamoIcon,
	val locked: UmamoIcon,
	val unlocked: UmamoIcon,
	val selectable: UmamoIcon,
	val unselectable: UmamoIcon,
	val spaceViewport: UmamoIcon,
	val spaceTexture: UmamoIcon,
	val spaceOutliner: UmamoIcon,
	val spaceHistory: UmamoIcon,
	val spaceParameters: UmamoIcon,
	val spaceKeyformSheet: UmamoIcon,
	val spaceProperties: UmamoIcon,
	val spaceTool: UmamoIcon,
	val spaceAnimation: UmamoIcon,
	val chevronUp: UmamoIcon,
	val chevronDown: UmamoIcon,
	val chevronLeft: UmamoIcon,
	val chevronRight: UmamoIcon,
	val dots: UmamoIcon,
	val gripVertical: UmamoIcon,
	val gripHorizontal: UmamoIcon,
	val toolBoxSelect: UmamoIcon,
	val toolCircleSelect: UmamoIcon,
	val toolGrab: UmamoIcon,
	val toolRotate: UmamoIcon,
	val toolScale: UmamoIcon,
	val transformPivot: UmamoIcon,
	val snap: UmamoIcon,
	val proportionalEdit: UmamoIcon,
	val logsTerminal: UmamoIcon,
	val copy: UmamoIcon,
	val floppy: UmamoIcon,
	val sources: UmamoIcon,
	val missingFile: UmamoIcon,
	val addFile: UmamoIcon,
	val refresh: UmamoIcon,
	val refreshAlert: UmamoIcon,
	val wand: UmamoIcon,
	val language: UmamoIcon,
	val overlays: UmamoIcon,
	val editorModeObject: UmamoIcon,
	val editorModeEdit: UmamoIcon,
	val meshSelectVertex: UmamoIcon,
	val meshSelectEdge: UmamoIcon,
	val meshSelectFace: UmamoIcon,
	val uvSelectVertex: UmamoIcon,
	val uvSelectEdge: UmamoIcon,
	val uvSelectFace: UmamoIcon,
	val pivotMedian: UmamoIcon,
	val pivotIndividual: UmamoIcon,
	val pivotActive: UmamoIcon,
	val pivotCursor: UmamoIcon,
	val cursor: UmamoIcon,
	val selection: UmamoIcon,
	val cursor2d: UmamoIcon,
)

/**
 * Builds a stroked Tabler icon from its SVG path data.  Tabler icons share a 24x24 viewport and a
 * 2-unit round stroke, so this captures those defaults in one place.
 *
 * @param String pathData SVG "d" attribute contents; may hold several "M..." subpaths.
 * @return UmamoIcon The icon ready to draw.
 */
private fun simpleIcon(pathData: String): UmamoIcon =
	UmamoIcon(layers = listOf(IconLayer(path = PathParser().parsePathString(pathData).toPath())))

/**
 * Builds one filled layer of a Blender icon from its SVG path data.  Blender's icons are filled shapes
 * whose two-tone contrast is encoded as fill opacity (a 1.0 highlight over a muted context, typically
 * 0.6 or 0.55), which this preserves as the layer opacity.
 *
 * @param String pathData SVG "d" attribute contents; may hold several "M..." subpaths.
 * @param Float opacity The source path's fill-opacity (1 = the highlight layer).
 * @param Color? color The layer's own paint color, overriding the caller's tint; null to follow it (the
 *   default for every theme-following layer).
 * @return IconLayer The layer ready to compose into a [compositeIcon].
 */
private fun compositeIconLayer(pathData: String, opacity: Float = 1f, color: Color? = null): IconLayer =
	IconLayer(path = PathParser().parsePathString(pathData).toPath(), style = IconStyle.Filled, opacity = opacity, color = color)

/**
 * Builds a Blender two-tone icon from its layers, given in the source SVG's paint order.  A couple of
 * the exports carry a 23-unit axis instead of 24 (the bake tool trimmed the viewBox), so the optional
 * offsets re-center those paths in the shared 24x24 viewport at parse time.
 *
 * @param IconLayer layers The paint layers, first to last (see [compositeIconLayer]).
 * @param Float offsetX Horizontal re-centering translation in viewport units.
 * @param Float offsetY Vertical re-centering translation in viewport units.
 * @return UmamoIcon The icon ready to draw.
 */
private fun compositeIcon(vararg layers: IconLayer, offsetX: Float = 0f, offsetY: Float = 0f): UmamoIcon {
	if (offsetX != 0f || offsetY != 0f) {
		for (layer in layers) {
			layer.path.translate(Offset(offsetX, offsetY))
		}
	}
	return UmamoIcon(layers = layers.toList())
}

/**
 * The bundled icon set.  Despite the name there is no CompositionLocal here yet; it is a shared
 * top-level value referenced directly (for example LocalUmamoIcons.reset), kept so a per-theme
 * override can layer on later without touching call sites.
 */
val LocalUmamoIcons =
	UmamoIcons(
		// Tabler "restore", with the middle dot deleted.  (viewBox 0 0 24 24, stroked)
		reset = simpleIcon("M3.06 13a9 9 0 1 0 .49 -4.087 M3 4.001v5h5"),
		// Tabler "restore" (viewBox 0 0 24 24, stroked).
		resetAll = simpleIcon("M3.06 13a9 9 0 1 0 .49 -4.087 M3 4.001v5h5 M11 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0"),
		// Tabler "folder-plus" (viewBox 0 0 24 24, stroked).
		groupAdd = simpleIcon("M12 19h-7a2 2 0 0 1 -2 -2v-11a2 2 0 0 1 2 -2h4l3 3h7a2 2 0 0 1 2 2v3.5 M16 19h6 M19 16v6"),
		// Tabler "adjustments-plus", rotated and flipped.  (viewBox 0 0 24 24, stroked)
		parameterAdd = simpleIcon("M10,4C11.097,4 12,4.903 12,6C12,7.097 11.097,8 10,8C8.903,8 8,7.097 8,6C8,4.903 8.903,4 10,4 M4,6L8,6 M12,6L20,6 M15.592,13.958C14.668,13.765 14,12.944 14,12C14,10.903 14.903,10 16,10C17.097,10 18,10.903 18,12 M4,12L14,12 M18,12L20,12 M7,16C8.097,16 9,16.903 9,18C9,19.097 8.097,20 7,20C5.903,20 5,19.097 5,18C5,16.903 5.903,16 7,16 M4,18L5,18 M9,18L12,18 M19,16L19,22 M16,19L22,19"),
		// Tabler "yoga" (viewBox 0 0 24 24, stroked).
		armature = simpleIcon("M4 20h4l1.5 -3 M17 20l-1 -5h-5l1 -7 M4 10l4 -1l4 -1l4 1.5l4 1.5 M10.007 5a2 2 0 1 0 4 0a2 2 0 1 0 -4 0"),
		// Tabler "stack-2" (viewBox 0 0 24 24, stroked).  When the part is isolated as a layer
		// or grouped by draw order, draw a rounded outline around this icon.
		part = simpleIcon("M12 4l-8 4l8 4l8 -4l-8 -4 M4 12l8 4l8 -4 M4 16l8 4l8 -4"),
		// Tabler "triangle" (viewBox 0 0 24 24, stroked).  Authored already rotated 15 degrees clockwise.
		mesh = simpleIcon("M14.166,3.054L2.836,14.031C2.598,14.269 2.428,14.565 2.341,14.889C2.071,15.896 2.67,16.947 3.674,17.228L19.336,21.421C20.346,21.678 21.389,21.067 21.659,20.061C21.746,19.737 21.746,19.396 21.659,19.071L17.329,3.9C17.142,3.258 16.632,2.758 15.986,2.585C15.34,2.412 14.649,2.59 14.166,3.053"),
		// Tabler "mesh" (viewBox 0 0 24 24, stroked).
		warpDeformer = simpleIcon("M3 9h18 M3 15h18 M8 4c.485 .445 3.5 3.312 3.5 8c0 .663 -.07 4.848 -3.5 8 M15 4a17 17 0 0 1 2.004 8c0 1.51 -.201 4.628 -2.004 8 M18.778 20h-13.556a2.22 2.22 0 0 1 -2.222 -2.222v-11.556c0 -1.227 .995 -2.222 2.222 -2.222h13.556c1.227 0 2.222 .995 2.222 2.222v11.556a2.22 2.22 0 0 1 -2.222 2.222"),
		// Tabler "circle-dot" (viewBox 0 0 24 24, stroked).
		rotationDeformer = simpleIcon("M11 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0 M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0"),
		// Tabler "search" (viewBox 0 0 24 24, stroked).
		search = simpleIcon("M3 10a7 7 0 1 0 14 0a7 7 0 1 0 -14 0 M21 21l-6 -6"),
		// Tabler "x" (viewBox 0 0 24 24, stroked).
		close = simpleIcon("M18 6l-12 12 M6 6l12 12"),
		// Tabler "plus" (viewBox 0 0 24 24, stroked).
		plus = simpleIcon("M12 5l0 14 M5 12l14 0"),
		// Tabler "color-picker" (viewBox 0 0 24 24, stroked).
		eyedropper = simpleIcon("M11 7l6 6 M4 16l11.7 -11.7a1 1 0 0 1 1.4 0l2.6 2.6a1 1 0 0 1 0 1.4l-11.7 11.7h-4v-4"),
		// Tabler "play-basketball" - Modified (viewBox 0 0 24 24, stroked).
		puppetRoot = simpleIcon("M14.493,5C14.493,6.097 13.59,7 12.493,7C11.396,7 10.493,6.097 10.493,5C10.493,3.903 11.396,3 12.493,3C13.59,3 14.493,3.903 14.493,5 M18.5,21L15.5,18L14.75,16.5 M9.5,21L9.5,17L13.5,14L13,8 M18.5,12L17.5,9L13,8L9.5,11L5.5,10.5"),
		// Tabler "filter" (viewBox 0 0 24 24, stroked).
		filterUnfiltered = simpleIcon("M8 4h12v2.172a2 2 0 0 1 -.586 1.414l-3.914 3.914m-.5 3.5v4l-6 2v-8.5l-4.48 -4.928a2 2 0 0 1 -.52 -1.345v-2.227 M3 3l18 18"),
		// Tabler "filter-off" (viewBox 0 0 24 24, stroked).
		filterFiltered = simpleIcon("M4 4h16v2.172a2 2 0 0 1 -.586 1.414l-4.414 4.414v7l-6 2v-8.5l-4.48 -4.928a2 2 0 0 1 -.52 -1.345v-2.227"),
		// Tabler "eye" (viewBox 0 0 24 24, stroked).
		eyeVisible = simpleIcon("M10 12a2 2 0 1 0 4 0a2 2 0 0 0 -4 0 M21 12c-2.4 4 -5.4 6 -9 6c-3.6 0 -6.6 -2 -9 -6c2.4 -4 5.4 -6 9 -6c3.6 0 6.6 2 9 6"),
		// Tabler "eye-off" (viewBox 0 0 24 24, stroked).
		eyeHidden = simpleIcon("M10.585 10.587a2 2 0 0 0 2.829 2.828 M16.681 16.673a8.717 8.717 0 0 1 -4.681 1.327c-3.6 0 -6.6 -2 -9 -6c1.272 -2.12 2.712 -3.678 4.32 -4.674m2.86 -1.146a9.055 9.055 0 0 1 1.82 -.18c3.6 0 6.6 2 9 6c-.666 1.11 -1.379 2.067 -2.138 2.87 M3 3l18 18"),
		// Tabler "link" (viewBox 0 0 24 24, stroked).
		linked = simpleIcon("M9 15l6 -6 M11 6l.463 -.536a5 5 0 0 1 7.071 7.072l-.534 .464 M13 18l-.397 .534a5.068 5.068 0 0 1 -7.127 0a4.972 4.972 0 0 1 0 -7.071l.524 -.463"),
		// Tabler "link-off" (viewBox 0 0 24 24, stroked).
		unlinked = simpleIcon("M9 15l3 -3m2 -2l1 -1 M11 6l.463 -.536a5 5 0 0 1 7.071 7.072l-.534 .464 M3 3l18 18 M13 18l-.397 .534a5.068 5.068 0 0 1 -7.127 0a4.972 4.972 0 0 1 0 -7.071l.524 -.463"),
		// Tabler "lock" (viewBox 0 0 24 24, stroked).
		locked = simpleIcon("M5 13a2 2 0 0 1 2 -2h10a2 2 0 0 1 2 2v6a2 2 0 0 1 -2 2h-10a2 2 0 0 1 -2 -2v-6 M11 16a1 1 0 1 0 2 0a1 1 0 0 0 -2 0 M8 11v-4a4 4 0 1 1 8 0v4"),
		// Tabler "lock-off" (viewBox 0 0 24 24, stroked).
		unlocked = simpleIcon("M15 11h2a2 2 0 0 1 2 2v2m0 4a2 2 0 0 1 -2 2h-10a2 2 0 0 1 -2 -2v-6a2 2 0 0 1 2 -2h4 M11 16a1 1 0 1 0 2 0a1 1 0 0 0 -2 0 M8 11v-3m.719 -3.289a4 4 0 0 1 7.281 2.289v4 M3 3l18 18"),
		// Tabler "pointer" (viewBox 0 0 24 24, stroked).
		selectable = simpleIcon("M7.904 17.563a1.2 1.2 0 0 0 2.228 .308l2.09 -3.093l4.907 4.907a1.067 1.067 0 0 0 1.509 0l1.047 -1.047a1.067 1.067 0 0 0 0 -1.509l-4.907 -4.907l3.113 -2.09a1.2 1.2 0 0 0 -.309 -2.228l-13.582 -3.904l3.904 13.563"),
		// Tabler "pointer-off" (viewBox 0 0 24 24, stroked).
		unselectable = simpleIcon("M15.662 11.628l2.229 -1.496a1.2 1.2 0 0 0 -.309 -2.228l-8.013 -2.303m-5.569 -1.601l3.904 13.563a1.2 1.2 0 0 0 2.228 .308l2.09 -3.093l4.907 4.907a1.067 1.067 0 0 0 1.509 0l.524 -.524 M3 3l18 18"),
		// Tabler "picnic-table"(It looks like Blender's 3D viewport icon and I think it's amusing.) (viewBox 0 0 24 24, stroked).
		spaceViewport = simpleIcon("M16 7l2 9m-10 -9l-2 9m-1 -9h14m2 5h-18"),
		// Tabler "texture" (viewBox 0 0 24 24, stroked).
		spaceTexture = simpleIcon("M6 3l-3 3 M21 18l-3 3 M11 3l-8 8 M16 3l-13 13 M21 3l-18 18 M21 8l-13 13 M21 13l-8 8"),
		// Tabler "list-tree" (viewBox 0 0 24 24, stroked).
		spaceOutliner = simpleIcon("M9 6h11 M12 12h8 M15 18h5 M5 6v.01 M8 12v.01 M11 18v.01"),
		// Tabler "history" (viewBox 0 0 24 24, stroked).
		spaceHistory = simpleIcon("M12 8l0 4l2 2 M3.05 11a9 9 0 1 1 .5 4m-.5 5v-5h5"),
		// Tabler "adjustments-horizontal" (viewBox 0 0 24 24, stroked).
		spaceParameters = simpleIcon("M12 6a2 2 0 1 0 4 0a2 2 0 1 0 -4 0 M4 6l8 0 M16 6l4 0 M6 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0 M4 12l2 0 M10 12l10 0 M15 18a2 2 0 1 0 4 0a2 2 0 1 0 -4 0 M4 18l11 0 M19 18l1 0"),
		// Tabler "chart-grid-dots" (viewBox 0 0 24 24, stroked).
		spaceKeyformSheet = simpleIcon("M16 6a2 2 0 1 0 4 0a2 2 0 1 0 -4 0 M4 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0 M4 18a2 2 0 1 0 4 0a2 2 0 1 0 -4 0 M16 18a2 2 0 1 0 4 0a2 2 0 1 0 -4 0 M8 18h8 M18 20v1 M18 3v1 M6 20v1 M6 10v-7 M12 3v18 M18 8v8 M8 12h13 M21 6h-1 M16 6h-13 M3 12h1 M20 18h1 M3 18h1 M6 14v2"),
		// Tabler "list-details" (viewBox 0 0 24 24, stroked).
		spaceProperties = simpleIcon("M13 5h8 M13 9h5 M13 15h8 M13 19h5 M3 5a1 1 0 0 1 1 -1h4a1 1 0 0 1 1 1v4a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1l0 -4 M3 15a1 1 0 0 1 1 -1h4a1 1 0 0 1 1 1v4a1 1 0 0 1 -1 1h-4a1 1 0 0 1 -1 -1l0 -4"),
		// Tabler "tools" (viewBox 0 0 24 24, stroked).
		spaceTool = simpleIcon("M3 21h4l13 -13a1.5 1.5 0 0 0 -4 -4l-13 13v4 M14.5 5.5l4 4 M12 8l-5 -5l-4 4l5 5 M7 8l-1.5 1.5 M16 12l5 5l-4 4l-5 -5 M16 17l-1.5 1.5"),
		// Tabler "movie" (viewBox 0 0 24 24, stroked).
		spaceAnimation = simpleIcon("M4 6a2 2 0 0 1 2 -2h12a2 2 0 0 1 2 2v12a2 2 0 0 1 -2 2h-12a2 2 0 0 1 -2 -2l0 -12 M8 4l0 16 M16 4l0 16 M4 8l4 0 M4 16l4 0 M4 12l16 0 M16 8l4 0 M16 16l4 0"),
		// Tabler "chevron-up" (viewBox 0 0 24 24, stroked).
		chevronUp = simpleIcon("M6 15l6 -6l6 6"),
		// Tabler "chevron-down" (viewBox 0 0 24 24, stroked).
		chevronDown = simpleIcon("M6 9l6 6l6 -6"),
		// Tabler "chevron-left" (viewBox 0 0 24 24, stroked).
		chevronLeft = simpleIcon("M15 6l-6 6l6 6"),
		// Tabler "chevron-right" (viewBox 0 0 24 24, stroked).
		chevronRight = simpleIcon("M9 6l6 6l-6 6"),
		// Tabler "dots" (viewBox 0 0 24 24, stroked).
		dots = simpleIcon("M4 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0 M11 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0 M18 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0"),
		// Tabler "grip-vertical" (viewBox 0 0 24 24, stroked).
		gripVertical = simpleIcon("M8 5a1 1 0 1 0 2 0a1 1 0 1 0 -2 0 M8 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0 M8 19a1 1 0 1 0 2 0a1 1 0 1 0 -2 0 M14 5a1 1 0 1 0 2 0a1 1 0 1 0 -2 0 M14 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0 M14 19a1 1 0 1 0 2 0a1 1 0 1 0 -2 0"),
		// Tabler "grip-horizontal" (viewBox 0 0 24 24, stroked).
		gripHorizontal = simpleIcon("M4 9a1 1 0 1 0 2 0a1 1 0 1 0 -2 0 M4 15a1 1 0 1 0 2 0a1 1 0 1 0 -2 0 M11 9a1 1 0 1 0 2 0a1 1 0 1 0 -2 0 M11 15a1 1 0 1 0 2 0a1 1 0 1 0 -2 0 M18 9a1 1 0 1 0 2 0a1 1 0 1 0 -2 0 M18 15a1 1 0 1 0 2 0a1 1 0 1 0 -2 0"),
		// Tabler "marquee-2" (viewBox 0 0 24 24, stroked) - the Box Select tool.
		toolBoxSelect = simpleIcon("M4 6v-1a1 1 0 0 1 1 -1h1m5 0h2m5 0h1a1 1 0 0 1 1 1v1m0 5v2m0 5v1a1 1 0 0 1 -1 1h-1m-5 0h-2m-5 0h-1a1 1 0 0 1 -1 -1v-1m0 -5v-2"),
		// Tabler "circle-dashed" (viewBox 0 0 24 24, stroked) - the Circle Select brush.
		toolCircleSelect = simpleIcon("M8.56 3.69a9 9 0 0 0 -2.92 1.95 M3.69 8.56a9 9 0 0 0 -.69 3.44 M3.69 15.44a9 9 0 0 0 1.95 2.92 M8.56 20.31a9 9 0 0 0 3.44 .69 M15.44 20.31a9 9 0 0 0 2.92 -1.95 M20.31 15.44a9 9 0 0 0 .69 -3.44 M20.31 8.56a9 9 0 0 0 -1.95 -2.92 M15.44 3.69a9 9 0 0 0 -3.44 -.69"),
		// Tabler "arrows-move" (viewBox 0 0 24 24, stroked) - the Grab (move) operator.
		toolGrab = simpleIcon("M18 9l3 3l-3 3 M15 12h6 M6 9l-3 3l3 3 M3 12h6 M9 18l3 3l3 -3 M12 15v6 M9 6l3 -3l3 3 M12 3v6"),
		// Tabler "rotate-clockwise" (viewBox 0 0 24 24, stroked) - the Rotate operator.
		toolRotate = simpleIcon("M4.05 11a8 8 0 1 1 .5 4m-.5 5v-5h5"),
		// Tabler "arrows-diagonal" (viewBox 0 0 24 24, stroked) - the Scale operator.
		toolScale = simpleIcon("M16 4l4 0l0 4 M14 10l6 -6 M8 20l-4 0l0 -4 M4 20l6 -6"),
		// Tabler "focus-centered" (viewBox 0 0 24 24, stroked) - the transform pivot point.
		transformPivot = simpleIcon("M12 12m-1.5 0a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0 M4 8v-2a2 2 0 0 1 2 -2h2 M4 16v2a2 2 0 0 0 2 2h2 M16 4h2a2 2 0 0 1 2 2v2 M16 20h2a2 2 0 0 0 2 -2v-2"),
		// Tabler "magnet" (viewBox 0 0 24 24, stroked) - the snap operations.
		snap = simpleIcon("M4 13v-8a2 2 0 0 1 2 -2h1a2 2 0 0 1 2 2v8a2 2 0 0 0 6 0v-8a2 2 0 0 1 2 -2h1a2 2 0 0 1 2 2v8a8 8 0 0 1 -16 0 M4 8l5 0 M15 8l4 0"),
		// Concentric circles in the Tabler idiom (authored here) - proportional editing's influence rings.
		proportionalEdit = simpleIcon("M12 12m-4 0a4 4 0 1 0 8 0a4 4 0 1 0 -8 0 M12 12m-9 0a9 9 0 1 0 18 0a9 9 0 1 0 -18 0"),
		// Tabler "terminal" (viewBox 0 0 24 24, stroked).
		logsTerminal = simpleIcon("M5 7l5 5l-5 5 M12 19l7 0"),
		// Tabler "copy" (viewBox 0 0 24 24, stroked).
		copy = simpleIcon("M7 9.667a2.667 2.667 0 0 1 2.667 -2.667h8.666a2.667 2.667 0 0 1 2.667 2.667v8.666a2.667 2.667 0 0 1 -2.667 2.667h-8.666a2.667 2.667 0 0 1 -2.667 -2.667l0 -8.66 M4.012 16.737a2.005 2.005 0 0 1 -1.012 -1.737v-10c0 -1.1 .9 -2 2 -2h10c.75 0 1.158 .385 1.5 1"),
		// Tabler "floppy" (viewBox 0 0 24 24, stroked).
		floppy = simpleIcon("M6 4h10l4 4v10a2 2 0 0 1 -2 2h-12a2 2 0 0 1 -2 -2v-12a2 2 0 0 1 2 -2 M10 14a2 2 0 1 0 4 0a2 2 0 1 0 -4 0 M14 4l0 4l-6 0l0 -4"),
		// Tabler "layers-selected" (viewBox 0 0 24 24, stroked).
		sources = simpleIcon("M8 10.5l6.492 -6.492 M13.496 16l6.504 -6.504l-6.504 6.504 M8.586 15.414l10.827 -10.827 M8 6a2 2 0 0 1 2 -2h8a2 2 0 0 1 2 2v8a2 2 0 0 1 -2 2h-8a2 2 0 0 1 -2 -2l0 -8 M16 16v2a2 2 0 0 1 -2 2h-8a2 2 0 0 1 -2 -2v-8a2 2 0 0 1 2 -2h2"),
		// Tabler "file-unknown" (viewBox 0 0 24 24, stroked).
		missingFile = simpleIcon("M14 3v4a1 1 0 0 0 1 1h4 M17 21h-10a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2h7l5 5v11a2 2 0 0 1 -2 2 M12 17v.01 M12 14a1.5 1.5 0 1 0 -1.14 -2.474"),
		// Tabler "file-plus" (viewBox 0 0 24 24, stroked).
		addFile = simpleIcon("M14 3v4a1 1 0 0 0 1 1h4 M17 21h-10a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2h7l5 5v11a2 2 0 0 1 -2 2 M12 11l0 6 M9 14l6 0"),
		// Tabler "refresh" (viewBox 0 0 24 24, stroked).
		refresh = simpleIcon("M20 11a8.1 8.1 0 0 0 -15.5 -2m-.5 -4v4h4 M4 13a8.1 8.1 0 0 0 15.5 2m.5 4v-4h-4"),
		// Tabler "refresh-alert" (viewBox 0 0 24 24, stroked).
		refreshAlert = simpleIcon("M20 11a8.1 8.1 0 0 0 -15.5 -2m-.5 -4v4h4 M4 13a8.1 8.1 0 0 0 15.5 2m.5 4v-4h-4 M12 9l0 3 M12 15l.01 0"),
		// Tabler "wand" (viewBox 0 0 24 24, stroked).
		wand = simpleIcon("M6 21l15 -15l-3 -3l-15 15l3 3 M15 6l3 3 M9 3a2 2 0 0 0 2 2a2 2 0 0 0 -2 2a2 2 0 0 0 -2 -2a2 2 0 0 0 2 -2 M19 13a2 2 0 0 0 2 2a2 2 0 0 0 -2 2a2 2 0 0 0 -2 -2a2 2 0 0 0 2 -2"),
		// Tabler "language" (viewBox 0 0 24 24, stroked).
		language = simpleIcon("M9 6.371c0 4.418 -2.239 6.629 -5 6.629 M4 6.371h7 M5 9c0 2.144 2.252 3.908 6 4 M12 20l4 -9l4 9 M19.1 18h-6.2 M6.694 3l.793 .582"),
		// Overlays (docs/design/icons/overlay-circles.svg, modified from Tabler "chart-circles"; viewBox 0 0 24 24, filled).
		overlays =
			compositeIcon(
				compositeIconLayer("M 9.2207031,8.0058594 C 5.7710971,8.1530745 3,11.0155 3,14.5 3,18.077998 5.9219992,21 9.5,21 c 3.482541,0 6.343956,-2.767944 6.494141,-6.214844 A 5.5,5.5 0 0 1 14.5,15 5.5,5.5 0 0 1 13.976562,14.960938 C 13.748254,17.239475 11.841711,19 9.5,19 7.0028766,19 5,16.997122 5,14.5 5,12.160286 6.7574869,10.254409 9.0332031,10.023438 a 5.5,5.5 0 0 1 0.1875,-2.0175786 z"),
				compositeIconLayer("M 14.5 3 A 6.4999976 6.5 0 0 0 8.1484375 8.1425781 C 8.5847152 8.049561 9.03682 8 9.5 8 C 13.078 8 16 10.922002 16 14.5 C 16 14.958411 15.950536 15.405781 15.859375 15.837891 A 6.4999976 6.5 0 0 0 14.5 3 z M 9.5 10 C 8.9934221 10 8.5079895 10.08407 8.0546875 10.236328 A 6.4999976 6.5 0 0 0 13.763672 15.945312 C 13.915929 15.492011 14 15.006578 14 14.5 C 14 12.002878 11.997124 10 9.5 10 z"),
			),
		// The icons below are adapted from the Blender project's icon set (GPL-2.0-or-later, compatible
		// with this project's GPL-3.0; source SVGs archived under docs/design/icons/).  Each keeps
		// Blender's two-tone contrast: a full-strength highlight layer over a muted context layer.
		// Blender "blender_icon_object_datamode" (viewBox 0 0 24 24, baked).
		editorModeObject =
			compositeIcon(
				compositeIconLayer(
					"M2.25,1.5C1.839,1.5 1.5,1.839 1.5,2.25L1.5,7.5L3,7.5L3,3L7.5,3L7.5,1.5L2.25,1.5ZM16.5,1.5L16.5,3L21,3L21,7.5L22.5,7.5L22.5,2.25C22.5,1.839 22.161,1.5 21.75,1.5L16.5,1.5ZM1.5,16.5L1.5,21.75C1.5,22.161 1.839,22.5 2.25,22.5L7.5,22.5L7.5,21L3,21L3,16.5L1.5,16.5ZM21,16.5L21,21L16.5,21L16.5,22.5L21.75,22.5C22.161,22.5 22.5,22.161 22.5,21.75L22.5,16.5L21,16.5Z",
					opacity = 0.6f,
				),
				compositeIconLayer(
					"M6.75,6C6.336,6 6,6.336 6,6.75L6,17.25C6,17.664 6.336,18 6.75,18L17.25,18C17.664,18 18,17.664 18,17.25L18,6.75C18,6.336 17.664,6 17.25,6L6.75,6Z",
				),
			),
		// Blender "blender_icon_editmode_hlt" (viewBox 0 0 24 24, baked).
		editorModeEdit =
			compositeIcon(
				compositeIconLayer(
					"M21.75,1.5C22.164,1.5 22.5,1.836 22.5,2.25L22.5,8.25C22.5,8.664 22.164,9 21.75,9L15.75,9C15.336,9 15,8.664 15,8.25L15,2.25C15,1.836 15.336,1.5 15.75,1.5L21.75,1.5Z",
				),
				compositeIconLayer(
					"M21,21.75C21,22.161 20.661,22.5 20.25,22.5L15.75,22.5C15.339,22.5 15,22.161 15,21.75L15,20.988L7.5,20.988L7.5,21.75C7.5,22.161 7.161,22.5 6.75,22.5L2.25,22.5C1.839,22.5 1.5,22.161 1.5,21.75L1.5,17.25C1.5,16.839 1.839,16.5 2.25,16.5L3,16.5L3,9L2.25,9C1.839,9 1.5,8.661 1.5,8.25L1.5,3.75C1.5,3.339 1.839,3 2.25,3L6.75,3C7.161,3 7.5,3.339 7.5,3.75L7.5,4.488L12.75,4.488C12.754,4.488 12.757,4.488 12.761,4.488C13.172,4.488 13.511,4.827 13.511,5.238C13.511,5.65 13.172,5.988 12.761,5.988C12.757,5.988 12.754,5.988 12.75,5.988L7.5,5.988L7.5,8.25C7.5,8.661 7.161,9 6.75,9L4.5,9L4.5,16.5L6.75,16.5C7.161,16.5 7.5,16.839 7.5,17.25L7.5,19.488L15,19.488L15,17.25C15,16.839 15.339,16.5 15.75,16.5L18,16.5L18,11.25C18,11.246 18,11.243 18,11.239C18,10.828 18.339,10.489 18.75,10.489C19.161,10.489 19.5,10.828 19.5,11.239C19.5,11.243 19.5,11.246 19.5,11.25L19.5,16.5L20.25,16.5C20.661,16.5 21,16.839 21,17.25L21,21.75ZM19.5,21L19.5,18L16.5,18L16.5,20.121C16.513,20.201 16.513,20.282 16.5,20.361L16.5,21L19.5,21ZM6,21L6,18L3,18L3,21L6,21ZM6,7.5L6,4.5L3,4.5L3,7.5L6,7.5Z",
					opacity = 0.6f,
				),
			),
		// Blender "blender_icon_vertexsel" (viewBox 0 0 24 23, baked; re-centered to 24x24).
		meshSelectVertex =
			compositeIcon(
				compositeIconLayer(
					"M8.246,1.583C8.047,1.583 7.856,1.662 7.715,1.803L4.724,4.794C3.988,5.501 5.078,6.591 5.785,5.855L8.556,3.083L20.996,3.083L20.996,15.523L16.935,19.583L4.499,19.583L4.505,15.074L3.005,15.071L2.996,20.333C2.996,20.748 3.331,21.083 3.746,21.083L17.246,21.083C17.445,21.083 17.635,21.004 17.776,20.864L22.276,16.364C22.417,16.223 22.496,16.032 22.496,15.833L22.496,2.333C22.496,1.919 22.16,1.583 21.746,1.583L8.246,1.583Z",
					opacity = 0.6f,
				),
				compositeIconLayer(
					"M2.254,7.575C1.84,7.575 1.504,7.91 1.504,8.325L1.504,12.825C1.504,13.239 1.84,13.575 2.254,13.575L6.754,13.575C7.169,13.575 7.504,13.239 7.504,12.825L7.504,8.325C7.504,7.91 7.169,7.575 6.754,7.575L2.254,7.575Z",
				),
				offsetY = 0.5f,
			),
		// Blender "blender_icon_edgesel" (viewBox 0 0 23 24, baked; re-centered to 24x24).
		meshSelectEdge =
			compositeIcon(
				compositeIconLayer(
					"M15.095,21.744C15.095,22.158 14.759,22.494 14.345,22.494L11.345,22.494C10.931,22.494 10.595,22.158 10.595,21.744L10.595,8.244C10.595,7.83 10.931,7.494 11.345,7.494L14.345,7.494C14.759,7.494 15.095,7.83 15.095,8.244L15.095,21.744Z",
				),
				compositeIconLayer(
					"M21.083,15.756C21.083,15.955 21.004,16.145 20.864,16.286L17.875,19.274C17.168,20.011 16.078,18.921 16.815,18.214L19.583,15.445L19.583,3.006L7.144,3.006L3.083,7.066L3.083,19.506L9.094,19.495L9.097,20.995L2.336,21.006C1.921,21.008 1.583,20.671 1.583,20.256L1.583,6.756C1.583,6.557 1.662,6.366 1.803,6.226L6.303,1.726C6.444,1.585 6.634,1.506 6.833,1.506L20.333,1.506C20.748,1.506 21.083,1.842 21.083,2.256L21.083,15.756Z",
					opacity = 0.6f,
				),
				offsetX = 0.5f,
			),
		// Blender "blender_icon_facesel" (viewBox 0 0 24 24, baked).
		meshSelectFace =
			compositeIcon(
				compositeIconLayer(
					"M2.256,7.494C1.842,7.494 1.506,7.83 1.506,8.244L1.506,21.744C1.506,22.158 1.842,22.494 2.256,22.494L15.756,22.494C16.17,22.494 16.506,22.158 16.506,21.744L16.506,8.244C16.506,7.83 16.17,7.494 15.756,7.494L2.256,7.494Z",
				),
				compositeIconLayer(
					"M8.244,1.506C8.045,1.506 7.855,1.585 7.714,1.726L4.726,4.714C4.545,4.888 4.506,5.057 4.506,5.244L4.506,5.994L6.006,5.994L6.01,5.55L8.555,3.006L20.994,3.006L20.994,15.445L18.449,17.99L18.006,17.994L18.006,19.494L18.756,19.494C18.932,19.494 19.112,19.455 19.274,19.286L22.274,16.286C22.415,16.145 22.494,15.955 22.494,15.756L22.494,2.256C22.494,1.842 22.158,1.506 21.744,1.506L8.244,1.506Z",
					opacity = 0.6f,
				),
			),
		// Blender "blender_icon_uv_vertexsel" (viewBox 0 0 24 24, baked).
		uvSelectVertex =
			compositeIcon(
				compositeIconLayer(
					"M2.25,1.5C1.836,1.5 1.5,1.836 1.5,2.25L1.5,6.75C1.5,7.164 1.836,7.5 2.25,7.5L6.75,7.5C7.164,7.5 7.5,7.164 7.5,6.75L7.5,2.25C7.5,1.836 7.164,1.5 6.75,1.5L2.25,1.5Z",
				),
				compositeIconLayer(
					"M8.945,3L8.945,4.5L12,4.5L12,12L4.5,12L4.5,9.012L3,9.012L3,21.75C3,22.161 3.339,22.5 3.75,22.5L12.75,22.5C13.162,22.5 13.5,22.161 13.5,21.75L13.5,13.5L21.75,13.5C22.162,13.5 22.5,13.161 22.5,12.75L22.5,3.75C22.5,3.338 22.162,3 21.75,3L8.945,3ZM13.5,4.5L21,4.5L21,12L13.5,12L13.5,4.5ZM4.5,13.5L12,13.5L12,21L4.5,21L4.5,13.5Z",
					opacity = 0.6f,
				),
			),
		// Blender "blender_icon_uv_edgesel" (viewBox 0 0 23 24, baked; re-centered to 24x24).
		uvSelectEdge =
			compositeIcon(
				compositeIconLayer(
					"M9.088,2.25C9.088,1.836 9.424,1.5 9.838,1.5L12.838,1.5C13.253,1.5 13.588,1.836 13.588,2.25L13.588,14.25C13.588,14.664 13.253,15 12.838,15L9.838,15C9.424,15 9.088,14.664 9.088,14.25L9.088,2.25Z",
				),
				compositeIconLayer(
					"M1.588,3.75C1.588,3.336 1.924,3 2.338,3L7.588,3L7.588,4.5L3.088,4.5L3.088,12L7.588,12L7.588,13.5L3.088,13.5L3.088,21L10.588,21L10.588,16.5L12.088,16.5L12.088,21.75C12.088,22.164 11.753,22.5 11.338,22.5L2.338,22.5C1.924,22.5 1.588,22.164 1.588,21.75L1.588,3.75ZM15.088,3L20.328,3.007C20.742,3.007 21.078,3.343 21.078,3.757L21.078,12.757C21.078,13.17 20.744,13.506 20.331,13.507L15.081,13.51L15.078,12.01L19.578,12.01L19.578,4.51L15.091,4.5L15.088,3Z",
					opacity = 0.6f,
				),
				offsetX = 0.5f,
			),
		// Blender "blender_icon_uv_facesel" (viewBox 0 0 24 24, baked).
		uvSelectFace =
			compositeIcon(
				compositeIconLayer(
					"M21.747,3.003L16.497,3.015L16.503,4.515L21,4.506L21,12.006L16.497,12.015L16.503,13.515L21.753,13.503C22.166,13.501 22.5,13.166 22.5,12.753L22.5,3.753C22.5,3.337 22.162,3.001 21.747,3.003L21.747,3.003ZM3,16.506L3,21.744C3,22.158 3.336,22.494 3.75,22.494L12.75,22.494C13.164,22.494 13.5,22.158 13.5,21.744L13.5,16.506L12,16.506L12,20.994L4.5,20.994L4.5,16.506L3,16.506Z",
					opacity = 0.6f,
				),
				compositeIconLayer(
					"M2.25,1.506C1.836,1.506 1.5,1.842 1.5,2.256L1.5,14.256C1.5,14.67 1.836,15.006 2.25,15.006L14.25,15.006C14.664,15.006 15,14.67 15,14.256L15,2.256C15,1.842 14.664,1.506 14.25,1.506L2.25,1.506Z",
				),
			),
		// Blender "blender_icon_pivot_median" (viewBox 0 0 24 24, baked) - the Median Point pivot.
		pivotMedian =
			compositeIcon(
				compositeIconLayer(
					"M16.219,1.523C19.54,1.797 22.186,4.434 22.474,7.755C22.762,11.075 20.609,14.132 17.385,14.973C16.984,15.079 16.573,14.839 16.468,14.437C16.363,14.036 16.603,13.625 17.004,13.52C19.53,12.861 21.206,10.486 20.98,7.884C20.754,5.281 18.697,3.232 16.096,3.017C13.495,2.803 11.128,4.489 10.48,7.019C10.416,7.282 10.216,7.491 9.956,7.565C9.696,7.639 9.416,7.567 9.224,7.377C9.031,7.187 8.956,6.908 9.027,6.647C9.853,3.419 12.899,1.25 16.219,1.523ZM8.25,9C8.521,8.996 8.772,9.138 8.909,9.372C9.045,9.605 9.045,9.894 8.909,10.128C8.772,10.362 8.521,10.504 8.25,10.5C6.125,10.5 4.212,11.776 3.399,13.74C2.585,15.704 3.035,17.959 4.538,19.462C6.042,20.965 8.296,21.415 10.26,20.601C12.224,19.788 13.5,17.875 13.5,15.75C13.497,15.479 13.639,15.228 13.872,15.091C14.106,14.955 14.395,14.955 14.629,15.091C14.862,15.228 15.004,15.479 15,15.75C15,18.478 13.355,20.943 10.834,21.987C8.314,23.031 5.407,22.451 3.478,20.522C1.549,18.593 0.969,15.686 2.013,13.166C3.057,10.645 5.522,9 8.25,9Z",
				),
				compositeIconLayer(
					"M14.995,11.253C14.995,12.494 13.989,13.5 12.748,13.5C12.152,13.5 11.58,13.263 11.159,12.842C10.737,12.42 10.5,11.849 10.5,11.253C10.5,10.657 10.737,10.085 11.159,9.663C11.58,9.242 12.152,9.005 12.748,9.005C13.989,9.005 14.995,10.011 14.995,11.253Z",
				),
			),
		// Blender "blender_icon_pivot_individual" (viewBox 0 0 24 24, baked) - the Individual Origins pivot.
		pivotIndividual =
			compositeIcon(
				compositeIconLayer(
					"M8.25,9C11.97,9 15,12.031 15,15.75C15,19.469 11.97,22.5 8.25,22.5C4.531,22.5 1.5,19.469 1.5,15.75C1.5,12.031 4.531,9 8.25,9ZM8.25,10.5C5.342,10.5 3,12.841 3,15.75C3,18.658 5.342,21 8.25,21C11.159,21 13.5,18.658 13.5,15.75C13.5,12.841 11.159,10.5 8.25,10.5Z",
				),
				compositeIconLayer(
					"M16.304,1.523C19.579,1.793 22.19,4.393 22.474,7.667C22.758,10.94 20.635,13.951 17.456,14.78C17.193,14.858 16.909,14.787 16.715,14.594C16.52,14.402 16.446,14.119 16.52,13.856C16.595,13.592 16.808,13.391 17.075,13.33C19.556,12.682 21.202,10.351 20.98,7.796C20.758,5.241 18.737,3.228 16.181,3.017C13.625,2.807 11.301,4.462 10.665,6.946C10.601,7.209 10.401,7.417 10.141,7.491C9.881,7.565 9.601,7.493 9.409,7.304C9.216,7.114 9.141,6.835 9.211,6.574C10.027,3.391 13.03,1.254 16.304,1.523Z",
				),
				compositeIconLayer(
					"M10.5,15.747C10.5,16.988 9.494,17.994 8.253,17.994C7.012,17.994 6.006,16.988 6.006,15.747C6.006,14.506 7.012,13.5 8.253,13.5C8.849,13.5 9.421,13.736 9.842,14.158C10.264,14.579 10.5,15.151 10.5,15.747Z",
				),
				compositeIconLayer(
					"M18,8.247C18,9.488 16.994,10.494 15.753,10.494C14.512,10.494 13.506,9.488 13.506,8.247C13.506,7.006 14.512,6 15.753,6C16.349,6 16.921,6.236 17.342,6.658C17.764,7.079 18,7.651 18,8.247Z",
				),
			),
		// Blender "blender_icon_pivot_active" (viewBox 0 0 24 24, baked) - the Active Element pivot.
		pivotActive =
			compositeIcon(
				compositeIconLayer(
					"M8.25,9C11.97,9 15,12.031 15,15.75C15,19.469 11.97,22.5 8.25,22.5C4.531,22.5 1.5,19.469 1.5,15.75C1.5,12.031 4.531,9 8.25,9ZM8.25,10.5C5.342,10.5 3,12.841 3,15.75C3,18.658 5.342,21 8.25,21C11.159,21 13.5,18.658 13.5,15.75C13.5,12.841 11.159,10.5 8.25,10.5Z",
				),
				compositeIconLayer(
					"M16.304,1.523C19.579,1.793 22.19,4.393 22.474,7.667C22.758,10.94 20.635,13.951 17.456,14.78C17.193,14.858 16.909,14.787 16.715,14.594C16.52,14.402 16.446,14.119 16.52,13.856C16.595,13.592 16.808,13.391 17.075,13.33C19.556,12.682 21.202,10.351 20.98,7.796C20.758,5.241 18.737,3.228 16.181,3.017C13.625,2.807 11.301,4.462 10.665,6.946C10.601,7.209 10.401,7.417 10.141,7.491C9.881,7.565 9.601,7.493 9.409,7.304C9.216,7.114 9.141,6.835 9.211,6.574C10.027,3.391 13.03,1.254 16.304,1.523Z",
					opacity = 0.6f,
				),
				compositeIconLayer(
					"M10.5,15.747C10.5,16.988 9.494,17.994 8.253,17.994C7.012,17.994 6.006,16.988 6.006,15.747C6.006,14.506 7.012,13.5 8.253,13.5C8.849,13.5 9.421,13.736 9.842,14.158C10.264,14.579 10.5,15.151 10.5,15.747Z",
				),
			),
		// Blender "blender_icon_pivot_cursor" (viewBox 0 0 24 24, baked) - the 2D Cursor pivot, and the
		// cursor in the UV snap pie (Blender's UV editor uses this one where the 3D view uses "cursor").
		pivotCursor =
			compositeIcon(
				compositeIconLayer(
					"M14.995,11.998C14.995,13.652 13.654,14.993 12,14.993C10.346,14.993 9.006,13.652 9.006,11.998C9.006,10.344 10.346,9.003 12,9.003C13.654,9.003 14.995,10.344 14.995,11.998ZM2.241,1.492C1.937,1.493 1.664,1.678 1.549,1.96C1.435,2.242 1.502,2.565 1.72,2.778L6.22,7.278C5.161,8.571 4.5,10.201 4.5,11.998C4.5,13.794 5.161,15.424 6.22,16.718L1.72,21.218C1.524,21.406 1.445,21.685 1.514,21.948C1.582,22.21 1.787,22.416 2.05,22.484C2.313,22.553 2.592,22.474 2.78,22.278L7.28,17.778C8.574,18.837 10.204,19.498 12,19.498C13.796,19.498 15.426,18.837 16.72,17.778L21.22,22.278C21.408,22.474 21.687,22.553 21.95,22.484C22.213,22.416 22.418,22.21 22.486,21.948C22.555,21.685 22.476,21.406 22.28,21.218L17.78,16.718C18.839,15.424 19.5,13.794 19.5,11.998C19.5,10.201 18.839,8.571 17.78,7.278L22.28,2.778C22.501,2.562 22.567,2.233 22.447,1.949C22.326,1.665 22.044,1.483 21.735,1.492C21.541,1.497 21.356,1.578 21.22,1.718L16.72,6.218C15.426,5.159 13.796,4.498 12,4.498C10.204,4.498 8.574,5.159 7.28,6.218L2.78,1.718C2.638,1.573 2.444,1.491 2.241,1.492ZM12,5.998C15.323,5.998 18,8.675 18,11.998C18,15.32 15.323,17.998 12,17.998C8.677,17.998 6,15.32 6,11.998C6,8.675 8.677,5.998 12,5.998Z",
				),
			),
		// Blender "blender_icon_cursor" (viewBox 0 0 24 24, baked) - the cursor as a monochrome glyph, for
		// the snap pie's cursor entries; cursor2d below is the viewport's own multi-color drawing.
		cursor =
			compositeIcon(
				compositeIconLayer(
					"M2.241,1.492C1.937,1.493 1.664,1.678 1.549,1.96C1.435,2.242 1.502,2.565 1.72,2.778L6.22,7.278C5.161,8.571 4.5,10.201 4.5,11.998C4.5,13.794 5.161,15.424 6.22,16.717L1.72,21.217C1.524,21.406 1.445,21.685 1.514,21.948C1.582,22.21 1.787,22.416 2.05,22.484C2.313,22.553 2.592,22.474 2.78,22.278L7.28,17.778C8.574,18.836 10.204,19.498 12,19.498C13.796,19.498 15.426,18.836 16.72,17.778L21.22,22.278C21.408,22.474 21.687,22.553 21.95,22.484C22.213,22.415 22.418,22.21 22.486,21.948C22.555,21.685 22.476,21.406 22.28,21.217L17.78,16.717C18.839,15.424 19.5,13.794 19.5,11.998C19.5,10.201 18.839,8.571 17.78,7.278L22.28,2.778C22.501,2.562 22.567,2.233 22.447,1.949C22.326,1.665 22.044,1.483 21.735,1.492C21.541,1.497 21.356,1.578 21.22,1.717L16.72,6.217C15.426,5.159 13.796,4.498 12,4.498C10.204,4.498 8.574,5.159 7.28,6.217L2.78,1.717C2.639,1.572 2.444,1.491 2.241,1.492ZM12,5.998C15.323,5.998 18,8.675 18,11.998C18,15.32 15.323,17.998 12,17.998C8.678,17.998 6,15.32 6,11.998C6,8.675 8.677,5.998 12,5.998Z",
				),
			),
		// Blender "blender_icon_restrict_select_off" (viewBox 0 0 24 24, baked) - the selection, for the snap
		// pies' selection entries.
		selection =
			compositeIcon(
				compositeIconLayer(
					"M3.804,5.463L9.775,20.518C10.042,21.193 11.046,21.146 11.228,20.443L12.992,13.739L19.692,11.976C20.389,11.791 20.449,10.824 19.78,10.555L4.806,4.552C4.02,4.336 3.591,4.926 3.804,5.463Z",
				),
			),
		// The 2D cursor (docs/design/cursors/2DCursor.svg, authored 1600x1600, converted verbatim):
		// a red/white dashed ring with axis-colored arm stubs - red X arms, blue Z arms - over
		// 0.85-alpha black arm shafts.  Genuinely multi-color, so every layer bakes its own paint
		// (the SVG's rects are rewritten as equivalent M/h/v/z path data; PathParser reads paths only).
		cursor2d =
			UmamoIcon(
				layers =
					listOf(
						// The ring's white sectors, then its red sectors.
						compositeIconLayer(
							"M377.065,801.822C376.711,725.254 397.337,651.256 434.776,587.043L496.976,623.308C465.915,676.582 448.771,737.966 449.064,801.489L377.065,801.822ZM801.808,377.064L801.517,449.063C771.034,448.94 740.038,452.831 709.191,461.097C678.344,469.362 649.555,481.49 623.218,496.838L586.966,434.63C618.702,416.136 653.386,401.51 690.556,391.55C727.726,381.59 765.077,376.915 801.808,377.064ZM1167.18,590.109L1104.66,625.821C1073.16,570.661 1027.62,526.074 974.08,495.468L1009.81,432.961C1074.34,469.851 1129.2,523.623 1167.18,590.109ZM1165.22,1012.96L1103.02,976.692C1134.09,923.418 1151.23,862.034 1150.94,798.511L1222.93,798.178C1223.29,874.746 1202.66,948.744 1165.22,1012.96ZM798.191,1222.94L798.482,1150.94C828.965,1151.06 859.961,1147.17 890.809,1138.9C921.656,1130.64 950.445,1118.51 976.782,1103.16L1013.03,1165.37C981.298,1183.86 946.614,1198.49 909.444,1208.45C872.273,1218.41 834.923,1223.09 798.191,1222.94ZM432.817,1009.89L495.336,974.179C526.844,1029.34 572.384,1073.93 625.92,1104.53L590.186,1167.04C525.657,1130.15 470.795,1076.38 432.817,1009.89Z",
							color = Color.White,
						),
						compositeIconLayer(
							"M434.776,1012.96C397.337,948.745 376.712,874.747 377.065,798.18L449.064,798.512C448.771,862.036 465.915,923.419 496.976,976.693L434.776,1012.96ZM590.185,432.962L625.919,495.468C572.383,526.074 526.844,570.662 495.336,625.822L432.816,590.11C470.794,523.624 525.656,469.852 590.185,432.962ZM1013.03,434.63L976.782,496.838C950.445,481.49 921.656,469.362 890.809,461.097C859.962,452.831 828.966,448.94 798.483,449.063L798.192,377.064C834.923,376.915 872.274,381.59 909.444,391.55C946.614,401.51 981.298,416.136 1013.03,434.63ZM1222.93,801.82L1150.94,801.488C1151.23,737.964 1134.09,676.581 1103.02,623.307L1165.22,587.042C1202.66,651.255 1223.29,725.253 1222.93,801.82ZM1009.82,1167.04L974.081,1104.53C1027.62,1073.93 1073.16,1029.34 1104.66,974.178L1167.18,1009.89C1129.21,1076.38 1074.34,1130.15 1009.82,1167.04ZM586.965,1165.37L623.217,1103.16C649.555,1118.51 678.343,1130.64 709.191,1138.9C740.038,1147.17 771.034,1151.06 801.517,1150.94L801.808,1222.94C765.077,1223.09 727.726,1218.41 690.556,1208.45C653.386,1198.49 618.701,1183.86 586.965,1165.37Z",
							color = Color(0xFFFF0000),
						),
						// The horizontal (X) arms: 0.85-alpha black shafts with red tips.
						compositeIconLayer("M120,760.609h480v80h-480z", color = Color.Black, opacity = 0.85f),
						compositeIconLayer("M0,760.304h120v80h-120z", color = Color(0xFFFF0000)),
						compositeIconLayer("M1000,760.304h480v80h-480z", color = Color.Black, opacity = 0.85f),
						compositeIconLayer("M1480,760h120v80h-120z", color = Color(0xFFFF0000)),
						// The vertical (Z) arms: 0.85-alpha black shafts with blue tips.
						compositeIconLayer(
							"M839.696,600.304L759.696,600.304L759.696,120.304L839.696,120.304L839.696,600.304Z",
							color = Color.Black,
							opacity = 0.85f,
						),
						compositeIconLayer("M840,120.304L760,120.304L760,0.304L840,0.304L840,120.304Z", color = Color(0xFF0000FF)),
						compositeIconLayer(
							"M840,1000.3L760,1000.3L760,1480.3L840,1480.3L840,1000.3Z",
							color = Color.Black,
							opacity = 0.85f,
						),
						compositeIconLayer("M840.304,1480.3L760.304,1480.3L760.304,1600.3L840.304,1600.3L840.304,1480.3Z", color = Color(0xFF0000FF)),
					),
				viewportSize = 1600f,
			),
	)

/**
 * Draws [icon] scaled about the origin to fill the current square [DrawScope], painting its layers in
 * order with each layer's style and opacity.  The caller supplies one tint; a two-tone icon's muted
 * layer derives from it via the layer opacity, so both tones follow the theme without a second color.
 *
 * @param UmamoIcon icon  The icon to paint.
 * @param Color     color The paint color (icons are monochrome and tinted by the caller).
 */
fun DrawScope.drawIcon(icon: UmamoIcon, color: Color) {
	// The paths live in a square viewport; scale it about the origin so it fills the (square) canvas.
	val drawScale = size.minDimension / icon.viewportSize
	withTransform({ scale(scaleX = drawScale, scaleY = drawScale, pivot = Offset.Zero) }) {
		for (layer in icon.layers) {
			val drawStyle =
				when (val style = layer.style) {
					IconStyle.Filled -> Fill
					is IconStyle.Outlined ->
						Stroke(
							width = style.strokeWidth,
							cap = StrokeCap.Round,
							join = StrokeJoin.Round,
						)
				}
			drawPath(path = layer.path, color = layer.color ?: color, alpha = layer.opacity, style = drawStyle)
		}
	}
}