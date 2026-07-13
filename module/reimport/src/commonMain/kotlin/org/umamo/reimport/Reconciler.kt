package org.umamo.reimport

import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayer

/**
 * Reconciles existing rig bindings against freshly-read source art - the headline "file refreshes"
 * feature.
 *
 * Hard rule: re-import never destroys rig work. Matched layers update in place;
 * added layers become unbound drawables; removed/renamed layers are flagged for review, never
 * silently deleted. The output [ReconcileReport] is therefore a reviewable diff, not an applied
 * mutation.
 *
 * 既存バインディングと再読込アートを突き合わせる。削除・改名は必ずレビュー対象として提示する。
 */
interface Reconciler {
	fun reconcile(bindings: List<SourceBinding>, newArt: SourceArt): ReconcileReport
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
 *
 * 改名・近似レイヤーをファジーに突き合わせる契約。最終判断はレビューに委ねる。
 */
fun interface LayerMatcher {
	fun bestMatch(binding: SourceBinding, candidates: List<SourceLayer>): SourceLayer?
}
