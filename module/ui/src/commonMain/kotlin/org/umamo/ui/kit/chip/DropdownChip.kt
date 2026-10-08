package org.umamo.ui.kit.chip

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.Tooltip
import org.umamo.ui.kit.button.accentControlFill
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.theme.UmamoIcon
import org.umamo.ui.theme.drawIcon

/*
 * The kit's chips: the one chip anatomy with its dropdown slot (this file), the chip over a stay-open
 * panel (PopupChip.kt), and the funnel preset for filters (FilterPopupChip.kt).  A chip leaves what drops
 * down to its caller, so nothing here imports the menus.
 */

/**
 * A toggle riding on a [DropdownChip]'s glyph: with one, the chip's face splits into the glyph as a lit /
 * unlit button of its own and the chevron that opens the dropdown - Blender's two-part header controls
 * (Show Overlays, Show Gizmo) as ONE chip, not a button beside a chip.  The toggle's action never opens the
 * dropdown, and the chevron never flips the toggle.
 *
 * @property Boolean  active             Whether the toggle is lit.
 * @property Function onToggle           Invoked when the glyph half is clicked.
 * @property String   contentDescription The glyph half's accessible label and tooltip.
 */
@Immutable
class ChipToggle(
	val active: Boolean,
	val onToggle: () -> Unit,
	val contentDescription: String,
)

/**
 * The roles a [DropdownChip] plays: a [Header] chrome chip (content-width, tab-fill, an accent open
 * state, a right/down disclosure chevron), a form [Field] (fills its column over the control fill, a
 * down/up chevron pushed to the trailing edge) that sits beside the other form controls, or a [Compact]
 * header chip sized for a 22.dp list row (a 14.dp glyph in 2.dp padding, so the face is 18.dp tall
 * where a Header's 24.dp would overflow the row).  Sanctioned variations of the one chip rather than
 * forks, so every role shares the anatomy.
 */
enum class DropdownChipStyle {
	Header,
	Field,
	Compact,
}

/**
 * The header dropdown chip, Blender-style: an optional 16.dp leading glyph, an optional labelMedium
 * text, and a 12.dp chevron that points right while the dropdown is closed and down while it is open.
 * Flat like the rest of the kit - the default indication is suppressed and the chip paints its own
 * three-state border and fill: accent while open, panelBackground / panelBorderHover under the
 * pointer, tabBackground / panelBorder at rest; content is accentText while open, text
 * otherwise.  The face is explicit params rather than a content slot on purpose: the anatomy (sizes,
 * paddings, chevron) stays un-forkable across every header chip, which is the drift this component
 * exists to prevent.  The dropdown stays a slot because consumers differ (a kit Menu that dismisses
 * per click vs a stay-open Popup).
 *
 * A Header chip holds its intrinsic width: the glyphs and the label ignore the incoming maximum, so a
 * parent with no room left pushes the chip off its edge instead of squeezing it down to an empty
 * padding box.  A Field chip still ellipsizes its label, which is what a form column wants.
 *
 * @param Boolean   expanded           Whether the chip's dropdown is open (drives the accent state).
 * @param Function  onExpandRequest    Invoked on click to open the dropdown.
 * @param String    contentDescription The accessible label (the face may be icon-only).
 * @param Modifier  modifier           The layout modifier.
 * @param UmamoIcon icon               Optional leading 16.dp glyph.
 * @param String    label              Optional labelMedium text between the icon and the chevron.
 * @param Boolean   enabled            When false the content dims to the disabled tint and clicks are inert
 *   (no-document chrome renders its chips this way rather than hiding them).
 * @param DropdownChipStyle style     Which role the chip plays; see [DropdownChipStyle].
 * @param Color?    iconTint           A status color for the glyph at rest, or null for the chip's own content color.
 * @param ChipToggle? iconToggle       A toggle riding on the glyph: the face splits into the glyph's own lit / unlit
 *   button and the chevron that opens the dropdown (see [ChipToggle]; needs [icon], Header and Compact only).
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
	iconTint: Color? = null,
	iconToggle: ChipToggle? = null,
	dropdown: @Composable () -> Unit,
) {
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	val interaction = remember { MutableInteractionSource() }
	val hoveredLive by interaction.collectIsHoveredAsState()
	// A disabled chip shows no hover feedback (the border and fill stay at rest).
	val hovered = hoveredLive && enabled
	val isField = style == DropdownChipStyle.Field
	// A Compact chip keeps the Header anatomy at list-row scale: the glyph matches the row's own 14.dp
	// icons and the padding halves, so the 18.dp face sits inside a 22.dp row instead of overflowing it.
	val isCompact = style == DropdownChipStyle.Compact
	val facePadding = if (isCompact) 2.dp else 4.dp
	val glyphSize = if (isCompact) 14.dp else 16.dp
	val chevronSize = if (isCompact) 10.dp else 12.dp
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
		// A toggle on the glyph splits the face into two halves; the plain face is one clickable.
		val toggle = iconToggle?.takeIf { icon != null && !isField }
		if (toggle != null && icon != null) {
			SplitChipFace(
				expanded = expanded,
				onExpandRequest = onExpandRequest,
				contentDescription = contentDescription,
				icon = icon,
				toggle = toggle,
				label = label,
				enabled = enabled,
				style = style,
			)
		} else {
			Tooltip(text = contentDescription) {
				// A Field fills its column so it lines up with the other form controls.  A Header chip is pinned to
				// its own intrinsic width and IGNORES the incoming maximum: a Row clamps itself to whatever width is
				// left, which would shrink the painted face out from under the glyphs.  Overflowing the parent (and
				// being clipped) is the legible failure; a chip squeezed down to an empty padding box is not.
				val faceWidth =
					if (isField) {
						Modifier.fillMaxWidth()
					} else {
						Modifier.requiredWidth(IntrinsicSize.Max)
					}
				Row(
					modifier =
						faceWidth
							.clip(shapes.small)
							// NOT focusable, like SectionHeader and Checkbox: the popup owns its own focus while open,
							// and on close the keyboard must return to the shell root, not to a chip that transient
							// chrome (the operation settings strip) may dispose on the next edit.
							.focusProperties { canFocus = false }
							.clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onExpandRequest)
							.border(width = 1.dp, color = borderColor, shape = shapes.small)
							.background(backgroundColor, shape = shapes.small)
							.padding(facePadding)
							.semantics { this.contentDescription = contentDescription },
					verticalAlignment = Alignment.CenterVertically,
				) {
					if (icon != null) {
						// requiredSize, not size: size coerces to the incoming constraints, so a starved parent
						// measures the glyph at zero and the chip renders as an empty padding box.  The chip holds
						// its glyphs at full size and overflows instead - being pushed off the edge is legible,
						// silently shrinking to nothing is not.
						// A status tint colors the glyph at rest only; the open and disabled faces keep their own contrast.
						val glyphColor = if (iconTint != null && enabled && !expanded) iconTint else chipContentColor
						Canvas(modifier = Modifier.requiredSize(glyphSize)) {
							drawIcon(icon, glyphColor)
						}
					}
					if (label != null) {
						// A Field weights its label so it fills the chip (ellipsizing when long) and pushes the
						// chevron to the trailing edge; a Header keeps the label content-width, which the Row's own
						// intrinsic sizing already guarantees room for.
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
					Canvas(modifier = Modifier.requiredSize(chevronSize)) {
						drawIcon(chevron, chipContentColor)
					}
				}
			}
		}
		if (expanded) {
			dropdown()
		}
	}
}

/**
 * The split face of a chip carrying an [iconToggle]: the glyph is a lit / unlit button of its own and the
 * chevron (with the label, if any) opens the dropdown, the two halves sharing one border with a seam between
 * them.  Each half paints its own fill and carries its own tooltip and accessible name; the border follows
 * the whole - accent while open, hover-lit while the pointer is over either half.  The toggle half's lit fill
 * is the IconButton family's accent ramp, so it reads like the header's other filled toggles.
 *
 * @param Boolean           expanded           Whether the dropdown is open.
 * @param Function          onExpandRequest    Invoked by the chevron half to open the dropdown.
 * @param String            contentDescription The chevron half's accessible label and tooltip.
 * @param UmamoIcon         icon               The glyph the toggle half draws.
 * @param ChipToggle        toggle             The toggle half's state, action, and label.
 * @param String?           label              Optional labelMedium text ahead of the chevron.
 * @param Boolean           enabled            When false both halves dim and clicks are inert.
 * @param DropdownChipStyle style              Header or Compact sizing.
 */
@Composable
private fun SplitChipFace(
	expanded: Boolean,
	onExpandRequest: () -> Unit,
	contentDescription: String,
	icon: UmamoIcon,
	toggle: ChipToggle,
	label: String?,
	enabled: Boolean,
	style: DropdownChipStyle,
) {
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	val toggleInteraction = remember { MutableInteractionSource() }
	val expandInteraction = remember { MutableInteractionSource() }
	val toggleHoveredLive by toggleInteraction.collectIsHoveredAsState()
	val togglePressedLive by toggleInteraction.collectIsPressedAsState()
	val expandHoveredLive by expandInteraction.collectIsHoveredAsState()
	// A disabled chip shows no hover or press feedback, as the plain face does not.
	val toggleHovered = toggleHoveredLive && enabled
	val togglePressed = togglePressedLive && enabled
	val expandHovered = expandHoveredLive && enabled
	val isCompact = style == DropdownChipStyle.Compact
	val facePadding = if (isCompact) 2.dp else 4.dp
	val glyphSize = if (isCompact) 14.dp else 16.dp
	val chevronSize = if (isCompact) 10.dp else 12.dp
	val borderColor =
		when {
			expanded -> colors.accent
			toggleHovered || expandHovered -> colors.panelBorderHover
			else -> colors.panelBorder
		}
	val lit = toggle.active && enabled
	val toggleFill =
		when {
			lit -> accentControlFill(colors, selected = true, hovered = toggleHovered, pressed = togglePressed)
			toggleHovered -> colors.panelBackground
			else -> colors.tabBackground
		}
	val toggleGlyphColor =
		when {
			!enabled -> colors.textDisabled
			lit -> colors.accentText
			else -> colors.text
		}
	val expandFill =
		when {
			expanded -> colors.accent
			expandHovered -> colors.panelBackground
			else -> colors.tabBackground
		}
	val expandContentColor =
		when {
			!enabled -> colors.textDisabled
			expanded -> colors.accentText
			else -> colors.text
		}
	// Intrinsic width like the plain Header face, and the halves fill the taller half's height so the seam
	// and both fills run the full face.
	Row(
		modifier =
			Modifier
				.requiredWidth(IntrinsicSize.Max)
				.height(IntrinsicSize.Min)
				.clip(shapes.small)
				.focusProperties { canFocus = false }
				.border(width = 1.dp, color = borderColor, shape = shapes.small),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Tooltip(text = toggle.contentDescription, modifier = Modifier.fillMaxHeight()) {
			Box(
				modifier =
					Modifier
						.fillMaxHeight()
						.background(toggleFill)
						.clickable(interactionSource = toggleInteraction, indication = null, enabled = enabled, onClick = toggle.onToggle)
						.padding(facePadding)
						.semantics { this.contentDescription = toggle.contentDescription },
				contentAlignment = Alignment.Center,
			) {
				Canvas(modifier = Modifier.requiredSize(glyphSize)) {
					drawIcon(icon, toggleGlyphColor)
				}
			}
		}
		// The seam between the halves, in the border's color so it reads as part of the one outline.
		Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(borderColor))
		Tooltip(text = contentDescription, modifier = Modifier.fillMaxHeight()) {
			Row(
				modifier =
					Modifier
						.fillMaxHeight()
						.background(expandFill)
						.clickable(interactionSource = expandInteraction, indication = null, enabled = enabled, onClick = onExpandRequest)
						.padding(facePadding)
						.semantics { this.contentDescription = contentDescription },
				verticalAlignment = Alignment.CenterVertically,
			) {
				if (label != null) {
					Text(
						text = label,
						style = LocalUmamoTypography.current.labelMedium,
						color = expandContentColor,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
						modifier = Modifier.padding(horizontal = 4.dp),
					)
				}
				val chevron =
					when {
						expanded -> LocalUmamoIcons.chevronDown
						else -> LocalUmamoIcons.chevronRight
					}
				Canvas(modifier = Modifier.requiredSize(chevronSize)) {
					drawIcon(chevron, expandContentColor)
				}
			}
		}
	}
}