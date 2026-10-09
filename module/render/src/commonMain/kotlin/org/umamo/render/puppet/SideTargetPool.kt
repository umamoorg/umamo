package org.umamo.render.puppet

import org.umamo.render.device.RenderDevice
import org.umamo.render.device.RenderTarget
import org.umamo.render.device.RenderTargetSpec
import org.umamo.render.device.TextureFormat

/**
 * The screen-space side targets a frame renders into and samples back: the mask coverage target, the
 * destination snapshot, and one composite layer target per nesting depth.
 *
 * All three kinds share ONE capacity, because the composite shader samples layer, snapshot, and mask
 * through a single screenTexSize divisor - so they must share allocation dims, and they grow together.
 * This class is the one owner of that capacity.
 *
 * Grow-only: the targets are kept at a per-axis high-water capacity and rendered into via an
 * origin-anchored viewport, so a gutter drag's per-frame size changes never destroy + recreate them.
 * The shader's screen-space lookup divides gl_FragCoord by the ALLOCATED size (screenTexSize), which is
 * what keeps the lookup lined up when the capacity exceeds the viewport.
 *
 * Render thread only.  Constructing it touches no device; every other call must run with the device's
 * context current.
 *
 * @property RenderDevice device The backend the targets live on.
 */
internal class SideTargetPool(
	private val device: RenderDevice,
) {
	// One layer target per nesting depth, grown lazily to the deepest isolated group actually rendered.
	private val layerTargets = ArrayList<RenderTarget>()

	/** The coverage target the mask pass renders into and the masked draws sample; null until [ensure]. */
	var maskTarget: RenderTarget? = null
		private set

	/**
	 * The draw-order target the order pass writes and the culling wireframe edges read, each pixel the
	 * packed back-to-front index of the frontmost covering drawable; null until [ensure].  Rgba8 like the
	 * rest, since every backend renders to and reads back that format.
	 */
	var drawOrderTarget: RenderTarget? = null
		private set

	/**
	 * The destination snapshot a composite blends against; null until [ensure].  Composites are strictly
	 * sequential, so a single snapshot suffices.
	 */
	var snapshotTarget: RenderTarget? = null
		private set

	/** The shared capacity's width in pixels, a high-water mark. */
	var capacityWidth: Int = 0
		private set

	/** The shared capacity's height in pixels, a high-water mark. */
	var capacityHeight: Int = 0
		private set

	/**
	 * Grows the shared side-target capacity (mask + draw order + snapshot + composite pool) to hold a
	 * [viewportWidth] x [viewportHeight] render, per-axis high-water: a request inside the current
	 * capacity allocates nothing (the per-frame path during a gutter drag), growth destroys the mask,
	 * draw order, snapshot, and pool together and recreates mask + draw order + snapshot at the new
	 * capacity (the pool refills lazily in [acquireLayer]).
	 *
	 * @param Int viewportWidth  The render width in pixels.
	 * @param Int viewportHeight The render height in pixels.
	 */
	fun ensure(viewportWidth: Int, viewportHeight: Int) {
		if (viewportWidth > capacityWidth || viewportHeight > capacityHeight) {
			maskTarget?.let { device.destroyRenderTarget(it) }
			drawOrderTarget?.let { device.destroyRenderTarget(it) }
			layerTargets.forEach { device.destroyRenderTarget(it) }
			layerTargets.clear()
			snapshotTarget?.let { device.destroyRenderTarget(it) }
			snapshotTarget = null
			capacityWidth = maxOf(viewportWidth, capacityWidth)
			capacityHeight = maxOf(viewportHeight, capacityHeight)
			maskTarget =
				device.createRenderTarget(
					RenderTargetSpec(capacityWidth, capacityHeight, TextureFormat.Rgba8, sampled = true),
				)
			drawOrderTarget =
				device.createRenderTarget(
					RenderTargetSpec(capacityWidth, capacityHeight, TextureFormat.Rgba8, sampled = true),
				)
		}
		if (snapshotTarget == null) {
			snapshotTarget =
				device.createRenderTarget(
					RenderTargetSpec(capacityWidth, capacityHeight, TextureFormat.Rgba8, sampled = true),
				)
		}
	}

	/**
	 * The pooled layer target for one composite nesting depth, allocated on first use at the shared
	 * side-target capacity.  Slots below [depth] are ancestors mid-composite; the slot itself is
	 * always free when asked for, because composites at one level are strictly sequential.
	 *
	 * @param Int depth The composite nesting depth.
	 * @return RenderTarget The depth's layer target.
	 */
	fun acquireLayer(depth: Int): RenderTarget {
		while (layerTargets.size <= depth) {
			layerTargets.add(
				device.createRenderTarget(
					RenderTargetSpec(capacityWidth, capacityHeight, TextureFormat.Rgba8, sampled = true),
				),
			)
		}
		return layerTargets[depth]
	}

	/**
	 * Frees the mask, draw-order, destination-snapshot, and composite-layer targets and resets their shared capacity,
	 * so the next [ensure] allocates them afresh at its own size.  The capacity is otherwise grow-only;
	 * this is how a one-off large render gives the memory back.
	 */
	fun release() {
		maskTarget?.let { target -> device.destroyRenderTarget(target) }
		maskTarget = null
		drawOrderTarget?.let { target -> device.destroyRenderTarget(target) }
		drawOrderTarget = null
		for (target in layerTargets) {
			device.destroyRenderTarget(target)
		}
		layerTargets.clear()
		snapshotTarget?.let { target -> device.destroyRenderTarget(target) }
		snapshotTarget = null
		capacityWidth = 0
		capacityHeight = 0
	}
}