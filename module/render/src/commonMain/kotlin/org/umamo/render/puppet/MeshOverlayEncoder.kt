package org.umamo.render.puppet

import org.umamo.render.device.DeformUniforms
import org.umamo.render.device.DeformedPositionStore
import org.umamo.render.device.DrawTextures
import org.umamo.render.device.FrameEncoder
import org.umamo.render.device.OverlayDrawUniforms
import org.umamo.render.device.RenderPassEncoder
import org.umamo.render.device.RenderPipeline
import org.umamo.render.device.WorldToNdc

/**
 * What an overlay draw needs from the frame it lands in, whichever scene that is.
 *
 * @property WorldToNdc affine The camera affine.
 * @property Int viewportWidth The pass viewport width in framebuffer pixels.
 * @property Int viewportHeight The pass viewport height in framebuffer pixels.
 * @property Float pixelScale Framebuffer pixels per display pixel, which scales the overlay's sizes.
 * @property MeshOverlayPalette palette The overlay colors.
 * @property Int screenTexWidth The screen-texture divisor's width the camera call takes.
 * @property Int screenTexHeight The screen-texture divisor's height.
 * @property Boolean drawWireframe Whether an Edit overlay's plain wireframe meshes draw in this frame.
 */
internal class OverlayFrame(
	val affine: WorldToNdc,
	val viewportWidth: Int,
	val viewportHeight: Int,
	val pixelScale: Float,
	val palette: MeshOverlayPalette,
	val screenTexWidth: Int,
	val screenTexHeight: Int,
	val drawWireframe: Boolean,
)

/**
 * Records the mesh overlay's frame work: the capture of the overlay meshes' deformed positions into the
 * overlay store, and the overlay draws over the finished art - the 2D scene's from that captured store, a
 * UV scene's from the positions its area uploaded.
 *
 * The capture mirrors the glue capture: its own pass outside any render pass, once per frame whose store
 * is stale, followed by a barrier.  The draws go domain-major - every mesh's face fills, then every mesh's
 * edges, then the active edges, then the dots - so each domain binds its pipeline once rather than once
 * per mesh, and so the actives land on top of every batch; an entry the current pose leaves unposed (a
 * hidden ancestor, a grid out of range) is skipped in both.  An Edit overlay's plain wireframe meshes (those
 * outside the edit) join the edge domain alone, ahead of the cage so its edges land on top, and only in a
 * frame that draws the wireframe.  An islands overlay goes island-major instead (see [drawIslands]): its
 * stacking is the point, so each island's fill covers the islands behind it.
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
		// An entry the current pose leaves unposed (a hidden ancestor, a grid out of range, or no pose yet) has
		// nothing to capture: its corners may be a stale earlier pose's, so the pose's own flag decides.  With
		// none posed the store stays stale for the pose that follows, rather than an empty pass clearing it.
		if (!overlayResidency.storeStale || overlayResidency.entries.none { entry -> entry.gpuDrawable.visible }) {
			return
		}
		val capture = frame.beginDeformCapturePass(pipelines.capture, store)
		for (entry in overlayResidency.entries) {
			val gpuDrawable = entry.gpuDrawable
			if (!gpuDrawable.visible) {
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
	 * Records the 2D overlay draws into the open pass over the finished art, per the frame's overlay value
	 * and palette; nothing when the frame carries no overlay or nothing of it is posed.
	 *
	 * @param RenderPassEncoder pass The open, unscissored pass on the frame's target.
	 * @param FrameInputs inputs The frame's inputs.
	 */
	fun encodeDraws(pass: RenderPassEncoder, inputs: FrameInputs) {
		val overlay = inputs.overlay ?: return
		val store = overlayResidency.store ?: return
		val entries = overlayResidency.entries.filter { resident -> resident.gpuDrawable.visible }.map { resident -> resident.entry }
		val frame =
			OverlayFrame(
				inputs.affine,
				inputs.viewportWidth,
				inputs.viewportHeight,
				inputs.pixelScale,
				inputs.overlayPalette,
				sideTargets.capacityWidth,
				sideTargets.capacityHeight,
				inputs.drawWireframe,
			)
		drawEntries(pass, overlay, entries, store, frame)
	}

	/**
	 * Records a UV scene's overlay draws into its open pass over the surface, from the positions its area's
	 * residency uploaded; nothing when the area shows no overlay or nothing of it paired.
	 *
	 * @param RenderPassEncoder pass The open pass on the area's target.
	 * @param UvSceneResidency residency The area's residency, already brought to its overlay.
	 * @param OverlayFrame frame The pass's camera, viewport, scale, and palette.
	 */
	fun encodeDirectDraws(pass: RenderPassEncoder, residency: UvSceneResidency, frame: OverlayFrame) {
		val direct = residency.applied ?: return
		val store = residency.store ?: return
		drawEntries(pass, direct.overlay, residency.residents.map { resident -> resident.entry }, store, frame)
	}

	/**
	 * Records an overlay's draws, domain-major: every entry's face fills, then its edges, then the active
	 * edges, then the dots and the active dots, each domain binding its pipeline once.  In an Edit overlay
	 * the fills, the actives, and the dots are the cage's alone; the plain wireframe meshes contribute their
	 * edges ahead of the cage's, and none when the frame draws no wireframe.
	 *
	 * @param RenderPassEncoder pass The open pass.
	 * @param MeshOverlay overlay The overlay value (kind, select mode, sizes).
	 * @param List<OverlayDrawEntry> entries The entries to draw, in store order.
	 * @param DeformedPositionStore store The store the entries' positions are in.
	 * @param OverlayFrame frame The pass's camera, viewport, scale, palette, and wireframe flag.
	 */
	private fun drawEntries(pass: RenderPassEncoder, overlay: MeshOverlay, entries: List<OverlayDrawEntry>, store: DeformedPositionStore, frame: OverlayFrame) {
		if (entries.isEmpty()) {
			return
		}
		if (overlay.kind == MeshOverlayKind.Islands) {
			drawIslands(pass, overlay, entries, store, frame)
			return
		}
		val palette = frame.palette
		val sizes = overlay.sizes
		val editing = overlay.kind == MeshOverlayKind.Edit
		val cage = if (editing) entries.filter { entry -> !entry.wireframeOnly } else entries
		val edged =
			when {
				!editing -> entries
				frame.drawWireframe -> entries.filter { entry -> entry.wireframeOnly } + cage
				else -> cage
			}
		uniformsScratch.viewportWidth = frame.viewportWidth.toFloat()
		uniformsScratch.viewportHeight = frame.viewportHeight.toFloat()

		if (editing) {
			// Face fills: every face in Face mode, only the selected ones otherwise; the active face fills
			// as selected, since the active face color belongs to its dot.
			bind(pass, pipelines.overlayFaceFill, frame)
			setColors(palette.faceIdle, palette.faceSelected, palette.faceSelected, opaque = false)
			uniformsScratch.sizePx = 0f
			uniformsScratch.fillIdle = overlay.selectMode == MeshOverlaySelectMode.Face
			for (entry in cage) {
				batch(entry)
				pass.drawOverlayFaceFill(entry.buffers, store, uniformsScratch)
			}
		}

		bind(pass, pipelines.overlayEdge, frame)
		setColors(palette.edgeIdle, palette.edgeSelected, palette.edgeActive, opaque = false)
		uniformsScratch.sizePx = sizes.edgeWidthPx * frame.pixelScale / 2f
		uniformsScratch.fillIdle = true
		for (entry in edged) {
			batch(entry)
			pass.drawOverlayEdges(entry.buffers, store, uniformsScratch)
		}
		if (editing) {
			for (entry in cage) {
				if (entry.activeEdgeA >= 0) {
					active(entry, entry.activeEdgeA, entry.activeEdgeB, -1)
					pass.drawOverlayEdges(entry.buffers, store, uniformsScratch)
				}
			}
		}

		if (editing && overlay.selectMode == MeshOverlaySelectMode.Vertex) {
			bind(pass, pipelines.overlayVertexDot, frame)
			setColors(palette.vertexIdle, palette.vertexSelected, palette.vertexActive, opaque = false)
			uniformsScratch.sizePx = sizes.vertexDotRadiusPx * frame.pixelScale
			for (entry in cage) {
				batch(entry)
				pass.drawOverlayVertexDots(entry.buffers, store, uniformsScratch)
			}
			for (entry in cage) {
				if (entry.activeVertex >= 0) {
					active(entry, entry.activeVertex, -1, -1)
					pass.drawOverlayVertexDots(entry.buffers, store, uniformsScratch)
				}
			}
		}

		if (editing && overlay.selectMode == MeshOverlaySelectMode.Face) {
			// The face colors carry the fill alpha; the dots are the click affordance and render opaque.
			bind(pass, pipelines.overlayFaceDot, frame)
			setColors(palette.faceIdle, palette.faceSelected, palette.faceActive, opaque = true)
			uniformsScratch.sizePx = sizes.faceDotRadiusPx * frame.pixelScale
			for (entry in cage) {
				batch(entry)
				pass.drawOverlayFaceDots(entry.buffers, store, uniformsScratch)
			}
			for (entry in cage) {
				if (entry.activeFaceA >= 0) {
					active(entry, entry.activeFaceA, entry.activeFaceB, entry.activeFaceC)
					pass.drawOverlayFaceDots(entry.buffers, store, uniformsScratch)
				}
			}
		}
	}

	/**
	 * Records an islands overlay island-major, in the entries' order, which the producer makes back to front:
	 * each island fills its every face in its fill role's color, then outlines its every edge in its edge
	 * role's color, so a nearer island's fill covers the outlines of the islands behind it, the way the
	 * islands stack for the pick.  No dots and no active primitive: an island is selected whole.  The roles
	 * resolve against the palette here, so a palette change re-colors without a new overlay, and a role
	 * change re-colors without an upload, since every island's flags are all idle.
	 *
	 * @param RenderPassEncoder pass The open pass.
	 * @param MeshOverlay overlay The islands overlay (its sizes).
	 * @param List<OverlayDrawEntry> entries The islands, back to front.
	 * @param DeformedPositionStore store The store the islands' positions are in.
	 * @param OverlayFrame frame The pass's camera, viewport, scale, and palette.
	 */
	private fun drawIslands(pass: RenderPassEncoder, overlay: MeshOverlay, entries: List<OverlayDrawEntry>, store: DeformedPositionStore, frame: OverlayFrame) {
		val palette = frame.palette
		val edgeHalfWidth = overlay.sizes.edgeWidthPx * frame.pixelScale / 2f
		uniformsScratch.viewportWidth = frame.viewportWidth.toFloat()
		uniformsScratch.viewportHeight = frame.viewportHeight.toFloat()
		uniformsScratch.fillIdle = true
		for (entry in entries) {
			val style = entry.islandStyle
			val fill =
				when (style?.fill ?: IslandFillRole.Idle) {
					IslandFillRole.Idle -> palette.faceIdle
					IslandFillRole.Selected -> palette.faceSelected
				}
			val edge =
				when (style?.edge ?: IslandEdgeRole.Idle) {
					IslandEdgeRole.Idle -> palette.edgeIdle
					IslandEdgeRole.Selected -> palette.edgeSelected
					IslandEdgeRole.Active -> palette.edgeActive
					IslandEdgeRole.Warning -> palette.warning
					IslandEdgeRole.Pinned -> palette.pinnedPlacement
				}
			bind(pass, pipelines.overlayFaceFill, frame)
			setColors(fill, fill, fill, opaque = false)
			uniformsScratch.sizePx = 0f
			batch(entry)
			pass.drawOverlayFaceFill(entry.buffers, store, uniformsScratch)
			bind(pass, pipelines.overlayEdge, frame)
			setColors(edge, edge, edge, opaque = false)
			uniformsScratch.sizePx = edgeHalfWidth
			batch(entry)
			pass.drawOverlayEdges(entry.buffers, store, uniformsScratch)
		}
	}

	/**
	 * Binds one overlay domain's pipeline and the frame's camera.
	 *
	 * @param RenderPassEncoder pass The open pass.
	 * @param RenderPipeline pipeline The domain's pipeline.
	 * @param OverlayFrame frame The frame's camera and divisor.
	 */
	private fun bind(pass: RenderPassEncoder, pipeline: RenderPipeline, frame: OverlayFrame) {
		pass.setPipeline(pipeline)
		pass.setCamera(frame.affine, frame.screenTexWidth, frame.screenTexHeight)
	}

	/**
	 * Points the scratch uniforms at one mesh's batch: its store offset, no active primitive.
	 *
	 * @param OverlayDrawEntry entry The mesh.
	 */
	private fun batch(entry: OverlayDrawEntry) {
		uniformsScratch.baseOffset = entry.baseOffset
		uniformsScratch.activeDraw = false
		uniformsScratch.activeIndexA = -1
		uniformsScratch.activeIndexB = -1
		uniformsScratch.activeIndexC = -1
	}

	/**
	 * Points the scratch uniforms at one mesh's active primitive.
	 *
	 * @param OverlayDrawEntry entry The mesh.
	 * @param Int indexA The primitive's first local index.
	 * @param Int indexB Its second, or -1.
	 * @param Int indexC Its third, or -1.
	 */
	private fun active(entry: OverlayDrawEntry, indexA: Int, indexB: Int, indexC: Int) {
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