package org.umamo.ui.tracks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.LocalUmamoColors
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How a partly-selected summary mark is drawn: split on the diagonal from its top-right corner to its
 * bottom-left, the accent on the top-left half.  In a group drag it comes apart, the selected part
 * traveling with the drag and the rest staying put.
 *
 * Pixel tests, because the question is what is on screen: the selection a summary carries is pinned in
 * TrackAxisTest, and nothing short of a render shows which half of the mark took the accent.
 */
@OptIn(ExperimentalTestApi::class)
class TrackPartialMarkTest {
	/** A folded group whose two tracks are keyed at 0, one key selected: one partly-selected summary mark. */
	private val partlySelectedGroup =
		TrackRow(
			key = "owner",
			label = "Owner",
			children =
				listOf(
					TrackRow(key = "a", label = "A", marks = listOf(TrackKeyMark(0, 0f, selection = TrackMarkSelection.Selected))),
					TrackRow(key = "b", label = "B", marks = listOf(TrackKeyMark(0, 0f))),
				),
		)

	/**
	 * One render of the sheet: its pixels, its width, and the theme colors a mark is drawn in.
	 *
	 * @property IntArray pixels The ARGB pixels, row by row.
	 * @property Int width The image width.
	 * @property Color accent The selected fill.
	 * @property Color plain The unselected fill.
	 */
	private class Render(val pixels: IntArray, val width: Int, val accent: Color, val plain: Color) {
		/**
		 * Whether the pixel at ([x], [y]) is nearer [accent] than [plain].
		 *
		 * @param Int x The column.
		 * @param Int y The row.
		 * @return Boolean True for the accent.
		 */
		fun isAccent(x: Int, y: Int): Boolean {
			val pixel = pixels[y * width + x]

			return distance(pixel, accent.toArgb()) < distance(pixel, plain.toArgb())
		}

		/**
		 * The summed channel distance between two ARGB colors.
		 *
		 * @param Int first One color.
		 * @param Int second The other.
		 * @return Int The distance.
		 */
		private fun distance(first: Int, second: Int): Int =
			listOf(16, 8, 0).sumOf { shift -> abs((first shr shift and 0xFF) - (second shr shift and 0xFF)) }
	}

	/**
	 * A drawn mark's bounds, found as a run of columns that differ from the mark-less render.
	 *
	 * @property Int left The first column.
	 * @property Int right The last column.
	 * @property Int top The first row.
	 * @property Int bottom The last row.
	 */
	private class Blob(val left: Int, val right: Int, val top: Int, val bottom: Int) {
		val centerX: Int get() = (left + right) / 2
		val centerY: Int get() = (top + bottom) / 2

		/** Half the mark's width: how far from the center a sample can sit and still be inside it. */
		val radius: Int get() = (right - left) / 2
	}

	/**
	 * Renders the sheet over [rows], with [groupDelta] as the in-flight group drag.
	 *
	 * @param List rows The rows.
	 * @param Float? groupDelta The group-drag offset, or null for none.
	 * @return Render The render.
	 */
	private fun render(rows: List<TrackRow>, groupDelta: Float?): Render {
		var result: Render? = null
		runComposeUiTest {
			var accent = Color.Unspecified
			var plain = Color.Unspecified
			setContent {
				accent = LocalUmamoColors.current.accent
				plain = LocalUmamoColors.current.controlGlyph
				Box(modifier = Modifier.size(width = 400.dp, height = 60.dp).testTag("sheet")) {
					TrackSheet(
						rows = rows,
						axis = TrackAxis(-30f, 30f),
						playhead = null,
						modifier = Modifier.fillMaxSize(),
						selectedMarkDragDelta = { groupDelta },
						markRadius = 12.dp,
					)
				}
			}
			val image = onNodeWithTag("sheet").captureToImage()
			val pixels = IntArray(image.width * image.height)
			image.readPixels(pixels)
			result = Render(pixels, image.width, accent, plain)
		}

		return requireNotNull(result)
	}

	/**
	 * The marks drawn over the bare lane, left to right.
	 *
	 * @param Render marked The render with the marks.
	 * @param Render bare The same sheet with none.
	 * @return List The marks' bounds.
	 */
	private fun blobs(marked: Render, bare: Render): List<Blob> {
		val height = marked.pixels.size / marked.width
		val changedColumns = (0 until marked.width).filter { x -> (0 until height).any { y -> marked.pixels[y * marked.width + x] != bare.pixels[y * marked.width + x] } }
		val runs = ArrayList<IntRange>()

		for (x in changedColumns) {
			val last = runs.lastOrNull()

			if (last != null && x == last.last + 1) {
				runs[runs.size - 1] = last.first..x
			} else {
				runs.add(x..x)
			}
		}

		return runs.map { run ->
			val rows = (0 until height).filter { y -> run.any { x -> marked.pixels[y * marked.width + x] != bare.pixels[y * marked.width + x] } }
			Blob(run.first, run.last, rows.first(), rows.last())
		}
	}

	/** The mark-less render of the same group, to find the marks against. */
	private fun bareRender(): Render =
		render(listOf(partlySelectedGroup.copy(children = partlySelectedGroup.children.map { child -> child.copy(marks = emptyList()) })), null)

	/** At rest, the mark is split on the NE-SW diagonal, the accent on its top-left half. */
	@Test
	fun aPartlySelectedSummaryIsSplitOnTheDiagonal() {
		val marked = render(listOf(partlySelectedGroup), null)
		val mark = blobs(marked, bareRender()).single()
		val reach = mark.radius / 2

		assertTrue(marked.isAccent(mark.centerX - reach, mark.centerY - reach), "the top-left corner takes the accent")
		assertTrue(marked.isAccent(mark.centerX - reach, mark.centerY), "and the left")
		assertTrue(marked.isAccent(mark.centerX, mark.centerY - reach), "and the top")
		assertTrue(!marked.isAccent(mark.centerX + reach, mark.centerY + reach), "the bottom-right corner keeps the plain fill")
		assertTrue(!marked.isAccent(mark.centerX + reach, mark.centerY), "and the right")
		assertTrue(!marked.isAccent(mark.centerX, mark.centerY + reach), "and the bottom")
	}

	/** In a group drag, the selected part travels as a selected mark and the rest stays put, unselected. */
	@Test
	fun aGroupDragTakesTheSelectedPartAlong() {
		val marked = render(listOf(partlySelectedGroup), 15f)
		val marks = blobs(marked, bareRender())
		assertEquals(2, marks.size, "the mark comes apart into two")
		val (stays, travels) = marks

		for (mark in listOf(stays, travels)) {
			val reach = mark.radius / 2
			val samples = listOf(mark.centerX - reach to mark.centerY - reach, mark.centerX + reach to mark.centerY + reach)
			val accentSamples = samples.count { (x, y) -> marked.isAccent(x, y) }
			val expected = if (mark === travels) samples.size else 0
			assertEquals(expected, accentSamples, if (mark === travels) "the traveling part is wholly selected" else "the part left behind is wholly unselected")
		}
	}

	/** A group drag clamped to a standstill moves nothing, so the mark stays whole and split. */
	@Test
	fun aStandstillKeepsTheMarkWhole() {
		val marked = render(listOf(partlySelectedGroup), 0f)
		val mark = blobs(marked, bareRender()).single()
		val reach = mark.radius / 2

		assertTrue(marked.isAccent(mark.centerX - reach, mark.centerY - reach), "still split: the top-left is the accent")
		assertTrue(!marked.isAccent(mark.centerX + reach, mark.centerY + reach), "and the bottom-right is not")
	}
}