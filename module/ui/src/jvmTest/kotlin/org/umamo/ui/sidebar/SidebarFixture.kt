package org.umamo.ui.sidebar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import org.umamo.edit.EditorSession
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.action.LocalKeymap
import org.umamo.ui.action.defaultKeymap
import org.umamo.ui.model.LocalEditorMode
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.model.LocalSelection
import org.umamo.ui.model.rememberSessionEditorState
import org.umamo.ui.workspace.LocalViewportChrome
import org.umamo.ui.workspace.SpaceKind
import org.umamo.ui.workspace.ViewportChromeState
import org.umamo.ui.workspace.sidebar.SidebarDrawer
import org.umamo.ui.workspace.sidebar.SidebarState

/*
 * The harness the sidebar tests share: what a sidebar reads from the shell, provided the way the shell provides
 * it, and a drawer mounted against the right edge of an area of a given size.
 */

/**
 * A registry holding Umamo's own tabs and panels, as the app's window holds one.
 *
 * @return SidebarRegistry The registry.
 */
internal fun builtInSidebarRegistry(): SidebarRegistry = SidebarRegistry().also { registry -> registerBuiltInSidebar(registry) }

/**
 * Provides what a sidebar reads around [content], as the shell provides it: an empty command registry and the
 * default keymap, [registry], chrome defaults with the sidebar open, the model as [session] publishes it, the
 * session, and the session-backed selection and mode handles.  [extra] adds the locals a case needs beyond those,
 * such as the density, the settings, the UI language, or the scrub-cancel seam.
 *
 * @param EditorSession session The session the sidebar reads and writes.
 * @param SidebarRegistry registry The window's registry.
 * @param ProvidedValue extra Further locals, none of them one of the above.
 * @param Function content The content.
 */
@Composable
internal fun ProvideSidebarLocals(
	session: EditorSession,
	registry: SidebarRegistry,
	vararg extra: ProvidedValue<*>,
	content: @Composable () -> Unit,
) {
	val puppet by session.model.collectAsState()
	val editorState = rememberSessionEditorState(session)
	CompositionLocalProvider(
		LocalCommands provides CommandRegistry(),
		LocalKeymap provides defaultKeymap(),
		LocalSidebarRegistry provides registry,
		LocalViewportChrome provides ViewportChromeState(showSidebar = true),
		LocalPuppet provides puppet,
		LocalEditorSession provides session,
		LocalSelection provides editorState,
		LocalEditorMode provides editorState,
		*extra,
		content = content,
	)
}

/**
 * A work surface's sidebar drawer against the right edge of an area [width] by [height].
 *
 * @param String areaId The area the sidebar belongs to.
 * @param SidebarState state The area's sidebar state.
 * @param Dp width The area's width.
 * @param Dp height The area's height.
 * @param Modifier modifier Layout modifier for the area's box (a test tag, say).
 * @param SpaceKind space The work surface the area shows, which scopes the panels.
 */
@Composable
internal fun SidebarDrawerInArea(
	areaId: String,
	state: SidebarState,
	width: Dp,
	height: Dp,
	modifier: Modifier = Modifier,
	space: SpaceKind = SpaceKind.Viewport2D,
) {
	Box(modifier = modifier.size(width, height)) {
		SidebarDrawer(
			areaId = areaId,
			space = space,
			state = state,
			passPointerThrough = false,
			modifier = Modifier.align(Alignment.CenterEnd),
		)
	}
}