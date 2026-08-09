package org.umamo.ui.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.action.LocalKeymap
import org.umamo.ui.action.defaultKeymap
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.theme.UmamoTheme
import kotlin.test.Test

/**
 * Every space's real header strip composes and lays out through the overflow row, at a comfortable width
 * and at the narrowest an area can be dragged to.
 *
 * The kit tests drive OverflowRow with synthetic fixed-size items; this drives it with the actual
 * headerContent builders through the actual registry, which is where the integration can break in ways a
 * synthetic strip cannot show - a builder reading a CompositionLocal outside an item body, a duplicate
 * slot key, or an intrinsic measurement the SubcomposeLayout cannot answer under the header's fixed
 * height.  All of those throw rather than look wrong, so composing at all is the assertion.
 */
class AreaHeaderOverflowTest {
	/** Every space's header composes at a width no area can be dragged below. */
	@Test
	fun everySpaceHeaderComposesAtTheMinimumAreaWidth() {
		for (kind in SpaceKind.entries) {
			composeHeader(kind = kind, headerWidth = MIN_AREA_WIDTH)
		}
	}

	/** Every space's header composes with room to spare, where nothing should collapse. */
	@Test
	fun everySpaceHeaderComposesAtAComfortableWidth() {
		for (kind in SpaceKind.entries) {
			composeHeader(kind = kind, headerWidth = 900.dp)
		}
	}

	/**
	 * The 2D viewport's strip - the widest in the app - collapses into a usable panel when squeezed.
	 *
	 * Opening the panel is the part worth driving: it composes the collapsed builders a second time, in a
	 * popup, which is where a control that only works inside a Row would surface.
	 */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun theViewportHeaderCollapsesIntoAnOpenablePanel() {
		runComposeUiTest {
			setHeader(kind = SpaceKind.Viewport2D, headerWidth = MIN_AREA_WIDTH)
			onNodeWithContentDescription(MORE_LABEL, useUnmergedTree = true).assertExists()
			onNodeWithContentDescription(MORE_LABEL, useUnmergedTree = true).performClick()
			waitForIdle()
			// The mode chip is pinned, so it is the one control that must still be on the strip itself.
			onNodeWithContentDescription(OBJECT_MODE_LABEL, useUnmergedTree = true).assertExists()
		}
	}

	/**
	 * The Outliner keeps its search box AND its filter chip on the strip well before the area gets tight.
	 *
	 * Two regressions meet here.  The chip's width used to be charged against every admission even when no
	 * chip would be shown, and the search box was a fixed 160.dp that took its natural width and pushed the
	 * filter into the dropdown - so the filter turned into an overflow chip with obvious slack still on the
	 * strip.  260.dp is comfortably tighter than a default panel and must still show both.
	 */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun theOutlinerKeepsItsSearchAndFilterOnATightStrip() {
		runComposeUiTest {
			setHeader(kind = SpaceKind.Outliner, headerWidth = 260.dp, puppet = emptyPuppet())
			onNodeWithContentDescription(FILTERS_LABEL, useUnmergedTree = true).assertExists()
			onNodeWithContentDescription(MORE_LABEL, useUnmergedTree = true).assertDoesNotExist()
		}
	}

	/**
	 * A document with no content - enough to satisfy the headers' open-document gate, which is all these
	 * layout assertions need.
	 *
	 * @return PuppetModel The empty document.
	 */
	private fun emptyPuppet(): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = emptyList(),
			rootChildren = emptyList(),
			rootPartId = null,
		)

	/**
	 * Composes one space's real header inside a fixed-width box and settles it.
	 *
	 * @param SpaceKind kind        The space whose header strip to mount.
	 * @param Dp        headerWidth The width the header is given.
	 */
	@OptIn(ExperimentalTestApi::class)
	private fun composeHeader(kind: SpaceKind, headerWidth: Dp) {
		runComposeUiTest {
			setHeader(kind = kind, headerWidth = headerWidth)
		}
	}

	/**
	 * Mounts one space's real header, with the command registry and keymap its controls read.
	 *
	 * No puppet and no session are provided, which is the no-document state every header already handles -
	 * the viewport chips render disabled and the panel headers render nothing.
	 *
	 * @param SpaceKind    kind        The space whose header strip to mount.
	 * @param Dp           headerWidth The width the header is given.
	 * @param PuppetModel? puppet      The open document, or null for the no-document state.
	 */
	@OptIn(ExperimentalTestApi::class)
	private fun ComposeUiTest.setHeader(kind: SpaceKind, headerWidth: Dp, puppet: PuppetModel? = null) {
		setContent {
			UmamoTheme {
				CompositionLocalProvider(
					LocalSpaceRegistry provides defaultSpaceRegistry(),
					LocalCommands provides CommandRegistry(),
					LocalKeymap provides defaultKeymap(),
					LocalPuppet provides puppet,
				) {
					Box(modifier = Modifier.width(headerWidth)) {
						AreaHeader(area = LeafArea("area-1", kind), scope = AreaScope("area-1"), onCommand = {})
					}
				}
			}
		}
		waitForIdle()
	}

	private companion object {
		/** The narrowest an area tree lets a leaf become, so the tightest strip the shell can actually produce. */
		val MIN_AREA_WIDTH = 48.dp

		/** The overflow chip's English name; it doubles as its accessible label. */
		const val MORE_LABEL = "More"

		/** The pinned mode chip's English name, taken from its current mode with no document open. */
		const val OBJECT_MODE_LABEL = "Object Mode"

		/** The filter chip's English name; it doubles as its accessible label. */
		const val FILTERS_LABEL = "Filters"
	}
}