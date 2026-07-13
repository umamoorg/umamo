package org.umamo.ui.kit.button

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.umamo.ui.kit.Tooltip
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.UmamoColors
import org.umamo.ui.theme.UmamoIcon
import org.umamo.ui.theme.drawIcon

/**
 * A small flat or elevation-filled icon button: a themed glyph over an optional control-fill chip, flat
 * (no ripple) with hover feedback.  Consolidates the kit's icon buttons - the reset and close affordances
 * are this control with a preset icon and size.
 *
 * The default suppresses keyboard focus because these buttons live inside the shell tree, where a click
 * that removes or restructures the button's own row would otherwise leave the focus owner null and the
 * shell root's onPreviewKeyEvent (all keyboard dispatch) dead until Tab traversal reclaims focus.  A
 * button anchored in stable chrome (a dialog header) can opt back in with [suppressFocus] = false.
 *
 * 小さなアイコンボタン。塗りなし（グリフが明暗）か、塗り付き（背景と角丸のチップ）を appearance で選ぶ。
 *
 * @param UmamoIcon            icon               The glyph to draw.
 * @param Function             onClick            Click callback.
 * @param String               contentDescription The accessible label (the face is only a glyph).
 * @param Modifier             modifier           Layout modifier.
 * @param IconButtonAppearance appearance         Flat glyph, or a Filled elevation chip (background plus corners).
 * @param DpSize               size               The button's box, square by default; 26x20 for header controls.
 * @param Dp                   glyphSize          The drawn glyph size inside the box.
 * @param Boolean              active             A lit toggle state (accent glyph when Flat, accent fill when Filled).
 * @param Boolean              enabled            When false the glyph dims to the disabled tint and clicks are inert
 *   (no-document chrome renders its controls this way rather than hiding them).
 * @param Boolean              suppressFocus      When true the click never pulls keyboard focus off the shell root.
 */
@Composable
fun IconButton(
	icon: UmamoIcon,
	onClick: () -> Unit,
	contentDescription: String,
	modifier: Modifier = Modifier,
	appearance: IconButtonAppearance = IconButtonAppearance.Flat,
	size: DpSize = DpSize(20.dp, 20.dp),
	glyphSize: Dp = 16.dp,
	active: Boolean = false,
	enabled: Boolean = true,
	suppressFocus: Boolean = true,
) {
	val colors = LocalUmamoColors.current
	val interaction = remember { MutableInteractionSource() }
	val hoveredLive by interaction.collectIsHoveredAsState()
	// A disabled button shows no hover feedback (the fill and border stay at rest).
	val hovered = hoveredLive && enabled
	val glyphColor =
		when {
			!enabled -> colors.textDisabled
			appearance is IconButtonAppearance.Filled -> accentControlGlyph(colors, active)
			active -> colors.accent
			hovered -> colors.text
			else -> colors.textMuted
		}
	var faceModifier = Modifier.size(size)
	if (appearance is IconButtonAppearance.Filled) {
		// Elevation is the inline clip + background idiom, not kit Surface: the fill must ramp on hover,
		// which a Surface's static color cannot do.
		faceModifier =
			faceModifier
				.clip(appearance.shape)
				.background(accentControlFill(colors, active && enabled, hovered))
				.border(
					width = 1.dp,
					color =
						when {
							hovered -> colors.panelBorderHover
							else -> colors.controlBorder
						},
					shape = appearance.shape,
				)
	}
	if (suppressFocus) {
		faceModifier = faceModifier.focusProperties { canFocus = false }
	}
	faceModifier =
		faceModifier
			.clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
			.semantics { this.contentDescription = contentDescription }
	// The caller's layout modifier rides on the tooltip wrapper (the outermost node) so alignment and
	// weight still resolve against the parent; the fixed-size face sits inside it.  contentDescription
	// doubles as the hover label - it is the human name of the control either way.
	Tooltip(text = contentDescription, modifier = modifier) {
		Box(modifier = faceModifier, contentAlignment = Alignment.Center) {
			Canvas(modifier = Modifier.size(glyphSize)) {
				drawIcon(icon, glyphColor)
			}
		}
	}
}

/**
 * How an [IconButton]'s face is painted: a bare glyph, or a filled elevation chip.
 *
 * エリアヘッダのような塗り付きボタンと、ダイアログの✕のような塗りなしボタンを一つの型で表す。
 */
sealed interface IconButtonAppearance {
	/** Flat: no fill; the glyph itself carries hover and active feedback. */
	data object Flat : IconButtonAppearance

	/**
	 * Filled: a control-fill elevation chip (a background plus corner rounding) sits under the glyph, the
	 * fill brightening on hover and switching to the accent while active.  This is the treatment that lets
	 * any icon button read as a raised button at rest.
	 *
	 * @property CornerBasedShape shape The corner rounding of the elevation fill.
	 */
	data class Filled(val shape: CornerBasedShape) : IconButtonAppearance
}

/**
 * The fill behind a filled icon button or button-group segment: the neutral control fill at rest, the
 * accent while selected, both brightening one step on hover.  Shared so the header's filled buttons and
 * a [ButtonGroup]'s segments stay one visual family rather than two copies of the same ramp.
 *
 * @param UmamoColors colors   The active color scheme.
 * @param Boolean     selected Whether the control is lit (accent) rather than neutral.
 * @param Boolean     hovered  Whether the pointer is over the control.
 * @return Color The resolved fill color.
 */
internal fun accentControlFill(colors: UmamoColors, selected: Boolean, hovered: Boolean): Color =
	when {
		selected && hovered -> colors.accentHover
		selected -> colors.accent
		hovered -> colors.buttonHover
		else -> colors.controlBackground
	}

/**
 * The glyph tint on a filled icon button or button-group segment: on-accent text while selected, the
 * neutral control glyph otherwise.  Paired with [accentControlFill].
 *
 * @param UmamoColors colors   The active color scheme.
 * @param Boolean     selected Whether the control is lit.
 * @return Color The resolved glyph color.
 */
internal fun accentControlGlyph(colors: UmamoColors, selected: Boolean): Color =
	if (selected) colors.accentText else colors.controlGlyph
