package org.umamo.editor.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import org.umamo.ui.theme.LocalUmamoColors
import java.awt.Window

/*
 * Desktop window-chrome integration: tint the host-OS title bar to match the editor's own palette so the
 * caption does not sit as a bright system-colored strip above a dark app. Only Windows 11's Desktop Window
 * Manager exposes this (DWMWA_CAPTION_COLOR); every other platform is a no-op. The caption color is read
 * from [LocalUmamoColors] so it tracks the active light/dark theme.
 *
 * デスクトップのウィンドウ装飾連携。タイトルバーをエディタの配色に合わせて着色する（Windows 11 の DWM のみ）。
 */

/**
 * The DWM window attribute that sets the title-bar (caption) fill color.
 *
 * Win32: DWMWA_CAPTION_COLOR (dwmapi.h, value 35).  Honored on Windows 11 build 22000 and later; older
 * systems return a failure HRESULT, which we ignore so the call is harmless everywhere.
 */
private const val DWMWA_CAPTION_COLOR = 35

/**
 * Minimal JNA binding to dwmapi.dll - only the single entry point we need.  Loaded lazily and only on
 * Windows (see [setWindowsCaptionColor]); the class is never touched on other platforms, so dwmapi.dll is
 * never resolved there.
 */
private interface Dwmapi : Library {
	/**
	 * Win32: DwmSetWindowAttribute.  Sets a Desktop Window Manager attribute on a window.
	 *
	 * pvAttribute is passed by reference; for DWMWA_CAPTION_COLOR it points at a single COLORREF (DWORD),
	 * so [value] is an int-by-reference and [valueSize] is 4.
	 *
	 * @param Pointer        windowHandle The target window's HWND.
	 * @param Int            attribute    The DWMWA_* attribute identifier.
	 * @param IntByReference value        Pointer to the attribute value (a COLORREF here).
	 * @param Int            valueSize    Size of the value in bytes.
	 * @return Int The HRESULT; 0 (S_OK) on success.
	 * @note PascalCase is mandatory: JNA's interface mapper binds this method to the dwmapi.dll export of
	 *       the same name, so it must match the Win32 symbol verbatim - hence the naming-rule suppression.
	 */
	@Suppress("ktlint:standard:function-naming")
	fun DwmSetWindowAttribute(windowHandle: Pointer, attribute: Int, value: IntByReference, valueSize: Int): Int
}

/** The dwmapi.dll binding, resolved on first use.  Only ever forced on Windows. */
private val dwmapi: Dwmapi by lazy { Native.load("dwmapi", Dwmapi::class.java) }

/**
 * Converts a Compose [Color] to a Win32 COLORREF.
 *
 * Win32 COLORREF packs the channels as 0x00BBGGRR (blue high, red low, top byte zero) - the reverse of
 * ARGB's byte order - so the red and blue channels are swapped relative to [toArgb].
 *
 * @return Int The COLORREF for this color.
 */
private fun Color.toColorRef(): Int {
	val argb = toArgb()
	val red = (argb shr 16) and 0xFF
	val green = (argb shr 8) and 0xFF
	val blue = argb and 0xFF
	return (blue shl 16) or (green shl 8) or red
}

/**
 * Sets the Windows title-bar caption color to [color] via the Desktop Window Manager.  A no-op on any
 * non-Windows OS, before the window has a native peer (no HWND yet), or on a Windows build that predates
 * DWMWA_CAPTION_COLOR - in the last case DWM returns a failure HRESULT, which is deliberately ignored so
 * the call degrades to "system default caption" rather than throwing.
 *
 * @param Window window The window whose caption to tint.
 * @param Color  color  The desired caption fill color.
 */
private fun setWindowsCaptionColor(window: Window, color: Color) {
	// EN: Gate on the OS before touching the JNA binding, so dwmapi.dll is only ever loaded on Windows.
	// JA: JNA に触れる前に OS を判定し、dwmapi.dll を Windows 以外で読み込まないようにする。
	if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
		return
	}
	if (!window.isDisplayable) {
		return
	}
	val windowHandle = Native.getWindowPointer(window) ?: return
	// HRESULT is ignored: a non-zero result (e.g. Windows 10, which lacks this attribute) just means the
	// caption keeps its system color.
	dwmapi.DwmSetWindowAttribute(windowHandle, DWMWA_CAPTION_COLOR, IntByReference(color.toColorRef()), Int.SIZE_BYTES)
}

/**
 * Tints [window]'s host-OS title bar to the theme's window-background color (Windows 11 only; a no-op
 * elsewhere).  Reads [LocalUmamoColors], so place it under the app theme; it re-applies whenever the
 * resolved background changes (a light/dark theme switch), keeping the caption in step with the UI.
 *
 * This composable emits no UI - it is purely a window-chrome side effect.
 *
 * @param Window window The host window whose caption to tint.
 */
@Composable
fun WindowsTitleBarTint(window: Window) {
	val captionColor = LocalUmamoColors.current.windowBackground
	LaunchedEffect(window, captionColor) {
		setWindowsCaptionColor(window, captionColor)
	}
}
