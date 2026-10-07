package org.umamo.editor.desktop.viewport

import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.viewport.AtlasPageBinding

/**
 * One render-loop tick's model/pages pairing decision.
 *
 * @property PuppetModel       orderModel   The model to render this tick (the published one, or the
 *                                          previous one while its pages are still in flight).
 * @property AtlasPageBinding? applyBinding The binding to apply before the model, or null.
 */
internal class AtlasPairingDecision(
	val orderModel: PuppetModel,
	val applyBinding: AtlasPageBinding?,
)

/**
 * Decides how the render loop takes up a published model and a published page binding as one
 * consistent pair.
 *
 * The two arrive on independent channels, and on an undo the baseline model would land frames before
 * the baseline pages - repacked pixels under baseline coordinates - so an atlas-CHANGING model waits
 * until the binding composed for its atlas has arrived, and the loop keeps rendering the previous
 * pair.  Non-atlas edits pass untouched: their atlas is the applied binding's own instance.  Atlas
 * comparison is identity first, then equality - the session's baseline short-circuit can publish the
 * baseline pages under an equal-but-distinct atlas instance.
 *
 * Pure and render-thread-agnostic so the hold/apply matrix is testable without a GL context.
 *
 * @param PuppetModel      publishedModel   The latest model the UI published.
 * @param AtlasPageBinding publishedBinding The latest page binding the UI published.
 * @param AtlasPageBinding appliedBinding   The binding the renderer currently holds.
 * @param PuppetModel?     lastModel        The model the loop last applied, or null on the first tick.
 * @return AtlasPairingDecision What to render and whether to swap pages first.
 */
internal fun resolveAtlasPairing(
	publishedModel: PuppetModel,
	publishedBinding: AtlasPageBinding,
	appliedBinding: AtlasPageBinding,
	lastModel: PuppetModel?,
): AtlasPairingDecision {
	val bindingMatchesPublished =
		publishedBinding.atlas === publishedModel.atlas || publishedBinding.atlas == publishedModel.atlas
	val appliedMatchesPublished =
		appliedBinding.atlas === publishedModel.atlas || appliedBinding.atlas == publishedModel.atlas
	val orderModel =
		if (!appliedMatchesPublished && !bindingMatchesPublished) {
			// The pages for this model's atlas have not arrived; keep the previous pair.  The first
			// tick has no previous model to keep, and renders the published one against whatever pages
			// exist rather than nothing at all.
			lastModel ?: publishedModel
		} else {
			publishedModel
		}
	val applyBinding =
		if (publishedBinding !== appliedBinding &&
			(publishedBinding.atlas === orderModel.atlas || publishedBinding.atlas == orderModel.atlas)
		) {
			publishedBinding
		} else {
			null
		}
	return AtlasPairingDecision(orderModel, applyBinding)
}