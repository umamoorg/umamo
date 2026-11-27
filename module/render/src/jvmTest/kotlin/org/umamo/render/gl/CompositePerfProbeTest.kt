package org.umamo.render.gl

import org.lwjgl.opengl.GL11
import org.umamo.format.moc3.Moc3
import org.umamo.interop.moc3.import.Moc3Import
import org.umamo.render.PuppetTextures
import org.umamo.render.ViewportCamera
import org.umamo.render.device.RenderTargetSpec
import org.umamo.render.device.TextureFormat
import org.umamo.render.puppet.PuppetRenderer
import org.umamo.render.restMeshesToCanvasSpace
import org.umamo.runtime.model.PartGroupMode
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.withDerivedRenderRoot
import org.umamo.testkit.skipTest
import java.io.File
import kotlin.test.Test

/**
 * Print-only compositing perf probe on Model A: frame times with the composites live vs the same
 * model with every isolated group demoted to Grouped, at a supersampled-ish viewport.  The composite
 * path runs BELOW the all-grouped baseline, so the printed delta is negative: faded and off-viewport
 * groups are skipped, identity composites are flattened, empty layers are skipped, and the surviving
 * composites are scissored to their subtree bounds.  Pins nothing.  Skips without a GL context or the
 * corpus.
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
			skipTest("modelA.moc3 not present; skipping perf probe")
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