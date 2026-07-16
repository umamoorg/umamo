package org.umamo.render

import org.umamo.render.device.RenderDevice
import org.umamo.render.device.RenderTarget
import org.umamo.render.device.RenderTargetSpec
import org.umamo.render.device.TextureFormat

/**
 * The viewport's offscreen surface pair: a supersampled draw target the renderer draws into, and a
 * display-size resolve target the draw is box-downscaled into for read-back.
 *
 * Backend-neutral - both targets and the resolve come from the [device], so every backend's viewport
 * host shares this class instead of re-owning two framebuffers apiece.  The draw target is write-only
 * (`sampled = false`, a renderbuffer on GL); the resolve target is sampled so it can be read back.
 *
 * All calls run on the render thread with the device's context current; this class never crosses
 * threads.  A size change frees and recreates both targets through the device - a resize is a rare,
 * user-driven event, so target churn there costs nothing that matters.
 *
 * ビューポートのオフスクリーン面：スーパーサンプル描画ターゲットと表示サイズの解決ターゲットの対。
 * デバイス経由なので全バックエンド共通。
 *
 * @property RenderDevice device The backend the targets live on.
 * @property Int          scale  Framebuffer pixels per display pixel (the supersample factor).
 */
class SupersampledSurface(
	private val device: RenderDevice,
	val scale: Int,
) {
	private var draw: RenderTarget? = null
	private var resolve: RenderTarget? = null
	private var displayWidth = -1
	private var displayHeight = -1

	/** The supersampled draw target ([scale] x the display size). Call [ensure] first. */
	val drawTarget: RenderTarget
		get() = draw ?: error("ensure(width, height) before drawTarget")

	/** The display-size resolve target the read-back reads. Call [ensure] first. */
	val resolveTarget: RenderTarget
		get() = resolve ?: error("ensure(width, height) before resolveTarget")

	/**
	 * Ensures both targets match [width] x [height] (the draw target at [scale] x), recreating them on a
	 * size change, and returns the draw target to render into.
	 *
	 * @param Int width  The display width in pixels.
	 * @param Int height The display height in pixels.
	 * @return RenderTarget The supersampled draw target.
	 */
	fun ensure(width: Int, height: Int): RenderTarget {
		require(width > 0 && height > 0) { "surface size must be positive, got ${width}x$height" }
		if (width != displayWidth || height != displayHeight) {
			dispose()
			draw = device.createRenderTarget(RenderTargetSpec(width * scale, height * scale, TextureFormat.Rgba8, sampled = false))
			resolve = device.createRenderTarget(RenderTargetSpec(width, height, TextureFormat.Rgba8, sampled = true))
			displayWidth = width
			displayHeight = height
		}
		return drawTarget
	}

	/** Box-downscales the supersampled draw target into the display-size resolve target. */
	fun resolve() {
		device.resolve(drawTarget, resolveTarget)
	}

	/** Frees both targets. Safe to call before the first [ensure] and idempotent. */
	fun dispose() {
		draw?.let { device.destroyRenderTarget(it) }
		resolve?.let { device.destroyRenderTarget(it) }
		draw = null
		resolve = null
		displayWidth = -1
		displayHeight = -1
	}
}
