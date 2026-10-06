package org.umamo.render.device

import org.umamo.format.raster.RasterImage

// The largest render target edge the recorder reports: large enough that no test tiles by accident.
private const val RECORDED_MAX_TARGET_SIZE = 16384

/** What a recorded texture holds. */
internal enum class RecordedTextureKind {
	/** An uploaded RGBA image: an atlas page, source artwork, or an underlay. */
	Image,

	/** A float data texture: a delta table or a warp's control points. */
	Float,

	/** A sampled render target's color contents. */
	TargetColor,
}

/**
 * A texture the recorder handed out.
 *
 * @property Int                 serial The order it was created in, for failure messages only.
 * @property RecordedTextureKind kind   What it holds.
 * @property Int                 width  Width in texels.
 * @property Int                 height Height in texels.
 * @property ByteArray?          pixels The uploaded RGBA array BY REFERENCE, so a test can tell which image
 *   a texture came from; null for a float or target texture.
 * @property TextureWrap?        wrap   The wrap it was created with; null for a float or target texture.
 */
internal class RecordedTexture(
	val serial: Int,
	val kind: RecordedTextureKind,
	val width: Int,
	val height: Int,
	val pixels: ByteArray?,
	val wrap: TextureWrap?,
) : GpuTexture {
	var destroyed: Boolean = false
}

/**
 * A mesh the recorder handed out.
 *
 * @property Int      serial The order it was created in, for failure messages only.
 * @property MeshSpec spec   What it was uploaded from.
 */
internal class RecordedMesh(
	val serial: Int,
	val spec: MeshSpec,
) : GpuMesh {
	var destroyed: Boolean = false

	/** The rest positions the mesh currently holds, by reference: the upload's, until an in-place update. */
	var restPositions: FloatArray = spec.restPositions

	/** The UVs the mesh currently holds, by reference: the upload's, until an in-place update. */
	var uvs: FloatArray = spec.uvs
}

/**
 * A render target the recorder handed out.
 *
 * @property Int              serial The order it was created in, for failure messages only.
 * @property RenderTargetSpec spec   What it was allocated as.
 */
internal class RecordedTarget(
	val serial: Int,
	val spec: RenderTargetSpec,
	override val sampledTexture: RecordedTexture?,
) : RenderTarget {
	var destroyed: Boolean = false
}

/**
 * A draw pipeline the recorder handed out.
 *
 * @property RenderPipelineSpec spec What it was asked for.
 */
internal class RecordedPipeline(
	val spec: RenderPipelineSpec,
) : RenderPipeline

/** The deform-capture pipeline the recorder handed out. */
internal class RecordedCapturePipeline : DeformCapturePipeline

/**
 * The deformed-position store the recorder handed out.
 *
 * @property Int vertexCapacity The vertex capacity it was allocated at.
 */
internal class RecordedStore(
	val vertexCapacity: Int,
) : DeformedPositionStore {
	var destroyed: Boolean = false
}

/**
 * One mesh's overlay instance buffers the recorder handed out.  The flag arrays are held by reference and
 * replaced by an in-place update, the way [RecordedMesh.restPositions] is.
 *
 * @property Int serial The creation ordinal, for a failure message.
 * @property OverlayMeshSpec spec The spec it was created from.
 */
internal class RecordedOverlayBuffers(
	val serial: Int,
	val spec: OverlayMeshSpec,
) : OverlayMeshBuffers {
	var vertexFlags: ByteArray = spec.vertexFlags
	var edgeFlags: ByteArray = spec.edgeFlags
	var faceFlags: ByteArray = spec.faceFlags
	var destroyed: Boolean = false
}

/**
 * A read-back claim ticket.
 *
 * @property Int width  The width of the image it yields.
 * @property Int height The height of the image it yields.
 */
internal class RecordedTicket(
	val width: Int,
	val height: Int,
) : ReadbackTicket

/** One unit of recorded frame work, in the order the renderer issued it. */
internal sealed interface RecordedStep

/**
 * One render pass and the draws recorded into it.
 *
 * @property RenderPassSpec spec The pass as the renderer described it.
 */
internal class RecordedPass(
	val spec: RenderPassSpec,
) : RecordedStep {
	val draws: MutableList<RecordedDraw> = ArrayList()
	var ended: Boolean = false

	/** The target this pass writes, as the recorder's own type. */
	val target: RecordedTarget get() = spec.colorTarget as RecordedTarget
}

/**
 * One deform-capture pass and the meshes captured in it.
 *
 * @property RecordedStore store Where the deformed positions landed.
 */
internal class RecordedCapturePass(
	val store: RecordedStore,
) : RecordedStep {
	val captures: MutableList<RecordedCapture> = ArrayList()
	var ended: Boolean = false
}

/**
 * One mesh's capture into the store.
 *
 * @property RecordedMesh mesh                    The mesh deformed.
 * @property Int          destinationVertexOffset Its base index in the store.
 * @property Int          vertexCount             How many vertices were captured.
 */
internal class RecordedCapture(
	val mesh: RecordedMesh,
	val destinationVertexOffset: Int,
	val vertexCount: Int,
)

/**
 * One filtered copy between targets.
 *
 * @property RecordedTarget source                The surface read.
 * @property Int            sourceUsedWidth       The rendered extent read, along x.
 * @property Int            sourceUsedHeight      The rendered extent read, along y.
 * @property RecordedTarget destination           The surface filled.
 * @property Int            destinationUsedWidth  The rendered extent filled, along x.
 * @property Int            destinationUsedHeight The rendered extent filled, along y.
 * @property ScissorRect?   region                The sub-rectangle copied, or null for the whole used region.
 */
internal class RecordedResolve(
	val source: RecordedTarget,
	val sourceUsedWidth: Int,
	val sourceUsedHeight: Int,
	val destination: RecordedTarget,
	val destinationUsedWidth: Int,
	val destinationUsedHeight: Int,
	val region: ScissorRect?,
) : RecordedStep

/**
 * A declared write-before-read dependency on the store.
 *
 * @property RecordedStore store The store written before and read after.
 */
internal class RecordedBarrier(
	val store: RecordedStore,
) : RecordedStep

/** One draw recorded into a pass. */
internal sealed interface RecordedDraw {
	/** The pipeline bound when the draw was issued. */
	val pipeline: RenderPipelineSpec
}

/**
 * One art-mesh draw, deforming or glue, with the values its uniform structs held at call time.
 *
 * The renderer refills one instance of each uniform struct per draw, so every value here is copied out
 * when the draw is issued, as a backend marshals it.
 *
 * @property RenderPipelineSpec pipeline          The pipeline bound.
 * @property RecordedMesh       mesh              The mesh drawn.
 * @property Boolean            isGlue            True for a glue draw, false for a deforming one.
 * @property RecordedTexture?   art               The art texture sampled, or null for a flat color draw.
 * @property RecordedTexture?   maskCoverage      The clip mask's coverage, or null when unmasked.
 * @property RecordedTexture?   deltaTexture      The morph delta table, or null for a glue draw.
 * @property RecordedTexture?   warpControlPoints The parent warp's control points, or null.
 * @property Int                screenTexWidth    The screen-space divisor's width set on the pass.
 * @property Int                screenTexHeight   The screen-space divisor's height set on the pass.
 * @property Boolean            useTexture        Whether the fragment samples [art].
 * @property Float              opacity           The opacity drawn at.
 * @property Boolean            useMask           Whether the fragment multiplies by the mask coverage.
 * @property Boolean            invertMask        Whether the coverage is inverted.
 * @property Float              highlight         How far the fragment tints toward the highlight color.
 * @property List<Float>        highlightColor    The highlight color, as red, green, and blue.
 * @property List<Float>        uvAffine          The six-float texture coordinate affine.
 * @property Int                cornerCount       The active keyform corners; 0 for a glue draw.
 * @property Int                parentType        0 direct, 1 rotation, 2 warp; 0 for a glue draw.
 * @property List<Float>        glueIntensities   The per-glue weld intensities; empty for a deforming draw.
 */
internal class RecordedMeshDraw(
	override val pipeline: RenderPipelineSpec,
	val mesh: RecordedMesh,
	val isGlue: Boolean,
	val art: RecordedTexture?,
	val maskCoverage: RecordedTexture?,
	val deltaTexture: RecordedTexture?,
	val warpControlPoints: RecordedTexture?,
	val screenTexWidth: Int,
	val screenTexHeight: Int,
	val useTexture: Boolean,
	val opacity: Float,
	val useMask: Boolean,
	val invertMask: Boolean,
	val highlight: Float,
	val highlightColor: List<Float>,
	val uvAffine: List<Float>,
	val cornerCount: Int,
	val parentType: Int,
	val glueIntensities: List<Float>,
) : RecordedDraw

/**
 * One layer composite draw, with the values its uniform struct held at call time.
 *
 * @property RenderPipelineSpec pipeline            The pipeline bound.
 * @property RecordedTexture    layer               The rendered layer blended in.
 * @property RecordedTexture    destinationSnapshot The copy of the destination blended against.
 * @property RecordedTexture?   maskCoverage        The clip mask's coverage, or null when unmasked.
 * @property Int                screenTexWidth      The screen-space divisor's width set on the pass.
 * @property Int                screenTexHeight     The screen-space divisor's height set on the pass.
 * @property Int                colorMode           The packed color blend mode.
 * @property Int                alphaMode           The packed alpha blend mode.
 * @property Float              opacity             The composite opacity.
 * @property List<Float>        multiplyColor       The multiply color, as red, green, and blue.
 * @property List<Float>        screenColor         The screen color, as red, green, and blue.
 * @property Boolean            useMask             Whether the layer is clipped by the mask coverage.
 * @property Boolean            invertMask          Whether the coverage is inverted.
 */
internal class RecordedCompositeDraw(
	override val pipeline: RenderPipelineSpec,
	val layer: RecordedTexture,
	val destinationSnapshot: RecordedTexture,
	val maskCoverage: RecordedTexture?,
	val screenTexWidth: Int,
	val screenTexHeight: Int,
	val colorMode: Int,
	val alphaMode: Int,
	val opacity: Float,
	val multiplyColor: List<Float>,
	val screenColor: List<Float>,
	val useMask: Boolean,
	val invertMask: Boolean,
) : RecordedDraw

/**
 * One grid backdrop draw.
 *
 * @property RenderPipelineSpec pipeline The pipeline bound.
 * @property GridUniforms       uniforms The grid's inputs.
 */
internal class RecordedGridDraw(
	override val pipeline: RenderPipelineSpec,
	val uniforms: GridUniforms,
) : RecordedDraw

/**
 * One world-origin axis line draw.
 *
 * @property RenderPipelineSpec pipeline The pipeline bound.
 * @property AxisLineUniforms   uniforms The line's inputs.
 */
internal class RecordedAxisDraw(
	override val pipeline: RenderPipelineSpec,
	val uniforms: AxisLineUniforms,
) : RecordedDraw

/**
 * One flat underlay quad draw.
 *
 * @property RenderPipelineSpec pipeline   The pipeline bound.
 * @property RecordedTexture    page       The image drawn.
 * @property Float              pageWidth  The quad width in texels.
 * @property Float              pageHeight The quad height in texels.
 */
internal class RecordedPageDraw(
	override val pipeline: RenderPipelineSpec,
	val page: RecordedTexture,
	val pageWidth: Float,
	val pageHeight: Float,
) : RecordedDraw

/**
 * One mesh-overlay draw with the values its uniform struct held at call time, copied out as a backend
 * marshals them.
 *
 * @property RenderPipelineSpec pipeline The pipeline bound.
 * @property PipelinePurpose purpose Which overlay domain drew.
 * @property RecordedOverlayBuffers buffers The mesh's overlay buffers.
 * @property RecordedStore store The store the positions came from.
 * @property Int baseOffset The mesh's first vertex in the store.
 * @property Float viewportWidth The pass viewport width the sizes expand in.
 * @property Float viewportHeight The pass viewport height.
 * @property Float sizePx The half-width or radius in framebuffer pixels.
 * @property Boolean fillIdle Whether idle faces fill.
 * @property Boolean activeDraw Whether this drew the one active primitive.
 * @property List<Int> activeIndices The active primitive's local indices.
 * @property List<Float> idleColor The idle color, straight RGBA.
 * @property List<Float> selectedColor The selected color.
 * @property List<Float> activeColor The active color.
 */
internal class RecordedOverlayDraw(
	override val pipeline: RenderPipelineSpec,
	val purpose: PipelinePurpose,
	val buffers: RecordedOverlayBuffers,
	val store: RecordedStore,
	val baseOffset: Int,
	val viewportWidth: Float,
	val viewportHeight: Float,
	val sizePx: Float,
	val fillIdle: Boolean,
	val activeDraw: Boolean,
	val activeIndices: List<Int>,
	val idleColor: List<Float>,
	val selectedColor: List<Float>,
	val activeColor: List<Float>,
) : RecordedDraw

/** One resource operation, in the order the renderer issued it. */
internal sealed interface ResourceEvent

/** A deformed-position store was allocated. */
internal class StoreCreated(
	val store: RecordedStore,
) : ResourceEvent

/** A deformed-position store was freed. */
internal class StoreDestroyed(
	val store: RecordedStore,
) : ResourceEvent

/** A mesh's overlay instance buffers were uploaded. */
internal class OverlayBuffersCreated(
	val buffers: RecordedOverlayBuffers,
) : ResourceEvent

/** A mesh's overlay flags were replaced in place. */
internal class OverlayFlagsUpdated(
	val buffers: RecordedOverlayBuffers,
	val vertexFlags: ByteArray,
	val edgeFlags: ByteArray,
	val faceFlags: ByteArray,
) : ResourceEvent

/** A mesh's overlay instance buffers were freed. */
internal class OverlayBuffersDestroyed(
	val buffers: RecordedOverlayBuffers,
) : ResourceEvent

/** A texture was created. */
internal class TextureCreated(
	val texture: RecordedTexture,
) : ResourceEvent

/** A texture was freed. */
internal class TextureDestroyed(
	val texture: RecordedTexture,
) : ResourceEvent

/** A float texture's contents were re-specified in place. */
internal class FloatTextureUpdated(
	val texture: RecordedTexture,
	val width: Int,
	val height: Int,
) : ResourceEvent

/** A mesh was uploaded. */
internal class MeshCreated(
	val mesh: RecordedMesh,
) : ResourceEvent

/** A mesh was freed. */
internal class MeshDestroyed(
	val mesh: RecordedMesh,
) : ResourceEvent

/** A resident mesh's rest positions were re-uploaded in place. */
internal class MeshPositionsUpdated(
	val mesh: RecordedMesh,
	val positions: FloatArray,
) : ResourceEvent

/** A resident mesh's UVs were re-uploaded in place. */
internal class MeshUvsUpdated(
	val mesh: RecordedMesh,
	val uvs: FloatArray,
) : ResourceEvent

/** A render target was allocated. */
internal class TargetCreated(
	val target: RecordedTarget,
) : ResourceEvent

/** A render target was freed. */
internal class TargetDestroyed(
	val target: RecordedTarget,
) : ResourceEvent

/**
 * A [RenderDevice] that draws nothing and records what it was asked to do, so a test can assert on the
 * renderer's pass structure and resource traffic without a GPU.
 *
 * Pixels cannot show structure: a composite the renderer flattened and one it composited for real land
 * on the same pixels, which is the whole point of flattening.  This records the passes, their load
 * actions and scissors, the draws and the values their uniforms held, the copies between targets, and
 * every resource created, updated, or freed.
 *
 * It also holds the renderer to the device contract on every run, failing the test the moment the
 * renderer breaks it: a handle used or freed after it was freed, a pass begun inside another, a draw
 * issued with the wrong pipeline for its kind, a copy made while a pass is open, or a pass sampling the
 * target it is writing.
 *
 * What it deliberately does not record is anything a backend is free to vary: pipelines are handed out
 * by spec and reused, exactly as the contract allows, so the order they are first asked for in leaves no
 * trace.
 *
 * Single-threaded, like every device.
 */
internal class RecordingRenderDevice : RenderDevice {
	private val recordedSteps = ArrayList<RecordedStep>()
	private val recordedResourceEvents = ArrayList<ResourceEvent>()
	private val pipelinesBySpec = HashMap<RenderPipelineSpec, RecordedPipeline>()
	private val capturePipeline = RecordedCapturePipeline()
	private var nextSerial = 0
	private var frameOpen = false
	private var openPass: RecordedPass? = null
	private var openCapture: RecordedCapturePass? = null

	/** Every unit of frame work recorded since the last [clearLog], in order. */
	val steps: List<RecordedStep> get() = recordedSteps

	/** Every resource operation recorded since the last [clearLog], in order. */
	val resourceEvents: List<ResourceEvent> get() = recordedResourceEvents

	/**
	 * Forgets everything recorded so far, so the next assertion sees only what follows.  The handles
	 * keep their state: one freed before the clear is still freed after it.
	 */
	fun clearLog() {
		recordedSteps.clear()
		recordedResourceEvents.clear()
	}

	/**
	 * The render passes recorded, in order.
	 *
	 * @return List<RecordedPass> The passes.
	 */
	fun passes(): List<RecordedPass> = recordedSteps.filterIsInstance<RecordedPass>()

	/**
	 * The deform-capture passes recorded, in order.
	 *
	 * @return List<RecordedCapturePass> The capture passes.
	 */
	fun capturePasses(): List<RecordedCapturePass> = recordedSteps.filterIsInstance<RecordedCapturePass>()

	/**
	 * The copies between targets recorded, in order.
	 *
	 * @return List<RecordedResolve> The copies.
	 */
	fun resolves(): List<RecordedResolve> = recordedSteps.filterIsInstance<RecordedResolve>()

	/**
	 * Every art-mesh draw recorded, across all passes, in order.
	 *
	 * @return List<RecordedMeshDraw> The draws.
	 */
	fun meshDraws(): List<RecordedMeshDraw> = passes().flatMap { pass -> pass.draws.filterIsInstance<RecordedMeshDraw>() }

	/**
	 * Every composite draw recorded, across all passes, in order.
	 *
	 * @return List<RecordedCompositeDraw> The draws.
	 */
	fun compositeDraws(): List<RecordedCompositeDraw> = passes().flatMap { pass -> pass.draws.filterIsInstance<RecordedCompositeDraw>() }

	/**
	 * The art-mesh draws of the mesh currently holding [positions], across all passes, in order.
	 *
	 * @param FloatArray positions The rest positions array the mesh was uploaded or updated with.
	 * @return List<RecordedMeshDraw> The draws.
	 */
	fun meshDrawsOf(positions: FloatArray): List<RecordedMeshDraw> = meshDraws().filter { draw -> draw.mesh.restPositions === positions }

	/**
	 * Every mesh-overlay draw in every recorded pass, in issue order.
	 *
	 * @return List<RecordedOverlayDraw> The draws.
	 */
	fun overlayDraws(): List<RecordedOverlayDraw> = passes().flatMap { pass -> pass.draws.filterIsInstance<RecordedOverlayDraw>() }

	/**
	 * The pass a draw was recorded into.
	 *
	 * @param RecordedDraw draw The draw.
	 * @return RecordedPass The pass holding it.
	 */
	fun passOf(draw: RecordedDraw): RecordedPass = passes().first { pass -> pass.draws.any { candidate -> candidate === draw } }

	/**
	 * The target whose contents a texture is, or null when the texture is not a target's.
	 *
	 * @param RecordedTexture texture The texture a draw sampled.
	 * @return RecordedTarget? The target it belongs to.
	 */
	fun targetSampledAs(texture: RecordedTexture): RecordedTarget? =
		recordedSteps
			.filterIsInstance<RecordedPass>()
			.map { pass -> pass.target }
			.firstOrNull { target -> target.sampledTexture === texture }

	override fun createTexture(
		width: Int,
		height: Int,
		format: TextureFormat,
		filter: TextureFilter,
		pixels: ByteArray?,
		wrap: TextureWrap,
	): GpuTexture {
		val texture = RecordedTexture(nextSerial++, RecordedTextureKind.Image, width, height, pixels, wrap)
		recordedResourceEvents.add(TextureCreated(texture))
		return texture
	}

	override fun createFloatTexture(width: Int, height: Int, filter: TextureFilter, texels: FloatArray): GpuTexture {
		val texture = RecordedTexture(nextSerial++, RecordedTextureKind.Float, width, height, pixels = null, wrap = null)
		recordedResourceEvents.add(TextureCreated(texture))
		return texture
	}

	override fun updateFloatTexture(texture: GpuTexture, width: Int, height: Int, texels: FloatArray) {
		val recorded = liveTexture(texture, "updateFloatTexture")
		check(recorded.kind == RecordedTextureKind.Float) { "updateFloatTexture on texture #${recorded.serial}, which is ${recorded.kind}" }
		recordedResourceEvents.add(FloatTextureUpdated(recorded, width, height))
	}

	override fun createMesh(spec: MeshSpec): GpuMesh {
		spec.glueAttributes?.let { attributes ->
			val vertexCount = spec.restPositions.size / 2
			// A backend reads one weld attribute per vertex; a shorter array reads past its buffer.
			check(attributes.partnerIndex.size == vertexCount && attributes.glueIndex.size == vertexCount && attributes.weldWeight.size == vertexCount) {
				"createMesh: glue attributes cover ${attributes.partnerIndex.size} vertices of a $vertexCount-vertex mesh"
			}
		}
		val mesh = RecordedMesh(nextSerial++, spec)
		recordedResourceEvents.add(MeshCreated(mesh))
		return mesh
	}

	override fun updateMeshPositions(mesh: GpuMesh, restPositions: FloatArray) {
		val recorded = liveMesh(mesh, "updateMeshPositions")
		recorded.restPositions = restPositions
		recordedResourceEvents.add(MeshPositionsUpdated(recorded, restPositions))
	}

	override fun updateMeshUvs(mesh: GpuMesh, uvs: FloatArray) {
		val recorded = liveMesh(mesh, "updateMeshUvs")
		recorded.uvs = uvs
		recordedResourceEvents.add(MeshUvsUpdated(recorded, uvs))
	}

	override fun createRenderTarget(spec: RenderTargetSpec): RenderTarget {
		val sampledTexture =
			if (spec.sampled) {
				RecordedTexture(nextSerial++, RecordedTextureKind.TargetColor, spec.width, spec.height, pixels = null, wrap = null)
			} else {
				null
			}
		val target = RecordedTarget(nextSerial++, spec, sampledTexture)
		recordedResourceEvents.add(TargetCreated(target))
		return target
	}

	override fun createDeformedPositionStore(vertexCapacity: Int): DeformedPositionStore {
		val store = RecordedStore(vertexCapacity)
		recordedResourceEvents.add(StoreCreated(store))
		return store
	}

	override fun createRenderPipeline(spec: RenderPipelineSpec): RenderPipeline = pipelinesBySpec.getOrPut(spec) { RecordedPipeline(spec) }

	override fun createDeformCapturePipeline(): DeformCapturePipeline = capturePipeline

	override fun destroyTexture(texture: GpuTexture) {
		val recorded = liveTexture(texture, "destroyTexture")
		check(recorded.kind != RecordedTextureKind.TargetColor) { "destroyTexture on a render target's own texture #${recorded.serial}" }
		recorded.destroyed = true
		recordedResourceEvents.add(TextureDestroyed(recorded))
	}

	override fun destroyMesh(mesh: GpuMesh) {
		val recorded = liveMesh(mesh, "destroyMesh")
		recorded.destroyed = true
		recordedResourceEvents.add(MeshDestroyed(recorded))
	}

	override fun destroyRenderTarget(target: RenderTarget) {
		val recorded = liveTarget(target, "destroyRenderTarget")
		recorded.destroyed = true
		recorded.sampledTexture?.destroyed = true
		recordedResourceEvents.add(TargetDestroyed(recorded))
	}

	override fun destroyDeformedPositionStore(store: DeformedPositionStore) {
		val recorded = liveStore(store, "destroyDeformedPositionStore")
		recorded.destroyed = true
		recordedResourceEvents.add(StoreDestroyed(recorded))
	}

	override fun createOverlayMeshBuffers(spec: OverlayMeshSpec): OverlayMeshBuffers {
		check(spec.edgeEndpoints.size % 2 == 0) { "overlay edge endpoints come in pairs" }
		check(spec.faceCorners.size % 3 == 0) { "overlay face corners come in threes" }
		check(spec.edgeFlags.size == spec.edgeEndpoints.size / 2) { "one overlay edge flag per edge" }
		check(spec.faceFlags.size == spec.faceCorners.size / 3) { "one overlay face flag per triangle" }
		val buffers = RecordedOverlayBuffers(nextSerial++, spec)
		recordedResourceEvents.add(OverlayBuffersCreated(buffers))
		return buffers
	}

	override fun updateOverlayMeshFlags(buffers: OverlayMeshBuffers, vertexFlags: ByteArray, edgeFlags: ByteArray, faceFlags: ByteArray) {
		val recorded = liveOverlayBuffers(buffers, "updateOverlayMeshFlags")
		check(vertexFlags.size == recorded.vertexFlags.size) { "updateOverlayMeshFlags changes the vertex flag count of overlay buffers #${recorded.serial}" }
		check(edgeFlags.size == recorded.edgeFlags.size) { "updateOverlayMeshFlags changes the edge flag count of overlay buffers #${recorded.serial}" }
		check(faceFlags.size == recorded.faceFlags.size) { "updateOverlayMeshFlags changes the face flag count of overlay buffers #${recorded.serial}" }
		recorded.vertexFlags = vertexFlags
		recorded.edgeFlags = edgeFlags
		recorded.faceFlags = faceFlags
		recordedResourceEvents.add(OverlayFlagsUpdated(recorded, vertexFlags, edgeFlags, faceFlags))
	}

	override fun destroyOverlayMeshBuffers(buffers: OverlayMeshBuffers) {
		val recorded = liveOverlayBuffers(buffers, "destroyOverlayMeshBuffers")
		recorded.destroyed = true
		recordedResourceEvents.add(OverlayBuffersDestroyed(recorded))
	}

	override fun beginFrame(): FrameEncoder {
		check(!frameOpen) { "beginFrame while a frame is already open" }
		frameOpen = true
		return RecordingFrame()
	}

	override fun resolve(source: RenderTarget, destination: RenderTarget, region: ScissorRect?) {
		val recordedSource = liveTarget(source, "resolve")
		val recordedDestination = liveTarget(destination, "resolve")
		resolveUsed(
			source,
			recordedSource.spec.width,
			recordedSource.spec.height,
			destination,
			recordedDestination.spec.width,
			recordedDestination.spec.height,
			region,
		)
	}

	override fun resolveUsed(
		source: RenderTarget,
		sourceUsedWidth: Int,
		sourceUsedHeight: Int,
		destination: RenderTarget,
		destinationUsedWidth: Int,
		destinationUsedHeight: Int,
		region: ScissorRect?,
	) {
		check(openPass == null && openCapture == null) { "a copy between targets while a pass is open" }
		val recordedSource = liveTarget(source, "resolveUsed")
		val recordedDestination = liveTarget(destination, "resolveUsed")
		check(recordedSource !== recordedDestination) { "a copy from target #${recordedSource.serial} onto itself" }
		if (region != null) {
			check(sourceUsedWidth == destinationUsedWidth && sourceUsedHeight == destinationUsedHeight) {
				"a region copy between used sizes ${sourceUsedWidth}x$sourceUsedHeight and ${destinationUsedWidth}x$destinationUsedHeight"
			}
		}
		recordedSteps.add(
			RecordedResolve(
				recordedSource,
				sourceUsedWidth,
				sourceUsedHeight,
				recordedDestination,
				destinationUsedWidth,
				destinationUsedHeight,
				region,
			),
		)
	}

	override fun beginReadback(target: RenderTarget): ReadbackTicket {
		val recorded = liveTarget(target, "beginReadback")
		return RecordedTicket(recorded.spec.width, recorded.spec.height)
	}

	override fun beginReadback(target: RenderTarget, usedWidth: Int, usedHeight: Int): ReadbackTicket {
		liveTarget(target, "beginReadback")
		return RecordedTicket(usedWidth, usedHeight)
	}

	override fun pollReadback(ticket: ReadbackTicket): RasterImage {
		val recorded = ticket as RecordedTicket
		return blankImage(recorded.width, recorded.height)
	}

	override fun cancelReadback(ticket: ReadbackTicket) {
		// Nothing is staged, so there is nothing to free.
	}

	override fun readPixels(target: RenderTarget): RasterImage {
		val recorded = liveTarget(target, "readPixels")
		return blankImage(recorded.spec.width, recorded.spec.height)
	}

	override fun readPixels(target: RenderTarget, usedWidth: Int, usedHeight: Int): RasterImage {
		liveTarget(target, "readPixels")
		return blankImage(usedWidth, usedHeight)
	}

	override fun maxRenderTargetSize(): Int = RECORDED_MAX_TARGET_SIZE

	override fun describeBackend(): String = "recording device (draws nothing)"

	/**
	 * A fully transparent image, standing in for pixels nothing drew.
	 *
	 * @param Int width  The image width in pixels.
	 * @param Int height The image height in pixels.
	 * @return RasterImage The image.
	 */
	private fun blankImage(width: Int, height: Int): RasterImage = RasterImage(width, height, ByteArray(width * height * 4))

	/**
	 * Narrows a texture handle to the recorder's own type, failing when it was already freed.
	 *
	 * @param GpuTexture texture   The handle the renderer passed.
	 * @param String     operation What the renderer was doing with it, for the failure message.
	 * @return RecordedTexture The live texture.
	 */
	private fun liveTexture(texture: GpuTexture, operation: String): RecordedTexture {
		val recorded = texture as RecordedTexture
		check(!recorded.destroyed) { "$operation used texture #${recorded.serial} after it was freed" }
		return recorded
	}

	/**
	 * Narrows a mesh handle to the recorder's own type, failing when it was already freed.
	 *
	 * @param GpuMesh mesh      The handle the renderer passed.
	 * @param String  operation What the renderer was doing with it, for the failure message.
	 * @return RecordedMesh The live mesh.
	 */
	private fun liveMesh(mesh: GpuMesh, operation: String): RecordedMesh {
		val recorded = mesh as RecordedMesh
		check(!recorded.destroyed) { "$operation used mesh #${recorded.serial} after it was freed" }
		return recorded
	}

	/**
	 * Narrows a target handle to the recorder's own type, failing when it was already freed.
	 *
	 * @param RenderTarget target    The handle the renderer passed.
	 * @param String       operation What the renderer was doing with it, for the failure message.
	 * @return RecordedTarget The live target.
	 */
	private fun liveTarget(target: RenderTarget, operation: String): RecordedTarget {
		val recorded = target as RecordedTarget
		check(!recorded.destroyed) { "$operation used render target #${recorded.serial} after it was freed" }
		return recorded
	}

	/**
	 * Narrows an optional sampled texture, failing when it was freed or when it is the contents of the
	 * target the pass is writing.
	 *
	 * @param GpuTexture?  texture   The texture a draw samples, or null for an unused slot.
	 * @param RecordedPass pass      The pass the draw is recorded into.
	 * @param String       operation What the renderer was doing, for the failure message.
	 * @return RecordedTexture? The live texture, or null for an unused slot.
	 */
	private fun sampledTexture(texture: GpuTexture?, pass: RecordedPass, operation: String): RecordedTexture? {
		if (texture == null) {
			return null
		}
		val recorded = liveTexture(texture, operation)
		check(recorded !== pass.target.sampledTexture) { "$operation samples target #${pass.target.serial}, which its own pass is writing" }
		return recorded
	}

	/**
	 * The recorded store behind a handle, failing when it was freed.
	 *
	 * @param DeformedPositionStore store The handle.
	 * @param String operation What the renderer was doing, for the failure message.
	 * @return RecordedStore The live store.
	 */
	private fun liveStore(store: DeformedPositionStore, operation: String): RecordedStore {
		val recorded = store as RecordedStore
		check(!recorded.destroyed) { "$operation on a deformed-position store that was freed" }
		return recorded
	}

	/**
	 * The recorded overlay buffers behind a handle, failing when they were freed.
	 *
	 * @param OverlayMeshBuffers buffers The handle.
	 * @param String operation What the renderer was doing, for the failure message.
	 * @return RecordedOverlayBuffers The live buffers.
	 */
	private fun liveOverlayBuffers(buffers: OverlayMeshBuffers, operation: String): RecordedOverlayBuffers {
		val recorded = buffers as RecordedOverlayBuffers
		check(!recorded.destroyed) { "$operation on overlay buffers #${recorded.serial} that were freed" }
		return recorded
	}

	/** Records one frame's passes, holding them to the no-nesting rule. */
	private inner class RecordingFrame : FrameEncoder {
		override fun beginRenderPass(spec: RenderPassSpec): RenderPassEncoder {
			check(frameOpen) { "beginRenderPass after the frame ended" }
			check(openPass == null && openCapture == null) { "beginRenderPass while another pass is open" }
			liveTarget(spec.colorTarget, "beginRenderPass")
			val pass = RecordedPass(spec)
			recordedSteps.add(pass)
			openPass = pass
			return RecordingPass(pass)
		}

		override fun beginDeformCapturePass(pipeline: DeformCapturePipeline, store: DeformedPositionStore): DeformCapturePassEncoder {
			check(frameOpen) { "beginDeformCapturePass after the frame ended" }
			check(openPass == null && openCapture == null) { "beginDeformCapturePass while another pass is open" }
			val capture = RecordedCapturePass(liveStore(store, "beginDeformCapturePass"))
			recordedSteps.add(capture)
			openCapture = capture
			return RecordingCapture(capture)
		}

		override fun barrier(store: DeformedPositionStore) {
			check(frameOpen) { "barrier after the frame ended" }
			check(openPass == null && openCapture == null) { "barrier while a pass is open" }
			recordedSteps.add(RecordedBarrier(liveStore(store, "barrier")))
		}

		override fun endFrame() {
			check(frameOpen) { "endFrame on a frame that already ended" }
			check(openPass == null && openCapture == null) { "endFrame while a pass is open" }
			frameOpen = false
		}
	}

	/**
	 * Records one render pass's draws.
	 *
	 * @property RecordedPass pass The pass being recorded.
	 */
	private inner class RecordingPass(
		private val pass: RecordedPass,
	) : RenderPassEncoder {
		private var boundPipeline: RecordedPipeline? = null
		private var screenTexWidth = 0
		private var screenTexHeight = 0

		override fun setPipeline(pipeline: RenderPipeline) {
			requireOpen("setPipeline")
			boundPipeline = pipeline as RecordedPipeline
		}

		override fun setCamera(worldToNdc: WorldToNdc, screenTexWidth: Int, screenTexHeight: Int) {
			requireOpen("setCamera")
			this.screenTexWidth = screenTexWidth
			this.screenTexHeight = screenTexHeight
		}

		override fun drawPuppetMesh(mesh: GpuMesh, deform: DeformUniforms, fragment: FragmentUniforms, textures: DrawTextures) {
			val pipeline = pipelineFor(PipelinePurpose.PuppetDeformDraw, "drawPuppetMesh")
			pass.draws.add(
				RecordedMeshDraw(
					pipeline = pipeline,
					mesh = liveMesh(mesh, "drawPuppetMesh"),
					isGlue = false,
					art = sampledTexture(textures.atlas, pass, "drawPuppetMesh"),
					maskCoverage = sampledTexture(textures.maskCoverage, pass, "drawPuppetMesh"),
					deltaTexture = sampledTexture(textures.deltaTexture, pass, "drawPuppetMesh"),
					warpControlPoints = sampledTexture(textures.warpControlPoints, pass, "drawPuppetMesh"),
					screenTexWidth = screenTexWidth,
					screenTexHeight = screenTexHeight,
					useTexture = fragment.useTexture,
					opacity = fragment.opacity,
					useMask = fragment.useMask,
					invertMask = fragment.invertMask,
					highlight = fragment.highlight,
					highlightColor = listOf(fragment.highlightRed, fragment.highlightGreen, fragment.highlightBlue),
					uvAffine = fragment.uvAffine.toList(),
					cornerCount = deform.cornerCount,
					parentType = deform.parentType,
					glueIntensities = emptyList(),
				),
			)
		}

		override fun drawGlueMesh(
			mesh: GpuMesh,
			store: DeformedPositionStore,
			baseVertexOffset: Int,
			glueIntensities: FloatArray,
			fragment: FragmentUniforms,
			textures: DrawTextures,
		) {
			val pipeline = pipelineFor(PipelinePurpose.PuppetGlueDraw, "drawGlueMesh")
			liveStore(store, "drawGlueMesh")
			pass.draws.add(
				RecordedMeshDraw(
					pipeline = pipeline,
					mesh = liveMesh(mesh, "drawGlueMesh"),
					isGlue = true,
					art = sampledTexture(textures.atlas, pass, "drawGlueMesh"),
					maskCoverage = sampledTexture(textures.maskCoverage, pass, "drawGlueMesh"),
					deltaTexture = null,
					warpControlPoints = null,
					screenTexWidth = screenTexWidth,
					screenTexHeight = screenTexHeight,
					useTexture = fragment.useTexture,
					opacity = fragment.opacity,
					useMask = fragment.useMask,
					invertMask = fragment.invertMask,
					highlight = fragment.highlight,
					highlightColor = listOf(fragment.highlightRed, fragment.highlightGreen, fragment.highlightBlue),
					uvAffine = fragment.uvAffine.toList(),
					cornerCount = 0,
					parentType = 0,
					glueIntensities = glueIntensities.toList(),
				),
			)
		}

		override fun drawAtlasPage(atlas: GpuTexture, pageWidth: Float, pageHeight: Float, fragment: FragmentUniforms) {
			val pipeline = pipelineFor(PipelinePurpose.AtlasPageDraw, "drawAtlasPage")
			val page = sampledTexture(atlas, pass, "drawAtlasPage") ?: error("drawAtlasPage with no page")
			pass.draws.add(RecordedPageDraw(pipeline, page, pageWidth, pageHeight))
		}

		override fun drawGrid(uniforms: GridUniforms) {
			pass.draws.add(RecordedGridDraw(pipelineFor(PipelinePurpose.GridBackdrop, "drawGrid"), uniforms))
		}

		override fun drawComposite(composite: CompositeUniforms, textures: DrawTextures) {
			val pipeline = pipelineFor(PipelinePurpose.Composite, "drawComposite")
			val layer = sampledTexture(textures.compositeLayer, pass, "drawComposite") ?: error("drawComposite with no layer")
			val snapshot = sampledTexture(textures.destinationSnapshot, pass, "drawComposite") ?: error("drawComposite with no destination snapshot")
			pass.draws.add(
				RecordedCompositeDraw(
					pipeline = pipeline,
					layer = layer,
					destinationSnapshot = snapshot,
					maskCoverage = sampledTexture(textures.maskCoverage, pass, "drawComposite"),
					screenTexWidth = screenTexWidth,
					screenTexHeight = screenTexHeight,
					colorMode = composite.colorMode,
					alphaMode = composite.alphaMode,
					opacity = composite.opacity,
					multiplyColor = listOf(composite.multiplyRed, composite.multiplyGreen, composite.multiplyBlue),
					screenColor = listOf(composite.screenRed, composite.screenGreen, composite.screenBlue),
					useMask = composite.useMask,
					invertMask = composite.invertMask,
				),
			)
		}

		override fun drawAxisLine(uniforms: AxisLineUniforms) {
			pass.draws.add(RecordedAxisDraw(pipelineFor(PipelinePurpose.WorldAxisLine, "drawAxisLine"), uniforms))
		}

		override fun drawOverlayFaceFill(buffers: OverlayMeshBuffers, store: DeformedPositionStore, uniforms: OverlayDrawUniforms) {
			recordOverlayDraw(PipelinePurpose.OverlayFaceFill, "drawOverlayFaceFill", buffers, store, uniforms)
		}

		override fun drawOverlayEdges(buffers: OverlayMeshBuffers, store: DeformedPositionStore, uniforms: OverlayDrawUniforms) {
			recordOverlayDraw(PipelinePurpose.OverlayEdge, "drawOverlayEdges", buffers, store, uniforms)
		}

		override fun drawOverlayVertexDots(buffers: OverlayMeshBuffers, store: DeformedPositionStore, uniforms: OverlayDrawUniforms) {
			recordOverlayDraw(PipelinePurpose.OverlayVertexDot, "drawOverlayVertexDots", buffers, store, uniforms)
		}

		override fun drawOverlayFaceDots(buffers: OverlayMeshBuffers, store: DeformedPositionStore, uniforms: OverlayDrawUniforms) {
			recordOverlayDraw(PipelinePurpose.OverlayFaceDot, "drawOverlayFaceDots", buffers, store, uniforms)
		}

		/**
		 * Records one overlay draw of any domain, copying the uniform values out as a backend marshals them.
		 *
		 * @param PipelinePurpose purpose The domain's pipeline purpose, which the bound pipeline must match.
		 * @param String operation The draw being issued, for the failure message.
		 * @param OverlayMeshBuffers buffers The mesh's overlay buffers.
		 * @param DeformedPositionStore store The overlay's store.
		 * @param OverlayDrawUniforms uniforms The draw's inputs.
		 */
		private fun recordOverlayDraw(
			purpose: PipelinePurpose,
			operation: String,
			buffers: OverlayMeshBuffers,
			store: DeformedPositionStore,
			uniforms: OverlayDrawUniforms,
		) {
			val pipeline = pipelineFor(purpose, operation)
			pass.draws.add(
				RecordedOverlayDraw(
					pipeline = pipeline,
					purpose = purpose,
					buffers = liveOverlayBuffers(buffers, operation),
					store = liveStore(store, operation),
					baseOffset = uniforms.baseOffset,
					viewportWidth = uniforms.viewportWidth,
					viewportHeight = uniforms.viewportHeight,
					sizePx = uniforms.sizePx,
					fillIdle = uniforms.fillIdle,
					activeDraw = uniforms.activeDraw,
					activeIndices = listOf(uniforms.activeIndexA, uniforms.activeIndexB, uniforms.activeIndexC),
					idleColor = uniforms.idleColor.toList(),
					selectedColor = uniforms.selectedColor.toList(),
					activeColor = uniforms.activeColor.toList(),
				),
			)
		}

		override fun end() {
			requireOpen("end")
			pass.ended = true
			openPass = null
		}

		/**
		 * Fails when this pass has ended, or when it is no longer the open pass.
		 *
		 * @param String operation What the renderer was doing, for the failure message.
		 */
		private fun requireOpen(operation: String) {
			check(!pass.ended && openPass === pass) { "$operation on a pass that already ended" }
		}

		/**
		 * The bound pipeline's spec, failing when none is bound or when it is the wrong kind for the draw.
		 *
		 * @param PipelinePurpose purpose   The purpose the draw requires.
		 * @param String          operation The draw being issued, for the failure message.
		 * @return RenderPipelineSpec The bound pipeline's spec.
		 */
		private fun pipelineFor(purpose: PipelinePurpose, operation: String): RenderPipelineSpec {
			requireOpen(operation)
			val pipeline = boundPipeline ?: error("$operation with no pipeline bound")
			check(pipeline.spec.purpose == purpose) { "$operation with a ${pipeline.spec.purpose} pipeline bound" }
			return pipeline.spec
		}
	}

	/**
	 * Records one deform-capture pass.
	 *
	 * @property RecordedCapturePass capture The pass being recorded.
	 */
	private inner class RecordingCapture(
		private val capture: RecordedCapturePass,
	) : DeformCapturePassEncoder {
		override fun captureDeformedPositions(
			mesh: GpuMesh,
			deform: DeformUniforms,
			textures: DrawTextures,
			destinationVertexOffset: Int,
			vertexCount: Int,
		) {
			check(!capture.ended && openCapture === capture) { "captureDeformedPositions on a pass that already ended" }
			check(destinationVertexOffset + vertexCount <= capture.store.vertexCapacity) {
				"a capture of $vertexCount vertices at $destinationVertexOffset overruns the store's ${capture.store.vertexCapacity}"
			}
			textures.deltaTexture?.let { texture -> liveTexture(texture, "captureDeformedPositions") }
			textures.warpControlPoints?.let { texture -> liveTexture(texture, "captureDeformedPositions") }
			capture.captures.add(RecordedCapture(liveMesh(mesh, "captureDeformedPositions"), destinationVertexOffset, vertexCount))
		}

		override fun end() {
			check(!capture.ended && openCapture === capture) { "end on a capture pass that already ended" }
			capture.ended = true
			openCapture = null
		}
	}
}