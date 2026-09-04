package org.umamo.reimport

import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayer
import org.umamo.runtime.model.SourceLayerRef

/**
 * Reconciles existing rig bindings (the model's per-tile [SourceLayerRef]s) against freshly-read
 * source art - the headline "file refreshes" feature.
 *
 * Hard rule: re-import never destroys rig work. Matched layers update in place;
 * added layers become unbound drawables; removed/renamed layers are flagged for review, never
 * silently deleted. The output [ReconcileReport] is therefore a reviewable diff, not an applied
 * mutation.
 */
interface Reconciler {
	fun reconcile(bindings: List<SourceLayerRef>, newArt: SourceArt): ReconcileReport
}

/**
 * The result of a reconcile pass: every binding's outcome, plus a convenience view of the items a
 * human must adjudicate before anything is applied.
 */
data class ReconcileReport(
	val results: List<ReconcileResult>,
) {
	/** Items that must not be auto-applied - removed/renamed/fuzzy-matched layers. */
	val needsReview: List<ReconcileResult.NeedsReview>
		get() = results.filterIsInstance<ReconcileResult.NeedsReview>()
}

/**
 * Heuristic matcher for the hard case: a binding whose layer id no longer resolves, matched against
 * remaining candidates (renames, near-duplicates). Returns the best candidate or null - the caller
 * still routes the decision through review.
 */
fun interface LayerMatcher {
	fun bestMatch(binding: SourceLayerRef, candidates: List<SourceLayer>): SourceLayer?
}