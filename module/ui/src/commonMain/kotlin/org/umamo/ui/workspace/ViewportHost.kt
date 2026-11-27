package org.umamo.ui.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * The seam between a 2D viewport area and what draws it.  A Viewport2D area asks the host to render
 * itself and knows nothing of how.  The host is common code: rememberPuppetViewportHost
 * (org.umamo.ui.viewport.viewport2d) builds one per open document over the platform's render service,
 * and the service is the part an app supplies (desktop renders offscreen through GLFW into a Compose
 * Image; Android has none yet).  The host registers each area with that service under its [areaId],
 * so every area has a surface of its own.
 */
fun interface ViewportHost {
	/**
	 * Renders the 2D GL viewport for the given area.
	 *
	 * @param String areaId The hosting area's stable id (used to key the GL surface).
	 * @param Modifier modifier The layout modifier for the viewport (typically fillMaxSize).
	 */
	@Composable
	fun Viewport2D(areaId: String, modifier: Modifier)
}

/**
 * The active [ViewportHost], or null on a platform that supplies no render service.  A Viewport2D area
 * with no host shows the plain viewport backdrop.
 */
val LocalViewportHost = staticCompositionLocalOf<ViewportHost?> { null }