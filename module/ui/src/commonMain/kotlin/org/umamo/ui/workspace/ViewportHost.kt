package org.umamo.ui.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * The seam through which an app injects the platform GL viewport into the common `:ui` shell. The 2D
 * viewport (desktop renders offscreen via GLFW into a Compose Image; Android will drive a GLSurfaceView)
 * lives in the app's platform source set - `:ui` can't reference it - so a Viewport2D area asks the host
 * to render itself. The [areaId] lets the host cache one GL surface per area (via movableContentOf), so
 * the surface moves rather than tears down when an unrelated area splits.
 *
 * 共通 `:ui` シェルにプラットフォーム GL ビューポートを注入する継ぎ目。areaId でエリアごとに GL
 * サーフェスをキャッシュし、無関係な分割で再生成されないようにする。
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
 * The active [ViewportHost], or null when no GL viewport is available (e.g. before a model is open, or
 * on a platform without the viewport wired). A Viewport2D area shows a placeholder when this is null.
 *
 * 有効な ViewportHost。null のときビューポートエリアはプレースホルダを表示する。
 */
val LocalViewportHost = staticCompositionLocalOf<ViewportHost?> { null }
