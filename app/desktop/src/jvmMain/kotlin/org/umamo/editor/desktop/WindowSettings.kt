package org.umamo.editor.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import org.umamo.settings.Settings

/**
 * The window size/position/placement to open with - restored from `interface.window.*`, or a sensible
 * default (1280×800, platform-placed, floating) on first run. A window saved while maximized or fullscreen
 * reopens that way; the persisted size/position are the floating restore geometry it returns to.
 *
 * @return WindowState The starting window state.
 */
fun Settings.savedWindowState(): WindowState {
	val width = (getDouble("interface.window.width") ?: 1280.0).dp
	val height = (getDouble("interface.window.height") ?: 800.0).dp
	val x = getDouble("interface.window.x")
	val y = getDouble("interface.window.y")
	val position = if (x != null && y != null) WindowPosition(x.dp, y.dp) else WindowPosition.PlatformDefault
	val placement =
		when (getString("interface.window.placement")) {
			"maximized" -> WindowPlacement.Maximized
			"fullscreen" -> WindowPlacement.Fullscreen
			else -> WindowPlacement.Floating
		}
	return WindowState(placement = placement, size = DpSize(width, height), position = position)
}

/**
 * Persists [state]'s current placement, plus its size + position, to `interface.window.*` so the next launch
 * restores them. Call on close (the [WindowState] tracks live resizes/moves/maximize, so it holds the final
 * state). Size and position are recorded only while floating: when maximized or fullscreen, [state]'s size
 * and position are the screen-filling bounds, so saving them would clobber the restore geometry the window
 * should return to on un-maximize - the last floating geometry is kept instead.
 *
 * @param WindowState state The window state to persist.
 */
fun Settings.saveWindowState(state: WindowState) {
	setString(
		"interface.window.placement",
		when (state.placement) {
			WindowPlacement.Maximized -> "maximized"
			WindowPlacement.Fullscreen -> "fullscreen"
			WindowPlacement.Floating -> "floating"
		},
	)
	if (state.placement == WindowPlacement.Floating) {
		setDouble("interface.window.width", state.size.width.value.toDouble())
		setDouble("interface.window.height", state.size.height.value.toDouble())
		val position = state.position
		if (position is WindowPosition.Absolute) {
			setDouble("interface.window.x", position.x.value.toDouble())
			setDouble("interface.window.y", position.y.value.toDouble())
		}
	}
}
