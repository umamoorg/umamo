package org.umamo.ui.viewport

import androidx.compose.ui.geometry.Offset
import java.awt.MouseInfo
import java.awt.Robot
import kotlin.math.roundToInt

/**
 * A single shared AWT Robot used to warp the cursor.  It is null when the platform denies low-level input
 * control - a headless environment (Robot's constructor throws there) or a locked-down SecurityManager -
 * in which case cursor warps degrade to no-ops rather than crashing.
 */
private val cursorWarpRobot: Robot? by lazy {
	try {
		Robot()
	} catch (error: Exception) {
		// AWTException (incl. headless), SecurityException, or HeadlessException - warp is unavailable.
		null
	}
}

/**
 * Desktop actual: teleport the OS cursor to the given screen pixel via AWT Robot, then read back where
 * it actually landed via MouseInfo.  The read-back (rather than trusting the request) is what keeps the
 * wrap compensation exact when mouseMove rounds, clamps at a screen edge, or is rescaled by the platform
 * (HiDPI / WSLg).  A no-op returning null when no Robot is available (headless / denied), so the caller
 * leaves its gesture anchor alone.
 *
 * デスクトップ実装：AWT Robot でカーソルを画面座標へ移動し、実際の着地点を MouseInfo で読み返す。
 *
 * @param Float screenX The requested absolute screen x in pixels.
 * @param Float screenY The requested absolute screen y in pixels.
 * @return Offset? The absolute screen position the cursor landed at, or null when no Robot is available.
 */
actual fun warpViewportCursor(screenX: Float, screenY: Float): Offset? {
	val robot = cursorWarpRobot ?: return null
	robot.mouseMove(screenX.roundToInt(), screenY.roundToInt())
	// MouseInfo can be unavailable (headless edge cases): fall back to the rounded request, the best
	// available estimate of where the cursor landed.
	val landed = MouseInfo.getPointerInfo()?.location ?: return Offset(screenX.roundToInt().toFloat(), screenY.roundToInt().toFloat())
	return Offset(landed.x.toFloat(), landed.y.toFloat())
}
