package org.umamo.ui.workspace.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.PieMenuKind
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.kit.menu.PieMenuOverlay
import org.umamo.ui.theme.UmamoTheme
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * Every viewport pie lays out its real labels apart: no two chips' text (digit through label) overlap,
 * and no chip covers the pie's title at the center.
 */
class ViewportPieMenusLayoutTest {
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun everyPieKeepsItsLabelsApartAndOffTheTitle() {
		var kind by mutableStateOf(PieMenuKind.entries.first())
		var labels = emptyList<String>()
		var title = ""
		runComposeUiTest {
			setContent {
				val entries = pieMenuEntriesFor(kind)
				labels = entries.map { entry -> stringResource(entry.label) }
				title = stringResource(pieMenuTitleFor(kind))
				UmamoTheme {
					CompositionLocalProvider(LocalCommands provides CommandRegistry()) {
						Box(modifier = Modifier.size(1200.dp, 800.dp)) {
							PieMenuOverlay(entries = entries, center = Offset(600f, 400f), onDismiss = {}, title = pieMenuTitleFor(kind))
						}
					}
				}
			}
			for (pieKind in PieMenuKind.entries) {
				kind = pieKind
				waitForIdle()
				val titleBounds = boundsOf(title)
				val chipSpans = labels.mapIndexed { slotIndex, label -> spanning(boundsOf("${slotIndex + 1}"), boundsOf(label)) }
				for ((slotIndex, chipSpan) in chipSpans.withIndex()) {
					assertFalse(chipSpan.overlaps(titleBounds), "$pieKind: \"${labels[slotIndex]}\" covers the title")
					for (otherSlot in slotIndex + 1 until chipSpans.size) {
						assertFalse(chipSpan.overlaps(chipSpans[otherSlot]), "$pieKind: \"${labels[slotIndex]}\" overlaps \"${labels[otherSlot]}\"")
					}
				}
			}
		}
	}

	/**
	 * The root-space bounds of the one text node reading exactly [text].
	 *
	 * @param String text The node's full text.
	 * @return Rect The node's bounds in dp.
	 */
	@OptIn(ExperimentalTestApi::class)
	private fun ComposeUiTest.boundsOf(text: String): Rect {
		val bounds = onNodeWithText(text, useUnmergedTree = true).getBoundsInRoot()
		return Rect(bounds.left.value, bounds.top.value, bounds.right.value, bounds.bottom.value)
	}

	/**
	 * The smallest rectangle holding both [first] and [second].
	 *
	 * @param Rect first One rectangle.
	 * @param Rect second The other rectangle.
	 * @return Rect Their bounding rectangle.
	 */
	private fun spanning(first: Rect, second: Rect): Rect =
		Rect(minOf(first.left, second.left), minOf(first.top, second.top), maxOf(first.right, second.right), maxOf(first.bottom, second.bottom))
}