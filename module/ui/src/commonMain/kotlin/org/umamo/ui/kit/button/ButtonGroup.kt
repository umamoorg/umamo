package org.umamo.ui.kit.button

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.umamo.ui.kit.StackAxis
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.Tooltip
import org.umamo.ui.kit.stackPositionOf
import org.umamo.ui.kit.stackedShape
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.theme.UmamoIcon
import org.umamo.ui.theme.drawIcon

/** One segment's face - wider than tall, like Blender's aligned toggle buttons. */
private val SEGMENT_WIDTH = 26.dp
private val SEGMENT_HEIGHT = 20.dp

/** Icon size inside a segment. */
private val SEGMENT_ICON_SIZE = 16.dp

/** The hairline seam between adjacent segments (the surface behind shows through). */
private val SEGMENT_GAP = 1.dp

/** The inset of a labeled segment's glyph and label from its edges. */
private val SEGMENT_LABEL_INSET = 6.dp

/** The gap between a labeled segment's glyph and its label. */
private val SEGMENT_ICON_LABEL_GAP = 6.dp

/**
 * One segment of a [ButtonGroup]: an icon toggle, optionally labeled, that lights with the accent while
 * [selected].  This is a data model rather than a slot DSL (same reasoning as [org.umamo.ui.kit.menu.MenuItem]) - the
 * group must know each segment's position to shape its corners, so callers hand over a list and one renderer
 * draws it.
 *
 * @property UmamoIcon icon The segment's glyph.
 * @property Boolean selected Whether the segment is lit (the caller owns the toggle state).
 * @property Function onClick Invoked on tap; the caller flips its own state.
 * @property String contentDescription The accessible name, and the segment's hover label whenever it says
 *   something the face does not.  Required: an icon-only segment's face is only a glyph, so an absent name
 *   would leave it nameless to a screen reader and silent on hover at once.
 * @property String? label Text drawn beside the glyph, or null for an icon-only segment.  A label equal to
 *   [contentDescription] makes the hover label redundant, so the segment shows none.
 */
data class ButtonGroupItem(
	val icon: UmamoIcon,
	val selected: Boolean,
	val onClick: () -> Unit,
	val contentDescription: String,
	val label: String? = null,
)

/**
 * A Blender-style run of butted toggles: the segments sit flush against each other separated by a
 * hairline seam, with the group's outer corners rounded and every shared edge square - one pill-shaped
 * control rather than a run of isolated chips.  Each segment carries its own selected state, so the
 * group serves both independent toggles (the outliner's restriction columns) and radio-style sets (the
 * viewport's mesh select modes, the proportional falloff curves) - the caller decides the semantics in
 * its onClick handlers.
 *
 * A horizontal group is a row of segments, each as wide as its face.  A vertical group is a column - an
 * expanded option list, Blender's enum laid out in a panel - whose segments all take the group's width:
 * the widest segment's, or the caller's when it gives the group a width.  Segments are icon-only (a
 * fixed glyph face) or labeled (the glyph and the label, centered in a row and start-aligned in a
 * column).
 *
 * @param List items The segments, in display order.
 * @param Modifier modifier The layout modifier.
 * @param StackAxis axis Whether the segments run in a row or a column.
 */
@Composable
fun ButtonGroup(items: List<ButtonGroupItem>, modifier: Modifier = Modifier, axis: StackAxis = StackAxis.Horizontal) {
	val shapes = LocalUmamoShapes.current
	when (axis) {
		StackAxis.Horizontal ->
			Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
				items.forEachIndexed { segmentIndex, item ->
					if (segmentIndex > 0) {
						Spacer(modifier = Modifier.width(SEGMENT_GAP))
					}
					ButtonGroupSegment(
						item = item,
						shape = segmentShape(shapes.small, segmentIndex, items.size, axis),
						axis = axis,
					)
				}
			}

		StackAxis.Vertical ->
			// IntrinsicSize.Max sizes the column to its widest segment, and each segment fills it, so the rows
			// line up as one block; a caller's own width wins over the intrinsic one.
			Column(modifier = modifier.width(IntrinsicSize.Max)) {
				items.forEachIndexed { segmentIndex, item ->
					if (segmentIndex > 0) {
						Spacer(modifier = Modifier.height(SEGMENT_GAP))
					}
					ButtonGroupSegment(
						item = item,
						shape = segmentShape(shapes.small, segmentIndex, items.size, axis),
						axis = axis,
					)
				}
			}
	}
}

/**
 * Shapes one segment by its position in the group, delegating to the shared [stackedShape] helper that
 * the vertical numeric-field stack also uses: outer corners take the group's rounding, corners shared
 * with a neighbor are squared.
 *
 * @param CornerBasedShape groupShape The rounding applied to the group's outer corners.
 * @param Int segmentIndex This segment's position.
 * @param Int segmentCount The number of segments in the group.
 * @param StackAxis axis The direction the group runs in.
 * @return Shape The segment's corner shape.
 */
private fun segmentShape(groupShape: CornerBasedShape, segmentIndex: Int, segmentCount: Int, axis: StackAxis): Shape =
	stackedShape(groupShape, stackPositionOf(segmentIndex, segmentCount), axis)

/**
 * One rendered segment: a full-bleed fill (accent while selected, neutral control fill otherwise, both
 * brightening on hover) under the glyph, and the label beside it when the segment has one.  Flat like the
 * rest of the kit - the default indication is suppressed and the fill carries the hover feedback.  The
 * fill and the content share [accentControlFill] / [accentControlGlyph] with the filled [IconButton], so
 * header controls and segments read as one family.
 *
 * @param ButtonGroupItem item The segment to draw.
 * @param Shape shape The position-dependent corner shape from [segmentShape].
 * @param StackAxis axis The direction the group runs in: a column's segments fill its width.
 */
@Composable
private fun ButtonGroupSegment(item: ButtonGroupItem, shape: Shape, axis: StackAxis) {
	val colors = LocalUmamoColors.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	val pressed by interaction.collectIsPressedAsState()
	val fill = accentControlFill(colors, item.selected, hovered, pressed)
	val contentColor = accentControlGlyph(colors, item.selected)
	val label = item.label
	// The description is the hover text, as it is on IconButton, whenever the face does not already say it:
	// a label equal to it would only be echoed.
	val tooltipText = if (label == item.contentDescription) "" else item.contentDescription
	val faceModifier =
		Modifier
			.clip(shape)
			.background(fill)
			.clickable(interactionSource = interaction, indication = null, onClick = item.onClick)
			.semantics {
				contentDescription = item.contentDescription
				selected = item.selected
			}
	val fillsColumn = axis == StackAxis.Vertical
	Tooltip(text = tooltipText, modifier = if (fillsColumn) Modifier.fillMaxWidth() else Modifier) {
		if (label == null) {
			// The fixed glyph face, unless a column stretches it to the group's width.
			val iconFaceSize =
				if (fillsColumn) {
					Modifier.fillMaxWidth().height(SEGMENT_HEIGHT)
				} else {
					Modifier.size(width = SEGMENT_WIDTH, height = SEGMENT_HEIGHT)
				}
			Box(modifier = iconFaceSize.then(faceModifier), contentAlignment = Alignment.Center) {
				Canvas(modifier = Modifier.size(SEGMENT_ICON_SIZE)) {
					drawIcon(item.icon, contentColor)
				}
			}
		} else {
			// A labeled segment: the glyph and the label in a row, centered in a row of segments and start-aligned
			// down a column, where the labels then line up as one list.
			Row(
				modifier =
					(if (fillsColumn) Modifier.fillMaxWidth() else Modifier)
						.heightIn(min = SEGMENT_HEIGHT)
						.then(faceModifier)
						.padding(horizontal = SEGMENT_LABEL_INSET),
				horizontalArrangement = if (fillsColumn) Arrangement.Start else Arrangement.Center,
				verticalAlignment = Alignment.CenterVertically,
			) {
				Canvas(modifier = Modifier.size(SEGMENT_ICON_SIZE)) {
					drawIcon(item.icon, contentColor)
				}
				Spacer(modifier = Modifier.width(SEGMENT_ICON_LABEL_GAP))
				Text(
					text = label,
					style = LocalUmamoTypography.current.labelMedium,
					color = contentColor,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
	}
}