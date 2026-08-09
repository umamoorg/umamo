package org.umamo.ui.kit

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.kit.button.IconSlot
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoIcons

/**
 * The expand / collapse chevron every tree in the app draws: a down glyph while open, a right glyph while
 * closed, named for the action a click performs.  Consolidates what were separate copies of the same
 * conditional glyph in the panel section header, the keyform sheet, the parameter groups, and the track
 * sheet, so the art and the label stay in one place.
 *
 * The default label matters more than it looks: a disclosure row's visible text names the section but never
 * says whether it is open, so the chevron's accessible name is the only place that state is exposed.  For
 * the same reason the tooltip is off by default - the chevron always sits beside its own readable label, and
 * a hover card repeating on-screen text is noise.  A caller with a genuinely icon-only chevron passes
 * [hoverLabel].
 *
 * @param Boolean  expanded           Whether the section is open (down glyph) or closed (right glyph).
 * @param Color    tint               The glyph color, resolved by the caller against its own row state.
 * @param Modifier modifier           Applied to the glyph's box, so a caller's toggle gesture covers it.
 * @param Dp       glyphSize          The drawn chevron size; the hit box matches it.
 * @param String   contentDescription The accessible name, defaulting to the action the chevron performs.
 * @param String   hoverLabel         The tooltip text; blank (the default) attaches none.
 */
@Composable
fun DisclosureChevron(
	expanded: Boolean,
	tint: Color,
	modifier: Modifier = Modifier,
	glyphSize: Dp = 12.dp,
	contentDescription: String =
		stringResource(if (expanded) Res.string.common_collapse else Res.string.common_expand),
	hoverLabel: String = "",
) {
	val icons = LocalUmamoIcons
	IconSlot(
		icon = if (expanded) icons.chevronDown else icons.chevronRight,
		contentDescription = contentDescription,
		tint = tint,
		modifier = modifier,
		slotSize = DpSize(glyphSize, glyphSize),
		glyphSize = glyphSize,
		hoverLabel = hoverLabel,
	)
}