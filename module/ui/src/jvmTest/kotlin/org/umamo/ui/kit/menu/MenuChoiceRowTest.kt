package org.umamo.ui.kit.menu

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.umamo.ui.kit.BelowAnchorPositionProvider
import org.umamo.ui.kit.chip.DropdownChip
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.UmamoTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A menu holding choice rows: the choice in effect reads as selected and the others as not, a row that is no choice
 * says nothing either way, and every row keeps the leading slot so the labels share one edge.  A menu with no choice
 * row and no icon keeps no slot, and the tick shares the slot with the icons rather than adding a column of its own.
 */
class MenuChoiceRowTest {
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aChoiceRowIsSelectedWhileItIsTheOneInEffect() {
		var rows by mutableStateOf(CHOICE_ROWS)
		runComposeUiTest {
			setContent {
				UmamoTheme {
					var expanded by remember { mutableStateOf(true) }
					Box(modifier = Modifier.size(400.dp, 300.dp)) {
						DropdownChip(
							expanded = expanded,
							onExpandRequest = { expanded = true },
							contentDescription = CHIP_LABEL,
							label = CHIP_LABEL,
						) {
							Menu(
								items = rows,
								onDismissRequest = { expanded = false },
								positionProvider = BelowAnchorPositionProvider,
							)
						}
					}
				}
			}
			waitForIdle()

			onNodeWithText(DIAL).assertIsSelected()
			onNodeWithText(FOLLOW).assertIsNotSelected()
			onNodeWithText(PLAIN).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))

			val dialLeft = onNodeWithText(DIAL, useUnmergedTree = true).getBoundsInRoot().left
			val plainLeft = onNodeWithText(PLAIN, useUnmergedTree = true).getBoundsInRoot().left
			assertEquals(dialLeft, onNodeWithText(FOLLOW, useUnmergedTree = true).getBoundsInRoot().left, "a ticked row and an unticked one share the label edge")
			assertEquals(dialLeft, plainLeft, "and so does a row that is no choice")

			rows =
				listOf(
					MenuItem.Action(label = FOLLOW, onSelect = {}, icon = LocalUmamoIcons.reset, checked = false),
					MenuItem.Action(label = DIAL, onSelect = {}, icon = LocalUmamoIcons.reset, checked = true),
					MenuItem.Action(label = PLAIN, onSelect = {}, icon = LocalUmamoIcons.reset),
				)
			waitForIdle()

			assertEquals(dialLeft, onNodeWithText(DIAL, useUnmergedTree = true).getBoundsInRoot().left, "the tick takes the icon's slot, not a column of its own")
			assertEquals(dialLeft, onNodeWithText(FOLLOW, useUnmergedTree = true).getBoundsInRoot().left, "an unticked choice row's icon sits in the same slot")
			assertEquals(dialLeft, onNodeWithText(PLAIN, useUnmergedTree = true).getBoundsInRoot().left, "and so does an icon row's")

			rows = listOf(MenuItem.Action(label = PLAIN, onSelect = {}))
			waitForIdle()

			assertTrue(onNodeWithText(PLAIN, useUnmergedTree = true).getBoundsInRoot().left < plainLeft, "a menu with no choice row and no icon keeps no slot")
		}
	}

	private companion object {
		const val CHIP_LABEL = "Control"
		const val FOLLOW = "Follow Settings"
		const val DIAL = "Dial"
		const val PLAIN = "Delete Parameter"

		val CHOICE_ROWS =
			listOf(
				MenuItem.Action(label = FOLLOW, onSelect = {}, checked = false),
				MenuItem.Action(label = DIAL, onSelect = {}, checked = true),
				MenuItem.Action(label = PLAIN, onSelect = {}),
			)
	}
}