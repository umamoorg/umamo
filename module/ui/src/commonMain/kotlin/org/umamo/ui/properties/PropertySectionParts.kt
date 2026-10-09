package org.umamo.ui.properties

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.Tooltip
import org.umamo.ui.kit.field.PropertyFieldRow
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoTypography

/*
 * What every Properties section shares and nothing outside the area needs: the numeric ranges its controls
 * clamp to, the gutter the Size rows hold for their aspect lock, and the labelled block a relation list
 * sits in.  The rows themselves (the label + control grid, the read-only line, the checkbox row) are the
 * kit's org.umamo.ui.kit.field.PropertyRows, shared with the operation strip and the overlays popover.
 * Sections live in the per-tab files beside this one; anything used by more than one of them belongs here.
 */

/** An unbounded float range: a numeric field that clamps nothing and draws no magnitude fill. */
internal val UNBOUNDED_RANGE = Float.NEGATIVE_INFINITY..Float.POSITIVE_INFINITY

/** A half-open float range (min set, no max): clamps below and draws no fill (needs both bounds). */
internal val POSITIVE_RANGE = 1f..Float.POSITIVE_INFINITY

/**
 * The clamp for a drawable's world extent.  Deliberately NOT [POSITIVE_RANGE]: a canvas is measured in
 * whole pixels so a floor of 1 is meaningful there, but a drawable extent is in world units and can
 * legitimately be a fraction - clamping it to 1 would silently double a typed 0.5.  The floor is the
 * smallest value the row's one-decimal display can actually show, which keeps the number in the field
 * honest while still refusing the zero (collapse) and negative (mirror) cases.
 */
internal val DRAWABLE_EXTENT_RANGE = 0.1f..Float.POSITIVE_INFINITY

/**
 * Space the Size rows reserve at their right edge for the aspect lock that overlays it: the 20dp icon
 * button plus a little breathing room.  Keeping it a named constant is what ties the reservation and the
 * overlaid control to the same width - if they drift, the lock either overlaps the field or floats away.
 */
internal val ASPECT_LOCK_GUTTER = 24.dp

/**
 * A labelled block wrapping a relation list, since a tall list does not fit the two-column field row.  The
 * [description] tooltips the label alone, for the reason [PropertyFieldRow] gives: the list's add, remove,
 * and pick buttons carry tooltips of their own.
 *
 * @param String label The block's localized label.
 * @param String description What the list does, as the label's tooltip; blank attaches none.
 * @param Function content The list to draw beneath it.
 */
@Composable
internal fun RelationListBlock(label: String, description: String = "", content: @Composable () -> Unit) {
	Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
		Tooltip(text = description) {
			Text(text = label, style = LocalUmamoTypography.current.bodySmall, color = LocalUmamoColors.current.text)
		}
		content()
	}
}