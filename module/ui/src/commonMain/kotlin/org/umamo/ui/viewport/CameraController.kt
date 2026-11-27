package org.umamo.ui.viewport

import org.umamo.edit.EditorSession

/**
 * The camera operations one editor area exposes to the shell's view commands (Fit / 1:1 / zoom / Zoom
 * Region / Frame Selected).  Both the 2D viewport and the UV editor host camera-bearing areas, and each
 * registers one of these into the AreaCameraHub for its area's lifetime; the shell's view commands
 * resolve the hovered area's controller through the hub at dispatch time.  A new camera-bearing space
 * reuses this by registering its own implementation - never by adding a branch to the view commands.
 */
internal interface CameraController {
	/** Frames the area's content to fit. */
	fun fit()

	/** Sets true 1:1 (one content unit per screen pixel). */
	fun actualSize()

	/**
	 * Zooms in one step about the view center.
	 *
	 * @param Boolean coarse Use the larger (Shift) step.
	 */
	fun zoomIn(coarse: Boolean)

	/**
	 * Zooms out one step about the view center.
	 *
	 * @param Boolean coarse Use the larger (Shift) step.
	 */
	fun zoomOut(coarse: Boolean)

	/**
	 * Arms the drag-a-box-to-frame Zoom Region gesture over this area.  The area's
	 * mounted region overlay captures the drag and frames the box on release.
	 */
	fun armZoomRegion()

	/** Frames the selection's covered bounds; a no-op with nothing covered. */
	fun frameSelected()
}

/**
 * A [CameraController] backed by the shared [PuppetViewportService], bound to one fixed area.  The five
 * navigation ops are identical across every camera-bearing space - they only differ in which area they
 * target and in how Frame Selected computes its bounds - so this hoists them here against the fixed
 * [areaId], leaving [frameSelected] to each concrete space.
 *
 * @property PuppetViewportService service The render service holding this area's per-area camera.
 * @property EditorSession session The session, for the Zoom Region arming flag and Frame Selected bounds.
 * @property String areaId The area this controller drives (the same id its space registered under).
 */
internal abstract class ServiceCameraController(
	protected val service: PuppetViewportService,
	protected val session: EditorSession,
	protected val areaId: String,
) : CameraController {
	override fun fit() {
		service.fit(areaId)
	}

	override fun actualSize() {
		service.actualSize(areaId)
	}

	override fun zoomIn(coarse: Boolean) {
		service.zoomCentered(areaId, zoomIn = true, coarse = coarse)
	}

	override fun zoomOut(coarse: Boolean) {
		service.zoomCentered(areaId, zoomIn = false, coarse = coarse)
	}

	override fun armZoomRegion() {
		// Arms the session flag for this area; the area's top-level region overlay captures the drag and
		// calls service.zoomToRegion on release.  Works in Object and Edit mode alike.
		session.armZoomRegion(areaId)
	}
}