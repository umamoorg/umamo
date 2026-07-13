package org.umamo.ui.workspace

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The editor surface the pointer last touched: an opaque workspace-leaf area id plus the space kind
 * hosting it.  The generalization of PuppetViewportService.activeAreaId to non-viewport spaces - the
 * UV editor does not participate in the GPU service, but its G / S / R (and future tool) commands
 * still need "which area does the pointer mean" resolved at dispatch time.
 *
 * ポインタが最後に触れたエディタ面（エリア id とその空間種別）。ビューポート以外の空間にも
 * ディスパッチ時のエリア解決を提供する。
 *
 * @property String areaId The last-touched leaf's area id.
 * @property SpaceKind kind The space kind that leaf hosts.
 */
internal data class HoveredSurface(val areaId: String, val kind: SpaceKind)

/**
 * The shell-wide holder of the last-touched editor surface.  DISPATCH-TIME ONLY, exactly like
 * PuppetViewportService.activeAreaId: command handlers read [lastTouched] inside their handler bodies
 * at invocation time, never during composition - it is a non-reactive var, so a composition-time gate
 * would go stale without recomposing.  Composition gates key off a latch's own area id instead
 * (ActiveOperator.areaId, ActiveSelectTool.areaId).
 * Stamped by the editor surfaces themselves: the 2D viewport's navigation loop and the UV editor's
 * pointer loop, on every non-Exit pointer event.
 */
internal class HoveredSurfaceTracker {
	/** The surface the pointer last touched, or null before any editor surface was touched. */
	var lastTouched: HoveredSurface? = null
}

/**
 * The shell's hovered-surface tracker, or null outside an editor shell (previews, tests).  Editor
 * surfaces stamp it from their pointer loops; the shell's command tables resolve it at dispatch.
 */
internal val LocalHoveredSurfaceTracker = staticCompositionLocalOf<HoveredSurfaceTracker?> { null }
