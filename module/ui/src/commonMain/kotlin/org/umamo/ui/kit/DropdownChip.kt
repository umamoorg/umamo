package org.umamo.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.theme.UmamoIcon
import org.umamo.ui.theme.drawIcon

/**
 * The two roles a [DropdownChip] plays: a [Header] chrome chip (content-width, tab-fill, an accent open
 * state, a right/down disclosure chevron) or a form [Field] (fills its column over the control fill, a
 * down/up chevron pushed to the trailing edge) that sits beside the other form controls.  A sanctioned
 * variation of the one chip rather than a fork, so both roles share the anatomy.
 */
enum class DropdownChipStyle {
	Header,
	Field,
}

/**
 * The header dropdown chip, Blender-style: an optional 16.dp leading glyph, an optional labelMedium
 * text, and a 12.dp chevron that points right while the dropdown is closed and down while it is open.
 * Flat like the rest of the kit - the default indication is suppressed and the chip paints its own
 * three-state border and fill: accent while open, panelBackground / panelBorderHover under the
 * pointer, tabBackground / panelBorder at rest; content is accentText while open, controlGlyph
 * otherwise.  The face is explicit params rather than a content slot on purpose: the anatomy (sizes,
 * paddings, chevron) stays un-forkable across every header chip, which is the drift this component
 * exists to prevent.  The dropdown stays a slot because consumers differ (a kit Menu that dismisses
 * per click vs a stay-open Popup).
 *
 * @param Boolean   expanded           Whether the chip's dropdown is open (drives the accent state).
 * @param Function  onExpandRequest    Invoked on click to open the dropdown.
 * @param String    contentDescription The accessible label (the face may be icon-only).
 * @param Modifier  modifier           The layout modifier.
 * @param UmamoIcon icon               Optional leading 16.dp glyph.
 * @param String    label              Optional labelMedium text between the icon and the chevron.
 * @param Boolean   enabled            When false the content dims to the disabled tint and clicks are inert
 *   (no-document chrome renders its chips this way rather than hiding them).
 * @param Function  dropdown           The popup content, rendered while expanded.
 */
@Composable
fun DropdownChip(
	expanded: Boolean,
	onExpandRequest: () -> Unit,
	contentDescription: String,
	modifier: Modifier = Modifier,
	icon: UmamoIcon? = null,
	label: String? = null,
	enabled: Boolean = true,
	style: DropdownChipStyle = DropdownChipStyle.Header,
	dropdown: @Composable () -> Unit,
) {
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	val interaction = remember { MutableInteractionSource() }
	val hoveredLive by interaction.collectIsHoveredAsState()
	// A disabled chip shows no hover feedback (the border and fill stay at rest).
	val hovered = hoveredLive && enabled
	val isField = style == DropdownChipStyle.Field
	// A Field sits on the control fill and shows its state through the border only; a Header fills with the
	// accent when open.  Content color follows: on-accent while a Header is open, plain text for a Field.
	val borderColor =
		when {
			expanded -> colors.accent
			hovered -> colors.panelBorderHover
			else -> colors.panelBorder
		}
	val backgroundColor =
		when {
			expanded -> colors.accent
			hovered -> colors.panelBackground
			else -> colors.tabBackground
		}
	val chipContentColor =
		when {
			!enabled -> colors.textDisabled
			expanded -> colors.accentText
			else -> colors.text
		}
	// The popup is a child of this Box rather than of the padded chip Row: the position provider is
	// handed the anchor's bounds, and the Row's inner box excludes its own padding and background, which
	// would shift the menu off the chip's painted corner (same pattern as MenuBarLabel).
	Box(modifier = modifier) {
		// The tooltip wraps the chip face only; the popup is a sibling below, so it is never wrapped and
		// its anchor bounds (the box) stay the chip's bounds.
		Tooltip(text = contentDescription) {
			// A Field is pinned to the shared control height (so it lines up with a NumberField) and takes no
			// vertical padding - the fixed height plus center alignment place the content; a Header stays
			// content-sized and keeps its symmetric padding.
			Row(
				modifier =
					(if (isField) Modifier.fillMaxWidth() else Modifier.wrapContentSize())
						.clip(shapes.small)
						.clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onExpandRequest)
						.border(width = 1.dp, color = borderColor, shape = shapes.small)
						.background(backgroundColor, shape = shapes.small)
						.padding(4.dp)
						.semantics { this.contentDescription = contentDescription },
				verticalAlignment = Alignment.CenterVertically,
			) {
				if (icon != null) {
					Canvas(modifier = Modifier.size(16.dp)) {
						drawIcon(icon, chipContentColor)
					}
				}
				if (label != null) {
					// A Field weights its label so it fills the chip (ellipsizing when long) and pushes the
					// chevron to the trailing edge; a Header keeps the label content-width.
					val labelModifier =
						if (isField) {
							Modifier.weight(1f).padding(horizontal = 4.dp)
						} else {
							Modifier.padding(horizontal = 4.dp)
						}
					Text(
						text = label,
						style = LocalUmamoTypography.current.labelMedium,
						color = chipContentColor,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
						modifier = labelModifier,
					)
				}
				val chevron =
					when {
						expanded -> LocalUmamoIcons.chevronDown
						else -> LocalUmamoIcons.chevronRight
					}
				Canvas(modifier = Modifier.size(12.dp)) {
					drawIcon(chevron, chipContentColor)
				}
			}
		}
		if (expanded) {
			dropdown()
		}
	}
}
