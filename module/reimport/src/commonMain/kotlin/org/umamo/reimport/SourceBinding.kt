package org.umamo.reimport

/**
 * Stable-identity link from a rig drawable to a source-art layer - the basis of
 * non-destructive re-import. The key is whatever survives an art edit: a KRA/CLIP layer ID or a
 * PSD lyid (all stable across renames/reorders), else a PSD name/path (the lossy fallback for a
 * PSD that omits lyid). See CLAUDE.md "Source-art binding".
 *
 * リグ描画オブジェクトとソースレイヤーを結ぶ安定キー。非破壊リインポートの土台。
 */
data class SourceBinding(
	val drawableId: String,
	val layerKey: LayerKey,
)

/** How a layer is identified across re-imports, in descending order of reliability. */
sealed interface LayerKey {
	/** CLIP's internal, rename-stable layer id. */
	data class ClipLayerId(val id: Long) : LayerKey

	/** Krita's per-layer uuid; rename/reorder-stable like CLIP's id - the reliable key for KRA sources. */
	data class KraLayerId(val uuid: String) : LayerKey

	/**
	 * PSD's lyid layer id (from the lyid additional-layer-info block) - Adobe's per-layer id, stable
	 * across rename and reorder. As reliable as the CLIP/KRA ids, but writer-dependent: Photoshop
	 * writes it; some exporters omit it, in which case the binding falls back to [PsdNamePath].
	 */
	data class PsdLayerId(val id: Int) : LayerKey

	/** PSD layer name + path; the fallback when a PSD omits lyid, only as stable as the artist's layer organisation. */
	data class PsdNamePath(val path: String) : LayerKey
}

/**
 * Outcome of reconciling one binding against changed art. Removals/renames are flagged,
 * never silently applied - re-import must never destroy rig work (CLAUDE.md hard rule).
 */
sealed interface ReconcileResult {
	data class Matched(val binding: SourceBinding) : ReconcileResult

	data class Added(val layerKey: LayerKey) : ReconcileResult

	/** Needs human review (fuzzy-matched rename, removed layer, etc.) - surfaced, not deleted. */
	data class NeedsReview(val binding: SourceBinding, val reason: String) : ReconcileResult
}
