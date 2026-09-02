package org.umamo.editor.desktop.viewport

import org.umamo.render.PuppetTextures
import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.viewport.AtlasPageBinding
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Pins the render loop's model/pages pairing: an atlas-changing model is held until the binding
 * composed for its atlas arrives (undo must never render repacked pixels under baseline
 * coordinates), non-atlas edits pass untouched, and the baseline's equal-but-distinct atlas
 * instance still pairs.  Pure - the decision is exactly what the loop carries out.
 */
class AtlasPairingTest {
	private fun modelWith(atlas: PuppetAtlas): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = emptyList(),
			rootChildren = emptyList(),
			rootPartId = null,
			canvasWidth = 10f,
			canvasHeight = 10f,
			worldOriginX = 5f,
			worldOriginY = 5f,
			atlas = atlas,
		)

	private fun bindingFor(atlas: PuppetAtlas): AtlasPageBinding =
		AtlasPageBinding(atlas, PuppetTextures(emptyList(), emptyMap(), premultipliedAlpha = false))

	private val baselineAtlas = PuppetAtlas(pages = listOf(AtlasPage(64, 64)))
	private val repackedAtlas = PuppetAtlas(pages = listOf(AtlasPage(128, 128)))

	@Test
	fun aNonAtlasEditPassesUntouched() {
		val baseline = modelWith(baselineAtlas)
		val applied = bindingFor(baselineAtlas)
		val edited = baseline.copy(canvasWidth = 20f)

		val decision = resolveAtlasPairing(edited, applied, applied, baseline)

		assertSame(edited, decision.orderModel, "a same-atlas model renders immediately")
		assertNull(decision.applyBinding, "and swaps nothing")
	}

	@Test
	fun anAtlasChangingModelWaitsForItsPages() {
		val baseline = modelWith(baselineAtlas)
		val applied = bindingFor(baselineAtlas)
		val repacked = modelWith(repackedAtlas)

		val held = resolveAtlasPairing(repacked, applied, applied, baseline)

		assertSame(baseline, held.orderModel, "the previous pair keeps rendering while pages are in flight")
		assertNull(held.applyBinding)

		val newBinding = bindingFor(repackedAtlas)
		val released = resolveAtlasPairing(repacked, newBinding, applied, baseline)
		assertSame(repacked, released.orderModel, "the matching binding releases the hold")
		assertSame(newBinding, released.applyBinding, "and the pages swap first")
	}

	@Test
	fun undoHoldsUntilTheBaselinePagesComeBack() {
		val baseline = modelWith(baselineAtlas)
		val repacked = modelWith(repackedAtlas)
		val repackedBinding = bindingFor(repackedAtlas)

		val held = resolveAtlasPairing(baseline, repackedBinding, repackedBinding, repacked)
		assertSame(repacked, held.orderModel, "baseline coordinates must not render against repacked pixels")
		assertNull(held.applyBinding)

		val baselineBinding = bindingFor(baselineAtlas)
		val released = resolveAtlasPairing(baseline, baselineBinding, repackedBinding, repacked)
		assertSame(baseline, released.orderModel)
		assertSame(baselineBinding, released.applyBinding)
	}

	@Test
	fun anEqualButDistinctBaselineAtlasStillPairs() {
		// The session's baseline short-circuit can publish the baseline pages under an atlas instance
		// that is equal to - but not identical with - the model's own.
		val modelAtlasTwin = PuppetAtlas(pages = listOf(AtlasPage(64, 64)))
		val model = modelWith(modelAtlasTwin)
		val applied = bindingFor(repackedAtlas)
		val baselineBinding = bindingFor(baselineAtlas)

		val decision = resolveAtlasPairing(model, baselineBinding, applied, modelWith(repackedAtlas))

		assertSame(model, decision.orderModel, "structural equality is enough to pair")
		assertSame(baselineBinding, decision.applyBinding)
	}

	@Test
	fun aBindingRepublishUnderTheCurrentAtlasAppliesAlone() {
		// The resolver's derive-failure fallback republishes pages under the model's own atlas.
		val model = modelWith(repackedAtlas)
		val applied = bindingFor(repackedAtlas)
		val republished = bindingFor(repackedAtlas)

		val decision = resolveAtlasPairing(model, republished, applied, model)

		assertSame(model, decision.orderModel)
		assertSame(republished, decision.applyBinding, "new pages under an unchanged atlas still swap")
	}

	@Test
	fun theFirstTickRendersThePublishedModelRegardless() {
		val model = modelWith(repackedAtlas)
		val applied = bindingFor(baselineAtlas)

		val decision = resolveAtlasPairing(model, applied, applied, lastModel = null)

		assertSame(model, decision.orderModel, "with no previous pair there is nothing to hold")
		assertNull(decision.applyBinding)
	}
}