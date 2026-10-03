package org.umamo.render.gl

import org.lwjgl.opengl.GL11
import org.umamo.format.moc3.Moc3
import org.umamo.interop.moc3.import.Moc3Import
import org.umamo.render.PuppetTextures
import org.umamo.render.ViewportCamera
import org.umamo.render.device.RenderTarget
import org.umamo.render.device.RenderTargetSpec
import org.umamo.render.device.TextureFormat
import org.umamo.render.eval.preparePose
import org.umamo.render.glsl.MAX_GLUES
import org.umamo.render.puppet.ModelUpdateKind
import org.umamo.render.puppet.PuppetRenderer
import org.umamo.render.puppet.resolvePose
import org.umamo.render.restMeshesToCanvasSpace
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.visibleDrawableIds
import java.io.File
import kotlin.test.Test

/** The output prefix every row carries, so the rows grep out of a verbose test log. */
private const val PROBE_TAG = "[edit-grab-render-perf]"

/** The label column width, so the numbers line up across rows. */
private const val LABEL_WIDTH = 86

/**
 * Print-only render-thread perf probe on the moc3.perfSample model (modelF by default: 1330 drawables,
 * 220k vertices): what one whole-selection Grab preview push costs the renderer on the path the engine
 * runs - the reconcile and position re-upload with the positions-only refresh, the pose rebake when
 * the push was structural, the frame, and the read-back - and then, in a second loop, the rebake a
 * positions-only push no longer pays, split into its CPU halves.  Runs on whatever GL the host has
 * (software under CI and WSLg, so the GPU rows are indicative and the CPU rows are what matter).  Pins
 * nothing - see docs/plan/edit-mode-performance.md for the numbers and what each phase is expected to
 * move.  Skips without a GL context or the corpus.  Standard streams are off in the build, so the rows
 * show with --info or in build/test-results.
 */
class EditGrabRenderPerfProbeTest {
	private val viewportWidth = 1600
	private val viewportHeight = 900
	private val rounds = 8
	private val forcedRounds = 4
	private val sample: File? = System.getProperty("moc3.perfSample")?.let(::File)?.takeIf { it.isFile }

	/** One preview push per round on the engine's path, then the forced rebake split into its halves. */
	@Test
	fun probePreviewPushStages() {
		val mocFile = sample
		if (mocFile == null) {
			println("moc3.perfSample not present; skipping perf probe")
			return
		}
		requireHeadlessGl(PROBE_TAG)
		val puppet = restMeshesToCanvasSpace(Moc3Import.fromMocDocument(Moc3.read(mocFile.readBytes()), null))
		report("GL: ${GL11.glGetString(GL11.GL_RENDERER)} / ${GL11.glGetString(GL11.GL_VERSION)}")
		val device = GlRenderDevice()
		val renderer = PuppetRenderer(puppet, PuppetTextures(emptyList(), emptyMap(), premultipliedAlpha = false), device)
		val initStart = System.nanoTime()
		renderer.initGl()
		GL11.glFinish()
		report("initGl (upload every drawable once): ${millisSince(initStart)} ms")
		renderer.setCamera(ViewportCamera.fit(renderer.contentBounds(), viewportWidth, viewportHeight))
		val poseStart = System.nanoTime()
		renderer.setPose(emptyMap())
		report("first setPose: ${millisSince(poseStart)} ms")
		val target = device.createRenderTarget(RenderTargetSpec(viewportWidth, viewportHeight, TextureFormat.Rgba8, sampled = true))
		repeat(3) {
			renderer.render(target, viewportWidth, viewportHeight)
		}
		GL11.glFinish()

		val current = probeEnginePath(renderer, device, target, puppet)
		probeForcedRebake(renderer, current)

		device.destroyRenderTarget(target)
		renderer.disposeGl()
	}

	/**
	 * The engine's path per preview push: the model update (with its positions-only refresh), the pose
	 * only when the update was structural, the frame, and the read-back.
	 *
	 * @param PuppetRenderer renderer The posed renderer.
	 * @param GlRenderDevice device The device.
	 * @param RenderTarget target The frame target.
	 * @param PuppetModel start The model the renderer holds.
	 * @return PuppetModel The last model pushed.
	 */
	private fun probeEnginePath(renderer: PuppetRenderer, device: GlRenderDevice, target: RenderTarget, start: PuppetModel): PuppetModel {
		val foldTimes = ArrayList<Long>(rounds)
		val updateTimes = ArrayList<Long>(rounds)
		val poseTimes = ArrayList<Long>(rounds)
		val renderTimes = ArrayList<Long>(rounds)
		val readbackTimes = ArrayList<Long>(rounds)
		var positionsOnlyCount = 0
		var current = start
		for (round in 0 until rounds) {
			val foldStart = System.nanoTime()
			val folded = translateEveryMesh(current, 2f * (round + 1))
			foldTimes.add(System.nanoTime() - foldStart)
			val updateStart = System.nanoTime()
			val kind = renderer.updateModel(folded)
			GL11.glFinish()
			val updateEnd = System.nanoTime()
			updateTimes.add(updateEnd - updateStart)
			if (kind == ModelUpdateKind.PositionsOnly) {
				positionsOnlyCount++
			} else {
				renderer.setPose(emptyMap())
				poseTimes.add(System.nanoTime() - updateEnd)
			}
			val renderStart = System.nanoTime()
			renderer.render(target, viewportWidth, viewportHeight)
			GL11.glFinish()
			val renderEnd = System.nanoTime()
			device.readPixels(target)
			readbackTimes.add(System.nanoTime() - renderEnd)
			renderTimes.add(renderEnd - renderStart)
			current = folded
		}
		report("pushes: $rounds, classified PositionsOnly=$positionsOnlyCount Structural=${rounds - positionsOnlyCount}")
		stats("G0 (probe only) translate every mesh into a new model", foldTimes)
		stats("G1 renderer.updateModel: diff + reconcile + re-upload + positions-only refresh [per push]", updateTimes)
		stats("G2 renderer.setPose on a Structural push only [per push; n=0 means every push kept the pose]", poseTimes)
		stats("G3 renderer.render ${viewportWidth}x$viewportHeight [per frame; host GL]", renderTimes)
		stats("G4 device.readPixels, synchronous [per frame; host GL]", readbackTimes)
		return current
	}

	/**
	 * The rebake a positions-only push no longer pays, forced after each push and split into its CPU
	 * halves, which are timed standalone over the same model (pure functions, so the renderer's own
	 * state is untouched).
	 *
	 * @param PuppetRenderer renderer The posed renderer.
	 * @param PuppetModel start The model the renderer holds.
	 */
	private fun probeForcedRebake(renderer: PuppetRenderer, start: PuppetModel) {
		val forcedTimes = ArrayList<Long>(forcedRounds)
		val prepareTimes = ArrayList<Long>(forcedRounds)
		val resolveTimes = ArrayList<Long>(forcedRounds)
		var current = start
		for (round in 0 until forcedRounds) {
			val folded = translateEveryMesh(current, -2f * (round + 1))
			renderer.updateModel(folded)
			val forcedStart = System.nanoTime()
			renderer.setPose(emptyMap())
			forcedTimes.add(System.nanoTime() - forcedStart)
			val prepareStart = System.nanoTime()
			val inputs = preparePose(folded, emptyMap())
			val prepareEnd = System.nanoTime()
			val renderableById = folded.drawables.associate { drawable -> drawable.id to (drawable.mesh?.indices?.isNotEmpty() == true) }
			resolvePose(inputs, renderableById, folded.visibleDrawableIds(), folded.drawables.map { drawable -> drawable.id }, folded.renderRoot, FloatArray(MAX_GLUES) { 1f })
			resolveTimes.add(System.nanoTime() - prepareEnd)
			prepareTimes.add(prepareEnd - prepareStart)
			current = folded
		}
		stats("G2r renderer.setPose forced after a positions-only push (the rebake the engine skips)", forcedTimes)
		stats("G2a   preparePose, standalone (deformer worlds + corners + channels)", prepareTimes)
		stats("G2b   resolvePose, standalone (draw order + plan; approximate residency set)", resolveTimes)
		stats("G2c   remainder = G2r - G2a - G2b (applyPose + planCompositeAcceleration), derived", forcedTimes.indices.map { index -> forcedTimes[index] - prepareTimes[index] - resolveTimes[index] })
	}

	/**
	 * A copy of [model] with every mesh's positions translated by [delta]: new arrays and new
	 * DrawableMesh wrappers, so the renderer's diff sees exactly what a whole-selection Grab preview
	 * hands it (positions changed, uvs and indices shared).
	 *
	 * @param PuppetModel model The model to translate.
	 * @param Float delta The offset added to every coordinate.
	 * @return PuppetModel The translated copy.
	 */
	private fun translateEveryMesh(model: PuppetModel, delta: Float): PuppetModel =
		model.copy(
			drawables =
				model.drawables.map { drawable ->
					val mesh = drawable.mesh ?: return@map drawable
					val moved = mesh.positions.copyOf()
					var slot = 0
					while (slot + 1 < moved.size) {
						moved[slot] += delta
						moved[slot + 1] += delta
						slot += 2
					}
					drawable.copy(mesh = DrawableMesh(moved, mesh.uvs, mesh.indices))
				},
		)

	/**
	 * Prints one row: the label with the min, median, and max over [nanos]; an empty list prints n=0.
	 *
	 * @param String label The row label.
	 * @param List<Long> nanos The timed runs in nanoseconds.
	 */
	private fun stats(label: String, nanos: List<Long>) {
		if (nanos.isEmpty()) {
			report("%-${LABEL_WIDTH}s (n=0)".format(label))
			return
		}
		val sorted = nanos.sorted()
		report(
			"%-${LABEL_WIDTH}s min %8.1f ms  median %8.1f ms  max %8.1f ms  (n=%d)".format(
				label,
				sorted.first() / 1e6,
				sorted[sorted.size / 2] / 1e6,
				sorted.last() / 1e6,
				sorted.size,
			),
		)
	}

	/**
	 * Milliseconds elapsed since a nanoTime stamp, formatted for a row.
	 *
	 * @param Long startNanos The stamp.
	 * @return String The elapsed milliseconds with one decimal.
	 */
	private fun millisSince(startNanos: Long): String = "%.1f".format((System.nanoTime() - startNanos) / 1e6)

	/**
	 * Prints one tagged line.
	 *
	 * @param String line The line.
	 */
	private fun report(line: String) {
		println("$PROBE_TAG $line")
	}
}