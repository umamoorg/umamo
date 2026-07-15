package org.umamo.ui.workspace.spaces

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Dp
import kotlin.math.floor

/**
 * Paints an alternating "zebra" stripe pattern across the whole drawing area, filling it top to bottom
 * regardless of how many list rows are present, so a short or empty list still reads as a prepared panel
 * rather than a list that runs out partway down.  The stripe grid is anchored to the first visible row
 * (read from [listState]) and steps in [rowHeight] bands, so the painted stripes stay locked to the rows
 * as the list scrolls; odd-indexed bands take [stripeColor] and even bands are left clear.  Rows draw only
 * their selection / hover / match overlays on top - the base stripe lives here, behind them.
 *
 * The caller must draw the list flush at the area's top edge with no leading vertical inset (row index 0
 * at y = 0) and rows of uniform [rowHeight]; otherwise the bands and rows drift apart.
 *
 * @param LazyListState listState  The list whose scroll position anchors the stripe grid.
 * @param Dp           rowHeight   The uniform row height, which is also the stripe band height.
 * @param Color        stripeColor The fill for the odd (striped) bands.
 * @return Modifier The modifier drawing the zebra fill behind its content.
 */
internal fun Modifier.zebraFill(listState: LazyListState, rowHeight: Dp, stripeColor: Color): Modifier =
	this.drawBehind {
		val rowHeightPx = rowHeight.toPx()
		if (rowHeightPx <= 0f) {
			return@drawBehind
		}
		// Anchor the band grid to the first visible row so bands track the rows while scrolling; an empty
		// list has no visible row, so fall back to band 0 flush at the area's top edge.
		val firstVisible = listState.layoutInfo.visibleItemsInfo.firstOrNull()
		val anchorTop = firstVisible?.offset?.toFloat() ?: 0f
		val anchorIndex = firstVisible?.index ?: 0
		// Step back to the band covering the top edge (its top is the greatest band top <= 0), then stripe
		// downward past the last row to the area's foot so a short or empty list still reads as filled.
		var bandIndex = anchorIndex + floor(-anchorTop / rowHeightPx).toInt()
		var bandTop = anchorTop + (bandIndex - anchorIndex) * rowHeightPx
		// Clip to the drawing area: drawBehind does not clip, and the first / last bands overhang the top and
		// bottom edges (the top band belongs to the partially scrolled-off first row) - unclipped they would
		// paint over the neighbouring chrome, e.g. the area header sitting directly above this body.
		clipRect(left = 0f, top = 0f, right = size.width, bottom = size.height) {
			while (bandTop < size.height) {
				// mod (not %) so the parity stays correct even if a band index ever goes negative.
				if (bandIndex.mod(2) == 1) {
					drawRect(
						color = stripeColor,
						topLeft = Offset(0f, bandTop),
						size = Size(size.width, rowHeightPx),
					)
				}
				bandTop += rowHeightPx
				bandIndex += 1
			}
		}
	}
