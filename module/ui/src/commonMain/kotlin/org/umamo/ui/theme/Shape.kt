package org.umamo.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/*
 * Compact corner rounding for a dense editor surface - tighter than the conventional defaults so panels,
 * chips, and menus read as crisp tool chrome. The small radius matches the 4 dp viewport zoom chip.
 *
 * 密度の高いエディタ向けの小さめの角丸。
 */

/**
 * The five corner-radius roles (same names as the conventional scale).  Typed as CornerBasedShape
 * rather than plain Shape so callers can derive positional variants via copy() - e.g. a ButtonGroup
 * segment squaring the corners it shares with a neighbour.
 */
data class UmamoShapes(
	val extraSmall: CornerBasedShape,
	val small: CornerBasedShape,
	val medium: CornerBasedShape,
	val large: CornerBasedShape,
	val extraLarge: CornerBasedShape,
)

/** The compact shape set. */
val umamoShapes =
	UmamoShapes(
		extraSmall = RoundedCornerShape(2.dp),
		small = RoundedCornerShape(4.dp),
		medium = RoundedCornerShape(6.dp),
		large = RoundedCornerShape(10.dp),
		extraLarge = RoundedCornerShape(14.dp),
	)

/** The active shape set (defaults to [umamoShapes]; [UmamoTheme] provides it). */
val LocalUmamoShapes = staticCompositionLocalOf { umamoShapes }
