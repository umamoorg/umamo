package org.umamo.reimport

import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayer
import org.umamo.format.art.SourceLayerKind
import org.umamo.runtime.model.SourceLayerRef

/**
 * Reconciles existing rig bindings (the model's per-tile [SourceLayerRef]s) against freshly-read
 * source art - the headline "file refreshes" feature.
 *
 * Hard rule: re-import never destroys rig work. Matched layers update in place;
 * added layers become new tiles bound to their layer; removed/renamed layers are flagged for review,
 * never silently deleted. The output [ReconcileReport] is therefore a reviewable diff, not an applied
 * mutation - [ArtworkReloadPlanner] turns the matched and added halves into a model delta.
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
 * The reconcile every reload runs: a binding matches when the re-read art has a raster layer under
 * exactly its key, a raster layer no binding names is added, and a binding whose key the art no
 * longer has needs review.  Keys only - the readers mint them stable where the format allows (a CLIP
 * or Krita uuid, Photoshop's lyid) and the fuzzy matching a weak key needs is a later matcher's work.
 *
 * Non-raster layers (folders, text, adjustment layers) are invisible to it: they never became tiles
 * at import, so they are neither matched nor added, and a raster layer that turned into one reads as
 * missing, which is the honest answer.
 */
object KeyReconciler : Reconciler {
	/**
	 * Classifies [bindings] against [newArt].
	 *
	 * @param List<SourceLayerRef> bindings The bindings of every tile bound to the re-read file.
	 * @param SourceArt            newArt   The file as just read.
	 * @return ReconcileReport One result per binding, then one per added layer, in the art's order.
	 */
	override fun reconcile(bindings: List<SourceLayerRef>, newArt: SourceArt): ReconcileReport {
		val rasterKeys = LinkedHashSet<String>()
		for (layer in newArt.layers.sortedBy { candidate -> candidate.order }) {
			if (layer.kind == SourceLayerKind.Raster) {
				rasterKeys.add(layer.id.raw)
			}
		}
		val boundKeys = bindings.mapTo(HashSet()) { binding -> binding.layerKey }
		val results = ArrayList<ReconcileResult>(bindings.size + rasterKeys.size)
		for (binding in bindings) {
			if (binding.layerKey in rasterKeys) {
				results.add(ReconcileResult.Matched(binding, binding.layerKey))
			} else {
				results.add(ReconcileResult.NeedsReview(binding, ReviewReason.LayerMissing))
			}
		}
		for (key in rasterKeys) {
			if (key !in boundKeys) {
				results.add(ReconcileResult.Added(key))
			}
		}
		return ReconcileReport(results)
	}
}

/**
 * Heuristic matcher for the hard case: a binding whose layer id no longer resolves, matched against
 * remaining candidates (renames, near-duplicates). Returns the best candidate or null - the caller
 * still routes the decision through review.
 */
fun interface LayerMatcher {
	fun bestMatch(binding: SourceLayerRef, candidates: List<SourceLayer>): SourceLayer?
}