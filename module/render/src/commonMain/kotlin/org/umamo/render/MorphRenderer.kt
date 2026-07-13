package org.umamo.render

/**
 * Opaque handle to a mesh resident in GPU memory (vertex/index/delta buffers). The concrete type is
 * platform GL state, so consumers only ever hold the handle - never its internals.
 * GPU 上のメッシュへの不透明ハンドル。中身はプラットフォーム GL 状態。
 */
interface GpuMesh

/** Opaque handle to a compiled shader program (the morph-blend vertex/fragment pipeline). */
interface GpuProgram

/**
 * The GPU side of Umamo's differentiator: the per-vertex morph-blend delta-sum runs here in a
 * vertex shader. The CPU computes blend weights (cheap; depends only on parameters - see
 * `:runtime`'s evaluator) and hands them down; the shader evaluates `p = base + Σ wᵢ·Δᵢ` per vertex.
 *
 * Kept as a plain `commonMain` interface (opaque handles) rather than `expect`/`actual`: the GL
 * binding lives in the eventual desktop/Android implementations, but the contract is shared.
 *
 * モーフブレンドの頂点和を GPU（頂点シェーダ）で実行する契約。重みは CPU から渡す。
 */
interface MorphRenderer {
	/**
	 * Uploads a base mesh plus its per-keyform position deltas, returning a reusable [GpuMesh].
	 * [keyformDeltas] is one delta array per keyform, each parallel to [basePositions].
	 */
	fun upload(basePositions: FloatArray, indices: IntArray, keyformDeltas: List<FloatArray>): GpuMesh

	/** Draws [mesh] with the given per-keyform [weights] (uploaded as shader uniforms). */
	fun drawMorph(mesh: GpuMesh, weights: FloatArray)
}
