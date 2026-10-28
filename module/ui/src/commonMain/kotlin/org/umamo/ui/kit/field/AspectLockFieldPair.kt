package org.umamo.ui.kit.field

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.kit.button.IconButton
import org.umamo.ui.kit.button.IconButtonAppearance
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoShapes
import kotlin.math.abs

/**
 * Space the pair's rows reserve at their right edge for the aspect lock that overlays it: the 20dp icon
 * button plus a little breathing room.  Keeping it a named constant is what ties the reservation and the
 * overlaid control to the same width - if they drift, the lock either overlaps the fields or floats away.
 */
private val ASPECT_LOCK_GUTTER = 24.dp

/**
 * Two joined number fields measuring one quantity along two axes (a size's extents, a scale's factors) plus
 * the aspect-ratio lock spanning them.  Its own composable rather than a plain [FieldStack] because the lock
 * is stateful and couples the two fields: while locked, editing one scales the other by the same factor (see
 * [aspectLockedPartner]), so the pair keeps its proportions.  The fields share one range, step, and unit for
 * the same reason.
 *
 * The lock is transient UI state (a tool preference, not document data), so it lives in a remember and resets
 * when the pair unmounts.  The ratio is read from the CURRENT values rather than captured when the lock was
 * engaged, so it always reflects what the fields are showing.  Nothing commits mid-scrub, so during one those
 * values are still the ones the scrub started from, and every frame scales against the same ratio.
 *
 * While one field is scrubbed the other follows it: the pair the scrub implies is held here, and the field not
 * being dragged shows its half (the dragged one shows its own draft).  That keeps the locked partner live
 * whether or not the caller previews the scrub anywhere else.
 *
 * @param String firstLabel The first field's localized label.
 * @param String firstDescription What the first field measures, as its label's tooltip.
 * @param Float firstValue The first field's value.
 * @param String secondLabel The second field's localized label.
 * @param String secondDescription What the second field measures, as its label's tooltip.
 * @param Float secondValue The second field's value.
 * @param ClosedFloatingPointRange range The range both fields clamp to, a locked partner included.
 * @param Int decimals The fractional places both fields show.
 * @param Float step The chevron increment and scrub sensitivity of both fields.
 * @param String? unitSuffix The unit after both numbers, or null with none.
 * @param Boolean enabled Whether the fields and the lock accept input; disabled, they only show the values.
 * @param Function onCommit Called with the new (first, second) values: a typed value, a chevron step, or a
 *   scrub's release.  The field not edited passes its value through, or its locked partner value.
 * @param Function? onPreview Called with the (first, second) values on each frame of a scrub, or null when
 *   only the fields show the scrub until its release commits.
 * @param Function? onScrubCancel Called when a scrub is cancelled, after both fields fell back to their
 *   values, or null with nothing to put back.
 */
@Composable
fun AspectLockFieldPair(
	firstLabel: String,
	firstDescription: String,
	firstValue: Float,
	secondLabel: String,
	secondDescription: String,
	secondValue: Float,
	range: ClosedFloatingPointRange<Float>,
	decimals: Int,
	step: Float = 1f,
	unitSuffix: String? = null,
	enabled: Boolean = true,
	onCommit: (Float, Float) -> Unit,
	onPreview: ((Float, Float) -> Unit)? = null,
	onScrubCancel: (() -> Unit)? = null,
) {
	var locked by remember { mutableStateOf(false) }
	// The values of the scrub in flight, or null with none.  Keyed on enabled: a field disabled mid-scrub drops
	// its draft without committing, and the values it implied go with it.
	var scrubbedPair by remember(enabled) { mutableStateOf<FieldPairValues?>(null) }
	val icons = LocalUmamoIcons
	// A locked edit of one field derives the other from the ratio the pair currently has.  The preview and the
	// commit both resolve through these, so the release lands exactly the values the last frame showed.
	val pairForFirst: (Float) -> FieldPairValues = { newFirst ->
		FieldPairValues(newFirst, aspectLockedPartner(newFirst, firstValue, secondValue, locked, range))
	}
	val pairForSecond: (Float) -> FieldPairValues = { newSecond ->
		FieldPairValues(aspectLockedPartner(newSecond, secondValue, firstValue, locked, range), newSecond)
	}
	val previewPair: (FieldPairValues) -> Unit = { pair ->
		scrubbedPair = pair
		onPreview?.invoke(pair.first, pair.second)
	}
	val commitPair: (FieldPairValues) -> Unit = { pair ->
		scrubbedPair = null
		onCommit(pair.first, pair.second)
	}
	// A cancel snaps the partner back with the dragged field: the values the scrub implied are gone.
	val cancelPair: () -> Unit = {
		scrubbedPair = null
		onScrubCancel?.invoke()
	}
	val shownPair = scrubbedPair ?: FieldPairValues(firstValue, secondValue)
	// The lock overlays the gutter the rows reserve, so the fields shrink by exactly the lock's width while the
	// label column keeps its half of the FULL row width - that is what keeps the pair lined up with the plain
	// rows above and below it.
	Box(modifier = Modifier.fillMaxWidth()) {
		FieldStack(
			listOf(
				{ position ->
					PropertyFieldRow(firstLabel, description = firstDescription, trailingGutter = ASPECT_LOCK_GUTTER) {
						NumberField(
							value = shownPair.first,
							onValueChange = { newFirst -> commitPair(pairForFirst(newFirst)) },
							onPreview = { newFirst -> previewPair(pairForFirst(newFirst)) },
							onScrubCancel = cancelPair,
							modifier = Modifier.fillMaxWidth(),
							range = range,
							decimals = decimals,
							step = step,
							unitSuffix = unitSuffix,
							enabled = enabled,
							stackPosition = position,
						)
					}
				},
				{ position ->
					PropertyFieldRow(secondLabel, description = secondDescription, trailingGutter = ASPECT_LOCK_GUTTER) {
						NumberField(
							value = shownPair.second,
							onValueChange = { newSecond -> commitPair(pairForSecond(newSecond)) },
							onPreview = { newSecond -> previewPair(pairForSecond(newSecond)) },
							onScrubCancel = cancelPair,
							modifier = Modifier.fillMaxWidth(),
							range = range,
							decimals = decimals,
							step = step,
							unitSuffix = unitSuffix,
							enabled = enabled,
							stackPosition = position,
						)
					}
				},
			),
		)
		// The chain glyph spans both fields.  IconButton tooltips itself from contentDescription, and its own
		// modifier already rides on that tooltip wrapper, so the alignment goes here rather than into a second
		// enclosing Tooltip.
		IconButton(
			icon = if (locked) icons.linked else icons.unlinked,
			contentDescription = stringResource(Res.string.common_lock_aspect_ratio),
			onClick = { locked = !locked },
			modifier = Modifier.align(Alignment.CenterEnd),
			active = locked,
			enabled = enabled,
			appearance = IconButtonAppearance.Filled(LocalUmamoShapes.current.small),
		)
	}
}

/**
 * The value an [AspectLockFieldPair] gives the field NOT edited when the other becomes [editedValue].
 *
 * Unlocked, the partner keeps its value.  Locked, it scales by the magnitude of the edited field's factor and
 * keeps its own sign, so the lock keeps the proportions while each axis keeps its own mirror: a scale pair of
 * (-1, 1) edited to (2, ?) becomes (2, 2), not (2, -2).  An edited field that was zero (a degenerate extent)
 * has no ratio to carry, so the partner keeps its value then too.  The result clamps to [range], which is where
 * the ratio gives way.
 *
 * @param Float editedValue The edited field's new value.
 * @param Float editedCurrent The edited field's value before the edit.
 * @param Float partnerCurrent The partner's value before the edit.
 * @param Boolean locked Whether the aspect lock is on.
 * @param ClosedFloatingPointRange range The range the pair's fields clamp to.
 * @return Float The partner's new value.
 */
internal fun aspectLockedPartner(
	editedValue: Float,
	editedCurrent: Float,
	partnerCurrent: Float,
	locked: Boolean,
	range: ClosedFloatingPointRange<Float>,
): Float {
	if (!locked || editedCurrent == 0f) {
		return partnerCurrent
	}

	return (partnerCurrent * abs(editedValue / editedCurrent)).coerceIn(range.start, range.endInclusive)
}

/**
 * The two values of an [AspectLockFieldPair], as one edit or scrub frame resolves them.
 *
 * @property Float first The first field's value.
 * @property Float second The second field's value.
 */
private class FieldPairValues(val first: Float, val second: Float)