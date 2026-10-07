package org.umamo.editor.desktop.viewport

import org.umamo.render.puppet.ModelUpdateKind

/**
 * Whether a render-loop tick that handed the renderer new inputs must also rebuild the pose.  The
 * pose's own inputs (the parameters, the channel overrides, the shown set) always do; a model push
 * does only when it was structural, because a positions-only push leaves the renderer's pose valid
 * (its inputs hold no positions, and the renderer refreshes the glue store and the composite bounds
 * itself).
 *
 * Pure, like [resolveAtlasPairing], so the rule is testable without a render thread.
 *
 * @param Boolean poseInputsChanged True when the parameters, the overrides, or the shown set changed.
 * @param ModelUpdateKind? modelUpdate How the renderer classified this tick's model push, or null when
 *   the model did not change.
 * @return Boolean True when setPose must run this tick.
 */
internal fun poseFollowsHandoff(poseInputsChanged: Boolean, modelUpdate: ModelUpdateKind?): Boolean =
	poseInputsChanged || modelUpdate == ModelUpdateKind.Structural