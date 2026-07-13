package org.umamo.ui.viewport

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerIcon

/**
 * The cursor shown while panning the viewport (the hand/grab pointer). Platform seam alongside
 * areaMovePointerIcon/splitterPointerIcon: desktop returns a system cursor, touch platforms have
 * no hover cursor and return the default.
 *
 * ビューポートをパン中に表示するカーソル。デスクトップはシステムカーソル、タッチ環境は既定を返す。
 *
 * @return PointerIcon The pan/grab cursor.
 */
expect fun grabPanPointerIcon(): PointerIcon

/**
 * Warps the OS pointer to an absolute screen position mid-gesture - Blender's cursor-wrap for modal
 * transforms, so a Grab / Scale / Rotate is not interrupted when the pointer reaches the viewport edge.
 * Desktop teleports the cursor via AWT Robot; touch platforms have no cursor and no-op.
 *
 * Returns where the cursor ACTUALLY landed (read back from the OS), not the requested target: the
 * platform may round to integer pixels, clamp at a screen edge, or rescale the request (HiDPI), and
 * any difference between request and landing must be folded into the gesture's wrap compensation or
 * it accumulates as drift across wraps.  Null means no warp happened (no cursor, or the platform
 * denied it) - the caller leaves its gesture anchor alone and the transform freezes at the edge.
 *
 * ジェスチャ中に OS ポインタを画面座標へ移動する（Blender のカーソルラップ）。実際の着地点を返す。
 * デスクトップのみ実装。
 *
 * @param Float screenX The requested absolute screen x in pixels.
 * @param Float screenY The requested absolute screen y in pixels.
 * @return Offset? The absolute screen position the cursor actually landed at, or null when no warp
 *   happened (platforms or configurations without warp support).
 */
expect fun warpViewportCursor(screenX: Float, screenY: Float): Offset?

/**
 * A fully transparent pointer icon, used to hide the OS cursor during a modal transform so the overlay's
 * own drawn cursor (the double-arrow and the dashed line to the pivot) reads cleanly without the system
 * pointer doubled on top.  Desktop returns a transparent custom cursor; touch platforms have no cursor to
 * hide and return the default.
 *
 * モーダル変換中に OS カーソルを隠すための透明なポインタアイコン。デスクトップのみ実装。
 *
 * @return PointerIcon A transparent (invisible) cursor, or the default where none can be built.
 */
expect fun hiddenPointerIcon(): PointerIcon
