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
 * @property PuppetModel? smearedModel The model the stroke would leave behind, or null for a cage stroke, which
 *   re-anchors the rig and leaves the art where it is.
 */
internal class BrushPreview(
	val restByDrawable: Map<DrawableId, MeshRestPositions> = emptyMap(),
	val latticeWorldByDeformer: Map<DeformerId, FloatArray> = emptyMap(),
	val smearedModel: PuppetModel? = null,
)

/**
 * The geometry a brush stroke works on, captured once: a mesh's world shape, or the cages' lattice points.
 *
 * A capture bakes the deformer chain, which is too much to repeat on every pointer event of a drag, so
 * the stroke takes it on the press and works on that snapshot throughout.
 *
 * @property PuppetModel model The model the capture was taken from.
 * @property List<DrawableWorldGeometry> meshes The session's meshes' world shapes, empty for a stroke over a cage.
 * @property Map<DeformerId, FloatArray> latticeWorld The cages' lattice points, in world space.
 */
internal class BrushGeometry(
	val model: PuppetModel,
	val meshes: List<DrawableWorldGeometry>,
	val latticeWorld: Map<DeformerId, FloatArray>,
)

/**
 * Captures the geometry a brush stroke would work on: the session's meshes, or the session's cages.
 *
 * @param PuppetModel model The model to capture.
 * @param List<ElementOwner> owners The session's element owners, which say what the brush smears.
 * @return BrushGeometry The capture.
 */
internal fun captureBrushGeometry(model: PuppetModel, owners: List<ElementOwner>): BrushGeometry {
	val drawableIds = owners.mapNotNull { owner -> owner.drawableId }

	if (drawableIds.isNotEmpty()) {
		return BrushGeometry(model, captureDrawableWorlds(model, emptyMap(), drawableIds), emptyMap())
	}

	val warpIds = owners.mapNotNull { owner -> owner.deformerId }
	val resolver = WarpLatticeResolver(model, emptyMap(), warpIds = warpIds.toList())
	val world = LinkedHashMap<DeformerId, FloatArray>()

	for (warpId in warpIds) {
		val lattice = resolver.lattice(warpId) ?: continue
		world[warpId] = lattice.world
	}

	return BrushGeometry(model, emptyList(), world)
}

/**
 * What a brush stroke does to the captured geometry: the meshes it smears, or the cages it smears.
 *
 * A mesh is smeared in world space and inverted back onto its rest arrays, so the art's shape changes where
 * the brush went.  A cage is smeared through the reshape the Edit-mode lattice drag already uses, which
 * re-anchors the rig and leaves the art at rest where it was.
 *
 * @param BrushGeometry geometry The captured geometry.
 * @param BrushStroke stroke The stroke's stamps.
 * @param BrushSettings settings The brush's configuration.
 * @return BrushLanding The landing.
 */
internal fun brushLanding(
	geometry: BrushGeometry,
	stroke: BrushStroke,
	settings: BrushSettings,
): BrushLanding {
	if (geometry.meshes.isNotEmpty()) {
		return brushMeshes(geometry.meshes, stroke, settings)
	}

	return brushCages(geometry.model, geometry.latticeWorld, stroke, settings)
}

/**
 * The meshes a brush stroke smears: each mesh's world shape moved by the stroke, inverted back onto its
 * rest arrays.  The vertices the brush did not reach keep their places.
 *
 * @param List<DrawableWorldGeometry> meshes The meshes' captured world shapes.
 * @param BrushStroke stroke The stroke's stamps.
 * @param BrushSettings settings The brush's configuration.
 * @return BrushLanding The landing.
 */
private fun brushMeshes(
	meshes: List<DrawableWorldGeometry>,
	stroke: BrushStroke,
	settings: BrushSettings,
): BrushLanding.Meshes {
	val rest = LinkedHashMap<DrawableId, MeshRestPositions>()
	val movedIndices = LinkedHashMap<DrawableId, List<Int>>()

	for (mesh in meshes) {
		val offsets = brushPointOffsets(stroke, mesh.world, settings.strength, settings.falloff, settings.mode)
		val moved = movedVerticesOf(offsets)

		if (moved.isEmpty()) {
			continue
		}

		val smeared = FloatArray(mesh.world.size) { componentIndex -> mesh.world[componentIndex] + offsets[componentIndex] }
		rest[mesh.drawableId] = mesh.worldToRest(smeared, moved.toSet())
		movedIndices[mesh.drawableId] = moved
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
 * @param Map<DeformerId, FloatArray> latticeWorld The cages' captured world points.
 * @param BrushStroke stroke The stroke's stamps.
 * @param BrushSettings settings The brush's configuration.
 * @return BrushLanding The landing.
 */
private fun brushCages(
	model: PuppetModel,
	latticeWorld: Map<DeformerId, FloatArray>,
	stroke: BrushStroke,
	settings: BrushSettings,
): BrushLanding.Cages {
	if (stroke.stamps.size < 2 || latticeWorld.isEmpty()) {
		return BrushLanding.Cages(LatticeReshape(model, emptyList(), emptyList()), emptyList(), emptyMap())
	}

	val world = LinkedHashMap<DeformerId, FloatArray>()

	for ((warpId, lattice) in latticeWorld) {
		val offsets = brushPointOffsets(stroke, lattice, settings.strength, settings.falloff, settings.mode)

		if (offsets.all { component -> component == 0f }) {
			continue
		}

		world[warpId] = FloatArray(lattice.size) { componentIndex -> lattice[componentIndex] + offsets[componentIndex] }
	}

	if (world.isEmpty()) {
		return BrushLanding.Cages(LatticeReshape(model, emptyList(), emptyList()), emptyList(), emptyMap())
	}

	return BrushLanding.Cages(latticeReshapeOf(model, world), world.keys.toList(), world)
}

/**
 * The live preview a brush stroke shows: the meshes it would reshape, or the cages it would move.
 *
 * The mesh path also carries the model the stroke would leave behind, because the renderer draws the art and
 * a previewed rest shape alone does not reach it.
 *
 * @param BrushGeometry geometry The captured geometry.
 * @param BrushStroke stroke The stroke's stamps.
 * @param BrushSettings settings The brush's configuration.
 * @return BrushPreview The preview.
 */
internal fun brushPreview(
	geometry: BrushGeometry,
	stroke: BrushStroke,
	settings: BrushSettings,
): BrushPreview {
	val landing = brushLanding(geometry, stroke, settings)

	return when (landing) {
		is BrushLanding.Meshes -> {
			BrushPreview(
				restByDrawable = landing.rest,
				smearedModel = geometry.model.withMeshPositions(landing.rest),
			)
		}

		is BrushLanding.Cages -> {
			BrushPreview(latticeWorldByDeformer = landing.world)
		}
	}
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
	val landing = brushLanding(captureBrushGeometry(model, owners), stroke, settings)

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
		val base = record.baseSnapshot.model
		val landing = brushLanding(captureBrushGeometry(base, owners), stroke.withRadius(adjusted.radiusWorld), adjusted)

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