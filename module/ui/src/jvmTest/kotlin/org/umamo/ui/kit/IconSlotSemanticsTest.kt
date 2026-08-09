package org.umamo.ui.kit

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.ui.kit.button.IconSlot
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.UmamoTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The kit's label contract for the slots whose gesture the caller owns: every one of them publishes an
 * accessible name, and a disclosure chevron publishes WHICH WAY it would move.
 *
 * These are the two places that invariant now lives, so they are the two worth pinning.  The failure they
 * guard against is silent by nature - a glyph-only control with no name looks perfectly fine on screen and
 * is simply absent to a screen reader - and it is exactly what the hand-rolled slots these replaced did.
 */
class IconSlotSemanticsTest {
	/** A slot publishes the name it was given. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aSlotPublishesItsContentDescription() {
		assertEquals("Toggle Visibility", slotDescription(hoverLabel = "Toggle Visibility"))
	}

	/**
	 * Suppressing the tooltip must not cost the accessible name.
	 *
	 * The blank hoverLabel is the escape hatch for a slot sitting beside its own visible text, and the whole
	 * point of separating it from contentDescription is that only the hover card goes away.
	 */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aBlankHoverLabelKeepsTheAccessibleName() {
		assertEquals("Toggle Visibility", slotDescription(hoverLabel = ""))
	}

	/**
	 * A chevron's default name distinguishes its two states.
	 *
	 * This is the reason a disclosure chevron is labelled at all: the row's own text names the section but
	 * never says whether it is open, so a name that read the same either way would carry nothing.  Asserted
	 * as a difference rather than against the English wording, which is free to change.
	 */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aChevronNamesTheDirectionItWouldMove() {
		val collapsed = chevronDescription(expanded = false)
		val expanded = chevronDescription(expanded = true)

		assertTrue(collapsed.isNotBlank(), "a collapsed chevron must publish a name")
		assertTrue(expanded.isNotBlank(), "an expanded chevron must publish a name")
		assertNotEquals(collapsed, expanded, "the name must say which way the chevron would move")
	}

	/** A caller with a more specific name than "expand" / "collapse" keeps it. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun anExplicitChevronNameWins() {
		assertEquals("Range", chevronDescription(expanded = false, contentDescription = "Range"))
	}

	/**
	 * Composes one [IconSlot] and returns the accessible name it published.
	 *
	 * @param String hoverLabel The tooltip text to pass; blank attaches no tooltip.
	 * @return String The published content description.
	 */
	@OptIn(ExperimentalTestApi::class)
	private fun slotDescription(hoverLabel: String): String {
		var description = ""
		runComposeUiTest {
			setContent {
				UmamoTheme {
					IconSlot(
						icon = LocalUmamoIcons.eyeVisible,
						contentDescription = "Toggle Visibility",
						tint = Color.White,
						modifier = Modifier.testTag(SLOT_TAG),
						hoverLabel = hoverLabel,
					)
				}
			}
			description = publishedDescription()
		}
		return description
	}

	/**
	 * Composes one [DisclosureChevron] and returns the accessible name it published.
	 *
	 * @param Boolean expanded         Whether the chevron reads as open.
	 * @param String? contentDescription An explicit name, or null to take the default.
	 * @return String The published content description.
	 */
	@OptIn(ExperimentalTestApi::class)
	private fun chevronDescription(expanded: Boolean, contentDescription: String? = null): String {
		var description = ""
		runComposeUiTest {
			setContent {
				UmamoTheme {
					if (contentDescription == null) {
						DisclosureChevron(
							expanded = expanded,
							tint = Color.White,
							modifier = Modifier.testTag(SLOT_TAG),
						)
					} else {
						DisclosureChevron(
							expanded = expanded,
							tint = Color.White,
							modifier = Modifier.testTag(SLOT_TAG),
							contentDescription = contentDescription,
						)
					}
				}
			}
			description = publishedDescription()
		}
		return description
	}

	/**
	 * Reads the tagged node's content description off the unmerged tree.
	 *
	 * Unmerged because the tag and the description ride the same modifier chain - the slot's face - and a
	 * merged read would answer for whatever ancestor absorbed them.
	 *
	 * @return String The single published description, or "" when the node published none.
	 */
	@OptIn(ExperimentalTestApi::class)
	private fun ComposeUiTest.publishedDescription(): String {
		waitForIdle()
		val node = onNodeWithTag(SLOT_TAG, useUnmergedTree = true).fetchSemanticsNode()
		return node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull().orEmpty()
	}

	private companion object {
		/** The tag both helpers hang on the slot's face, so the description is read off that exact node. */
		const val SLOT_TAG = "slot"
	}
}