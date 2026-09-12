package org.umamo.reimport

import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayerKind
import org.umamo.runtime.model.ArtSourceLayer
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
 * or Krita uuid, Photoshop's lyid) and the fuzzy matching a weak key needs is a [LayerMatcher]'s work
 * ([InventoryLayerMatcher], run over the review half by [suggestionsFor]), never this reconcile's.
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
 * One layer a missing layer's binding could move to: its inventory row and, when the file could be
 * read, its pixels on demand.
 *
 * @property ArtSourceLayer row    The candidate as the inventory records it.
 * @property Function       raster Its pixels, decoded on first use; null when they cannot be read.
 */
class MatchCandidate(
	val row: ArtSourceLayer,
	val raster: () -> LayerRaster?,
)

/**
 * How one candidate scored on each signal, kept beside the score so a person can see why.
 *
 * @property Float   name      Name similarity, 0..1.
 * @property Float   path      Folder-path agreement, 0..1.
 * @property Float?  bounds    Canvas overlap of the two rectangles, 0..1, or null when either has no known extent.
 * @property Float?  size      The smaller area over the larger, 0..1, or null when either has no known extent.
 * @property Float?  pixels    Pixel similarity, 0..1, or null when either side's pixels were not read.
 * @property Boolean hashEqual Whether both rows carry a content hash and they agree - the same pixels.
 */
data class MatchSignals(
	val name: Float,
	val path: Float,
	val bounds: Float?,
	val size: Float?,
	val pixels: Float?,
	val hashEqual: Boolean,
)

/**
 * One ranked candidate for a missing layer.
 *
 * @property String       key     The candidate's layer key.
 * @property Float        score   The combined confidence, 0..1.
 * @property MatchSignals signals The per-signal scores behind it.
 */
data class LayerMatch(
	val key: String,
	val score: Float,
	val signals: MatchSignals,
)

/**
 * The matcher for the hard case: a binding whose layer key the file no longer has, ranked against the
 * file's layers no tile is bound to.  Scored rather than decided - the caller applies a threshold or
 * shows the ranking, and a person can always override.
 */
fun interface LayerMatcher {
	/**
	 * Ranks [candidates] for [missing], best first.
	 *
	 * @param ArtSourceLayer       missing       The lost layer as the inventory recorded it.
	 * @param LayerRaster?         missingRaster The pixels the document holds for it, or null.
	 * @param List<MatchCandidate> candidates    The layers it could move to.
	 * @return List<LayerMatch> Every candidate with its score, best first; empty when there are none.
	 */
	fun rank(missing: ArtSourceLayer, missingRaster: LayerRaster?, candidates: List<MatchCandidate>): List<LayerMatch>
}