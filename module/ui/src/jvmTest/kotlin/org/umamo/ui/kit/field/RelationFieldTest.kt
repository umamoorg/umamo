package org.umamo.ui.kit.field

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.UmamoTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The relation field reads out its binding until it is focused, and then searches from an empty query: the tap that
 * focuses it never carries the read-out into the search, and typing edits the query.
 */
@OptIn(ExperimentalTestApi::class)
class RelationFieldTest {
	@Test
	fun focusingTheFieldSearchesFromAnEmptyQuery() =
		runComposeUiTest {
			setContent {
				UmamoTheme {
					Box(modifier = Modifier.width(240.dp)) {
						RelationField(
							selected = "lattice",
							candidates = listOf("lattice", "turn"),
							label = { candidate -> candidate },
							icon = { LocalUmamoIcons.warpDeformer },
							onSelect = {},
							modifier = Modifier.width(240.dp),
						)
					}
				}
			}
			waitForIdle()

			onNode(hasText("lattice") and hasSetTextAction()).performClick()
			waitForIdle()

			assertTrue(onAllNodes(hasText("") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty(), "the focused field holds an empty query")
			assertTrue(onAllNodes(hasText("lattice") and hasSetTextAction()).fetchSemanticsNodes().isEmpty(), "the read-out did not carry into the search")

			onNode(hasSetTextAction()).performTextInput("tu")
			waitForIdle()

			assertTrue(onAllNodes(hasText("tu") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty(), "typing edits the query")
		}
}