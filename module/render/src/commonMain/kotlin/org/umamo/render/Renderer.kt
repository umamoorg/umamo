package org.umamo.render

import org.umamo.runtime.model.ParameterId

/**
 * Backend-agnostic puppet renderer - the seam that keeps the GPU API out of the deformation eval and lets
 * GL today / Vulkan / Metal later be swapped without touching callers. A renderer is constructed around a
 * model (the concrete impl decides how), then driven per frame: [setPose] hands it the parameters (the
 * impl runs `preparePose` internally and uploads the cheap per-pose data - never per-vertex geometry), and
 * [render] draws into the current framebuffer. [setPose] takes parameters rather than a deformed mesh so
 * the per-vertex morph runs in the backend (the GPU vertex shader), which is Umamo's differentiator.
 *
 * バックエンド非依存のレンダラ契約。GL/Vulkan/Metal を差し替え可能にし、変形評価を GPU API から隔離する。
 */
interface Renderer {
	/**
	 * Sets the pose to display from parameter values - the impl prepares the backend-neutral deform inputs
	 * and uploads them (weights + baked transforms), deforming per vertex in the backend on [render].
	 *
	 * @param Map parameters Parameter id → value (partial; the rest fall back to each parameter's default).
	 */
	fun setPose(parameters: Map<ParameterId, Float>)

	/**
	 * Sets the camera (pan/zoom) the next [render] projects through. Held until changed, so framing stays
	 * stable across pose changes rather than reframing on every pose. If never set, [render] falls back to
	 * fitting [contentBounds] into the viewport.
	 *
	 * @param ViewportCamera camera The world-space pan/zoom to view through.
	 */
	fun setCamera(camera: ViewportCamera)

	/**
	 * Returns the model's content extent in world space (the rest/default pose), so a caller can frame an
	 * initial fit. Computed once and cached.
	 *
	 * @return ContentBounds The world-space bounds of the shown drawables at rest.
	 */
	fun contentBounds(): ContentBounds

	/**
	 * Draws the current pose into the bound framebuffer, projected through the camera set by [setCamera].
	 *
	 * @param Int viewportWidth  Viewport width in pixels.
	 * @param Int viewportHeight Viewport height in pixels.
	 */
	fun render(viewportWidth: Int, viewportHeight: Int)
}
