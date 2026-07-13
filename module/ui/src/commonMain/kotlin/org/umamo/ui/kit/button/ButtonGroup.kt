package org.umamo.ui.kit.button

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.umamo.ui.kit.Tooltip
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.UmamoIcon
import org.umamo.ui.theme.drawIcon

/** One segment's face - wider than tall, like Blender's aligned toggle buttons. */
private val SEGMENT_WIDTH = 26.dp
private val SEGMENT_HEIGHT = 20.dp

/** Icon size inside a segment. */
private val SEGMENT_ICON_SIZE = 16.dp

/** The hairline seam between adjacent segments (the surface behind shows through). */
private val SEGMENT_GAP = 1.dp

/**
 * One segment of a [ButtonGroup]: an icon toggle that lights with the accent while [selected].  This is
 * a data model rather than a slot DSL (same reasoning as [MenuItem]) - the group must know each
 * segment's position to shape its corners, so callers hand over a list and one renderer draws it.
 *
 * ButtonGroup の 1 セグメント。selected の間アクセント色で点灯するアイコントグル。
 *
 * @property UmamoIcon icon The segment's glyph.
 * @property Boolean selected Whether the segment is lit (the caller owns the toggle state).
 * @property Function onClick Invoked on tap; the caller flips its own state.
 * @property String? contentDescription The accessible label, or null for none.
 */
data class ButtonGroupItem(
	val icon: UmamoIcon,
	val selected: Boolean,
	val onClick: () -> Unit,
	val contentDescription: String? = null,
)

/**
 * A Blender-style run of butted icon toggles: the segments sit flush against each other separated by a
 * hairline seam, with the group's outer corners rounded and every shared edge square - one pill-shaped
 * control rather than a row of isolated chips.  Each segment carries its own selected state, so the
 * group serves both independent toggles (the outliner's restriction columns) and radio-style sets (the
 * viewport's mesh select modes) - the caller decides the semantics in its onClick handlers.
 *
 * Blender 式の連結トグル列。両端だけ角丸で、隣接辺は直角。各セグメントが自分の選択状態を持つ。
 *
 * @param List items The segments, in display order.
 * @param Modifier modifier The layout modifier.
 */
@Composable
fun ButtonGroup(items: List<ButtonGroupItem>, modifier: Modifier = Modifier) {
	val shapes = LocalUmamoShapes.current
	Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
		items.forEachIndexed { segmentIndex, item ->
			if (segmentIndex > 0) {
				Spacer(modifier = Modifier.width(SEGMENT_GAP))
			}
			ButtonGroupSegment(
				item = item,
				shape = segmentShape(shapes.small, segmentIndex, items.size),
			)
		}
	}
}

/**
 * Shapes one segment by its position in the group: outer corners take the group's rounding, corners
 * shared with a neighbour are square.  RoundedCornerShape's start/end corners already mirror under RTL,
 * so the first segment stays the visual leading end either way.
 *
 * @param CornerBasedShape groupShape The rounding applied to the group's outer corners.
 * @param Int segmentIndex This segment's position.
 * @param Int segmentCount The number of segments in the group.
 * @return Shape The segment's corner shape.
 */
private fun segmentShape(groupShape: CornerBasedShape, segmentIndex: Int, segmentCount: Int): Shape {
	val isFirst = segmentIndex == 0
	val isLast = segmentIndex == segmentCount - 1
	return when {
		isFirst && isLast -> groupShape
		isFirst -> groupShape.copy(topEnd = ZeroCornerSize, bottomEnd = ZeroCornerSize)
		isLast -> groupShape.copy(topStart = ZeroCornerSize, bottomStart = ZeroCornerSize)
		else -> RectangleShape
	}
}

/**
 * One rendered segment: a full-bleed fill (accent while selected, neutral control fill otherwise, both
 * brightening on hover) under a centered icon.  Flat like the rest of the kit - the default indication
 * is suppressed and the fill carries the hover feedback.  The fill and glyph share [accentControlFill] /
 * [accentControlGlyph] with the filled [IconButton], so header controls and segments read as one family.
 *
 * @param ButtonGroupItem item The segment to draw.
 * @param Shape shape The position-dependent corner shape from [segmentShape].
 */
@Composable
private fun ButtonGroupSegment(item: ButtonGroupItem, shape: Shape) {
	val colors = LocalUmamoColors.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	val fill = accentControlFill(colors, item.selected, hovered)
	val iconTint = accentControlGlyph(colors, item.selected)
	val semanticsModifier =
		if (item.contentDescription != null) {
			Modifier.semantics { contentDescription = item.contentDescription }
		} else {
			Modifier
		}
	// A null description means an unlabelled segment, so the tooltip text is blank and no card attaches.
	Tooltip(text = item.contentDescription ?: "") {
		Box(
			modifier =
				Modifier
					.size(width = SEGMENT_WIDTH, height = SEGMENT_HEIGHT)
					.clip(shape)
					.background(fill)
					.clickable(interactionSource = interaction, indication = null, onClick = item.onClick)
					.then(semanticsModifier),
			contentAlignment = Alignment.Center,
		) {
			Canvas(modifier = Modifier.size(SEGMENT_ICON_SIZE)) {
				drawIcon(item.icon, iconTint)
			}
		}
	}
}
