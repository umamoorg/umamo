package org.umamo.ui.workspace.area

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.Dp
import org.umamo.edit.EditorSession
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.action.LocalKeymap
import org.umamo.ui.action.defaultKeymap
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.theme.UmamoTheme
import org.umamo.ui.workspace.AreaScope
import org.umamo.ui.workspace.LocalSpaceRegistry
import org.umamo.ui.workspace.SpaceKind
import org.umamo.ui.workspace.layout.LeafArea
import org.umamo.ui.workspace.shell.defaultSpaceRegistry

/*
 * The header test mount shared by the overflow tests and the header-control tests: one space's REAL header
 * strip through the real registry, with the locals its controls read, inside a fixed-width box.
 */

/** The area id the shared mount gives its leaf. */
internal const val HEADER_TEST_AREA_ID = "area-1"

/**
 * Mounts one space's real header, with the command registry and keymap its controls read.
 *
 * By default no puppet and no session are provided, which is the no-document state every header already
 * handles - the viewport chips render disabled and the panel headers render nothing.  A puppet comes with a
 * session over it, as the app provides the two together.  The scope is the test's to hold when it reads the
 * per-area state a control writes.
 *
 * @param SpaceKind kind        The space whose header strip to mount.
 * @param Dp        headerWidth The width the header is given.
 * @param State     puppet      The open document, or null for the no-document state; a case that writes it
 *   publishes a model, as an edit does.
 * @param AreaScope scope       The hosting area's scope, the one channel the header shares state through.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.setAreaHeader(
	kind: SpaceKind,
	headerWidth: Dp,
	puppet: State<PuppetModel?> = mutableStateOf(null),
	scope: AreaScope = AreaScope(HEADER_TEST_AREA_ID),
) {
	setContent {
		val session = remember { puppet.value?.let { model -> EditorSession(model) } }
		UmamoTheme {
			CompositionLocalProvider(
				LocalSpaceRegistry provides defaultSpaceRegistry(),
				LocalCommands provides CommandRegistry(),
				LocalKeymap provides defaultKeymap(),
				LocalPuppet provides puppet.value,
				LocalEditorSession provides session,
			) {
				Box(modifier = Modifier.width(headerWidth)) {
					AreaHeader(area = LeafArea(scope.areaId, kind), scope = scope, onCommand = {})
				}
			}
		}
	}
	waitForIdle()
}

/**
 * A document with no content - enough to satisfy the headers' open-document gate, which is all the header
 * tests need.
 *
 * @return PuppetModel The empty document.
 */
internal fun emptyHeaderPuppet(): PuppetModel =
	PuppetModel(
		parameters = emptyList(),
		parts = emptyList(),
		deformers = emptyList(),
		drawables = emptyList(),
		rootChildren = emptyList(),
		rootPartId = null,
	)