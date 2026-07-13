package org.umamo.ui.viewport

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Cursor
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Robot
import java.awt.Toolkit
import java.awt.image.BufferedImage
import kotlin.math.roundToInt

/**
 * Desktop actual: the AWT system move cursor (the same four-way cursor the area drag corner uses),
 * shown while middle-mouse panning the viewport.
 *
 * デスクトップ実装：AWT の移動カーソルを返す。
 *
 * @return PointerIcon The AWT-backed move cursor.
 */
actual fun grabPanPointerIcon(): PointerIcon = PointerIcon(Cursor(Cursor.MOVE_CURSOR))

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

/**
 * A single cached transparent AWT cursor; falls back to the default pointer if the toolkit cannot build a
 * custom cursor (a locked-down or headless environment).
 */
private val hiddenCursorIcon: PointerIcon by lazy {
	try {
		val transparent = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
		val cursor = Toolkit.getDefaultToolkit().createCustomCursor(transparent, Point(0, 0), "umamo-hidden")
		PointerIcon(cursor)
	} catch (error: Exception) {
		// HeadlessException, IndexOutOfBoundsException (bad hotspot), or a toolkit failure - fall back.
		PointerIcon.Default
	}
}

/**
 * Desktop actual: an invisible cursor built from a fully transparent 16x16 image.
 *
 * デスクトップ実装：透明画像から作った不可視カーソルを返す。
 *
 * @return PointerIcon The transparent cursor, or the default where none can be built.
 */
actual fun hiddenPointerIcon(): PointerIcon = hiddenCursorIcon
