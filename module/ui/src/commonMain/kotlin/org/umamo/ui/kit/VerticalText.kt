package org.umamo.ui.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.isSpecified
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.theme.rememberCjkFontFamily
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * How far the ideographic em box reaches above the baseline, as a fraction of the font size: Noto Sans CJK's
 * OS/2 sTypoAscender (880 of 1000 units), which its ideographs are designed to.
 */
private const val EM_BOX_ASCENT = 0.88f

/**
 * The OpenType feature that swaps in a glyph's vertical form.  Written bare: the desktop text stack reads
 * only a bare four-letter tag and silently drops the quoted CSS form.
 */
private const val VERTICAL_FORMS_FEATURE = "vert"

/** The ellipsis after rotated text, lying on its side with it. */
private const val ROTATED_ELLIPSIS = "…"

/** The ellipsis after upright text: the vertical ellipsis, which stands in a cell of its own. */
private const val UPRIGHT_ELLIPSIS = "︙"

/**
 * One measured piece of a label, ready to draw.
 *
 * @property TextLayoutResult layout The piece's horizontal layout.
 * @property Boolean rotated Whether it is drawn turned a quarter turn clockwise.
 * @property Float left For a rotated piece, the x the turned layout's top edge lands on; for an upright one,
 *   the layout's left edge.  Whole px.
 * @property Float top The y the layout's start lands on.  Whole px.
 */
internal class DrawnPiece(
	val layout: TextLayoutResult,
	val rotated: Boolean,
	val left: Float,
	val top: Float,
)

/**
 * A vertical label measured and laid out, keyed only by what changes its geometry, so a color change (a
 * hover, a selection) draws it again without measuring it again.
 *
 * @property String text The full label, which the semantics carry even when it is cut short.
 * @property Boolean truncated Whether the label was cut short to fit its maximum length.
 * @property Int widthPx The column's width.
 * @property Int heightPx The column's height.
 * @property List pieces The pieces to draw, each where the layout placed it.
 */
@Immutable
internal class MeasuredVerticalText internal constructor(
	val text: String,
	val truncated: Boolean,
	val widthPx: Int,
	val heightPx: Int,
	private val pieces: List<DrawnPiece>,
) {
	/**
	 * Draws the label into [drawScope] in [color], each piece where the layout placed it.
	 *
	 * @param DrawScope drawScope The scope to draw into, the column's own.
	 * @param Color color The text color.
	 */
	fun draw(drawScope: DrawScope, color: Color) {
		for (piece in pieces) {
			if (piece.rotated) {
				drawScope.withTransform({
					translate(left = piece.left, top = piece.top)
					rotate(degrees = 90f, pivot = Offset.Zero)
				}) {
					drawText(piece.layout, color = color)
				}
			} else {
				drawScope.drawText(piece.layout, color = color, topLeft = Offset(piece.left, piece.top))
			}
		}
	}
}

/**
 * Renders a label down a narrow column, for a vertical tab: the way Japanese is set vertically.  Kana,
 * kanji, Hangul, and CJK punctuation stand upright, one cell under the next, taking the vertical forms of
 * ー, the small kana, and the punctuation; Latin and every other horizontal script lies on its side, turned
 * a quarter turn clockwise so it reads top to bottom with the letter tops facing right.  The column is the
 * style's line height wide whatever the script, so labels in one style line up.
 *
 * A label that would run past [maxLength] is cut after its last whole character that leaves room for an
 * ellipsis.  A parent that bounds the column's height only clips it; [maxLength] is what shortens it.
 *
 * @param String text The label.
 * @param Modifier modifier Layout modifier.
 * @param TextStyle style The text style (defaults to the label scale).
 * @param Color color Explicit color, or Unspecified to inherit from the style/theme.
 * @param Dp maxLength The longest the column may run, or Unspecified for no limit.
 * @param Function? onTruncation Called after each composition with whether the label was cut short.
 */
@Composable
fun VerticalText(
	text: String,
	modifier: Modifier = Modifier,
	style: TextStyle = LocalUmamoTypography.current.labelMedium,
	color: Color = Color.Unspecified,
	maxLength: Dp = Dp.Unspecified,
	onTruncation: ((Boolean) -> Unit)? = null,
) {
	val measured = rememberMeasuredVerticalText(text, style, maxLength)
	val resolvedColor = color.takeOrElse { style.color.takeOrElse { LocalUmamoColors.current.text } }
	val latestOnTruncation by rememberUpdatedState(onTruncation)
	SideEffect { latestOnTruncation?.invoke(measured.truncated) }
	VerticalText(measured, resolvedColor, modifier)
}

/**
 * Renders a label [rememberMeasuredVerticalText] already measured: the half of [VerticalText] a caller uses
 * when it needs to know whether the label was cut short in the same composition, as a tab that shows the
 * whole label in a tooltip when it was.
 *
 * @param MeasuredVerticalText measured The measured label.
 * @param Color color The text color, already resolved.
 * @param Modifier modifier Layout modifier.
 */
@Composable
internal fun VerticalText(measured: MeasuredVerticalText, color: Color, modifier: Modifier = Modifier) {
	Layout(
		modifier =
			modifier
				.semantics { text = AnnotatedString(measured.text) }
				.drawBehind { measured.draw(this, color) },
	) { _, constraints ->
		layout(constraints.constrainWidth(measured.widthPx), constraints.constrainHeight(measured.heightPx)) {}
	}
}

/**
 * Measures and lays out a vertical label (see [VerticalText]), again only when the text, the style's
 * geometry, the maximum length, the fonts, or the density change.
 *
 * @param String text The label.
 * @param TextStyle style The text style; its color is ignored.
 * @param Dp maxLength The longest the column may run, or Unspecified for no limit.
 * @return MeasuredVerticalText The measured label.
 */
@Composable
internal fun rememberMeasuredVerticalText(text: String, style: TextStyle, maxLength: Dp): MeasuredVerticalText {
	val measurer = rememberTextMeasurer()
	val cjkFontFamily = rememberCjkFontFamily()
	val density = LocalDensity.current
	val geometryStyle = style.copy(color = Color.Unspecified)

	return remember(text, geometryStyle, maxLength, measurer, cjkFontFamily, density) {
		measureVerticalText(text, geometryStyle, cjkFontFamily, maxLength, measurer, density)
	}
}

/**
 * Measures each piece of a vertical label, lays the pieces down the column, and measures again the one
 * rotated run a cut falls inside, so the kept part draws on its own.
 *
 * @param String text The label.
 * @param TextStyle style The text style, color-free.
 * @param FontFamily cjkFontFamily The family upright cells are shaped in: the CJK cut alone, the only family
 *   the vertical forms shape correctly in.
 * @param Dp maxLength The longest the column may run, or Unspecified for no limit.
 * @param TextMeasurer measurer The measurer.
 * @param Density density The density the label is laid out at.
 * @return MeasuredVerticalText The measured label.
 */
private fun measureVerticalText(
	text: String,
	style: TextStyle,
	cjkFontFamily: FontFamily,
	maxLength: Dp,
	measurer: TextMeasurer,
	density: Density,
): MeasuredVerticalText {
	val singleLine = text.replace('\n', ' ').replace('\r', ' ')
	val uprightStyle = style.copy(fontFamily = cjkFontFamily, fontFeatureSettings = VERTICAL_FORMS_FEATURE)
	val emSize = with(density) { style.fontSize.toPx() }

	/**
	 * Lays one piece out on a single line.
	 *
	 * @param String piece The text to lay out.
	 * @param TextStyle pieceStyle The style to lay it out in.
	 * @return TextLayoutResult The layout.
	 */
	fun measurePiece(piece: String, pieceStyle: TextStyle): TextLayoutResult = measurer.measure(text = piece, style = pieceStyle, softWrap = false, maxLines = 1, density = density)

	val segments = verticalSegmentsOf(singleLine)
	val layouts = ArrayList<TextLayoutResult>(segments.size)
	val measured = ArrayList<MeasuredSegment>(segments.size)

	for (segment in segments) {
		val piece = singleLine.substring(segment.start, segment.end)

		if (segment.orientation == GlyphOrientation.Rotated) {
			val layout = measurePiece(piece, style)
			val clusterEndLengths =
				FloatArray(segment.clusters.size) { clusterIndex ->
					layout.getHorizontalPosition(segment.clusters[clusterIndex].end - segment.start, usePrimaryDirection = true)
				}
			layouts.add(layout)
			measured.add(MeasuredSegment(segment, clusterEndLengths, layout.size.height.toFloat()))
		} else {
			// An upright cell is one em down the column, the advance a CJK font sets vertically, and its own
			// advance across it.
			val layout = measurePiece(piece, uprightStyle)
			layouts.add(layout)
			measured.add(MeasuredSegment(segment, floatArrayOf(emSize), layout.size.width.toFloat()))
		}
	}

	val rotatedEllipsisLayout = measurePiece(ROTATED_ELLIPSIS, style)
	val uprightEllipsisLayout = measurePiece(UPRIGHT_ELLIPSIS, uprightStyle)
	val placement =
		placeVerticalSegments(
			measured = measured,
			rotatedEllipsis = EllipsisMetrics(rotatedEllipsisLayout.size.width.toFloat(), rotatedEllipsisLayout.size.height.toFloat()),
			uprightEllipsis = EllipsisMetrics(emSize, uprightEllipsisLayout.size.width.toFloat()),
			minimumThickness = lineHeightPx(style, density),
			maxLength = if (maxLength.isSpecified) with(density) { maxLength.toPx() } else Float.POSITIVE_INFINITY,
		)
	val pieces = ArrayList<DrawnPiece>(placement.segments.size + 1)

	for (segmentPlacement in placement.segments) {
		val segment = segments[segmentPlacement.segmentIndex]
		val isCut = segmentPlacement.keptClusterCount < segment.clusters.size
		val layout =
			if (isCut) {
				measurePiece(singleLine.substring(segment.start, segment.clusters[segmentPlacement.keptClusterCount - 1].end), style)
			} else {
				layouts[segmentPlacement.segmentIndex]
			}
		pieces.add(drawnPiece(layout, segment.orientation, segmentPlacement.crossOffset, segmentPlacement.alongOffset, emSize))
	}

	placement.ellipsis?.let { ellipsis ->
		val layout = if (ellipsis.orientation == GlyphOrientation.Upright) uprightEllipsisLayout else rotatedEllipsisLayout
		pieces.add(drawnPiece(layout, ellipsis.orientation, ellipsis.crossOffset, ellipsis.alongOffset, emSize))
	}

	return MeasuredVerticalText(
		text = text,
		truncated = placement.truncated,
		widthPx = ceil(placement.thickness).toInt(),
		heightPx = ceil(placement.length).toInt(),
		pieces = pieces,
	)
}

/**
 * Where a laid-out piece is drawn, snapped to whole px so the turned glyphs stay as sharp as the upright ones.
 * A rotated piece is turned about its layout's origin, so its top edge lands at its cross offset plus its
 * line box's height; an upright piece has its ideographic em box set on its cell.
 *
 * @param TextLayoutResult layout The piece's layout.
 * @param GlyphOrientation orientation Which way the piece stands.
 * @param Float crossOffset Its offset across the column, in px.
 * @param Float alongOffset Its start down the column, in px.
 * @param Float emSize The font size, in px.
 * @return DrawnPiece The piece, positioned.
 */
private fun drawnPiece(layout: TextLayoutResult, orientation: GlyphOrientation, crossOffset: Float, alongOffset: Float, emSize: Float): DrawnPiece {
	if (orientation == GlyphOrientation.Rotated) {
		return DrawnPiece(layout, rotated = true, left = (crossOffset + layout.size.height).roundToInt().toFloat(), top = alongOffset.roundToInt().toFloat())
	}

	val emBoxTop = layout.firstBaseline - EM_BOX_ASCENT * emSize

	return DrawnPiece(layout, rotated = false, left = crossOffset.roundToInt().toFloat(), top = (alongOffset - emBoxTop).roundToInt().toFloat())
}

/**
 * The style's line height in px, the column's width: an sp height converted, an em height scaled by the
 * font size, and no line height at all read as zero (the measured pieces then decide).
 *
 * @param TextStyle style The text style.
 * @param Density density The density.
 * @return Float The line height in px.
 */
private fun lineHeightPx(style: TextStyle, density: Density): Float =
	with(density) {
		when (style.lineHeight.type) {
			TextUnitType.Sp -> style.lineHeight.toPx()
			TextUnitType.Em -> style.lineHeight.value * style.fontSize.toPx()
			else -> 0f
		}
	}