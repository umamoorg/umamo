package org.umamo.reimport

import org.umamo.runtime.model.SourceLayerRef

/*
 * The reconcile's vocabulary.  The binding itself is model state - `AtlasTile.source`, a
 * SourceLayerRef naming the artwork file and the layer key the reader minted - so this module adds
 * no identity type of its own: one key representation, minted by the readers, persisted on the tile,
 * and diffed here.  See CLAUDE.md "Source-art binding".
 */

/** Why a binding needs a person's decision rather than an automatic update. */
enum class ReviewReason {
	/** The re-read art has no layer under the binding's key: removed, or renamed under a weak key. */
	LayerMissing,

	/** The file was repointed to art that mints different keys (another format), so the binding's key names nothing there. */
	SourceReplaced,

	/** The layer is still in the file but erased to nothing: deleted by another route, or meant to be blank - a person decides. */
	LayerEmptied,
}

/**
 * Outcome of reconciling one tile's binding against re-read art.  Removals and renames are flagged,
 * never silently applied - re-import must never destroy rig work (CLAUDE.md hard rule).
 */
sealed interface ReconcileResult {
	/**
	 * The binding's layer is present under the same key; its art updates in place.
	 *
	 * @property SourceLayerRef binding  The binding that matched.
	 * @property String         layerKey The key it matched on - the binding's own, restated so a report
	 *   reads uniformly with [Added].
	 */
	data class Matched(val binding: SourceLayerRef, val layerKey: String) : ReconcileResult

	/** A layer the re-read art has that no tile is bound to; it becomes a new tile bound to it. */
	data class Added(val layerKey: String) : ReconcileResult

	/**
	 * A binding whose key the re-read art no longer has, rebound by the matcher to a layer no tile was
	 * bound to at or above the threshold - the lost layer re-created under a new key - so its tile
	 * takes that layer's art in the same step instead of a fresh drawable being minted beside it.
	 *
	 * @property SourceLayerRef binding  The binding as it was, under the lost key.
	 * @property String         layerKey The layer it moved to.
	 * @property Float          score    The matcher's confidence, 0..1.
	 */
	data class Rebound(val binding: SourceLayerRef, val layerKey: String, val score: Float) : ReconcileResult

	/** Needs human review - surfaced, never deleted. */
	data class NeedsReview(val binding: SourceLayerRef, val reason: ReviewReason) : ReconcileResult
}