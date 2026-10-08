package org.umamo.editor.desktop.viewport

import org.umamo.render.ViewportCamera

/**
 * Minimum interval between resize-driven re-renders while an area's size is actively changing (a
 * gutter drag or a window-edge resize): about 10 Hz of live feedback, with the Compose side
 * stretching the previous frame between them.  Pose / camera / state changes are never throttled.
 */
internal const val RESIZE_THROTTLE_NANOS = 50_000_000L

/** How long a size must hold still before it counts as settled (the full-quality render then runs). */
internal const val RESIZE_SETTLE_NANOS = 25_000_000L

/**
 * The values one scheduling pass judges every area against, read once at the top of the pass.
 *
 * @property Long paramsVersion    The pose version a puppet area must have rendered to be fresh.
 * @property Int  settleScale      Framebuffer pixels per display pixel for a still frame.
 * @property Int  interactiveScale The scale for a frame rendered while a size is still in motion.
 * @property Long nowNanos         The pass's monotonic timestamp.
 */
internal class RenderTick(
	val paramsVersion: Long,
	val settleScale: Int,
	val interactiveScale: Int,
	val nowNanos: Long,
)

/** What the loop does with one sized, idle area this pass. */
internal sealed interface AreaRenderDecision {
	/** Every stamp matches what the area shows: nothing to draw. */
	data object Fresh : AreaRenderDecision

	/**
	 * Only the size is stale and the resize throttle holds it back: skip this pass and let the idle
	 * sleep revisit.
	 */
	data object Deferred : AreaRenderDecision

	/**
	 * Render now.
	 *
	 * @property Int scale Framebuffer pixels per display pixel for this render.
	 */
	data class Render(val scale: Int) : AreaRenderDecision
}

/**
 * Tracks size-change recency for the resize throttle.  The FIRST observation (a fresh slot, observed
 * 0x0) does not stamp, so a newly opened area counts as settled and its first frame renders at full
 * quality immediately.  Runs for every sized area, in flight or not, so a size that changes during a
 * read-back still restarts the settle window.
 *
 * @param AreaSlot slot     The area observed.
 * @param Int      width    The area's requested width.
 * @param Int      height   The area's requested height.
 * @param Long     nowNanos The pass's monotonic timestamp.
 */
internal fun observeAreaSize(slot: AreaSlot, width: Int, height: Int, nowNanos: Long) {
	if (width != slot.observedWidth || height != slot.observedHeight) {
		val firstObservation = slot.observedWidth == 0 && slot.observedHeight == 0
		slot.observedWidth = width
		slot.observedHeight = height
		if (!firstObservation) {
			slot.sizeChangedNanos = nowNanos
		}
	}
}

/**
 * Whether the area's last render covers its size at the settle scale.  A frame rendered below the
 * settle scale stays size-stale on purpose, so the settle pass re-renders it at full quality once the
 * size holds still.
 *
 * @param AreaSlot slot        The area.
 * @param Int      width       The requested width.
 * @param Int      height      The requested height.
 * @param Int      settleScale The scale a still frame renders at.
 * @return Boolean True when neither the size nor the quality is stale.
 */
internal fun isSizeFresh(slot: AreaSlot, width: Int, height: Int, settleScale: Int): Boolean =
	slot.renderedWidth == width && slot.renderedHeight == height && slot.renderedScale == settleScale

/**
 * Whether everything but the size is fresh.  A UV scene is model-independent, so its freshness ignores
 * the pose version and the puppet render version: it re-renders only on the camera / the content it
 * shows (the page index or the layer raster, with the overlay and placement preview riding it), plus
 * what the atlas render version tracks (the grid colors, the overlay palette, an applied page set).
 * The puppet keeps the full freshness via the puppet render version.  Both watch the area's own render
 * options (its grid geometry and frame overlays) by value, so a toggle re-renders that area alone.
 *
 * @param AreaSlot       slot             The area.
 * @param ViewportCamera camera           The camera established for this pass, compared by identity.
 * @param Long           paramsVersion    The pose version.
 * @param Long           puppetRenderBump The puppet render version.
 * @param Long           atlasRenderBump  The atlas render version.
 * @return Boolean True when the area's last render still shows the current state.
 */
internal fun isRestFresh(
	slot: AreaSlot,
	camera: ViewportCamera,
	paramsVersion: Long,
	puppetRenderBump: Long,
	atlasRenderBump: Long,
): Boolean =
	when (slot.scene) {
		RenderScene.Puppet2D ->
			slot.renderedParamsVersion == paramsVersion &&
				slot.renderedCamera === camera &&
				slot.puppetRenderBumpDone == puppetRenderBump &&
				slot.renderedOverlays == slot.overlays

		// Kind and payload are read as ONE value, so a switch can never be observed half
		// applied.  Equality rather than identity: AtlasPage compares its index, SourceLayer's
		// image compares by reference, and the overlay and placement preview either carries
		// compare by identity, which is the freshness test either surface wants.
		RenderScene.UvScene ->
			slot.renderedUvContent == slot.uvContent &&
				slot.renderedCamera === camera &&
				slot.atlasRenderBumpDone == atlasRenderBump &&
				slot.renderedOverlays == slot.overlays
	}

/**
 * The resize-throttle gate: whether a render whose ONLY staleness is its size should wait.  A size
 * that has held still for the settle window renders immediately (the full-quality settle pass);
 * one still in motion renders at most once per throttle interval.
 *
 * @param AreaSlot slot The area being considered.
 * @param Long nowNanos The loop pass's monotonic timestamp.
 * @return Boolean True to skip this tick and let the idle sleep revisit.
 */
internal fun shouldDeferResizeRender(slot: AreaSlot, nowNanos: Long): Boolean {
	val settled = nowNanos - slot.sizeChangedNanos >= RESIZE_SETTLE_NANOS
	val throttleElapsed = nowNanos - slot.resizeRenderNanos >= RESIZE_THROTTLE_NANOS
	return !settled && !throttleElapsed
}

/**
 * The scale one render runs at: a size still in motion renders at the interactive scale; pose /
 * camera / state changes during that motion share the burst's quality rather than forcing a
 * full-scale render.
 *
 * @param AreaSlot slot             The area being rendered.
 * @param Long     nowNanos         The pass's monotonic timestamp.
 * @param Int      settleScale      The scale a still frame renders at.
 * @param Int      interactiveScale The scale a frame renders at while the size is in motion.
 * @return Int Framebuffer pixels per display pixel for this render.
 */
internal fun renderScaleFor(slot: AreaSlot, nowNanos: Long, settleScale: Int, interactiveScale: Int): Int {
	val sizeInMotion = nowNanos - slot.sizeChangedNanos < RESIZE_SETTLE_NANOS
	return if (sizeInMotion) interactiveScale else settleScale
}

/**
 * Decides what the loop does with one sized, idle area.  Freshness splits into the size axis
 * (throttled during an active resize) and the rest: fresh on both means nothing to do, size-only
 * staleness waits on the throttle, and anything else renders at the scale the size's motion allows.
 * Pure over the slot's render-thread bookkeeping, so the matrix is testable without a render thread.
 *
 * The two render versions are passed per area rather than carried on the tick because the loop reads
 * them as it reaches each area, so a bump landing mid-pass is seen by the areas judged after it.
 *
 * @param AreaSlot       slot             The area.
 * @param Int            width            The requested width.
 * @param Int            height           The requested height.
 * @param ViewportCamera camera           The camera established for this pass.
 * @param RenderTick     tick             The pass's pose version, scales, and timestamp.
 * @param Long           puppetRenderBump The puppet render version, as read for this area.
 * @param Long           atlasRenderBump  The atlas render version, as read for this area.
 * @return AreaRenderDecision Fresh, Deferred, or Render with its scale.
 */
internal fun decideAreaRender(
	slot: AreaSlot,
	width: Int,
	height: Int,
	camera: ViewportCamera,
	tick: RenderTick,
	puppetRenderBump: Long,
	atlasRenderBump: Long,
): AreaRenderDecision {
	val sizeFresh = isSizeFresh(slot, width, height, tick.settleScale)
	val restFresh = isRestFresh(slot, camera, tick.paramsVersion, puppetRenderBump, atlasRenderBump)
	if (sizeFresh && restFresh) {
		return AreaRenderDecision.Fresh
	}
	if (restFresh && shouldDeferResizeRender(slot, tick.nowNanos)) {
		return AreaRenderDecision.Deferred
	}
	return AreaRenderDecision.Render(renderScaleFor(slot, tick.nowNanos, tick.settleScale, tick.interactiveScale))
}