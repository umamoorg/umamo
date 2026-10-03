package org.umamo.render.puppet

import org.umamo.render.device.DeformUniforms
import org.umamo.render.device.DrawTextures
import org.umamo.render.device.FrameEncoder
import org.umamo.render.device.OverlayDrawUniforms
import org.umamo.render.device.RenderPassEncoder
import org.umamo.render.device.RenderPipeline

/**
 * Records the mesh overlay's frame work: the capture of the overlay meshes' deformed positions into the
 * overlay store, and the overlay draws over the finished art.
 *
 * The capture mirrors the glue capture: its own pass outside any render pass, once per frame whose store
 * is stale, followed by a barrier.  The draws go domain-major - every mesh's face fills, then every mesh's
 * edges, then the active edges, then the dots - so each domain binds its pipeline once rather than once
 * per mesh, and so the actives land on top of every batch; an unposed entry (a hidden ancestor) is
 * skipped in both.
 *
 * @param DrawPipelines pipelines The capture and overlay pipelines.
 * @param SideTargetPool sideTargets The side targets, whose capacity names the screen-space divisor.
 * @param MeshOverlayResidency overlayResidency The overlay's store and buffers.
 */
internal class MeshOverlayEncoder(
	private val pipelines: DrawPipelines,
	private val sideTargets: SideTargetPool,
	private val overlayResidency: MeshOverlayResidency,
) {
	// Reused per-draw scratch, refilled each draw rather than allocated.  Render-thread only.
	private val deformScratch = DeformUniforms()
	private val texturesScratch = DrawTextures()
	private val uniformsScratch = OverlayDrawUniforms()

	/**
	 * Records the overlay capture when the store is behind the positions, and nothing otherwise.
	 *
	 * @param FrameEncoder frame The frame being recorded.
	 */
	fun encodeCapture(frame: FrameEncoder) {
		val store = overlayResidency.store ?: return
		// An entry without a pose (a resident the engine has not posed yet) has nothing to capture; with none
		// posed the store stays stale for the pose that follows, rather than an empty pass clearing it.
		if (!overlayResidency.storeStale || overlayResidency.entries.none { entry -> entry.gpuDrawable.corners != null }) {
			return
		}
		val capture = frame.beginDeformCapturePass(pipelines.capture, store)
		for (entry in overlayResidency.entries) {
			val gpuDrawable = entry.gpuDrawable
			if (gpuDrawable.corners == null) {
				continue
			}
			fillDeform(deformScratch, gpuDrawable)
			texturesScratch.atlas = null
			texturesScratch.maskCoverage = null
			texturesScratch.deltaTexture = gpuDrawable.deltaTexture
			texturesScratch.warpControlPoints = gpuDrawable.cpTexture
			capture.captureDeformedPositions(gpuDrawable.mesh, deformScratch, texturesScratch, entry.baseOffset, gpuDrawable.vertexCount)
		}
		capture.end()
		frame.barrier(store)
		overlayResidency.storeStale = false
	}

	/**
	 * Records the overlay draws into the open pass over the finished art, per the frame's overlay value
	 * and palette; nothing when the frame carries no overlay or nothing of it paired.
	 *
	 * @param RenderPassEncoder pass The open, unscissored pass on the frame's target.
	 * @param FrameInputs inputs The frame's inputs.
	 */
	fun encodeDraws(pass: RenderPassEncoder, inputs: FrameInputs) {
		val overlay = inputs.overlay ?: return
		val store = overlayResidency.store ?: return
		val entries = overlayResidency.entries.filter { entry -> entry.gpuDrawable.corners != null }
		if (entries.isEmpty()) {
			return
		}
		val palette = inputs.overlayPalette
		val sizes = overlay.sizes
		val editing = overlay.kind == MeshOverlayKind.Edit
		uniformsScratch.viewportWidth = inputs.viewportWidth.toFloat()
		uniformsScratch.viewportHeight = inputs.viewportHeight.toFloat()

		if (editing) {
			// Face fills: every face in Face mode, only the selected ones otherwise; the active face fills
			// as selected, since the active face color belongs to its dot.
			bind(pass, pipelines.overlayFaceFill, inputs)
			setColors(palette.faceIdle, palette.faceSelected, palette.faceSelected, opaque = false)
			uniformsScratch.sizePx = 0f
			uniformsScratch.fillIdle = overlay.selectMode == MeshOverlaySelectMode.Face
			for (entry in entries) {
				batch(entry)
				pass.drawOverlayFaceFill(entry.buffers, store, uniformsScratch)
			}
		}

		bind(pass, pipelines.overlayEdge, inputs)
		setColors(palette.edgeIdle, palette.edgeSelected, palette.edgeActive, opaque = false)
		uniformsScratch.sizePx = sizes.edgeWidthPx * inputs.pixelScale / 2f
		uniformsScratch.fillIdle = true
		for (entry in entries) {
			batch(entry)
			pass.drawOverlayEdges(entry.buffers, store, uniformsScratch)
		}
		if (editing) {
			for (entry in entries) {
				if (entry.activeEdgeA >= 0) {
					active(entry, entry.activeEdgeA, entry.activeEdgeB, -1)
					pass.drawOverlayEdges(entry.buffers, store, uniformsScratch)
				}
			}
		}

		if (editing && overlay.selectMode == MeshOverlaySelectMode.Vertex) {
			bind(pass, pipelines.overlayVertexDot, inputs)
			setColors(palette.vertexIdle, palette.vertexSelected, palette.vertexActive, opaque = false)
			uniformsScratch.sizePx = sizes.vertexDotRadiusPx * inputs.pixelScale
			for (entry in entries) {
				batch(entry)
				pass.drawOverlayVertexDots(entry.buffers, store, uniformsScratch)
			}
			for (entry in entries) {
				if (entry.activeVertex >= 0) {
					active(entry, entry.activeVertex, -1, -1)
					pass.drawOverlayVertexDots(entry.buffers, store, uniformsScratch)
				}
			}
		}

		if (editing && overlay.selectMode == MeshOverlaySelectMode.Face) {
			// The face colors carry the fill alpha; the dots are the click affordance and render opaque.
			bind(pass, pipelines.overlayFaceDot, inputs)
			setColors(palette.faceIdle, palette.faceSelected, palette.faceActive, opaque = true)
			uniformsScratch.sizePx = sizes.faceDotRadiusPx * inputs.pixelScale
			for (entry in entries) {
				batch(entry)
				pass.drawOverlayFaceDots(entry.buffers, store, uniformsScratch)
			}
			for (entry in entries) {
				if (entry.activeFaceA >= 0) {
					active(entry, entry.activeFaceA, entry.activeFaceB, entry.activeFaceC)
					pass.drawOverlayFaceDots(entry.buffers, store, uniformsScratch)
				}
			}
		}
	}

	/**
	 * Binds one overlay domain's pipeline and the frame's camera.
	 *
	 * @param RenderPassEncoder pass The open pass.
	 * @param RenderPipeline pipeline The domain's pipeline.
	 * @param FrameInputs inputs The frame's inputs.
	 */
	private fun bind(pass: RenderPassEncoder, pipeline: RenderPipeline, inputs: FrameInputs) {
		pass.setPipeline(pipeline)
		pass.setCamera(inputs.affine, sideTargets.capacityWidth, sideTargets.capacityHeight)
	}

	/**
	 * Points the scratch uniforms at one mesh's batch: its store offset, no active primitive.
	 *
	 * @param OverlayResident entry The mesh.
	 */
	private fun batch(entry: OverlayResident) {
		uniformsScratch.baseOffset = entry.baseOffset
		uniformsScratch.activeDraw = false
		uniformsScratch.activeIndexA = -1
		uniformsScratch.activeIndexB = -1
		uniformsScratch.activeIndexC = -1
	}

	/**
	 * Points the scratch uniforms at one mesh's active primitive.
	 *
	 * @param OverlayResident entry The mesh.
	 * @param Int indexA The primitive's first local index.
	 * @param Int indexB Its second, or -1.
	 * @param Int indexC Its third, or -1.
	 */
	private fun active(entry: OverlayResident, indexA: Int, indexB: Int, indexC: Int) {
		uniformsScratch.baseOffset = entry.baseOffset
		uniformsScratch.activeDraw = true
		uniformsScratch.activeIndexA = indexA
		uniformsScratch.activeIndexB = indexB
		uniformsScratch.activeIndexC = indexC
	}

	/**
	 * Writes one domain's three colors into the scratch uniforms.
	 *
	 * @param OverlayColor idle The idle color.
	 * @param OverlayColor selected The selected color.
	 * @param OverlayColor active The active color.
	 * @param Boolean opaque True to force every alpha to 1 (the face dots).
	 */
	private fun setColors(idle: OverlayColor, selected: OverlayColor, active: OverlayColor, opaque: Boolean) {
		writeColor(uniformsScratch.idleColor, idle, opaque)
		writeColor(uniformsScratch.selectedColor, selected, opaque)
		writeColor(uniformsScratch.activeColor, active, opaque)
	}

	/**
	 * Writes one color's components into a four-float slot.
	 *
	 * @param FloatArray slot The slot.
	 * @param OverlayColor color The color.
	 * @param Boolean opaque True to write alpha 1 instead of the color's.
	 */
	private fun writeColor(slot: FloatArray, color: OverlayColor, opaque: Boolean) {
		slot[0] = color.red
		slot[1] = color.green
		slot[2] = color.blue
		slot[3] = if (opaque) 1f else color.alpha
	}
}