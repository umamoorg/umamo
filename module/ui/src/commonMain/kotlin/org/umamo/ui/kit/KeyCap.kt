package org.umamo.ui.kit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.theme.UmamoIcon
import org.umamo.ui.theme.drawIcon

/** A key cap's height: a key, not a label. */
private val CAP_HEIGHT = 20.dp

/** A key cap's horizontal padding, tight enough for a single letter. */
private val CAP_PADDING = 5.dp

/** A large key cap's height, for a key view that is the panel's subject. */
private val LARGE_CAP_HEIGHT = 26.dp

/** A large key cap's horizontal padding. */
private val LARGE_CAP_PADDING = 8.dp

/** The gap between a cap and the plus beside it. */
private val CAP_GAP = 3.dp

/** A key cap's glyph size. */
private val CAP_ICON_SIZE = 12.dp

/** A large key cap's glyph size. */
private val LARGE_CAP_ICON_SIZE = 15.dp

/**
 * The glyph a special key draws on its cap, or null when the key is written out.
 *
 * The arrows, the escape and the numpad plus have no letters to spell, so they are drawn instead.
 *
 * @param String keyName The position-based key token (e.g. "ArrowUp").
 * @return UmamoIcon? The glyph, or null when the key is written out.
 */
fun specialKeyIcon(keyName: String): UmamoIcon? =
	when (keyName) {
		"ArrowUp" -> LocalUmamoIcons.chevronUp
		"ArrowDown" -> LocalUmamoIcons.chevronDown
		"ArrowLeft" -> LocalUmamoIcons.chevronLeft
		"ArrowRight" -> LocalUmamoIcons.chevronRight
		"PageUp" -> LocalUmamoIcons.chevronUp
		"PageDown" -> LocalUmamoIcons.chevronDown
		"Escape" -> LocalUmamoIcons.close
		"NumpadAdd" -> LocalUmamoIcons.plus
		else -> null
	}

/**
 * One key of an accelerator, drawn as a key cap: a small raised box with the key's name on it.
 *
 * The active cap is the one the user is pressing, and it takes the accent so the key view shows what was
 * caught at a glance.
 *
 * @param String text The key's label.
 * @param Modifier modifier The layout modifier.
 * @param Boolean active Whether this cap is the one just pressed.
 * @param Boolean large Whether the cap is drawn large, for a key view that is the panel's subject.
 * @param UmamoIcon? icon A glyph drawn on the cap instead of its name.
 */
@Composable
fun KeyCap(
	text: String,
	modifier: Modifier = Modifier,
	active: Boolean = false,
	large: Boolean = false,
	icon: UmamoIcon? = null,
) {
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	val typography = LocalUmamoTypography.current
	val contentColor = if (active) colors.accentText else colors.text

	Box(
		modifier =
			modifier
				.heightIn(min = if (large) LARGE_CAP_HEIGHT else CAP_HEIGHT)
				.clip(shapes.small)
				.background(if (active) colors.controlBackgroundHover else colors.controlBackground)
				.border(BorderStroke(1.dp, if (active) colors.accent else colors.controlBorder), shapes.small)
				.padding(horizontal = if (large) LARGE_CAP_PADDING else CAP_PADDING),
		contentAlignment = Alignment.Center,
	) {
		if (icon != null) {
			Canvas(modifier = Modifier.size(if (large) LARGE_CAP_ICON_SIZE else CAP_ICON_SIZE)) {
				drawIcon(icon, contentColor)
			}
		} else {
			Text(
				text = text,
				style = if (large) typography.bodyMedium else typography.labelSmall,
				color = contentColor,
			)
		}
	}
}

/**
 * An accelerator drawn as its key caps - "Ctrl + Shift + P", or the caps side by side with no connector
 * between them.
 *
 * The caps come from [org.umamo.ui.action.acceleratorParts], so this and the flat text form the menus use
 * are the same binding read two ways.
 *
 * @param List<String> parts The cap texts, in order.
 * @param Modifier modifier The layout modifier.
 * @param Int activeIndex The index of the cap just pressed, or -1 for none.
 * @param String? separator The connector drawn between the caps, or null for none.
 * @param Boolean large Whether the caps are drawn large, for a key view that is the panel's subject.
 * @param UmamoIcon? keyIcon A glyph drawn on the key's own cap, for a key with no letters to spell.
 */
@Composable
fun KeyChordView(
	parts: List<String>,
	modifier: Modifier = Modifier,
	activeIndex: Int = -1,
	separator: String? = "+",
	large: Boolean = false,
	keyIcon: UmamoIcon? = null,
) {
	val colors = LocalUmamoColors.current
	val typography = LocalUmamoTypography.current

	Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) {
		parts.forEachIndexed { index, part ->
			if (index > 0) {
				Spacer(modifier = Modifier.width(CAP_GAP))

				if (separator != null) {
					Text(
						text = separator,
						style = if (large) typography.bodyMedium else typography.labelSmall,
						color = colors.textMuted,
					)
					Spacer(modifier = Modifier.width(CAP_GAP))
				}
			}

			KeyCap(
				text = part,
				active = index == activeIndex,
				large = large,
				icon = if (index == parts.lastIndex) keyIcon else null,
			)
		}
	}
}