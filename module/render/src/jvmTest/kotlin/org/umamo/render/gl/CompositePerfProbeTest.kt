package org.umamo.render.gl

import org.lwjgl.opengl.GL11
import org.umamo.format.moc3.Moc3
import org.umamo.interop.moc3.Moc3Import
import org.umamo.render.PuppetTextures
import org.umamo.render.ViewportCamera
import org.umamo.render.device.RenderTargetSpec
import org.umamo.render.device.TextureFormat
import org.umamo.render.puppet.PuppetRenderer
import org.umamo.render.restMeshesToCanvasSpace
import org.umamo.runtime.model.PartGroupMode
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.withDerivedRenderRoot
import java.io.File
import kotlin.test.Test

/**
 * Print-only compositing perf probe on Model A: frame times with the composites live vs the same
 * model with every isolated group demoted to Grouped, at a supersampled-ish viewport.  With the
 * bounds-scissor + identity-flatten + empty-layer-skip optimizations in place the composite path
 * runs BELOW the all-grouped baseline (faded / off-viewport groups are skipped, and the surviving
 * composites are scissored to their subtree bounds), so the delta is now negative.  Pins nothing -
 * see docs/plan/offscreen-support.md for the numbers and the mechanisms.  Skips without a GL
 * context or the corpus.
 */
class CompositePerfProbeTest {
	private val viewportWidth = 1200
	private val viewportHeight = 1800
	private val warmupFrames = 10
	private val timedFrames = 60

	@Test
	fun probeCompositeFrameCost() {
		val mocFile =
			System.getProperty("moc3.samples")
				?.let(::File)
				?.takeIf { it.isDirectory }
				?.walkTopDown()
				?.firstOrNull { it.name == "modelA.moc3" }
		if (mocFile == null) {
			println("modelA.moc3 not present; skipping perf probe")
			return
		}
		requireHeadlessGl("[composite-perf]")
		val puppet = restMeshesToCanvasSpace(Moc3Import.fromMocDocument(Moc3.read(mocFile.readBytes()), null))
		// Demote Isolated to Grouped (not PassThrough) so the stripped model keeps the same
		// draw-order grouping and the comparison isolates the composite cost alone.
		val stripped =
			puppet.copy(
				parts =
					puppet.parts.map { part ->
						if (part.groupMode is PartGroupMode.Isolated) {
							part.copy(groupMode = PartGroupMode.Grouped)
						} else {
							part
						}
					},
			).withDerivedRenderRoot()
		val withComposites = timeFrames(puppet)
		val withoutComposites = timeFrames(stripped)
		println(
			"[composite-perf] modelA ${viewportWidth}x$viewportHeight, $timedFrames frames: " +
				"composites=${"%.2f".format(withComposites)} ms/frame, " +
				"stripped=${"%.2f".format(withoutComposites)} ms/frame, " +
				"delta=${"%.2f".format(withComposites - withoutComposites)} ms",
		)
	}

	/** Average render() wall time in milliseconds over [timedFrames] static-pose frames. */
	private fun timeFrames(source: PuppetModel): Double {
		val device = GlRenderDevice()
		val renderer = PuppetRenderer(source, PuppetTextures(emptyList(), emptyMap(), premultipliedAlpha = false), device)
		renderer.initGl()
		renderer.setCamera(ViewportCamera(0f, 0f, 0.5f))
		renderer.setPose(emptyMap())
		val target = device.createRenderTarget(RenderTargetSpec(viewportWidth, viewportHeight, TextureFormat.Rgba8, sampled = true))
		repeat(warmupFrames) {
			renderer.render(target, viewportWidth, viewportHeight)
		}
		GL11.glFinish()
		val startNanos = System.nanoTime()
		repeat(timedFrames) {
			renderer.render(target, viewportWidth, viewportHeight)
		}
		GL11.glFinish()
		return (System.nanoTime() - startNanos) / 1e6 / timedFrames
	}
}
