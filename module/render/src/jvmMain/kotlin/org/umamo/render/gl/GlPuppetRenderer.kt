package org.umamo.render.gl

import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL31
import org.umamo.render.ContentBounds
import org.umamo.render.DecodedImage
import org.umamo.render.GpuRenderer
import org.umamo.render.GridColors
import org.umamo.render.PuppetTextures
import org.umamo.render.Renderer
import org.umamo.render.ViewportCamera
import org.umamo.render.eval.DeformedGeometry
import org.umamo.render.eval.DeformerWorld
import org.umamo.render.eval.PoseDeformInputs
import org.umamo.render.eval.RotationWorld
import org.umamo.render.eval.WarpWorld
import org.umamo.render.eval.WeightedCell
import org.umamo.render.eval.applyCpuDeform
import org.umamo.render.eval.cellsByLinearIndex
import org.umamo.render.eval.paintOrder
import org.umamo.render.eval.preparePose
import org.umamo.render.eval.renderOrder
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RenderGroup
import org.umamo.runtime.model.visibleDrawableIds
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import javax.imageio.ImageIO

// Standard texture units, shared across all three programs so the per-mesh deform uniforms can be set the
// same way regardless of which program is bound. atlas/mask are fragment-stage; delta/cp/position are
// vertex-stage (texture fetch).
private const val UNIT_ATLAS = 0
private const val UNIT_MASK = 1
private const val UNIT_DELTA = 2
private const val UNIT_CP = 3
private const val UNIT_POSITION = 4

private const val MAX_GLUES = 64

// How strongly a selected drawable is tinted toward the selection accent in the fragment shader (0 = no
// tint, 1 = fully the accent color). A subtle wash so the art stays readable under the highlight.
private const val SELECTION_TINT_STRENGTH = 0.35f

// Live-render vertex shader: deforms (shared DEFORM_GLSL deformWorld) then projects. The deform body is
// shared with the glue pass-1 TF shader and the GPU-vs-CPU test, so all three exercise identical math.
private val VERTEX_SHADER =
	"#version 330 core\n" +
		"layout(location = 0) in vec2 inBase;\n" + // rest-pose position (parent-deformer-local space)
		"layout(location = 1) in vec2 inUv;\n" + // atlas texture coordinate (CMO3 [0,1])
		"uniform vec4 worldToNdc;\n" + // (scaleX, scaleY, offsetX, offsetY) - the camera's world→NDC affine
		"out vec2 vUv;\n" +
		DEFORM_GLSL.trimIndent() + "\n" +
		"void main() {\n" +
		"	vUv = inUv;\n" +
		"	vec2 world = deformWorld(inBase);\n" +
		"	gl_Position = vec4(world.x * worldToNdc.x + worldToNdc.z, world.y * worldToNdc.y + worldToNdc.w, 0.0, 1.0);\n" +
		"}\n"

// Glue pass-2 vertex shader: positions are NOT deformed here - they were written to the shared position
// buffer by pass 1 (already world, post-Y-flip). Each vertex reads its own deformed position and, when it
// is a glued seam vertex, its partner's, and applies the weld `own + (partner − own)·w·intensity` on the
// GPU (matching the CPU `applyGluesResolved`). Then projects. Non-glued verts pass through unchanged.
private val GLUE_VERTEX_SHADER =
	"#version 330 core\n" +
		"layout(location = 1) in vec2 inUv;\n" +
		"layout(location = 2) in int inPartnerIndex;\n" + // partner vertex's global index, or own when not glued
		"layout(location = 3) in int inGlueIndex;\n" + // which glue (for its per-pose intensity), or -1
		"layout(location = 4) in float inWeldWeight;\n" + // this vertex's weld weight (0 when not glued)
		"uniform vec4 worldToNdc;\n" +
		"uniform samplerBuffer positionBuffer;\n" + // RG = pass-1 deformed world positions, by global index
		"uniform int baseOffset;\n" + // this mesh's base index in positionBuffer
		"uniform float glueIntensity[$MAX_GLUES];\n" +
		"out vec2 vUv;\n" +
		"void main() {\n" +
		"	vUv = inUv;\n" +
		"	vec2 own = texelFetch(positionBuffer, baseOffset + gl_VertexID).rg;\n" +
		"	vec2 world = own;\n" +
		// Skip the partner read when the weld is a no-op: a zero per-pose intensity also flags an unposed
		// partner (set CPU-side), whose position-buffer region is uninitialised - so it is never read.
		"	if (inGlueIndex >= 0 && inWeldWeight != 0.0 && glueIntensity[inGlueIndex] != 0.0) {\n" +
		"		vec2 partner = texelFetch(positionBuffer, inPartnerIndex).rg;\n" +
		"		vec2 welded = own + (partner - own) * (inWeldWeight * glueIntensity[inGlueIndex]); if (welded.x == welded.x && welded.y == welded.y && distance(welded, own) < 100000.0) { world = welded; }\n" +
		"	}\n" +
		"	gl_Position = vec4(world.x * worldToNdc.x + worldToNdc.z, world.y * worldToNdc.y + worldToNdc.w, 0.0, 1.0);\n" +
		"}\n"

private const val FRAGMENT_SHADER =
	"""
	#version 330 core
	in vec2 vUv;
	out vec4 fragColor;
	uniform sampler2D atlas;
	uniform int useTexture;
	uniform vec4 drawColor;
	uniform float opacity;
	uniform int useMask;
	uniform sampler2D maskTexture;
	uniform vec2 viewportSize;
	uniform int invertMask;
	uniform float highlight;
	uniform vec3 highlightColor;
	void main() {
		vec4 base = (useTexture == 1) ? texture(atlas, vUv) : drawColor;
		float alpha = base.a * opacity;
		if (useMask == 1) {
			float coverage = texture(maskTexture, gl_FragCoord.xy / viewportSize).a;
			alpha *= (invertMask == 1) ? (1.0 - coverage) : coverage;
		}
		vec3 rgb = mix(base.rgb, highlightColor, highlight);
		fragColor = vec4(rgb * alpha, alpha);
	}
	"""

// Atlas-page underlay vertex shader (UV editor): emits the four corners of the page rectangle from
// gl_VertexID (no vertex buffer, only an empty VAO), directly in Y-up display / texel space - world
// X in [0, W], Y in [0, H] - with NO Cubism Y negation (unlike deformWorld: the page is placed straight
// into the already-Y-up display space).  The UVs carry the V-flip so atlas V=0 (the top texel row) lands
// at the top of the Y-up quad - corner (0, H) -> uv (0, 0) - matching UvDisplayMapping's displayY=(1-v)*H.
// Projects through the same worldToNdc affine as the puppet.
private val PAGE_VERTEX_SHADER =
	"#version 330 core\n" +
		"uniform vec4 worldToNdc;\n" + // (scaleX, scaleY, offsetX, offsetY) - the camera's world→NDC affine
		"uniform vec2 pageSize;\n" + // the atlas page size in texels (W, H)
		"out vec2 vUv;\n" +
		"void main() {\n" +
		"	float cornerX = float(gl_VertexID & 1);\n" + // 0,1,0,1 across the triangle strip
		"	float cornerY = float((gl_VertexID >> 1) & 1);\n" + // 0,0,1,1
		"	vec2 world = vec2(cornerX * pageSize.x, cornerY * pageSize.y);\n" +
		"	vUv = vec2(cornerX, 1.0 - cornerY);\n" + // V-flip: display top (Y=H) samples the atlas top row (v=0)
		"	gl_Position = vec4(world.x * worldToNdc.x + worldToNdc.z, world.y * worldToNdc.y + worldToNdc.w, 0.0, 1.0);\n" +
		"}\n"

private const val MAX_CORNERS = 16

/**
 * GPU-deforming puppet renderer (`:render`, GL 3.3 core). The keyform morph + deformer cascade run in the
 * vertex shader ([DEFORM_GLSL]); the CPU only [preparePose]s the cheap per-pose data. Glue (seam-welding
 * vertex pairs across two meshes) is a two-pass GPU step: pass 1 transform-feedback-deforms every
 * glue-involved mesh into one shared position buffer, pass 2 renders the visible glue meshes with a
 * shader that reads own + partner positions from that buffer (a texture buffer) and welds. Non-glue meshes
 * render in a single deform pass. Draws in Cubism render order with per-drawable opacity, blend, and masks.
 *
 * GPU 変形パペットレンダラ。モーフ＋カスケードは頂点シェーダ、グルーは 2 パス（TF 変形→ウェルド）で GPU 実行。
 */
class GlPuppetRenderer(
	private val model: PuppetModel,
	private val textures: PuppetTextures,
) : Renderer {
	private var programHandle = 0
	private var glueDeformProgram = 0
	private var glueProgram = 0

	// The atlas-page underlay program (UV editor) + its empty VAO: a static full-page quad reusing the
	// shared FRAGMENT_SHADER, drawn by [renderAtlasPage] instead of the posed puppet for a UV area.
	private var pageProgram = 0
	private var pageVao = 0

	// Rest-pose content extent, computed lazily and reused for contentBounds()/the fit fallback; re-armed
	// by updateModel/setShownDrawables so the framing follows geometry and visibility edits.
	private var minX = 0f
	private var minY = 0f
	private var spanX = 1f
	private var spanY = 1f
	private var bboxReady = false

	// The view to project through. null until setCamera; render() then fits contentBounds by default.
	private var currentCamera: ViewportCamera? = null

	// The last pose's prepared deform inputs, cached so picking can re-run the CPU deform on demand (at
	// click time, off the render thread) without re-doing it every frame. Immutable once built, so the
	// volatile reference is a safe publish to the UI thread that calls pickGeometry. null before first pose.
	@Volatile
	private var lastPoseInputs: PoseDeformInputs? = null

	// Drawables currently selected, tinted by the highlight uniform when drawn. Set from the UI thread
	// (a wholesale immutable-set swap, so a volatile reference is a safe publish); read on the render thread.
	@Volatile
	private var selectedIds: Set<DrawableId> = emptySet()

	// The color selected drawables are tinted toward (the selection highlight), fed from the editor settings
	// on the UI thread and read on the render thread. Defaults to the classic blue accent until the host
	// pushes the configured color. RGB, each 0..1; the immutable FloatArray swap makes the volatile
	// reference a safe publish.
	@Volatile
	private var highlightColor: FloatArray = floatArrayOf(0.20f, 0.55f, 1.0f)

	// The active (last-selected) drawable, tinted toward activeHighlightColor instead of highlightColor so
	// the primary target of a multi-selection reads apart from the rest. Set from the UI thread; null when
	// nothing is active (single selection, or an in-flight preview stroke with no active yet). Always a
	// member of selectedIds when non-null.
	@Volatile
	private var activeId: DrawableId? = null

	// The color the active drawable is tinted toward, fed from the editor settings alongside highlightColor.
	// Defaults to the edit-mode active green (#7DE400) until the host pushes the configured color. RGB, each
	// 0..1; the immutable FloatArray swap makes the volatile reference a safe publish.
	@Volatile
	private var activeHighlightColor: FloatArray = floatArrayOf(0.49f, 0.89f, 0.0f)

	// The last pose's resolved draw list (back-to-front; last = front), published for picking. This folds
	// in the parts/group hierarchy, so it is the authoritative front/back order — unlike the raw per-drawable
	// draw-order scalar. Render-thread-written, UI-thread-read; the immutable list swap is a safe publish.
	@Volatile
	private var lastDrawnOrder: List<DrawableId> = emptyList()

	// The canvas backdrop: the world-aligned grid drawn behind the puppet. Reuses the platform GpuRenderer
	// seam so desktop and Android share one implementation.
	private val background = GpuRenderer()

	// Framebuffer pixels per on-screen pixel. 1 = native; the offscreen service sets >1 when it supersamples,
	// so the grid line width scales to match and reads back at a constant on-screen size.
	private var gridPixelScale: Float = 1f

	// The grid backdrop colors, set from the editor theme via setGrid; defaults to a neutral grey grid so a
	// caller that never themes the backdrop is unchanged.
	private var gridColors: GridColors = GridColors.Classic

	// The per-document grid geometry (major line spacing in world units, and subdivisions per major cell),
	// set via setGrid.  Defaults keep an unconfigured caller on the built-in 100-unit / 10-subdivision grid.
	private var gridScale: Float = 100f
	private var gridSubdivisions: Int = 10

	private var maskFramebuffer = 0
	private var maskTexture = 0
	private var maskWidth = 0
	private var maskHeight = 0

	// Shared deformed-position buffer for glue: pass 1 writes every glue mesh's world positions here (at
	// its [GpuDrawable.glueBaseOffset]); pass 2 reads own/partner from it via [positionTbo]. 0 if no glue.
	private var globalPositionBuffer = 0
	private var positionTbo = 0
	private var glueIntensities = FloatArray(MAX_GLUES) { 1f }

	// Pass 1 only needs to re-deform the shared glue buffer when the pose changed; on a static pose its
	// contents are unchanged, so pass 2 reads them directly. Set by [setPose], consumed by [render]. Gating
	// here also confines the (necessary) glue write→read sync to pose-change frames (see [render]).
	private var glueBufferDirty = true

	/**
	 * A drawable resident on the GPU. Static: VAO (rest positions + UVs + indices, plus per-vertex glue
	 * attributes for a glue mesh), per-mesh delta texture, optional warp control-point texture, atlas,
	 * fallback color, blend mode, masks. [isGlueMesh] meshes are deformed in pass 1 (even index-less
	 * anchors, whose positions are weld partners) and rendered via the glue program in pass 2.
	 */
	private class GpuDrawable(
		val id: DrawableId,
		val vao: Int,
		val positionVbo: Int,
		val uvVbo: Int,
		val glueVbo: Int,
		val indexEbo: Int,
		val deltaTexture: Int,
		val vertexCount: Int,
		val indexCount: Int,
		val cpTexture: Int,
		val atlasTexture: Int,
		val color: FloatArray,
		val blendMode: BlendMode,
		val maskIds: List<DrawableId>,
		val invertMask: Boolean,
		val isGlueMesh: Boolean,
		val glueBaseOffset: Int,
	) {
		var corners: List<WeightedCell>? = null
		var parentWorld: DeformerWorld? = null
		var opacity: Float = 1f
		var visible: Boolean = false
	}

	/**
	 * A mesh's uploaded GL handles: the VAO plus every buffer it references - the position VBO (attr 0,
	 * kept so an edit can re-upload it) and the UV / glue / index buffers (kept so a structural reconcile
	 * can free them; 0 where the mesh has none).
	 */
	private class MeshBuffers(
		val vao: Int,
		val positionVbo: Int,
		val uvVbo: Int,
		val glueVbo: Int,
		val indexEbo: Int,
	)

	// The live model used for the per-pose deform eval, the render order, and the base-position re-upload
	// diff. A var so an edit (a reorder, a deformer reparent, or a base-mesh move) can re-push it via
	// [updateModel]; a base-mesh move additionally re-uploads that drawable's position VBO there, while a
	// reorder / reparent leaves the GPU buffers untouched. The construction-time [model] still seeds the GPU
	// buffers at init (glue welds, masks, UVs, indices, deltas). @Volatile because the render thread writes it
	// (updateModel) while the UI thread reads it (pickGeometry) - a PuppetModel is immutable, so the reference
	// swap is a safe publish, mirroring lastPoseInputs / selectedIds.
	@Volatile
	private var currentModel: PuppetModel = model
	private var baseOrder: List<DrawableId> = model.drawables.map { it.id }
	private var currentRenderRoot: RenderGroup = model.renderRoot

	// Effective Parts-panel visibility (own eyeball ∧ every ancestor part's). The eyeball is an authoring
	// toggle, not animated, so the cascade is resolved once per change rather than per frame. Gates only
	// the drawn list (pass 2); hidden meshes that are mask sources or glue partners still deform. A var (not
	// val) so a visibility edit can re-push the recomputed shown set via [setShownDrawables]; the geometry
	// buffers are unchanged by a visibility toggle, so only this filter set moves. Render-thread only.
	private var shownDrawableIds: Set<DrawableId> = model.visibleDrawableIds()
	private var gpuDrawables: List<GpuDrawable> = emptyList()
	private var glueDeformList: List<GpuDrawable> = emptyList()
	private var gpuById: Map<DrawableId, GpuDrawable> = emptyMap()

	// The uploaded atlas pages' GL handles, index-parallel to [PuppetTextures.atlases].  Retained past
	// initGl so the structural reconcile in [updateModel] can bind a newly-uploaded drawable to its page.
	private var atlasHandles: List<Int> = emptyList()

	private val cornerCellBuffer = BufferUtils.createIntBuffer(MAX_CORNERS)
	private val cornerWeightBuffer = BufferUtils.createFloatBuffer(MAX_CORNERS)

	// Reusable scratch for re-uploading an edited mesh's positions (glBufferSubData in updateModel). Grown on
	// demand so a live preview edit (~60/s, possibly several drawables) never allocates. Render-thread only.
	private var positionUploadBuffer: FloatBuffer = BufferUtils.createFloatBuffer(0)

	/**
	 * Compiles the three programs, uploads the atlas page(s), lays out the shared glue position buffer, and
	 * uploads each drawable's static data (rest positions + UVs + indices + delta texture, plus glue
	 * attributes + a control-point texture as needed). Must run with a GL context current.
	 */
	fun initGl() {
		programHandle = linkProgram(VERTEX_SHADER, FRAGMENT_SHADER.trimIndent())
		glueDeformProgram = linkTransformFeedbackProgram()
		glueProgram = linkProgram(GLUE_VERTEX_SHADER, FRAGMENT_SHADER.trimIndent())
		pageProgram = linkProgram(PAGE_VERTEX_SHADER, FRAGMENT_SHADER.trimIndent())
		pageVao = GL30.glGenVertexArrays() // core profile requires a bound VAO even for the attribute-less page quad
		atlasHandles = textures.atlases.map { uploadAtlas(it) }
		val warpDeformerIds = model.deformers.filterIsInstance<Deformer.Warp>().map { it.id }.toSet()

		// Glue addressing: every mesh in any glue pair (incl. zero-triangle anchors) gets a region in the
		// shared position buffer, plus per-vertex weld attributes (partner global index, glue index, weight).
		val glueMeshIds = HashSet<DrawableId>()
		for (glue in model.glues) {
			glueMeshIds.add(glue.meshA)
			glueMeshIds.add(glue.meshB)
		}
		val glueBaseOffsetById = HashMap<DrawableId, Int>()
		var globalVertexCount = 0
		for (drawable in model.drawables) {
			if (drawable.id !in glueMeshIds) {
				continue
			}
			glueBaseOffsetById[drawable.id] = globalVertexCount
			globalVertexCount += (drawable.mesh?.positions?.size ?: 0) / 2
		}
		val glueAttrById = buildGlueAttributes(glueMeshIds, glueBaseOffsetById)
		if (globalVertexCount > 0) {
			globalPositionBuffer = GL15.glGenBuffers()
			GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, globalPositionBuffer)
			GL15.glBufferData(
				GL31.GL_TEXTURE_BUFFER,
				globalVertexCount.toLong() * 2 * Float.SIZE_BYTES,
				GL15.GL_DYNAMIC_COPY,
			)
			positionTbo = GL11.glGenTextures()
			GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, positionTbo)
			GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RG32F, globalPositionBuffer)
		}

		val uploaded = ArrayList<GpuDrawable>()
		for (drawable in model.drawables) {
			val isGlue = drawable.id in glueMeshIds
			val gpuDrawable =
				uploadDrawable(
					drawable = drawable,
					isGlue = isGlue,
					glueAttr = glueAttrById[drawable.id],
					glueBaseOffset = glueBaseOffsetById[drawable.id] ?: 0,
					warpDeformerIds = warpDeformerIds,
				) ?: continue
			uploaded.add(gpuDrawable)
		}
		gpuById = uploaded.associateBy { it.id }
		glueDeformList = uploaded.filter { it.isGlueMesh }
	}

	/**
	 * Uploads one drawable's static GPU data (mesh buffers, delta texture, optional warp control-point
	 * texture, atlas binding) and returns its resident [GpuDrawable], or null when it has nothing to
	 * upload (no mesh, no keyforms, empty geometry, or a triangle-less non-glue mesh).  Shared by
	 * [initGl] and the structural reconcile in [updateModel]; must run with the GL context current.
	 *
	 * @param Drawable drawable The model drawable to upload.
	 * @param Boolean isGlue Whether it participates in a glue weld (glue attrs + the weld program path).
	 * @param ByteBuffer? glueAttr Its per-vertex glue attributes, or null when not glued.
	 * @param Int glueBaseOffset Its base index in the shared glue position buffer (0 when not glued).
	 * @param Set<DeformerId> warpDeformerIds The model's warp deformers (a warp-parented drawable gets a
	 *   control-point texture).
	 * @return GpuDrawable? The uploaded drawable, or null when it draws nothing.
	 */
	private fun uploadDrawable(
		drawable: Drawable,
		isGlue: Boolean,
		glueAttr: ByteBuffer?,
		glueBaseOffset: Int,
		warpDeformerIds: Set<DeformerId>,
	): GpuDrawable? {
		val mesh = drawable.mesh ?: return null
		val grid = drawable.keyforms ?: return null
		if (mesh.positions.isEmpty()) {
			return null
		}
		val renderable = mesh.indices.isNotEmpty()
		if (!isGlue && !renderable) {
			return null // a non-glue mesh with no triangles draws nothing and is no weld partner
		}
		val cellCount = maxOf(1, grid.axes.fold(1) { count, axis -> count * axis.keys.size })
		val deltaTexture = uploadDeltaTexture(grid, mesh.positions, cellCount)
		val meshBuffers = uploadMesh(mesh.positions, mesh.uvs, mesh.indices, glueAttr)
		val cpTexture = if (drawable.parentDeformerId in warpDeformerIds) GL11.glGenTextures() else 0
		// The atlas mapping is keyed by the SOURCE format's drawable ids, so a session-created copy
		// resolves its page through the drawable it was duplicated from (Drawable.textureSourceId).
		val atlasIndex = textures.atlasIndexByDrawableId[(drawable.textureSourceId ?: drawable.id).raw]
		return GpuDrawable(
			id = drawable.id,
			vao = meshBuffers.vao,
			positionVbo = meshBuffers.positionVbo,
			uvVbo = meshBuffers.uvVbo,
			glueVbo = meshBuffers.glueVbo,
			indexEbo = meshBuffers.indexEbo,
			deltaTexture = deltaTexture,
			vertexCount = mesh.positions.size / 2,
			indexCount = mesh.indices.size,
			cpTexture = cpTexture,
			atlasTexture = atlasIndex?.let { atlasHandles[it] } ?: 0,
			color = colorFor(drawable.id.raw),
			blendMode = drawable.blendMode,
			maskIds = drawable.maskedBy,
			invertMask = drawable.invertMask,
			isGlueMesh = isGlue,
			glueBaseOffset = glueBaseOffset,
		)
	}

	/**
	 * Frees one resident drawable's GPU objects: the VAO, its position / UV / glue / index buffers, and
	 * the delta + control-point textures.  The atlas texture is shared across drawables and stays.  Must
	 * run with the GL context current.
	 *
	 * @param GpuDrawable gpuDrawable The resident drawable to free.
	 */
	private fun deleteDrawable(gpuDrawable: GpuDrawable) {
		GL30.glDeleteVertexArrays(gpuDrawable.vao)
		GL15.glDeleteBuffers(gpuDrawable.positionVbo)
		GL15.glDeleteBuffers(gpuDrawable.uvVbo)
		if (gpuDrawable.glueVbo != 0) {
			GL15.glDeleteBuffers(gpuDrawable.glueVbo)
		}
		if (gpuDrawable.indexEbo != 0) {
			GL15.glDeleteBuffers(gpuDrawable.indexEbo)
		}
		GL11.glDeleteTextures(gpuDrawable.deltaTexture)
		if (gpuDrawable.cpTexture != 0) {
			GL11.glDeleteTextures(gpuDrawable.cpTexture)
		}
	}

	/**
	 * Builds the per-vertex glue attribute buffers (12 bytes/vertex: int partner global index, int glue
	 * index, float weld weight). A non-glued vertex points at itself with weight 0 (a no-op weld). For each
	 * glue pair, mesh A's vertex points at mesh B's (weight wA) and vice-versa.
	 *
	 * @param Set glueMeshIds        Meshes participating in any glue.
	 * @param Map glueBaseOffsetById Each glue mesh's base index in the shared position buffer.
	 * @return Map DrawableId → its per-vertex glue attribute byte buffer.
	 */
	private fun buildGlueAttributes(
		glueMeshIds: Set<DrawableId>,
		glueBaseOffsetById: Map<DrawableId, Int>,
	): Map<DrawableId, ByteBuffer> {
		val byId = HashMap<DrawableId, ByteBuffer>()
		for (id in glueMeshIds) {
			val base = glueBaseOffsetById[id] ?: continue
			val drawable = model.drawables.firstOrNull { it.id == id } ?: continue
			val vertexCount = (drawable.mesh?.positions?.size ?: 0) / 2
			val buffer = BufferUtils.createByteBuffer(vertexCount * 3 * Int.SIZE_BYTES)
			for (vertexIndex in 0 until vertexCount) {
				buffer.putInt(base + vertexIndex) // partner = self
				buffer.putInt(-1) // not glued
				buffer.putFloat(0f) // weld weight
			}
			byId[id] = buffer
		}
		for ((glueIndex, glue) in model.glues.withIndex()) {
			val baseA = glueBaseOffsetById[glue.meshA] ?: continue
			val baseB = glueBaseOffsetById[glue.meshB] ?: continue
			val attrA = byId[glue.meshA] ?: continue
			val attrB = byId[glue.meshB] ?: continue
			for (pair in glue.pairs) {
				writeGlueAttr(attrA, pair.indexA, baseB + pair.indexB, glueIndex, pair.weightA)
				writeGlueAttr(attrB, pair.indexB, baseA + pair.indexA, glueIndex, pair.weightB)
			}
		}
		byId.values.forEach { it.flip() }
		return byId
	}

	/** Writes one vertex's glue attribute (partner global index, glue index, weld weight) at its slot. */
	private fun writeGlueAttr(
		buffer: ByteBuffer,
		vertexIndex: Int,
		partnerGlobalIndex: Int,
		glueIndex: Int,
		weight: Float,
	) {
		val slot = vertexIndex * 3 * Int.SIZE_BYTES
		buffer.putInt(slot, partnerGlobalIndex)
		buffer.putInt(slot + Int.SIZE_BYTES, glueIndex)
		buffer.putFloat(slot + 2 * Int.SIZE_BYTES, weight)
	}

	override fun setPose(parameters: Map<ParameterId, Float>) {
		// currentModel (not the construction-time model) so a deformer reparent / reorder re-evaluates here.
		val inputs = preparePose(currentModel, parameters)
		lastPoseInputs = inputs // publish for on-demand picking (CPU deform re-run at click time)
		glueBufferDirty = true // the pose moved: pass 1 must re-deform the shared glue buffer next render
		for (gpuDrawable in gpuById.values) {
			gpuDrawable.visible = false
		}
		val drawOrderById = HashMap<DrawableId, Float>(inputs.drawables.size)
		for (drawableInputs in inputs.drawables) {
			val gpuDrawable = gpuById[drawableInputs.drawableId] ?: continue
			val corners = drawableInputs.corners
			if (corners == null || (drawableInputs.isParented && drawableInputs.parentWorld == null)) {
				continue
			}
			gpuDrawable.corners = corners
			val parentWorld = drawableInputs.parentWorld
			gpuDrawable.parentWorld = parentWorld
			// Warp control points are pose-dependent but frame-INVARIANT: upload them here, once per pose
			// change, NOT every frame in the draw loop. Re-specifying this texture (glTexImage2D) 60×/sec
			// churned the d3d12/Mesa driver and progressively corrupted the sampled control points (the
			// "facial features warp/flicker over time" bug, worst on masked warp meshes that draw twice).
			if (parentWorld is WarpWorld && gpuDrawable.cpTexture != 0) {
				uploadControlPoints(gpuDrawable.cpTexture, parentWorld)
			}
			gpuDrawable.opacity = drawableInputs.opacity
			gpuDrawable.visible = true
			drawOrderById[drawableInputs.drawableId] = drawableInputs.drawOrder
		}
		// Glue weld intensity per pose, gated on BOTH meshes being posed this frame. Pass 1 only writes a
		// posed glue mesh's region of the shared position buffer; if a partner is unposed its region is
		// uninitialised, so welding to it would read garbage (random spikes on a live, dirty GPU). Zeroing
		// the intensity makes the pass-2 shader skip the partner read entirely - matching the CPU
		// `applyGluesResolved`, which skips a glue whose partner produced no geometry.
		for ((glueIndex, glue) in inputs.glues.withIndex()) {
			if (glueIndex >= MAX_GLUES) {
				continue
			}
			val bothPosed = gpuById[glue.meshA]?.visible == true && gpuById[glue.meshB]?.visible == true
			glueIntensities[glueIndex] = if (bothPosed) glue.intensity else 0f
		}
		// Hierarchical render order over the draw-order group tree (with per-pose animated part order); flat
		// base order if the model carries no groups.
		val ordered =
			if (currentRenderRoot.children.isEmpty()) {
				paintOrder(baseOrder, drawOrderById)
			} else {
				renderOrder(currentRenderRoot, drawOrderById, inputs.partDrawOrders)
			}
		gpuDrawables =
			ordered
				.mapNotNull { gpuById[it] }
				.filter { it.visible && it.indexCount > 0 && it.id in shownDrawableIds }
		lastDrawnOrder = gpuDrawables.map { it.id } // publish the resolved back-to-front order for picking
	}

	override fun setCamera(camera: ViewportCamera) {
		currentCamera = camera
	}

	/**
	 * Sets how many framebuffer pixels map to one on-screen pixel, so the grid line width drawn into the
	 * framebuffer stays a constant on-screen size when the offscreen service renders supersampled and
	 * downscales the result. 1 = native, 2 = 2× supersampled.
	 *
	 * @param Float scale Framebuffer pixels per on-screen pixel.
	 */
	fun setRenderScale(scale: Float) {
		gridPixelScale = scale
	}

	/**
	 * Sets the grid backdrop's colors and geometry, so the viewport can follow the editor theme (a dark
	 * scheme gets a dark grid) and the per-document grid config.  The next [render] picks them up.
	 *
	 * グリッド背景の色と間隔（主線間隔・分割数）を設定する（テーマ / ドキュメント連動）。次の render で反映。
	 *
	 * @param GridColors colors      The background / major / minor grid colors.
	 * @param Float      scale       The major grid line spacing in world units.
	 * @param Int        subdivisions The minor lines per major cell.
	 */
	fun setGrid(colors: GridColors, scale: Float, subdivisions: Int) {
		gridColors = colors
		gridScale = scale
		gridSubdivisions = subdivisions
	}

	/**
	 * Sets which drawables are highlighted (object-mode selection). The next [render] tints them via the
	 * fragment shader's highlight uniform. Kept concrete (not on [Renderer]) like [setGrid],
	 * since selection is an editor concern the desktop host drives directly.
	 *
	 * ハイライトするドロウアブル（選択）を設定する。次の render で反映される。
	 *
	 * @param Set<DrawableId> ids The selected drawable ids.
	 */
	fun setSelection(ids: Set<DrawableId>) {
		selectedIds = ids
	}

	/**
	 * Sets which drawable is active (the last-selected object of a multi-selection). The next [render] tints
	 * it toward [activeHighlightColor] rather than the shared [highlightColor], so the primary target reads
	 * apart from the rest. Null clears the distinction (every selected drawable tints plain). Kept concrete
	 * (not on [Renderer]) like [setSelection], since selection is an editor concern the desktop host drives.
	 *
	 * アクティブ（最後に選択した）ドロウアブルを設定する。次の render でアクティブ色に反映される。
	 *
	 * @param DrawableId? id The active drawable id, or null when none is active.
	 */
	fun setActiveSelection(id: DrawableId?) {
		activeId = id
	}

	/**
	 * Updates the set of drawables that are actually drawn (the resolved Parts-panel visibility cascade),
	 * so a visibility edit takes effect on the next [render]. The geometry buffers are unchanged by a
	 * visibility toggle, so this only swaps the pass-2 draw filter. Concrete (not on [Renderer]) like
	 * [setSelection], pushed by the desktop host when the model's visibility changes.
	 *
	 * 描画される drawable の集合（表示カスケードの解決結果）を更新する。次の render で反映される。
	 *
	 * @param Set ids The drawable ids to draw.
	 */
	fun setShownDrawables(ids: Set<DrawableId>) {
		if (ids != shownDrawableIds) {
			// The bbox skips hidden drawables, so a visibility change invalidates the cached framing.
			bboxReady = false
		}
		shownDrawableIds = ids
	}

	// Whether render() draws the world-origin axis lines between the grid backdrop and the drawables.
	// Off by default so headless render-diff tests (GPU vs the CPU oracle) stay line-free; the editor's
	// viewport host opts in.
	private var worldAxesVisible = false

	/**
	 * Shows or hides the world-origin axis lines (the red X / blue Z cross at the model's world origin,
	 * drawn behind the puppet). Concrete (not on [Renderer]) like [setShownDrawables] - an editor
	 * affordance the viewport host opts into, not part of the model render itself.
	 *
	 * ワールド原点の軸線の表示を切り替える（パペットの背面、エディタ用）。
	 *
	 * @param Boolean visible True to draw the axes each frame.
	 */
	fun setWorldAxesVisible(visible: Boolean) {
		worldAxesVisible = visible
	}

	/**
	 * Reconciles the renderer with the current model after an edit.  Four tiers, cheapest first:
	 * a layer reorder / reparent re-sorts the draw order and deform chain on the next [setPose] with no
	 * buffer work; a base-mesh move re-uploads the changed drawables' position VBOs in place; a UV edit
	 * (the UV editor's G / S / R and Mirror retarget which atlas texels the mesh samples, with the
	 * topology unchanged) re-uploads the UV VBOs in place the same way; and a STRUCTURAL change - a
	 * drawable added (Object-mode duplicate), removed (its undo), or remeshed (merge / rip / connect /
	 * element duplicate change the vertex count, indices, or keyform grid) - frees the stale resident
	 * drawable and re-uploads it whole through [uploadDrawable].
	 * Concrete (not on [Renderer]) like [setShownDrawables], pushed by the desktop host on model change.
	 *
	 * Structural limits: a session-created drawable never joins the load-time glue layout (glues
	 * reference source ids, so a fresh id welds nothing - correct for duplicates), and a REMESHED glue
	 * mesh degrades to an unwelded draw: the shared-buffer regions and per-vertex weld attrs index the
	 * old vertex order and cannot be remapped here (merge-across-glue is not yet supported; its partner
	 * keeps welding toward the region's last-deformed positions).
	 *
	 * Runs on the render thread with the GL context current (the render loop is the only caller), so the
	 * uploads are direct GL calls. The diff is keyed by drawable id (robust to a simultaneous reorder)
	 * and by array reference (copy-on-write leaves an unedited drawable's arrays untouched, so it is
	 * skipped cheaply); it compares against [currentModel] and therefore must run BEFORE the
	 * reassignment, keeping the invariant "GPU buffer contents === currentModel's arrays".
	 *
	 * 編集後にレンダラを現在のモデルへ整合させる。位置のみの編集は VBO を差し替え、トポロジ変更や
	 * 複製・削除はドロウアブル単位で GPU 資源を再アップロードする。
	 *
	 * @param PuppetModel newModel The current model.
	 */
	fun updateModel(newModel: PuppetModel) {
		val oldDrawableById = currentModel.drawables.associateBy { it.id }
		val warpDeformerIds = newModel.deformers.filterIsInstance<Deformer.Warp>().map { it.id }.toSet()
		val reconciled = LinkedHashMap<DrawableId, GpuDrawable>()
		for (drawable in newModel.drawables) {
			val existing = gpuById[drawable.id]
			val newMesh = drawable.mesh
			if (existing == null) {
				// A drawable the GPU has never seen (an Object-mode duplicate, or one skipped at load for
				// having no geometry): upload it whole.  Never part of the load-time glue layout.
				val uploadedDrawable =
					uploadDrawable(drawable, isGlue = false, glueAttr = null, glueBaseOffset = 0, warpDeformerIds = warpDeformerIds)
				if (uploadedDrawable != null) {
					reconciled[drawable.id] = uploadedDrawable
				}
				continue
			}
			val oldMesh = oldDrawableById[drawable.id]?.mesh
			val oldKeyforms = oldDrawableById[drawable.id]?.keyforms
			val remeshed =
				newMesh != null &&
					(
						newMesh.positions.size != existing.vertexCount * 2 ||
							(oldMesh != null && newMesh.indices !== oldMesh.indices) ||
							drawable.keyforms !== oldKeyforms
					)
			if (remeshed) {
				// A topology edit: the VAO / EBO / UV buffer / delta texture are all stale, so free the
				// resident drawable and re-upload it against the new mesh + re-strided keyform grid.  A
				// remeshed glue mesh comes back unwelded (see the docblock's structural limits).
				deleteDrawable(existing)
				val uploadedDrawable =
					uploadDrawable(drawable, isGlue = false, glueAttr = null, glueBaseOffset = 0, warpDeformerIds = warpDeformerIds)
				if (uploadedDrawable != null) {
					reconciled[drawable.id] = uploadedDrawable
				}
				continue
			}
			reconciled[drawable.id] = existing
			if (newMesh == null || oldMesh == null) {
				continue
			}
			// Positions-only tier: re-upload the VBO in place when the array instance changed.
			val newPositions = newMesh.positions
			if (newPositions !== oldMesh.positions) {
				if (positionUploadBuffer.capacity() < newPositions.size) {
					positionUploadBuffer = BufferUtils.createFloatBuffer(newPositions.size)
				}
				positionUploadBuffer.clear()
				positionUploadBuffer.put(newPositions).flip()
				GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, existing.positionVbo)
				GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, positionUploadBuffer)
			}
			// UVs-only tier: a UV edit retargets the sampled atlas texels with the topology unchanged, so
			// only the UV VBO is stale - re-upload it in place (the scratch buffer is shared with the
			// positions tier; uploads are sequential on this thread).  The length guard is defensive: the
			// copy-on-write edit path (withMeshUvs) never changes the array length, so a mismatch can only
			// mean a mesh this renderer padded at upload (uploadMesh's safeUvs) - leave that resident alone.
			val newUvs = newMesh.uvs
			if (newUvs !== oldMesh.uvs && newUvs.size == existing.vertexCount * 2) {
				if (positionUploadBuffer.capacity() < newUvs.size) {
					positionUploadBuffer = BufferUtils.createFloatBuffer(newUvs.size)
				}
				positionUploadBuffer.clear()
				positionUploadBuffer.put(newUvs).flip()
				GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, existing.uvVbo)
				GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, positionUploadBuffer)
			}
		}
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0)
		// Free residents whose drawable the edit removed (the undo of a duplicate).
		for ((drawableId, gpuDrawable) in gpuById) {
			if (drawableId !in reconciled) {
				deleteDrawable(gpuDrawable)
			}
		}
		gpuById = reconciled
		glueDeformList = reconciled.values.filter { it.isGlueMesh }
		currentModel = newModel
		currentRenderRoot = newModel.renderRoot
		baseOrder = newModel.drawables.map { it.id }
		// A base-mesh edit can grow or shrink the content extent; recompute the framing on next query so
		// view.fit follows the geometry instead of the bounds frozen at open.
		bboxReady = false
	}

	/**
	 * Sets the color selected drawables are tinted toward (the selection highlight). The next [render]
	 * applies it through the fragment shader's highlightColor uniform. Concrete (not on [Renderer]) like
	 * [setSelection], since the highlight color is an editor setting the desktop host drives directly.
	 *
	 * 選択ハイライトの色を設定する。次の render で反映される。
	 *
	 * @param Float red   The tint red,   0..1.
	 * @param Float green The tint green, 0..1.
	 * @param Float blue  The tint blue,  0..1.
	 */
	fun setSelectionHighlightColor(red: Float, green: Float, blue: Float) {
		highlightColor = floatArrayOf(red, green, blue)
	}

	/**
	 * Sets the color the active drawable is tinted toward (the active-selection highlight). The next [render]
	 * applies it through the fragment shader's highlightColor uniform for the active drawable only. Concrete
	 * (not on [Renderer]) like [setSelectionHighlightColor], since it is an editor setting the host drives.
	 *
	 * アクティブ選択ハイライトの色を設定する。次の render でアクティブなドロウアブルに反映される。
	 *
	 * @param Float red   The tint red,   0..1.
	 * @param Float green The tint green, 0..1.
	 * @param Float blue  The tint blue,  0..1.
	 */
	fun setActiveSelectionHighlightColor(red: Float, green: Float, blue: Float) {
		activeHighlightColor = floatArrayOf(red, green, blue)
	}

	/**
	 * Evaluates the current pose's deformed world geometry on the CPU for hit-testing (picking), or null
	 * before the first pose. Pure CPU with no GL calls, so it is safe to call from the UI thread; it reuses
	 * the immutable per-pose inputs cached by the last [setPose] rather than re-deforming every frame.
	 *
	 * ピッキング用に現在ポーズの変形ジオメトリを CPU 評価する（GL 不使用、UI スレッドから安全に呼べる）。
	 *
	 * @return DeformedGeometry The current deformed geometry, or null before the first pose.
	 */
	fun pickGeometry(): DeformedGeometry? {
		val inputs = lastPoseInputs ?: return null
		// currentModel (a @Volatile safe publish), not the construction-time model, so picking / centroids
		// follow a base-mesh edit; inputs were prepared from currentModel by the last setPose, so the base
		// positions applyCpuDeform reads here are consistent with them.
		return applyCpuDeform(currentModel, inputs)
	}

	/**
	 * The last frame's resolved draw order (back-to-front; last = front), or empty before the first pose.
	 * This is the hierarchy-correct front/back ranking picking uses to choose among overlapping meshes —
	 * the raw per-drawable draw-order scalar ignores the parts/group hierarchy and is wrong for it.
	 *
	 * 最後のフレームの解決済み描画順（背面→前面）。ピッキングの前面判定に使う。
	 *
	 * @return List<DrawableId> The drawn drawables, back-to-front.
	 */
	fun drawnOrder(): List<DrawableId> = lastDrawnOrder

	override fun contentBounds(): ContentBounds {
		ensureContentBounds()
		return ContentBounds(minX, minY, spanX, spanY)
	}

	override fun render(viewportWidth: Int, viewportHeight: Int) {
		val targetFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING)
		ensureMaskTarget(viewportWidth, viewportHeight)

		// Pass 1: transform-feedback-deform every visible glue mesh into the shared position buffer. Only when
		// the pose changed - a static pose leaves the buffer (and pass 2's reads of it) unchanged.
		if (globalPositionBuffer != 0 && glueBufferDirty) {
			GL20.glUseProgram(glueDeformProgram)
			GL20.glUniform1i(GL20.glGetUniformLocation(glueDeformProgram, "deltaTex"), UNIT_DELTA)
			GL20.glUniform1i(GL20.glGetUniformLocation(glueDeformProgram, "cpTex"), UNIT_CP)
			GL11.glEnable(GL30.GL_RASTERIZER_DISCARD)
			for (gpuDrawable in glueDeformList) {
				if (gpuDrawable.corners == null) {
					continue
				}
				setDeformUniforms(glueDeformProgram, gpuDrawable)
				GL30.glBindVertexArray(gpuDrawable.vao)
				GL30.glBindBufferRange(
					GL30.GL_TRANSFORM_FEEDBACK_BUFFER,
					0,
					globalPositionBuffer,
					gpuDrawable.glueBaseOffset.toLong() * 2 * Float.SIZE_BYTES,
					gpuDrawable.vertexCount.toLong() * 2 * Float.SIZE_BYTES,
				)
				GL30.glBeginTransformFeedback(GL11.GL_POINTS)
				GL11.glDrawArrays(GL11.GL_POINTS, 0, gpuDrawable.vertexCount)
				GL30.glEndTransformFeedback()
			}
			GL11.glDisable(GL30.GL_RASTERIZER_DISCARD)
			// Pass 2 reads this buffer back through the position TBO. The WSL d3d12/Mesa stack does not reliably
			// order the transform-feedback writes before that texture-buffer read, so pass 2 can sample a
			// half-written buffer → garbage vertex welds while a parameter moves. Force the
			// writes to complete first. glFinish (full sync) is the reliable fix: transform-feedback writes are
			// coherent-pipeline writes, outside the scope of glMemoryBarrier's incoherent-store barriers, so
			// a memory barrier would not order them. Gated on [glueBufferDirty], this runs only on pose-change
			// frames, so a static/idle pose pays nothing.
			GL11.glFinish()
			glueBufferDirty = false
		}

		// Pass 2: render. Non-glue meshes deform in-shader; glue meshes read pass-1 positions + weld.
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, targetFramebuffer)
		GL11.glViewport(0, 0, viewportWidth, viewportHeight)
		// The grid is an opaque full-screen fill, so it both clears and paints the backdrop - no flat clear.
		val camera = effectiveCamera(viewportWidth, viewportHeight)
		val transform = camera.worldToNdc(viewportWidth, viewportHeight)
		drawGrid(transform, currentModel.worldOriginX, currentModel.worldOriginY, gridScale, gridScale, viewportWidth, viewportHeight)
		if (worldAxesVisible) {
			// The world-origin axes sit between the backdrop and the drawables, so they read as part of the
			// canvas (behind the puppet) rather than an overlay.
			background.axisLines(
				originNdcX = transform[0] * currentModel.worldOriginX + transform[2],
				originNdcY = transform[1] * currentModel.worldOriginY + transform[3],
			)
		}
		GL11.glEnable(GL11.GL_BLEND)
		var boundProgram = 0
		for (gpuDrawable in gpuDrawables) {
			boundProgram = useProgramFor(gpuDrawable.isGlueMesh, boundProgram, transform, viewportWidth, viewportHeight)
			val masked = gpuDrawable.maskIds.isNotEmpty()
			if (masked) {
				renderMaskCoverage(gpuDrawable.maskIds)
				boundProgram = useProgramFor(gpuDrawable.isGlueMesh, 0, transform, viewportWidth, viewportHeight)
				GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, targetFramebuffer)
				GL11.glViewport(0, 0, viewportWidth, viewportHeight)
				GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT_MASK)
				GL11.glBindTexture(GL11.GL_TEXTURE_2D, maskTexture)
			}
			GL20.glUniform1i(uniform(boundProgram, "useMask"), if (masked) 1 else 0)
			GL20.glUniform1i(uniform(boundProgram, "invertMask"), if (masked && gpuDrawable.invertMask) 1 else 0)
			applyBlendMode(gpuDrawable.blendMode)
			val isActive = activeId != null && gpuDrawable.id == activeId
			val highlight = if (isActive || gpuDrawable.id in selectedIds) SELECTION_TINT_STRENGTH else 0f
			drawDrawable(boundProgram, gpuDrawable, gpuDrawable.opacity, highlight, isActive)
		}
		GL30.glBindVertexArray(0)
		GL20.glUseProgram(0)
	}

	/**
	 * Renders one atlas page as a flat, upright underlay for a UV-editor area (instead of the posed
	 * puppet): the themed grid backdrop, then the whole page as a single textured quad projected
	 * through the area camera.  Concrete (not on [Renderer]) like the other editor affordances, since it
	 * reuses this renderer's private atlas textures, grid pass, and blend.  Draws into the currently
	 * bound framebuffer, so the offscreen service's supersample/resolve/read-back tail is reused verbatim.
	 *
	 * The page samples the SAME [atlasHandles] texture the puppet does, through the same premultiplied
	 * FRAGMENT_SHADER, so the underlay matches the puppet's texel rendering exactly (no CPU un-premultiply).
	 * A null or out-of-range [pageIndex] (an untextured active drawable) paints the grid only.
	 *
	 * @param Int pageIndex The atlas page to draw (index into [PuppetTextures.atlases]), or null for none.
	 * @param Int viewportWidth  The target framebuffer width in pixels.
	 * @param Int viewportHeight The target framebuffer height in pixels.
	 */
	fun renderAtlasPage(pageIndex: Int?, viewportWidth: Int, viewportHeight: Int) {
		val targetFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING)
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, targetFramebuffer)
		GL11.glViewport(0, 0, viewportWidth, viewportHeight)
		// The grid is an opaque full-screen fill, so it both clears and paints the backdrop - no flat clear.
		val camera = effectiveCamera(viewportWidth, viewportHeight)
		val transform = camera.worldToNdc(viewportWidth, viewportHeight)
		val page = pageIndex?.let { textures.atlases.getOrNull(it) }
		// The UV grid's major lines fall on the unit atlas tile (UV integers), so the major spacing is the
		// page's pixel extent (UV 0..1 maps to 0..pageSize in this page-pixel world space); minor lines
		// subdivide the tile.  With no page, fall back to the square world grid so the backdrop still reads.
		val majorSpacingX = page?.width?.toFloat() ?: gridScale
		val majorSpacingY = page?.height?.toFloat() ?: gridScale
		// The UV grid's unit tile starts at the page origin (UV 0,0 = page-pixel 0,0), so anchor at (0, 0).
		drawGrid(transform, 0f, 0f, majorSpacingX, majorSpacingY, viewportWidth, viewportHeight)
		if (pageIndex == null || page == null) {
			return
		}
		val handle = atlasHandles.getOrNull(pageIndex) ?: return
		GL11.glEnable(GL11.GL_BLEND)
		applyBlendMode(BlendMode.Normal)
		GL20.glUseProgram(pageProgram)
		GL20.glUniform4f(uniform(pageProgram, "worldToNdc"), transform[0], transform[1], transform[2], transform[3])
		GL20.glUniform2f(uniform(pageProgram, "pageSize"), page.width.toFloat(), page.height.toFloat())
		GL20.glUniform1i(uniform(pageProgram, "atlas"), UNIT_ATLAS)
		GL20.glUniform1i(uniform(pageProgram, "useTexture"), 1)
		GL20.glUniform1i(uniform(pageProgram, "useMask"), 0)
		GL20.glUniform1f(uniform(pageProgram, "opacity"), 1f)
		GL20.glUniform1f(uniform(pageProgram, "highlight"), 0f)
		GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT_ATLAS)
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, handle)
		GL30.glBindVertexArray(pageVao)
		GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4)
		GL30.glBindVertexArray(0)
		GL20.glUseProgram(0)
	}

	/**
	 * Binds the program for a glue vs non-glue mesh (if not already bound) and sets its per-frame uniforms
	 * (projection, viewport, sampler units, and the glue intensities). Returns the now-bound program.
	 */
	private fun useProgramFor(
		isGlueMesh: Boolean,
		currentProgram: Int,
		transform: FloatArray,
		viewportWidth: Int,
		viewportHeight: Int,
	): Int {
		val program = if (isGlueMesh) glueProgram else programHandle
		if (program == currentProgram) {
			return program
		}
		GL20.glUseProgram(program)
		GL20.glUniform4f(uniform(program, "worldToNdc"), transform[0], transform[1], transform[2], transform[3])
		GL20.glUniform2f(uniform(program, "viewportSize"), viewportWidth.toFloat(), viewportHeight.toFloat())
		GL20.glUniform1i(uniform(program, "atlas"), UNIT_ATLAS)
		GL20.glUniform1i(uniform(program, "maskTexture"), UNIT_MASK)
		if (isGlueMesh) {
			GL20.glUniform1i(uniform(program, "positionBuffer"), UNIT_POSITION)
			GL20.glUniform1fv(uniform(program, "glueIntensity"), glueIntensities)
			GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT_POSITION)
			GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, positionTbo)
		} else {
			GL20.glUniform1i(uniform(program, "deltaTex"), UNIT_DELTA)
			GL20.glUniform1i(uniform(program, "cpTex"), UNIT_CP)
		}
		return program
	}

	private fun renderMaskCoverage(maskIds: List<DrawableId>) {
		val transform = worldToNdc(maskWidth, maskHeight)
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, maskFramebuffer)
		GL11.glViewport(0, 0, maskWidth, maskHeight)
		GL11.glClearColor(0f, 0f, 0f, 0f)
		GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
		applyBlendMode(BlendMode.Normal)
		var boundProgram = 0
		for (maskId in maskIds) {
			val mask = gpuById[maskId] ?: continue
			if (!mask.visible || mask.indexCount == 0) {
				continue
			}
			boundProgram = useProgramFor(mask.isGlueMesh, boundProgram, transform, maskWidth, maskHeight)
			GL20.glUniform1i(uniform(boundProgram, "useMask"), 0)
			drawDrawable(boundProgram, mask, 1f) // coverage of the mask's shape, ignoring its own opacity
		}
	}

	/**
	 * Sets a drawable's per-pose deform uniforms (active corners + baked parent transform) and binds its
	 * delta/warp textures, for [program] (the live deform program or the glue pass-1 TF program).
	 */
	private fun setDeformUniforms(program: Int, gpuDrawable: GpuDrawable) {
		val corners = gpuDrawable.corners ?: return
		val cornerCount = minOf(MAX_CORNERS, corners.size)
		cornerCellBuffer.clear()
		cornerWeightBuffer.clear()
		for (cornerIndex in 0 until cornerCount) {
			cornerCellBuffer.put(corners[cornerIndex].linearIndex)
			cornerWeightBuffer.put(corners[cornerIndex].weight)
		}
		cornerCellBuffer.flip()
		cornerWeightBuffer.flip()
		GL20.glUniform1i(uniform(program, "cornerCount"), cornerCount)
		GL20.glUniform1iv(uniform(program, "cornerCell"), cornerCellBuffer)
		GL20.glUniform1fv(uniform(program, "cornerWeight"), cornerWeightBuffer)
		when (val parentWorld = gpuDrawable.parentWorld) {
			is RotationWorld -> {
				val xform = parentWorld.xform
				GL20.glUniform1fv(
					uniform(program, "rot"),
					floatArrayOf(xform.c12, xform.c13, xform.c14, xform.c15, xform.ox, xform.oy),
				)
				GL20.glUniform1i(uniform(program, "parentType"), 1)
			}

			is WarpWorld -> {
				// Bind only - the control-point data was uploaded in setPose (see the note there). Binding
				// per draw is required because every warp mesh shares texture unit UNIT_CP.
				GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT_CP)
				GL11.glBindTexture(GL11.GL_TEXTURE_2D, gpuDrawable.cpTexture)
				GL20.glUniform1i(uniform(program, "warpCols"), parentWorld.cols)
				GL20.glUniform1i(uniform(program, "warpRows"), parentWorld.rows)
				GL20.glUniform1i(uniform(program, "warpBilinear"), if (parentWorld.bilinear) 1 else 0)
				GL20.glUniform1i(uniform(program, "parentType"), 2)
			}

			else -> GL20.glUniform1i(uniform(program, "parentType"), 0)
		}
		GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT_DELTA)
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, gpuDrawable.deltaTexture)
	}

	/**
	 * Binds a drawable's texture/color + opacity and issues its triangle draw, under [program]. A glue
	 * mesh ([GpuDrawable.isGlueMesh]) needs only its base offset (positions come from the shared buffer); a
	 * non-glue mesh needs its deform uniforms set first.
	 */
	private fun drawDrawable(program: Int, gpuDrawable: GpuDrawable, opacity: Float, highlight: Float = 0f, isActive: Boolean = false) {
		if (gpuDrawable.isGlueMesh) {
			GL20.glUniform1i(uniform(program, "baseOffset"), gpuDrawable.glueBaseOffset)
		} else {
			setDeformUniforms(program, gpuDrawable)
		}
		if (gpuDrawable.atlasTexture > 0) {
			GL20.glUniform1i(uniform(program, "useTexture"), 1)
			GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT_ATLAS)
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, gpuDrawable.atlasTexture)
		} else {
			GL20.glUniform1i(uniform(program, "useTexture"), 0)
			GL20.glUniform4f(
				uniform(program, "drawColor"),
				gpuDrawable.color[0],
				gpuDrawable.color[1],
				gpuDrawable.color[2],
				gpuDrawable.color[3],
			)
		}
		GL20.glUniform1f(uniform(program, "opacity"), opacity)
		GL20.glUniform1f(uniform(program, "highlight"), highlight)
		val tint = if (isActive) activeHighlightColor else highlightColor
		GL20.glUniform3f(uniform(program, "highlightColor"), tint[0], tint[1], tint[2])
		GL30.glBindVertexArray(gpuDrawable.vao)
		GL11.glDrawElements(GL11.GL_TRIANGLES, gpuDrawable.indexCount, GL11.GL_UNSIGNED_INT, 0L)
	}

	/**
	 * The camera to project through this frame: the one set by [setCamera], or - before any is set - a
	 * fit of the rest-pose content into the viewport (the default view).
	 *
	 * @param Int viewportWidth  Target width in pixels.
	 * @param Int viewportHeight Target height in pixels.
	 * @return ViewportCamera The effective camera.
	 */
	private fun effectiveCamera(viewportWidth: Int, viewportHeight: Int): ViewportCamera =
		currentCamera ?: ViewportCamera.fit(contentBounds(), viewportWidth, viewportHeight)

	/**
	 * The shader's world-to-NDC parameters for this frame, via the [effectiveCamera].
	 *
	 * @param Int viewportWidth  Target width in pixels.
	 * @param Int viewportHeight Target height in pixels.
	 * @return FloatArray The (scaleX, scaleY, offsetX, offsetY) affine.
	 */
	private fun worldToNdc(viewportWidth: Int, viewportHeight: Int): FloatArray =
		effectiveCamera(viewportWidth, viewportHeight).worldToNdc(viewportWidth, viewportHeight)

	/**
	 * Paints the world-aligned grid backdrop.  The grid lines land at world coordinates that are multiples
	 * of the spacing offset from ([originX], [originY]) - the world origin the axes cross and the grid snap
	 * rounds to - so a major line always passes through the axes and the lines mark the snap targets.  The
	 * line width scales with the supersample factor so it resolves to a constant on-screen width after the
	 * downscale.
	 *
	 * @param FloatArray worldToNdc     The (scaleX, scaleY, offsetX, offsetY) world-to-NDC affine.
	 * @param Float      originX        World x the lattice is anchored on.
	 * @param Float      originY        World y the lattice is anchored on.
	 * @param Float      majorSpacingX  Major grid line spacing along X, in world units.
	 * @param Float      majorSpacingY  Major grid line spacing along Y, in world units.
	 * @param Int        viewportWidth  Target width in pixels.
	 * @param Int        viewportHeight Target height in pixels.
	 */
	private fun drawGrid(
		worldToNdc: FloatArray,
		originX: Float,
		originY: Float,
		majorSpacingX: Float,
		majorSpacingY: Float,
		viewportWidth: Int,
		viewportHeight: Int,
	) {
		background.grid(
			viewportWidth,
			viewportHeight,
			worldToNdc,
			originX,
			originY,
			majorSpacingX,
			majorSpacingY,
			gridSubdivisions,
			gridPixelScale,
			gridColors,
		)
	}

	/**
	 * Computes the rest-pose content bounds lazily, from a CPU eval at default parameters (shown drawables
	 * only).  Evaluates the live [currentModel] (not the construction-time model) and is re-armed by
	 * [updateModel] / [setShownDrawables], so a base-mesh edit that grows the content re-frames view.fit
	 * instead of leaving the camera clamped to stale bounds.
	 */
	private fun ensureContentBounds() {
		if (bboxReady) {
			return
		}
		computeBbox(applyCpuDeform(currentModel, preparePose(currentModel, emptyMap())))
		bboxReady = true
	}

	/** Fills minX/minY/spanX/spanY from a CPU-evaluated pose's world positions (shown drawables only). */
	private fun computeBbox(geometry: DeformedGeometry) {
		var loX = Float.MAX_VALUE
		var loY = Float.MAX_VALUE
		var hiX = -Float.MAX_VALUE
		var hiY = -Float.MAX_VALUE
		for ((drawableId, world) in geometry.worldPositions) {
			// A hidden full-canvas guide image / background must not stretch the framing it isn't drawn in.
			if (drawableId !in shownDrawableIds) {
				continue
			}
			var coordIndex = 0
			while (coordIndex < world.size) {
				loX = minOf(loX, world[coordIndex])
				hiX = maxOf(hiX, world[coordIndex])
				loY = minOf(loY, world[coordIndex + 1])
				hiY = maxOf(hiY, world[coordIndex + 1])
				coordIndex += 2
			}
		}
		minX = loX
		minY = loY
		spanX = maxOf(hiX - loX, 1f)
		spanY = maxOf(hiY - loY, 1f)
	}

	private fun ensureMaskTarget(viewportWidth: Int, viewportHeight: Int) {
		if (maskFramebuffer != 0 && maskWidth == viewportWidth && maskHeight == viewportHeight) {
			return
		}
		if (maskFramebuffer != 0) {
			GL30.glDeleteFramebuffers(maskFramebuffer)
			GL11.glDeleteTextures(maskTexture)
		}
		maskTexture = GL11.glGenTextures()
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, maskTexture)
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
		GL11.glTexImage2D(
			GL11.GL_TEXTURE_2D,
			0,
			GL11.GL_RGBA8,
			viewportWidth,
			viewportHeight,
			0,
			GL11.GL_RGBA,
			GL11.GL_UNSIGNED_BYTE,
			null as ByteBuffer?,
		)
		maskFramebuffer = GL30.glGenFramebuffers()
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, maskFramebuffer)
		GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, maskTexture, 0)
		maskWidth = viewportWidth
		maskHeight = viewportHeight
	}

	private fun uniform(program: Int, name: String): Int = GL20.glGetUniformLocation(program, name)

	/**
	 * Reads the framebuffer back into a PNG (headless verification; call before swapBuffers).
	 *
	 * @param String path           Output PNG path.
	 * @param Int    viewportWidth  Viewport width in pixels.
	 * @param Int    viewportHeight Viewport height in pixels.
	 */
	fun dumpPng(path: String, viewportWidth: Int, viewportHeight: Int) {
		val pixels = BufferUtils.createByteBuffer(viewportWidth * viewportHeight * 4)
		GL11.glReadPixels(0, 0, viewportWidth, viewportHeight, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels)
		val image = BufferedImage(viewportWidth, viewportHeight, BufferedImage.TYPE_INT_ARGB)
		for (y in 0 until viewportHeight) {
			for (x in 0 until viewportWidth) {
				val offset = ((viewportHeight - 1 - y) * viewportWidth + x) * 4
				val red = pixels.get(offset).toInt() and 0xFF
				val green = pixels.get(offset + 1).toInt() and 0xFF
				val blue = pixels.get(offset + 2).toInt() and 0xFF
				val alpha = pixels.get(offset + 3).toInt() and 0xFF
				image.setRGB(x, y, (alpha shl 24) or (red shl 16) or (green shl 8) or blue)
			}
		}
		ImageIO.write(image, "png", File(path))
	}

	/**
	 * Uploads a mesh's per-keyform-cell vertex deltas as an RG32F texture: column = cell (linear grid
	 * index), row = vertex id, RG = (Δx, Δy).
	 *
	 * @param KeyformGrid grid      The mesh's keyform grid.
	 * @param FloatArray  positions The rest positions (for the vertex count).
	 * @param Int         cellCount The grid's linear cell extent (texture width).
	 * @return Int The delta texture handle.
	 */
	private fun uploadDeltaTexture(grid: KeyformGrid<MeshForm>, positions: FloatArray, cellCount: Int): Int {
		val vertexCount = positions.size / 2
		val cells = cellsByLinearIndex(grid)
		val deltaData = BufferUtils.createFloatBuffer(vertexCount * cellCount * 2)
		for (vertexIndex in 0 until vertexCount) {
			for (cellIndex in 0 until cellCount) {
				val deltas = cells[cellIndex]?.form?.positionDeltas
				if (deltas != null && vertexIndex * 2 + 1 < deltas.size) {
					deltaData.put(deltas[vertexIndex * 2]).put(deltas[vertexIndex * 2 + 1])
				} else {
					deltaData.put(0f).put(0f)
				}
			}
		}
		deltaData.flip()
		val texture = nearestTexture()
		GL11.glTexImage2D(
			GL11.GL_TEXTURE_2D,
			0,
			GL30.GL_RG32F,
			cellCount,
			vertexCount,
			0,
			GL30.GL_RG,
			GL11.GL_FLOAT,
			deltaData,
		)
		return texture
	}

	/**
	 * (Re)specifies a warp's control-point texture from its baked world control points (RG32F, one texel per
	 * point). Called from [setPose] - once per pose change, never from the per-frame draw loop: the
	 * control points are frame-invariant, and re-specifying this texture every frame churned the d3d12/Mesa
	 * driver into progressively corrupting it. The draw loop only binds this texture (see [setDeformUniforms]).
	 */
	private fun uploadControlPoints(cpTexture: Int, warp: WarpWorld) {
		val controlPointBuffer = BufferUtils.createFloatBuffer(warp.cp.size)
		controlPointBuffer.put(warp.cp).flip()
		GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT_CP)
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, cpTexture)
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
		GL11.glTexImage2D(
			GL11.GL_TEXTURE_2D,
			0,
			GL30.GL_RG32F,
			warp.cols + 1,
			warp.rows + 1,
			0,
			GL30.GL_RG,
			GL11.GL_FLOAT,
			controlPointBuffer,
		)
	}

	/**
	 * Uploads a mesh's rest geometry into a VAO: rest positions (attr 0), UVs (attr 1), indices, and
	 * - for a glue mesh - per-vertex glue attributes (attrs 2,3,4: partner index, glue index, weld weight).
	 * The position and UV VBOs are GL_DYNAMIC_DRAW (a base-mesh edit re-uploads positions in place via
	 * [updateModel], and a UV edit re-uploads UVs the same way); the indices and glue attributes never
	 * change under either, so they stay GL_STATIC_DRAW.
	 *
	 * @param FloatArray  positions Rest positions (interleaved x,y).
	 * @param FloatArray  uvs       Atlas UVs.
	 * @param IntArray    indices   Triangle indices (may be empty for a glue anchor).
	 * @param ByteBuffer? glueAttr  Per-vertex glue attributes, or null for a non-glue mesh.
	 * @return MeshBuffers The VAO handle and the position VBO handle (kept for later re-upload).
	 */
	private fun uploadMesh(positions: FloatArray, uvs: FloatArray, indices: IntArray, glueAttr: ByteBuffer?): MeshBuffers {
		var glueVbo = 0
		var indexEbo = 0
		val vao = GL30.glGenVertexArrays()
		GL30.glBindVertexArray(vao)

		val positionBuffer = BufferUtils.createFloatBuffer(positions.size)
		positionBuffer.put(positions).flip()
		val positionVbo = GL15.glGenBuffers()
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, positionVbo)
		GL15.glBufferData(GL15.GL_ARRAY_BUFFER, positionBuffer, GL15.GL_DYNAMIC_DRAW)
		GL20.glEnableVertexAttribArray(0)
		GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 2 * Float.SIZE_BYTES, 0L)

		val vertexCount = positions.size / 2
		val safeUvs = if (uvs.size >= vertexCount * 2) uvs else FloatArray(vertexCount * 2)
		val uvBuffer = BufferUtils.createFloatBuffer(safeUvs.size)
		uvBuffer.put(safeUvs).flip()
		val uvVbo = GL15.glGenBuffers()
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, uvVbo)
		GL15.glBufferData(GL15.GL_ARRAY_BUFFER, uvBuffer, GL15.GL_DYNAMIC_DRAW)
		GL20.glEnableVertexAttribArray(1)
		GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 2 * Float.SIZE_BYTES, 0L)

		if (glueAttr != null) {
			glueVbo = GL15.glGenBuffers()
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, glueVbo)
			GL15.glBufferData(GL15.GL_ARRAY_BUFFER, glueAttr, GL15.GL_STATIC_DRAW)
			val stride = 3 * Int.SIZE_BYTES
			GL20.glEnableVertexAttribArray(2)
			GL30.glVertexAttribIPointer(2, 1, GL11.GL_INT, stride, 0L)
			GL20.glEnableVertexAttribArray(3)
			GL30.glVertexAttribIPointer(3, 1, GL11.GL_INT, stride, Int.SIZE_BYTES.toLong())
			GL20.glEnableVertexAttribArray(4)
			GL20.glVertexAttribPointer(4, 1, GL11.GL_FLOAT, false, stride, (2 * Int.SIZE_BYTES).toLong())
		}

		if (indices.isNotEmpty()) {
			val indexBuffer = BufferUtils.createIntBuffer(indices.size)
			indexBuffer.put(indices).flip()
			indexEbo = GL15.glGenBuffers()
			GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indexEbo)
			GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL15.GL_STATIC_DRAW)
		}

		GL30.glBindVertexArray(0)
		return MeshBuffers(vao, positionVbo, uvVbo, glueVbo, indexEbo)
	}

	private fun nearestTexture(): Int {
		val texture = GL11.glGenTextures()
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture)
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
		return texture
	}

	private fun applyBlendMode(mode: BlendMode) {
		when (mode) {
			BlendMode.Normal ->
				GL14.glBlendFuncSeparate(
					GL11.GL_ONE,
					GL11.GL_ONE_MINUS_SRC_ALPHA,
					GL11.GL_ONE,
					GL11.GL_ONE_MINUS_SRC_ALPHA,
				)

			BlendMode.Additive ->
				GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE)

			BlendMode.Multiply ->
				GL14.glBlendFuncSeparate(GL11.GL_DST_COLOR, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE)
		}
	}

	private fun uploadAtlas(atlas: DecodedImage): Int {
		val pixelBuffer = BufferUtils.createByteBuffer(atlas.rgba.size)
		pixelBuffer.put(atlas.rgba).flip()
		val handle = GL11.glGenTextures()
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, handle)
		// Plain bilinear, no mip chain: mipmapping a texture ATLAS bleeds neighbouring packed regions into
		// each other at coarse levels (visible as color/coverage halos at clip-mask edges). Minification
		// aliasing is handled instead by supersampling the whole offscreen render (see OffscreenPuppetService),
		// which has no such cross-region bleed. アトラスのミップマップは領域間の滲みを生むので使わない。
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
		GL11.glTexImage2D(
			GL11.GL_TEXTURE_2D,
			0,
			GL11.GL_RGBA8,
			atlas.width,
			atlas.height,
			0,
			GL11.GL_RGBA,
			GL11.GL_UNSIGNED_BYTE,
			pixelBuffer,
		)
		return handle
	}

	private fun colorFor(id: String): FloatArray {
		val hash = id.hashCode()
		val red = 0.35f + ((hash shr 16) and 0xFF) / 255f * 0.6f
		val green = 0.35f + ((hash shr 8) and 0xFF) / 255f * 0.6f
		val blue = 0.35f + (hash and 0xFF) / 255f * 0.6f
		return floatArrayOf(red, green, blue, 0.85f)
	}

	private fun linkProgram(vertexSource: String, fragmentSource: String): Int {
		val program = attachShaders(vertexSource, fragmentSource)
		GL20.glLinkProgram(program)
		check(GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) != GL11.GL_FALSE) {
			"GL program link failed: ${GL20.glGetProgramInfoLog(program)}"
		}
		return program
	}

	/** Links the glue pass-1 program: the shared TF deform shader capturing `outWorld` via feedback. */
	private fun linkTransformFeedbackProgram(): Int {
		val program = attachShaders(TF_DEFORM_VERTEX_SHADER, "#version 330 core\nvoid main() {}\n")
		GL30.glTransformFeedbackVaryings(program, arrayOf("outWorld"), GL30.GL_INTERLEAVED_ATTRIBS)
		GL20.glLinkProgram(program)
		check(GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) != GL11.GL_FALSE) {
			"TF program link failed: ${GL20.glGetProgramInfoLog(program)}"
		}
		return program
	}

	private fun attachShaders(vertexSource: String, fragmentSource: String): Int {
		val vertexShader = compileShader(GL20.GL_VERTEX_SHADER, vertexSource)
		val fragmentShader = compileShader(GL20.GL_FRAGMENT_SHADER, fragmentSource)
		val program = GL20.glCreateProgram()
		GL20.glAttachShader(program, vertexShader)
		GL20.glAttachShader(program, fragmentShader)
		return program
	}

	private fun compileShader(stage: Int, source: String): Int {
		val shader = GL20.glCreateShader(stage)
		GL20.glShaderSource(shader, source)
		GL20.glCompileShader(shader)
		check(GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) != GL11.GL_FALSE) {
			"GL shader compile failed: ${GL20.glGetShaderInfoLog(shader)}"
		}
		return shader
	}
}
