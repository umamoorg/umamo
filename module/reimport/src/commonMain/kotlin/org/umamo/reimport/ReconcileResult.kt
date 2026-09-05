package org.umamo.reimport

import org.umamo.runtime.model.SourceLayerRef

/*
 * The reconcile's vocabulary.  The binding itself is model state - `AtlasTile.source`, a
 * SourceLayerRef naming the artwork file and the layer key the reader minted - so this module adds
 * no identity type of its own: one key representation, minted by the readers, persisted on the tile,
 * and diffed here.  See CLAUDE.md "Source-art binding".
 */

/**
 * Outcome of reconciling one tile's binding against re-read art.  Removals and renames are flagged,
 * never silently applied - re-import must never destroy rig work (CLAUDE.md hard rule).
 */
sealed interface ReconcileResult {
	/** The binding's layer is present under the same key; its art updates in place. */
	data class Matched(val binding: SourceLayerRef) : ReconcileResult

	/** A layer the re-read art has that no tile is bound to; it becomes a new unbound tile. */
	data class Added(val layerKey: String) : ReconcileResult

	/** Needs human review (a fuzzy-matched rename, a removed layer, an unstable key) - surfaced, not deleted. */
	data class NeedsReview(val binding: SourceLayerRef, val reason: String) : ReconcileResult
}