package org.umamo.ui.transform

import org.umamo.edit.AdjustableOperation
import org.umamo.edit.BrushSettings
import org.umamo.edit.DeformerChange
import org.umamo.edit.EditorSession
import org.umamo.edit.mesh.commitLatticeReshape
import org.umamo.edit.transform.BrushStroke
import org.umamo.edit.transform.brushLatticeOffsets
import org.umamo.edit.transform.brushParameters
import org.umamo.edit.transform.brushSettingsOf
import org.umamo.render.eval.WarpLatticeResolver
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.deformerId

/*
 * The deform brush's landing: the world points a stroke gives the warps' lattices, the model those points
 * reshape, and the operation strip registration that re-runs the stroke under new settings.  Lives in :ui
 * because the world points come from :render's evaluator, which :edit cannot see.
 */

/**
 * The warps the brush smears: the session's element owners that are warps.  A brush over a mesh has
 * nothing to smear, so the set is what the brush acts on.
 *
 * @param EditorSession session The session the brush runs in.
 * @return List The warps' ids.
 */
internal fun brushWarpIds(session: EditorSession): List<DeformerId> =
	session.meshSelection.value.owners.mapNotNull { owner -> owner.deformerId }

/**
 * The world points a brush stroke gives each warped lattice: the lattice's own points moved by the
 * stroke's weighted travel.  A warp the stroke does not reach keeps its points and is left out.
 *
 * @param PuppetModel model The model the stroke is over.
 * @param BrushStroke stroke The stroke's stamps.
 * @param BrushSettings settings The brush's configuration.
 * @param Collection<DeformerId> warpIds The warps the stroke may touch.
 * @return Map The world points per warp, interleaved x, z.
 */
internal fun brushLatticeWorld(
	model: PuppetModel,
	stroke: BrushStroke,
	settings: BrushSettings,
	warpIds: Collection<DeformerId>,
): Map<DeformerId, FloatArray> {
	if (stroke.stamps.size < 2 || warpIds.isEmpty()) {
		return emptyMap()
	}

	val resolver = WarpLatticeResolver(model, emptyMap(), warpIds = warpIds.toList())
	val world = LinkedHashMap<DeformerId, FloatArray>()

	for (warpId in warpIds) {
		val lattice = resolver.lattice(warpId) ?: continue
		val offsets = brushLatticeOffsets(stroke.stamps, lattice.world, settings.strength, settings.falloff)

		if (offsets.all { component -> component == 0f }) {
			continue
		}

		world[warpId] = FloatArray(lattice.world.size) { componentIndex -> lattice.world[componentIndex] + offsets[componentIndex] }
	}

	return world
}

/**
 * The model with a brush stroke smeared over the warps' lattices: every point the brush covered takes
 * the brush's travel, and the warps' children rest where they rested.
 *
 * @param PuppetModel model The model the stroke is over.
 * @param BrushStroke stroke The stroke's stamps.
 * @param BrushSettings settings The brush's configuration.
 * @param Collection<DeformerId> warpIds The warps the stroke may touch.
 * @return LatticeReshape The reshaped model and the children it could not keep in place.
 */
internal fun brushLatticeReshape(
	model: PuppetModel,
	stroke: BrushStroke,
	settings: BrushSettings,
	warpIds: Collection<DeformerId>,
): LatticeReshape {
	val world = brushLatticeWorld(model, stroke, settings, warpIds)

	if (world.isEmpty()) {
		return LatticeReshape(model, emptyList(), emptyList())
	}

	return latticeReshapeOf(model, world)
}

/**
 * Commits a finished brush stroke as ONE undo step, and registers it on the operation settings strip so
 * the rigger can re-run it at another strength, falloff, or radius.
 *
 * @param EditorSession session The session the stroke ran in.
 * @param String areaId The viewport area the stroke ran in (where the strip shows).
 * @param BrushStroke stroke The stroke's stamps.
 * @param BrushSettings settings The configuration the stroke landed with.
 * @param Collection<DeformerId> warpIds The warps the stroke touched.
 * @return Boolean True when a step was recorded.
 */
internal fun commitBrushStroke(
	session: EditorSession,
	areaId: String,
	stroke: BrushStroke,
	settings: BrushSettings,
	warpIds: Collection<DeformerId>,
): Boolean {
	val model = session.model.value
	val reshape = brushLatticeReshape(model, stroke, settings, warpIds)

	if (reshape.model === model) {
		return false
	}

	session.commitLatticeReshape(DeformerChange.BrushLattice(warpIds.toList()), reshape.model, reshape.unconvertedCount)
	registerBrushAdjustment(session, areaId, stroke, settings, warpIds)

	return true
}

/**
 * Registers the brush stroke that just committed as the session's adjustable operation, with the rows
 * that re-run it.  The rerun applies the SAME stamps under the new settings, over the record's base, so
 * an adjustment lands over the stroke's own step instead of adding a second one.
 *
 * @param EditorSession session The session the stroke committed into.
 * @param String areaId The viewport area the stroke ran in (where the strip shows).
 * @param BrushStroke stroke The stroke's stamps.
 * @param BrushSettings settings The configuration the stroke landed with.
 * @param Collection<DeformerId> warpIds The warps the stroke touched.
 * @return AdjustableOperation? The record, or null when the session refused the registration.
 */
internal fun registerBrushAdjustment(
	session: EditorSession,
	areaId: String,
	stroke: BrushStroke,
	settings: BrushSettings,
	warpIds: Collection<DeformerId>,
): AdjustableOperation? =
	session.registerAdjustableOperation(session.model.value, areaId, brushParameters(settings)) { record ->
		val adjusted = brushSettingsOf(record.parameters, settings)
		val reshape = brushLatticeReshape(record.baseSnapshot.model, stroke.withRadius(adjusted.radiusWorld), adjusted, warpIds)

		if (reshape.model === record.baseSnapshot.model) {
			return@registerAdjustableOperation
		}

		session.amendLastCommit(record, reshape.model)
		// The adjusted settings become the ones the next stroke starts from, as the proportional rows do.
		session.setBrushSettings(adjusted)
	}