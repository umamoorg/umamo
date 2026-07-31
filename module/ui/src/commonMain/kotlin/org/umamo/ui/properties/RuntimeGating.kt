package org.umamo.ui.properties

import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.RuntimeFeature
import org.umamo.runtime.model.RuntimeTarget

/*
 * Pure option filters and row predicates for runtime-target gating, kept Compose-free so
 * PropertyTabRegistryTest-style unit tests cover them directly.  The gating contract: an option
 * list filters to the supported values PLUS the current value (an unsupported stored value never
 * vanishes from its own control, so nothing breaks), while whole-feature blocks hide entirely at
 * their section site.
 */

/**
 * The blend-mode dropdown options under [target]: the full display order when the 5.3 blend-mode
 * family is supported, else the legacy modes plus [current], preserving display-order positions.
 *
 * @param RuntimeTarget target The document's runtime target.
 * @param BlendMode current The control's current value, kept selectable even when unsupported.
 * @return List The options the dropdown should present.
 */
fun blendModeOptionsFor(target: RuntimeTarget, current: BlendMode): List<BlendMode> {
	if (target.supports(RuntimeFeature.ExtendedBlendModes)) {
		return blendModeDisplayOrder()
	}
	return blendModeDisplayOrder().filter { mode -> mode.isLegacy || mode == current }
}

/**
 * The group-mode dropdown options under [target]: all three kinds when offscreen drawing is
 * supported, else Isolated only while it is [current].
 *
 * @param RuntimeTarget target The document's runtime target.
 * @param PartGroupModeKind current The control's current value, kept selectable even when unsupported.
 * @return List The options the dropdown should present.
 */
fun partGroupModeOptionsFor(target: RuntimeTarget, current: PartGroupModeKind): List<PartGroupModeKind> {
	if (target.supports(RuntimeFeature.PartComposite)) {
		return PartGroupModeKind.entries
	}
	return PartGroupModeKind.entries.filter { kind -> kind != PartGroupModeKind.Isolated || kind == current }
}

/**
 * Whether the alpha-composition row shows: the blend mode must not ignore alpha, AND either the
 * target supports the 5.3 blend-mode family the alpha modes belong to or [currentAlpha] already
 * holds an out-of-target value - a stored value never vanishes from its own control, so the rigger
 * can still see it and reset it to Over.
 *
 * @param RuntimeTarget target The document's runtime target.
 * @param BlendMode blendMode The owning control's current blend mode.
 * @param AlphaBlendMode currentAlpha The control's current alpha mode.
 * @return Boolean True when the alpha row should render.
 */
fun showsAlphaBlendRow(target: RuntimeTarget, blendMode: BlendMode, currentAlpha: AlphaBlendMode): Boolean =
	!blendMode.ignoresAlphaBlend &&
		(target.supports(RuntimeFeature.ExtendedBlendModes) || currentAlpha != AlphaBlendMode.Over)

/**
 * The alpha-composition dropdown options under [target]: the full list when the 5.3 blend-mode
 * family is supported, else Over (the pre-5.3 behavior) plus [current], so an out-of-target stored
 * value stays visible and the only forward move is back to Over.
 *
 * @param RuntimeTarget target The document's runtime target.
 * @param AlphaBlendMode current The control's current value, kept selectable even when unsupported.
 * @return List The options the dropdown should present.
 */
fun alphaBlendModeOptionsFor(target: RuntimeTarget, current: AlphaBlendMode): List<AlphaBlendMode> {
	if (target.supports(RuntimeFeature.ExtendedBlendModes)) {
		return AlphaBlendMode.entries
	}
	return AlphaBlendMode.entries.filter { mode -> mode == AlphaBlendMode.Over || mode == current }
}
