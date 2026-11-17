package org.umamo.ui.transform

import org.umamo.edit.AdjustableOperation
import org.umamo.edit.BrushSettings
import org.umamo.edit.DeformerChange
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshChange
import org.umamo.edit.mesh.MeshRestPositions
import org.umamo.edit.mesh.commitLatticeReshape
import org.umamo.edit.mesh.commitMeshPositions
import org.umamo.edit.mesh.withMeshPositions
import org.umamo.edit.transform.BrushStroke
import org.umamo.edit.transform.brushParameters
import org.umamo.edit.transform.brushPointOffsets
import org.umamo.edit.transform.brushSettingsOf
import org.umamo.render.eval.WarpLatticeResolver
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ElementOwner
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.deformerId
import org.umamo.runtime.model.drawableId

/*
 * The deform brush's landing: what a stroke does to the model.  The brush smears the geometry the Edit
 * session is editing - a mesh's vertices, so the art changes shape, or a warp's cage, so the rig is
 * re-anchored - and both land as one undo step.
 *
 * Lives in :ui because the lowering needs :render's evaluator: a mesh's world shape is read through its
 * deformer chain and inverted back onto its rest arrays, which :edit cannot see.
 */

/**
 * What a brush stroke lands on.
 */
internal sealed interface BrushLanding {
	/**
	 * The meshes the stroke smeared.
	 *
	 * @property Map<DrawableId, MeshRestPositions> rest Each smeared drawable's new rest shape.
	 * @property Map<DrawableId, List<Int>> movedIndices Each smeared drawable's moved vertices.
	 */
	class Meshes(
		val rest: Map<DrawableId, MeshRestPositions>,
		val movedIndices: Map<DrawableId, List<Int>>,
	) : BrushLanding

	/**
	 * The cages the stroke smeared.
	 *
	 * @property LatticeReshape reshape The reshaped model and the children it could not keep in place.
	 * @property List<DeformerId> warpIds The smeared warps.
	 * @property Map<DeformerId, FloatArray> world Each smeared cage's new world points, for the live preview.
	 */
	class Cages(
		val reshape: LatticeReshape,
		val warpIds: List<DeformerId>,
		val world: Map<DeformerId, FloatArray>,
	) : BrushLanding
}

/**
 * The live preview a brush stroke shows: the meshes it would reshape, or the cages it would move.
 *
 * @property Map<DrawableId, MeshRestPositions> restByDrawable Each smeared drawable's previewed rest shape.
 * @property Map<DeformerId, FloatArray> latticeWorldByDeformer Each smeared cage's previewed world points.
 */
internal class BrushPreview(
	val restByDrawable: Map<DrawableId, MeshRestPositions> = emptyMap(),
	val latticeWorldByDeformer: Map<DeformerId, FloatArray> = emptyMap(),
)

/**
 * What a brush stroke does to [model]: the meshes it smears, or the cages it smears.
 *
 * A mesh is smeared in world space and inverted back onto its rest arrays, so the art's shape changes where
 * the brush went.  A cage is smeared through the reshape the Edit-mode lattice drag already uses, which
 * re-anchors the rig and leaves the art at rest where it was.
 *
 * @param PuppetModel model The model the stroke is over.
 * @param BrushStroke stroke The stroke's stamps.
 * @param BrushSettings settings The brush's configuration.
 * @param List<ElementOwner> owners The session's element owners, which say what the brush smears.
 * @return BrushLanding The landing.
 */
internal fun brushLanding(
	model: PuppetModel,
	stroke: BrushStroke,
	settings: BrushSettings,
	owners: List<ElementOwner>,
): BrushLanding {
	val drawableIds = owners.mapNotNull { owner -> owner.drawableId }

	if (drawableIds.isNotEmpty()) {
		return brushMeshes(model, stroke, settings, drawableIds)
	}

	return brushCages(model, stroke, settings, owners.mapNotNull { owner -> owner.deformerId })
}

/**
 * The meshes a brush stroke smears: each drawable's world shape moved by the stroke, inverted back onto
 * its rest arrays.  The vertices the brush did not reach keep their places.
 *
 * @param PuppetModel model The model the stroke is over.
 * @param BrushStroke stroke The stroke's stamps.
 * @param BrushSettings settings The brush's configuration.
 * @param List<DrawableId> drawableIds The drawables to smear.
 * @return BrushLanding The landing.
 */
private fun brushMeshes(
	model: PuppetModel,
	stroke: BrushStroke,
	settings: BrushSettings,
	drawableIds: List<DrawableId>,
): BrushLanding.Meshes {
	val rest = LinkedHashMap<DrawableId, MeshRestPositions>()
	val movedIndices = LinkedHashMap<DrawableId, List<Int>>()

	for (capture in captureDrawableWorlds(model, emptyMap(), drawableIds)) {
		val offsets = brushPointOffsets(stroke.stamps, capture.world, settings.strength, settings.falloff)
		val moved = movedVerticesOf(offsets)

		if (moved.isEmpty()) {
			continue
		}

		val smeared = FloatArray(capture.world.size) { componentIndex -> capture.world[componentIndex] + offsets[componentIndex] }
		rest[capture.drawableId] = capture.worldToRest(smeared, moved.toSet())
		movedIndices[capture.drawableId] = moved
	}

	return BrushLanding.Meshes(rest, movedIndices)
}

/**
 * The vertices a set of per-point offsets moved: the ones with an offset on either of their two
 * components.
 *
 * @param FloatArray offsets The per-point world offsets, interleaved x, z.
 * @return List The moved vertex indices.
 */
private fun movedVerticesOf(offsets: FloatArray): List<Int> =
	(0 until offsets.size / 2).filter { vertexIndex -> offsets[vertexIndex * 2] != 0f || offsets[vertexIndex * 2 + 1] != 0f }

/**
 * The cages a brush stroke smears, through the Edit-mode reshape the lattice drag already uses.
 *
 * @param PuppetModel model The model the stroke is over.
 * @param BrushStroke stroke The stroke's stamps.
 * @param BrushSettings settings The brush's configuration.
 * @param List<DeformerId> warpIds The warps to smear.
 * @return BrushLanding The landing.
 */
private fun brushCages(
	model: PuppetModel,
	stroke: BrushStroke,
	settings: BrushSettings,
	warpIds: List<DeformerId>,
): BrushLanding.Cages {
	if (stroke.stamps.size < 2 || warpIds.isEmpty()) {
		return BrushLanding.Cages(LatticeReshape(model, emptyList(), emptyList()), emptyList(), emptyMap())
	}

	val resolver = WarpLatticeResolver(model, emptyMap(), warpIds = warpIds.toList())
	val world = LinkedHashMap<DeformerId, FloatArray>()

	for (warpId in warpIds) {
		val lattice = resolver.lattice(warpId) ?: continue
		val offsets = brushPointOffsets(stroke.stamps, lattice.world, settings.strength, settings.falloff)

		if (offsets.all { component -> component == 0f }) {
			continue
		}

		world[warpId] = FloatArray(lattice.world.size) { componentIndex -> lattice.world[componentIndex] + offsets[componentIndex] }
	}

	if (world.isEmpty()) {
		return BrushLanding.Cages(LatticeReshape(model, emptyList(), emptyList()), emptyList(), emptyMap())
	}

	return BrushLanding.Cages(latticeReshapeOf(model, world), world.keys.toList(), world)
}

/**
 * The live preview a brush stroke shows: the meshes it would reshape, or the cages it would move.
 *
 * @param PuppetModel model The model the stroke is over.
 * @param BrushStroke stroke The stroke's stamps.
 * @param BrushSettings settings The brush's configuration.
 * @param List<ElementOwner> owners The session's element owners.
 * @return BrushPreview The preview.
 */
internal fun brushPreview(
	model: PuppetModel,
	stroke: BrushStroke,
	settings: BrushSettings,
	owners: List<ElementOwner>,
): BrushPreview =
	when (val landing = brushLanding(model, stroke, settings, owners)) {
		is BrushLanding.Meshes -> BrushPreview(restByDrawable = landing.rest)
		is BrushLanding.Cages -> BrushPreview(latticeWorldByDeformer = landing.world)
	}

/**
 * Commits a finished brush stroke as ONE undo step, and registers it on the operation settings strip so
 * the rigger can re-run it at another strength, falloff, or radius.
 *
 * @param EditorSession session The session the stroke ran in.
 * @param String areaId The viewport area the stroke ran in (where the strip shows).
 * @param BrushStroke stroke The stroke's stamps.
 * @param BrushSettings settings The configuration the stroke landed with.
 * @param List<ElementOwner> owners The session's element owners, which say what the stroke smeared.
 * @return Boolean True when a step was recorded.
 */
internal fun commitBrushStroke(
	session: EditorSession,
	areaId: String,
	stroke: BrushStroke,
	settings: BrushSettings,
	owners: List<ElementOwner>,
): Boolean {
	val model = session.model.value
	val landing = brushLanding(model, stroke, settings, owners)

	when (landing) {
		is BrushLanding.Meshes -> {
			if (landing.rest.isEmpty()) {
				return false
			}

			session.commitMeshPositions(MeshChange.BrushVertices(landing.rest.keys.toList()), landing.rest)
		}

		is BrushLanding.Cages -> {
			if (landing.reshape.model === model) {
				return false
			}

			session.commitLatticeReshape(DeformerChange.BrushLattice(landing.warpIds), landing.reshape.model, landing.reshape.unconvertedCount)
		}
	}

	registerBrushAdjustment(session, areaId, stroke, settings, owners)

	return true
}

/**
 * Registers the brush stroke that just committed as the session's adjustable operation, with the rows
 * that re-run it.  The rerun applies the SAME stamps under the new settings, over the record's base, so an
 * adjustment lands over the stroke's own step instead of adding a second one.
 *
 * @param EditorSession session The session the stroke committed into.
 * @param String areaId The viewport area the stroke ran in (where the strip shows).
 * @param BrushStroke stroke The stroke's stamps.
 * @param BrushSettings settings The configuration the stroke landed with.
 * @param List<ElementOwner> owners The session's element owners, which say what the stroke smeared.
 * @return AdjustableOperation? The record, or null when the session refused the registration.
 */
internal fun registerBrushAdjustment(
	session: EditorSession,
	areaId: String,
	stroke: BrushStroke,
	settings: BrushSettings,
	owners: List<ElementOwner>,
): AdjustableOperation? =
	session.registerAdjustableOperation(session.model.value, areaId, brushParameters(settings)) { record ->
		val adjusted = brushSettingsOf(record.parameters, settings)
		val landing = brushLanding(record.baseSnapshot.model, stroke.withRadius(adjusted.radiusWorld), adjusted, owners)
		val base = record.baseSnapshot.model

		when (landing) {
			is BrushLanding.Meshes -> {
				if (landing.rest.isEmpty()) {
					return@registerAdjustableOperation
				}

				session.amendLastCommit(record, base.withMeshPositions(landing.rest))
			}

			is BrushLanding.Cages -> {
				if (landing.reshape.model === base) {
					return@registerAdjustableOperation
				}

				session.amendLastCommit(record, landing.reshape.model)
			}
		}

		// The adjusted settings become the ones the next stroke starts from, as the proportional rows do.
		session.setBrushSettings(adjusted)
	}