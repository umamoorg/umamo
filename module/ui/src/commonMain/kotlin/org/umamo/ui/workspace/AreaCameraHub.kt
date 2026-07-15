package org.umamo.ui.workspace

import androidx.compose.runtime.staticCompositionLocalOf
import org.umamo.ui.viewport.CameraController

/**
 * The registry from a camera-bearing area id to its live [CameraController]: each 2D viewport and UV
 * editor space registers its ops for its area's lifetime, and the shell's view commands resolve the
 * hovered area here at dispatch time.  Both surfaces drive the same per-area render camera, so one hub
 * serves them uniformly - the resolver is a single opsFor(hoveredArea) lookup with no per-space branch,
 * and a future camera-bearing space joins simply by registering its own controller.
 *
 * カメラを持つエリア id からカメラ操作への台帳。2D ビューポートと UV エディタの各空間が登録し、
 * ビューコマンドがディスパッチ時にホバー中のエリアの分を解決する。
 */
internal class AreaCameraHub {
	private val opsByArea = mutableMapOf<String, CameraController>()

	/**
	 * Registers one area's camera controller (replacing any prior registration for the id).
	 *
	 * @param String areaId The camera-bearing leaf's area id.
	 * @param CameraController ops The area's live camera controller.
	 */
	fun register(areaId: String, ops: CameraController) {
		opsByArea[areaId] = ops
	}

	/**
	 * Removes one area's registration (the area died or switched space).
	 *
	 * @param String areaId The camera-bearing leaf's area id.
	 */
	fun unregister(areaId: String) {
		opsByArea.remove(areaId)
	}

	/**
	 * The live controller for an area, or null when none is registered under the id.
	 *
	 * @param String areaId The camera-bearing leaf's area id.
	 * @return CameraController? The area's camera controller, or null.
	 */
	fun opsFor(areaId: String): CameraController? = opsByArea[areaId]
}

/**
 * The shell's area camera hub, or null outside an editor shell (previews, tests).  Camera-bearing
 * spaces register their areas; the view commands resolve the hovered area at dispatch.
 */
internal val LocalAreaCameraHub = staticCompositionLocalOf<AreaCameraHub?> { null }
