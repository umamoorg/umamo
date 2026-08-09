package org.umamo.ui.kit.button

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.umamo.ui.kit.Tooltip
import org.umamo.ui.theme.UmamoIcon
import org.umamo.ui.theme.drawIcon

/**
 * A labelled glyph whose gesture the caller owns: a themed icon centered in a fixed-size box, carrying the
 * accessible name and (by default) the matching hover tooltip.  This is [IconButton]'s sibling for the
 * slots that cannot be an [IconButton] - a tree row's eye, its selectable pointer, a disclosure chevron -
 * because they consume their own press so the whole-row selection handler skips them, and they must not
 * take keyboard focus off a row the very edit beside them can dispose.
 *
 * Both live here so the kit's label contract has one neighborhood rather than two: a control's accessible
 * name doubles as its hover label, and passing one string to both keeps them in lockstep.
 *
 * @param UmamoIcon icon               The glyph to draw.
 * @param String    contentDescription The accessible name (the face is only a glyph).
 * @param Color     tint               The glyph color, resolved by the caller against its own row state.
 * @param Modifier  modifier           Applied to the sized face, so the caller's gesture covers the whole slot.
 * @param DpSize    slotSize           The hit box; set it equal to [glyphSize] for a bare inline glyph.
 * @param Dp        glyphSize          The drawn glyph size inside the box.
 * @param String    hoverLabel         The tooltip text, defaulting to [contentDescription].  A slot sitting
 *   beside its own visible text passes "" to keep the accessible name without a hover card that only
 *   repeats what is already on screen.
 */
@Composable
fun IconSlot(
	icon: UmamoIcon,
	contentDescription: String,
	tint: Color,
	modifier: Modifier = Modifier,
	slotSize: DpSize = DpSize(20.dp, 20.dp),
	glyphSize: Dp = 16.dp,
	hoverLabel: String = contentDescription,
) {
	// Unlike IconButton, the caller's modifier rides on the FACE rather than the tooltip wrapper: here it
	// carries the gesture, which has to cover the slot's hit box exactly.  The wrapper sizes to the face,
	// so the hover region matches either way.
	Tooltip(text = hoverLabel) {
		Box(
			modifier =
				Modifier
					.size(slotSize)
					.then(modifier)
					.semantics { this.contentDescription = contentDescription },
			contentAlignment = Alignment.Center,
		) {
			Canvas(modifier = Modifier.size(glyphSize)) {
				drawIcon(icon, tint)
			}
		}
	}
}