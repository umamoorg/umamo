package org.umamo.render.puppet

import org.umamo.render.device.GpuMesh
import org.umamo.render.device.GpuTexture
import org.umamo.render.eval.DeformerWorld
import org.umamo.render.eval.MeshBlendState
import org.umamo.runtime.eval.WeightedCell
import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.MeshDeltaForm

/**
 * A drawable resident on the GPU.  Static residency (mesh, delta texture, optional warp control-point
 * texture, atlas) plus the per-pose state setPose stamps onto it.  [isGlueMesh] meshes are deformed in
 * pass 1 (even index-less anchors, whose positions are weld partners) and rendered via the glue pipeline
 * in pass 2.
 */
internal class GpuDrawable(
	val id: DrawableId,
	val mesh: GpuMesh,
	val deltaTexture: GpuTexture,
	val vertexCount: Int,
	val indexCount: Int,
	val cpTexture: GpuTexture?,
	// The atlas page this drawable samples.  A var because a repack swaps the page set mid-session
	// and setAtlasPages re-stamps every resident - the index map moves with the pages, not just the
	// handles.
	var atlasTexture: GpuTexture?,
	// The drawable's SOURCE ARTWORK alternative to the atlas, and the affine carrying its stored
	// coordinates into that image's frame.  Both var: a drawable acquires them when the document
	// switches to source-artwork display, which is a state change, not a re-upload.  Null means this
	// drawable has no recoverable art and keeps rendering from the atlas - the mode is best-effort
	// per drawable, so a document is routinely mixed.
	var layerTexture: GpuTexture? = null,
	var layerUvAffine: FloatArray? = null,
	val color: FloatArray,
	// Static composite state: var because a composite-only edit is a ModelDiff Keep (no buffer work),
	// which re-stamps these on the reused resident in updateModel - or the viewport would keep drawing
	// with the blend/mask/culling captured at upload until an unrelated topology change forced a reupload.
	var blendMode: BlendMode,
	/** The drawable's 5.3 alpha blend; non-Over routes the draw through the composite path. */
	var alphaBlendMode: AlphaBlendMode,
	/** The Cubism Culling toggle: true culls back faces, false (default) is double-sided. */
	var culling: Boolean,
	var maskIds: List<DrawableId>,
	var invertMask: Boolean,
	val isGlueMesh: Boolean,
	val glueBaseOffset: Int,
	/** The static blend-shape column assignment in [deltaTexture] (empty when binding-free). */
	val blendLayout: BlendColumnLayout,
	/**
	 * Rest positions for the exact composite-scissor bounds (see PosedAabb).  A var because an
	 * in-place base-mesh move (updateModel's Keep-with-positions tier) re-uploads new positions to
	 * the GPU mesh and must re-point this at them, or the bounds walk would size the scissor to the
	 * pre-edit geometry and clip the moved vertices.
	 */
	var boundsBase: FloatArray,
	/** Grid cells by linear index for the bounds walk; a keyform edit re-uploads whole (Reupload). */
	val boundsCells: Map<Int, KeyformCell<MeshDeltaForm>>,
	/**
	 * The reference the delta texture's grid columns were uploaded relative to (deltaUploadReference), or
	 * null for a plain upload.  The in-place base-mesh move re-bases its new rest positions with it.
	 */
	val restReference: DoubleArray? = null,
) {
	/**
	 * The texture this drawable actually samples: its source artwork when the document displays from
	 * artwork AND this drawable has recoverable art, else the atlas page it was packed onto.
	 *
	 * The fallback is per drawable on purpose - art the document does not retain, a placement that
	 * will not invert, or a layer that would not decode all leave a drawable on the atlas rather than
	 * removing it from the puppet.
	 *
	 * @return GpuTexture? The texture to bind, or null when the drawable has neither.
	 */
	fun activeTexture(): GpuTexture? = layerTexture ?: atlasTexture

	var corners: List<WeightedCell>? = null
	var parentWorld: DeformerWorld? = null
	var opacity: Float = 1f
	var multiplyColor: ColorRgb = ColorRgb.MultiplyIdentity
	var screenColor: ColorRgb = ColorRgb.ScreenIdentity
	var visible: Boolean = false

	/** The pose's resolved blend-shape state; null for binding-free drawables. */
	var blend: MeshBlendState? = null

	/**
	 * Whether this drawable's blend is a 5.3 extended blend (not fixed-function-expressible), so it
	 * routes through the destination-sampling composite path rather than a plain draw.
	 */
	val isExtendedBlend: Boolean
		get() = !blendMode.isLegacy || alphaBlendMode != AlphaBlendMode.Over
}