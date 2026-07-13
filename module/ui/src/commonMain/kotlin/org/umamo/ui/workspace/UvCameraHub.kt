package org.umamo.ui.workspace

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The camera operations one UV editor area exposes to the shell's view commands - the UV analogue of
 * the viewport's ViewportCameraController.  Implemented by the UV space over the service's per-area
 * camera; every call acts on that one area.
 */
internal interface UvCameraOps {
	/** Frames the whole atlas page. */
	fun fit()

	/** Sets true 1:1 (one texel per screen pixel). */
	fun actualSize()

	/**
	 * Zooms in one step about the view centre.
	 *
	 * @param Boolean coarse Use the larger (Shift) step.
	 */
	fun zoomIn(coarse: Boolean)

	/**
	 * Zooms out one step about the view centre.
	 *
	 * @param Boolean coarse Use the larger (Shift) step.
	 */
	fun zoomOut(coarse: Boolean)

	/** Frames the selection's covered UV bounds (Blender's numpad-period); a no-op with nothing covered. */
	fun frameSelected()

	/**
	 * Arms the drag-a-box-to-frame Zoom Region gesture (Blender's Shift+B) over this UV area.  The UV
	 * space's mounted region overlay captures the drag and frames the box; area-generic like the 2D
	 * viewport's, so no separate UV zoom-to-region path is needed.
	 */
	fun armZoomRegion()
}

/**
 * The registry from UV editor area id to its live [UvCameraOps]: each UV space registers its ops for
 * its area's lifetime, and the shell's view commands resolve the hovered UV area here at dispatch
 * time.  The UV editor has no GPU render service (its camera is per-area Compose state), so this hub
 * is the seam the keyboard view commands reach it through - the structural sibling of the service's
 * per-area camera map.
 *
 * UV エディタのエリア id からカメラ操作への台帳。各 UV 空間が登録し、ビューコマンドがディスパッチ時に
 * 解決する。
 */
internal class UvCameraHub {
	private val opsByArea = mutableMapOf<String, UvCameraOps>()

	/**
	 * Registers one area's camera ops (replacing any prior registration for the id).
	 *
	 * @param String areaId The UV editor leaf's area id.
	 * @param UvCameraOps ops The area's live camera operations.
	 */
	fun register(areaId: String, ops: UvCameraOps) {
		opsByArea[areaId] = ops
	}

	/**
	 * Removes one area's registration (the area died or switched space).
	 *
	 * @param String areaId The UV editor leaf's area id.
	 */
	fun unregister(areaId: String) {
		opsByArea.remove(areaId)
	}

	/**
	 * The live ops for an area, or null when none is registered under the id.
	 *
	 * @param String areaId The UV editor leaf's area id.
	 * @return UvCameraOps? The area's camera operations, or null.
	 */
	fun opsFor(areaId: String): UvCameraOps? = opsByArea[areaId]
}

/**
 * The shell's UV camera hub, or null outside an editor shell (previews, tests).  UV spaces register
 * their areas; the view commands resolve the hovered area at dispatch.
 */
internal val LocalUvCameraHub = staticCompositionLocalOf<UvCameraHub?> { null }
