package org.umamo.ui.action

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.menu_file_new
import org.umamo.ui.resources.menu_file_save
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.UmamoTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The command palette's rows: each leads with its command's glyph in a slot every row keeps, so a command with no
 * glyph drawn shows a blank there and every label starts in one column.
 */
@OptIn(ExperimentalTestApi::class)
class CommandPaletteTest {
	/**
	 * Mounts the palette over [commands] in an 800 x 600 box at density 1, where a dp is a pixel.
	 *
	 * @param List<Command> commands The commands listed, in order.
	 * @param Function onInvoke Takes the command a row was picked for.
	 */
	private fun ComposeUiTest.mountPalette(commands: List<Command>, onInvoke: (Command) -> Unit = {}) {
		setContent {
			UmamoTheme {
				CompositionLocalProvider(LocalDensity provides Density(1f), LocalKeymap provides Keymap(emptyMap())) {
					Box(modifier = Modifier.size(BOX_WIDTH.dp, 600.dp)) {
						CommandPalette(commands = commands, onDismiss = {}, onInvoke = onInvoke)
					}
				}
			}
		}
		waitForIdle()
	}

	/**
	 * The bounds of the row label reading [label].
	 *
	 * @param String label The label.
	 * @return Rect The bounds in the root, in pixels.
	 */
	private fun ComposeUiTest.labelBounds(label: String): Rect {
		val bounds = onNodeWithText(label, useUnmergedTree = true).getBoundsInRoot()

		return Rect(bounds.left.value, bounds.top.value, bounds.right.value, bounds.bottom.value)
	}

	/**
	 * How many different colors are drawn in the glyph slot of the row labeled [label]: one for a blank slot, which
	 * shows only the row behind it, and more where a glyph is drawn.
	 *
	 * @param String label The row's label.
	 * @return Int The number of distinct colors.
	 */
	private fun ComposeUiTest.colorsInGlyphSlot(label: String): Int {
		val bounds = labelBounds(label)
		val image = onRoot().captureToImage()
		val pixels = IntArray(image.width * image.height)
		image.readPixels(pixels)
		val colors = HashSet<Int>()

		for (rowIndex in bounds.top.toInt() until bounds.bottom.toInt()) {
			for (columnIndex in (bounds.left - GLYPH_SLOT - GLYPH_GAP).toInt() until (bounds.left - GLYPH_GAP).toInt()) {
				colors.add(pixels[rowIndex * image.width + columnIndex])
			}
		}

		return colors.size
	}

	/** Every label starts in the same column, a glyph slot and its gap in from where a row begins. */
	@Test
	fun everyLabelStartsInOneColumnPastTheGlyphSlot() =
		runComposeUiTest {
			mountPalette(listOf(PLAIN, GLYPHED))

			val plain = labelBounds("Save")
			val glyphed = labelBounds("New")

			assertEquals(plain.left, glyphed.left, "a command with no glyph keeps the slot")
			assertTrue(plain.left - CARD_LEFT >= GLYPH_SLOT + GLYPH_GAP, "the labels start ${plain.left - CARD_LEFT} px into the card, with no room for a glyph before them")
		}

	/** A command's glyph is drawn in its row's slot, and a command with none leaves the slot blank. */
	@Test
	fun aRowDrawsItsCommandsGlyphAndABlankWhereThereIsNone() =
		runComposeUiTest {
			mountPalette(listOf(PLAIN, GLYPHED))

			assertEquals(1, colorsInGlyphSlot("Save"), "a blank slot shows only the row behind it")
			assertTrue(colorsInGlyphSlot("New") > 1, "the glyph is drawn in its slot")
		}

	/** The highlighted row keeps its slot too: its glyph is drawn over the highlight, and a blank one is the highlight alone. */
	@Test
	fun theHighlightedRowKeepsItsSlot() =
		runComposeUiTest {
			mountPalette(listOf(GLYPHED, PLAIN))

			assertTrue(colorsInGlyphSlot("New") > 1, "the first row is the highlighted one, and its glyph is still drawn")
			assertEquals(labelBounds("New").left, labelBounds("Save").left)
		}

	@Test
	fun aRowRunsItsCommand() =
		runComposeUiTest {
			val invoked = ArrayList<String>()
			mountPalette(listOf(PLAIN, GLYPHED)) { command -> invoked.add(command.id) }

			onNodeWithText("New", useUnmergedTree = true).performClick()
			waitForIdle()

			assertEquals(listOf("test.new"), invoked)
		}

	private companion object {
		/** The width of the box the palette is mounted in, in pixels. */
		const val BOX_WIDTH = 800

		/** The palette card's left edge: the card is 480 wide and centered. */
		const val CARD_LEFT = (BOX_WIDTH - 480) / 2f

		/** The glyph slot's width, in pixels at density 1. */
		const val GLYPH_SLOT = 16f

		/** The gap between the glyph slot and the label. */
		const val GLYPH_GAP = 8f

		/** A command with no glyph drawn. */
		val PLAIN = Command("test.save", title = Res.string.menu_file_save, handler = {})

		/** A command with a glyph. */
		val GLYPHED = Command("test.new", title = Res.string.menu_file_new, icon = LocalUmamoIcons.cursor, handler = {})
	}
}