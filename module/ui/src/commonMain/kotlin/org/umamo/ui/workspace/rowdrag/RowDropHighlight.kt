package org.umamo.ui.workspace.rowdrag

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.UmamoColors

/** How far the ring's outer edge sits inside the row, so it never lands on an edge a flush neighbour shares. */
private val RING_INSET = 1.dp

/** The ring's stroke, weighted like the insertion lines the row drags draw (2.5 dp) rather than a hairline. */
private val RING_STROKE = 2.dp

/**
 * The drop-target treatment every row drag shares: while [active], the drop-target fill in [shape]
 * with a 2 dp accent ring inset 1 dp inside the row, drawn behind the row's content.  Inset and
 * weighted on purpose - a 1 dp border on the row's own edge sits on the line a flush neighbour and the
 * zebra band already occupy, and in the same hue as the fill it outlines it reads as the fill's
 * antialiased edge rather than as a ring.  Applied by the outliner, the parameters panel, and the
 * Sources table, so the three read alike.
 *
 * @param Boolean     active Whether this row is the drop target right now.
 * @param Shape       shape  The row's shape, so the fill and the ring follow its corners.
 * @param UmamoColors colors The palette the fill and the ring come from.
 * @return Modifier This modifier with the highlight behind it, or unchanged when inactive.
 */
fun Modifier.rowDropHighlight(active: Boolean, shape: Shape, colors: UmamoColors): Modifier {
	if (!active) {
		return this
	}
	return drawBehind {
		drawOutline(shape.createOutline(size, layoutDirection, this), colors.dropTargetBackground)
		val stroke = RING_STROKE.toPx()
		// The stroke is centred on the inset outline, so its outer edge sits RING_INSET inside the row.
		inset(RING_INSET.toPx() + stroke / 2f) {
			drawOutline(shape.createOutline(size, layoutDirection, this), colors.accent, style = Stroke(width = stroke))
		}
	}
}