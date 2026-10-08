package org.umamo.render.gl

import org.lwjgl.opengl.GL11
import org.umamo.format.moc3.Moc3
import org.umamo.interop.moc3.import.Moc3Import
import org.umamo.render.ContentBounds
import org.umamo.render.DecodedImage
import org.umamo.render.FrameOverlays
import org.umamo.render.PuppetTextures
import org.umamo.render.ViewportCamera
import org.umamo.render.device.RenderTarget
import org.umamo.render.device.RenderTargetSpec
import org.umamo.render.device.TextureFormat
import org.umamo.render.eval.preparePose
import org.umamo.render.glsl.MAX_GLUES
import org.umamo.render.puppet.DirectMeshOverlay
import org.umamo.render.puppet.IslandEdgeRole
import org.umamo.render.puppet.IslandFillRole
import org.umamo.render.puppet.IslandStyle
import org.umamo.render.puppet.MeshOverlay
import org.umamo.render.puppet.MeshOverlayKind
import org.umamo.render.puppet.MeshOverlayMesh
import org.umamo.render.puppet.MeshOverlaySelectMode
import org.umamo.render.puppet.MeshOverlaySizes
import org.umamo.render.puppet.ModelUpdateKind
import org.umamo.render.puppet.OVERLAY_FLAG_SELECTED
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
 * Print-only render-thread perf probe on the moc3.perfSample model (modelF by default: 1330 drawables, 220k
 * vertices): what one whole-selection Grab preview push costs the renderer on the path the engine runs - the
 * reconcile and position re-upload with the positions-only refresh, the pose rebake when the push was
 * structural, the frame, and the read-back - and then, in a second loop, the rebake a positions-only push no
 * longer pays, split into its CPU halves, in a third the frame with the Edit overlay a select-all publishes
 * over every mesh, in a fourth the frame with the Object-mode wireframe of every mesh (shown and hidden), and in
 * a fifth a UV area's frame with that overlay and with the Object-mode islands.
 * Runs on whatever GL the host has (software under CI and WSLg, so the GPU rows are indicative and the CPU
 * rows are what matter).  Pins nothing.  Skips without a GL context or the corpus.  Standard streams are off
 * in the build, so the rows show with --info or in build/test-results.
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
		probeOverlayFrames(renderer, target, current)
		probeWireframeFrames(renderer, target, current)
		probeUvSceneFrames(renderer, target, current)

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
	 * The frame with the Edit overlay over every mesh, as a select-all in Vertex mode publishes it: the
	 * first frame pays the buffer uploads and the capture; a frame with nothing moved pays the draws
	 * alone; a frame after a preview push pays the re-capture and the draws.
	 *
	 * @param PuppetRenderer renderer The posed renderer.
	 * @param RenderTarget target The frame target.
	 * @param PuppetModel start The model the renderer holds.
	 */
	private fun probeOverlayFrames(renderer: PuppetRenderer, target: RenderTarget, start: PuppetModel) {
		val buildStart = System.nanoTime()
		val overlay = selectAllOverlay(start)
		val buildNanos = System.nanoTime() - buildStart
		report(
			"overlay: ${overlay.meshes.size} meshes, ${overlay.meshes.sumOf { mesh -> mesh.vertexCount }} vertices, " +
				"${overlay.meshes.sumOf { mesh -> mesh.edgeCount }} edges; value built in %.1f ms (probe only)".format(buildNanos / 1e6),
		)
		renderer.setMeshOverlay(overlay)
		val firstStart = System.nanoTime()
		renderer.render(target, viewportWidth, viewportHeight)
		GL11.glFinish()
		val firstNanos = System.nanoTime() - firstStart
		val stillTimes = ArrayList<Long>(rounds)
		val pushedTimes = ArrayList<Long>(rounds)
		var current = start
		for (round in 0 until rounds) {
			val stillStart = System.nanoTime()
			renderer.render(target, viewportWidth, viewportHeight)
			GL11.glFinish()
			stillTimes.add(System.nanoTime() - stillStart)
			current = translateEveryMesh(current, 2f * (round + 1))
			renderer.updateModel(current)
			GL11.glFinish()
			val pushedStart = System.nanoTime()
			renderer.render(target, viewportWidth, viewportHeight)
			GL11.glFinish()
			pushedTimes.add(System.nanoTime() - pushedStart)
		}
		renderer.setMeshOverlay(null)
		report("G5 first frame with the overlay (buffer uploads + capture + draws): %.1f ms".format(firstNanos / 1e6))
		stats("G6 renderer.render with the overlay, nothing moved (draws only) [per frame; host GL]", stillTimes)
		stats("G7 renderer.render with the overlay after a preview push (re-capture + draws) [per frame; host GL]", pushedTimes)
	}

	/**
	 * The frame with the Object-mode wireframe of every mesh: the first frame (every edge buffer uploaded, the
	 * capture, the draws), a still frame, a frame that holds the wireframe but hides it (an area with the row
	 * off while another has it on: no capture, no draws), and a frame after a preview push (re-capture + draws).
	 *
	 * @param PuppetRenderer renderer The renderer.
	 * @param RenderTarget target The frame target.
	 * @param PuppetModel start The model the renderer holds.
	 */
	private fun probeWireframeFrames(renderer: PuppetRenderer, target: RenderTarget, start: PuppetModel) {
		val edit = selectAllOverlay(start)
		val overlay =
			MeshOverlay(
				MeshOverlayKind.ObjectWireframe,
				MeshOverlaySelectMode.Vertex,
				edit.meshes.map { mesh -> MeshOverlayMesh(mesh.drawableId, mesh.vertexCount, mesh.edgeEndpoints, ByteArray(0), ByteArray(0), ByteArray(0), null, null, null, wireframeOnly = true) },
				edit.sizes,
			)
		renderer.setMeshOverlay(overlay)
		val firstStart = System.nanoTime()
		renderer.render(target, viewportWidth, viewportHeight)
		GL11.glFinish()
		val firstNanos = System.nanoTime() - firstStart
		val stillTimes = ArrayList<Long>(rounds)
		val hiddenTimes = ArrayList<Long>(rounds)
		val pushedTimes = ArrayList<Long>(rounds)
		var current = start
		for (round in 0 until rounds) {
			val stillStart = System.nanoTime()
			renderer.render(target, viewportWidth, viewportHeight)
			GL11.glFinish()
			stillTimes.add(System.nanoTime() - stillStart)
			val hiddenStart = System.nanoTime()
			renderer.render(target, viewportWidth, viewportHeight, overlays = FrameOverlays(wireframe = false))
			GL11.glFinish()
			hiddenTimes.add(System.nanoTime() - hiddenStart)
			current = translateEveryMesh(current, 2f * (round + 1))
			renderer.updateModel(current)
			GL11.glFinish()
			val pushedStart = System.nanoTime()
			renderer.render(target, viewportWidth, viewportHeight)
			GL11.glFinish()
			pushedTimes.add(System.nanoTime() - pushedStart)
		}
		renderer.setMeshOverlay(null)
		report("G5w first frame with the wireframe of every mesh (buffer uploads + capture + draws): %.1f ms".format(firstNanos / 1e6))
		stats("G6w renderer.render with the wireframe, nothing moved (draws only) [per frame; host GL]", stillTimes)
		stats("G6h renderer.render holding the wireframe but hiding it (no capture, no draws) [per frame; host GL]", hiddenTimes)
		stats("G7w renderer.render with the wireframe after a preview push (re-capture + draws) [per frame; host GL]", pushedTimes)
	}

	/**
	 * A UV area's frame over a 4096-texel surface with the select-all Edit overlay of every mesh drawn over
	 * it from display positions: the frame without the overlay, the first frame with it (the store, the
	 * buffers, and every position uploaded), a still frame, and frames after one mesh and after every mesh
	 * moved (their positions uploaded).  The moved rows alternate between two overlays, so every round
	 * uploads what moved.  Then the Object-mode islands of every mesh: the first frame, a still one, and
	 * one after a selection change.
	 *
	 * @param PuppetRenderer renderer The renderer.
	 * @param RenderTarget target The frame target.
	 * @param PuppetModel model The model whose texture coordinates the overlay shows.
	 */
	private fun probeUvSceneFrames(renderer: PuppetRenderer, target: RenderTarget, model: PuppetModel) {
		val side = 4096
		val surface = DecodedImage(ByteArray(side * side * 4), side, side)
		renderer.setCamera(ViewportCamera.fit(ContentBounds(0f, 0f, side.toFloat(), side.toFloat()), viewportWidth, viewportHeight))
		val overlay = selectAllOverlay(model)
		val meshById = model.drawables.associate { drawable -> drawable.id to drawable.mesh }
		val positionsById = overlay.meshes.associate { mesh -> mesh.drawableId to displayPositionsOf(meshById.getValue(mesh.drawableId)!!.uvs, side, 0f) }
		val movedById = overlay.meshes.associate { mesh -> mesh.drawableId to displayPositionsOf(meshById.getValue(mesh.drawableId)!!.uvs, side, 2f) }
		val indicesById = overlay.meshes.associate { mesh -> mesh.drawableId to meshById.getValue(mesh.drawableId)!!.indices }
		val still = DirectMeshOverlay(overlay, positionsById, indicesById)
		val firstId = overlay.meshes.first().drawableId
		val oneMoved = DirectMeshOverlay(overlay, positionsById + (firstId to movedById.getValue(firstId)), indicesById)
		val allMoved = DirectMeshOverlay(overlay, movedById, indicesById)
		report("UV overlay: ${overlay.meshes.size} meshes over a ${side}x$side surface")

		renderer.renderUnderlayImage(target, surface, viewportWidth, viewportHeight)
		GL11.glFinish()
		val bareTimes = ArrayList<Long>(rounds)
		repeat(rounds) {
			val start = System.nanoTime()
			renderer.renderUnderlayImage(target, surface, viewportWidth, viewportHeight, "uv", null)
			GL11.glFinish()
			bareTimes.add(System.nanoTime() - start)
		}
		val firstStart = System.nanoTime()
		renderer.renderUnderlayImage(target, surface, viewportWidth, viewportHeight, "uv", still)
		GL11.glFinish()
		val firstNanos = System.nanoTime() - firstStart
		val stillTimes = ArrayList<Long>(rounds)
		val oneTimes = ArrayList<Long>(rounds)
		val allTimes = ArrayList<Long>(rounds)
		for (round in 0 until rounds) {
			val stillStart = System.nanoTime()
			renderer.renderUnderlayImage(target, surface, viewportWidth, viewportHeight, "uv", still)
			GL11.glFinish()
			stillTimes.add(System.nanoTime() - stillStart)
			val oneStart = System.nanoTime()
			renderer.renderUnderlayImage(target, surface, viewportWidth, viewportHeight, "uv", if (round % 2 == 0) oneMoved else still)
			GL11.glFinish()
			oneTimes.add(System.nanoTime() - oneStart)
		}
		for (round in 0 until rounds) {
			val allStart = System.nanoTime()
			renderer.renderUnderlayImage(target, surface, viewportWidth, viewportHeight, "uv", if (round % 2 == 0) allMoved else still)
			GL11.glFinish()
			allTimes.add(System.nanoTime() - allStart)
		}
		// The Object-mode islands of every mesh: the first frame, a still one, and one after a selection
		// change, which re-styles the islands over the same arrays.
		val islandMeshes = overlay.meshes.map { mesh -> MeshOverlayMesh(mesh.drawableId, mesh.vertexCount, mesh.edgeEndpoints, ByteArray(0), ByteArray(0), ByteArray(0), null, null, null, null) }
		val islands = DirectMeshOverlay(MeshOverlay(MeshOverlayKind.Islands, MeshOverlaySelectMode.Vertex, islandMeshes, overlay.sizes), positionsById, indicesById)
		val selectedStyle = IslandStyle(IslandFillRole.Selected, IslandEdgeRole.Active)
		val restyled =
			DirectMeshOverlay(
				MeshOverlay(MeshOverlayKind.Islands, MeshOverlaySelectMode.Vertex, islandMeshes.mapIndexed { meshIndex, mesh -> if (meshIndex == 0) MeshOverlayMesh(mesh.drawableId, mesh.vertexCount, mesh.edgeEndpoints, ByteArray(0), ByteArray(0), ByteArray(0), null, null, null, selectedStyle) else mesh }, overlay.sizes),
				positionsById,
				indicesById,
			)
		renderer.retainUvScenes { false }
		val islandsFirstStart = System.nanoTime()
		renderer.renderUnderlayImage(target, surface, viewportWidth, viewportHeight, "uv", islands)
		GL11.glFinish()
		val islandsFirstNanos = System.nanoTime() - islandsFirstStart
		val islandStillTimes = ArrayList<Long>(rounds)
		val islandRestyleTimes = ArrayList<Long>(rounds)
		for (round in 0 until rounds) {
			val stillStart = System.nanoTime()
			renderer.renderUnderlayImage(target, surface, viewportWidth, viewportHeight, "uv", islands)
			GL11.glFinish()
			islandStillTimes.add(System.nanoTime() - stillStart)
			val restyleStart = System.nanoTime()
			renderer.renderUnderlayImage(target, surface, viewportWidth, viewportHeight, "uv", restyled)
			GL11.glFinish()
			islandRestyleTimes.add(System.nanoTime() - restyleStart)
		}
		renderer.retainUvScenes { false }
		stats("U3 UV frame without the overlay (grid, surround, surface) [per frame; host GL]", bareTimes)
		report("U4 first UV frame with the overlay (store + buffers + every position uploaded + draws): %.1f ms".format(firstNanos / 1e6))
		stats("U5 UV frame with the overlay, nothing moved (draws only) [per frame; host GL]", stillTimes)
		stats("U6 UV frame with the overlay, one mesh moved (one upload + draws) [per drive frame; host GL]", oneTimes)
		stats("U7 UV frame with the overlay, every mesh moved (every upload + draws) [per drive frame; host GL]", allTimes)
		report("U8 first UV frame with the islands (buffers + every position uploaded + draws): %.1f ms".format(islandsFirstNanos / 1e6))
		stats("U9 UV frame with the islands, nothing changed (island-major draws only) [per frame; host GL]", islandStillTimes)
		stats("U10 UV frame with the islands after a selection change (re-styled, nothing uploaded) [per frame; host GL]", islandRestyleTimes)
	}

	/**
	 * Texture coordinates as UV display positions over a square surface (u times the side, and one minus v
	 * times the side, y up), moved by [delta] texels on both axes, in a new array.
	 *
	 * @param FloatArray uvs The coordinates.
	 * @param Int side The surface's side in texels.
	 * @param Float delta The offset in texels.
	 * @return FloatArray The positions.
	 */
	private fun displayPositionsOf(uvs: FloatArray, side: Int, delta: Float): FloatArray =
		FloatArray(uvs.size) { componentIndex ->
			if (componentIndex % 2 == 0) {
				uvs[componentIndex] * side + delta
			} else {
				(1f - uvs[componentIndex]) * side + delta
			}
		}

	/**
	 * The Edit overlay a select-all in Vertex mode publishes over [model]: every renderable mesh with its
	 * unique edges in first-encounter order, every vertex, edge, and face flagged selected, no active
	 * element, at the viewport's default sizes.
	 *
	 * @param PuppetModel model The model.
	 * @return MeshOverlay The overlay.
	 */
	private fun selectAllOverlay(model: PuppetModel): MeshOverlay {
		val meshes =
			model.drawables.mapNotNull { drawable ->
				val mesh = drawable.mesh
				if (mesh == null || mesh.indices.isEmpty()) {
					return@mapNotNull null
				}
				val vertexCount = mesh.positions.size / 2
				val edges = uniqueEdgesOf(mesh.indices)
				MeshOverlayMesh(
					drawableId = drawable.id,
					vertexCount = vertexCount,
					edgeEndpoints = edges,
					vertexFlags = ByteArray(vertexCount) { OVERLAY_FLAG_SELECTED },
					edgeFlags = ByteArray(edges.size / 2) { OVERLAY_FLAG_SELECTED },
					faceFlags = ByteArray(mesh.indices.size / 3) { OVERLAY_FLAG_SELECTED },
					activeVertex = null,
					activeEdge = null,
					activeFace = null,
				)
			}
		return MeshOverlay(MeshOverlayKind.Edit, MeshOverlaySelectMode.Vertex, meshes, MeshOverlaySizes(3.5f, 1f, 2.5f))
	}

	/**
	 * The unique undirected edges of a triangle list, low index first, in first-encounter order.
	 *
	 * @param IntArray indices The triangle corners, three per triangle.
	 * @return IntArray Two endpoints per edge.
	 */
	private fun uniqueEdgesOf(indices: IntArray): IntArray {
		val seen = HashSet<Long>(indices.size)
		val edges = ArrayList<Int>(indices.size * 2)
		var triangleStart = 0
		while (triangleStart + 2 < indices.size) {
			for (cornerIndex in 0 until 3) {
				val first = indices[triangleStart + cornerIndex]
				val second = indices[triangleStart + (cornerIndex + 1) % 3]
				val low = minOf(first, second)
				val high = maxOf(first, second)
				if (seen.add((low.toLong() shl 32) or high.toLong())) {
					edges.add(low)
					edges.add(high)
				}
			}
			triangleStart += 3
		}
		return edges.toIntArray()
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
					drawable.copy(mesh = DrawableMesh.withLocalEqualToCanvas(moved, mesh.uvs, mesh.indices))
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