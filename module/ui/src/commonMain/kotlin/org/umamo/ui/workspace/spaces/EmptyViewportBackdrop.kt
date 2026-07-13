package org.umamo.ui.workspace.spaces

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import org.umamo.ui.theme.LocalUmamoColors
import kotlin.math.ceil

// The no-document backdrop has no camera to anchor a world-aligned grid to, so it draws a fixed
// screen-space grid purely as decoration: a minor line every this many framebuffer pixels, with a major
// line every MINOR_LINES_PER_MAJOR of them.  These are display constants, not the document grid config.
private const val EMPTY_GRID_MINOR_CELL_PX = 16f
private const val MINOR_LINES_PER_MAJOR = 8

/**
 * The no-document viewport body: a flat themed backdrop with a faint grid, the Compose stand-in for the
 * world-aligned grid the GL renderer draws behind an open model - so the viewport area reads as the work
 * surface even before a file is opened, and opening one only swaps in the puppet.

 * Unlike the GL grid this one is screen-space (the empty state has no camera to align world coordinates
 * to), so it is decorative only.  The colors are the LocalUmamoColors grid palette the binding also pushes
 * into the GL renderer, so this backdrop matches the GL one per theme and recolors on a theme switch for
 * free.  The minor and major lines each batch into ONE path stroked in a single command rather than a draw
 * per line: the window surface re-rasterizes this backdrop's recorded draw commands on every frame a
 * sibling live viewport produces, so a per-line command count would be paid every frame; the two paths
 * keep that replay cheap.
 *
 * ドキュメント未オープン時のビューポート背景。GL レンダラと同じテーマ連動グリッド（ただし画面固定・装飾用）。
 *
 * @param Modifier modifier The layout modifier.
 */
@Composable
internal fun EmptyViewportBackdrop(modifier: Modifier = Modifier) {
	val colors = LocalUmamoColors.current
	Canvas(modifier = modifier.fillMaxSize()) {
		drawRect(color = colors.viewportGridBackground)
		val minorCell = EMPTY_GRID_MINOR_CELL_PX
		val columnCount = ceil(size.width / minorCell).toInt()
		val rowCount = ceil(size.height / minorCell).toInt()
		val minorLines = Path()
		val majorLines = Path()
		for (columnIndex in 0..columnCount) {
			val x = columnIndex * minorCell
			val target = if (columnIndex % MINOR_LINES_PER_MAJOR == 0) majorLines else minorLines
			target.moveTo(x, 0f)
			target.lineTo(x, size.height)
		}
		for (rowIndex in 0..rowCount) {
			val y = rowIndex * minorCell
			val target = if (rowIndex % MINOR_LINES_PER_MAJOR == 0) majorLines else minorLines
			target.moveTo(0f, y)
			target.lineTo(size.width, y)
		}
		drawPath(path = minorLines, color = colors.viewportGridLineMinor, style = Stroke(width = 1f))
		drawPath(path = majorLines, color = colors.viewportGridLineMajor, style = Stroke(width = 1f))
	}
}
