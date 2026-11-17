package org.umamo.interop.cmo3.import

import org.umamo.format.cmo3.model.custom.CRotationDeformerForm
import org.umamo.format.cmo3.model.gen.CRotationDeformerSource
import org.umamo.format.cmo3.model.gen.CWarpDeformerForm
import org.umamo.format.cmo3.model.gen.CWarpDeformerSource
import org.umamo.interop.cmo3.idStrOf
import org.umamo.interop.cmo3.uuidOf
import org.umamo.runtime.keyform.fanOutRotation
import org.umamo.runtime.keyform.fanOutWarp
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.RotationForm
import org.umamo.runtime.model.WarpForm

/*
 * The deformer producer of the CMO3 import: warp and rotation deformers with their keyform grids and
 * blend shapes.
 */

/**
 * Builds the runtime deformers, in the deformer set's order.
 *
 * @param Cmo3ImportContext context The import's shared reads.
 * @return List<Deformer> The deformers; a source with no id, or of an unknown kind, is left out.
 */
internal fun importDeformers(context: Cmo3ImportContext): List<Deformer> =
	context.deformerSources.mapNotNull { source ->
		val id = idStrOf(source.id)?.let(::DeformerId) ?: return@mapNotNull null
		// CMO3: ACParameterControllableSource.localName is the user-facing deformer name; an absent or empty one falls back to the id.
		val name = displayNameOf(source.localName, source.id)
		// CMO3: targetDeformerGuid = parent deformer (transform tree); parentGuid = part (org tree).
		val parent = context.deformerIdByUuid[uuidOf(source.targetDeformerGuid)]
		val partId = context.partIdByUuid[uuidOf(source.parentGuid)]

		when (source) {
			is CWarpDeformerSource -> {
				val fanned = buildGrid(source.keyformGridSource, source.keyforms, context.paramIdByUuid, ::warpForm)?.fanOutWarp()
				Deformer.Warp(
					id,
					name,
					parent,
					partId,
					rows = source.row,
					columns = source.col,
					isQuadTransform = source.isQuadTransform,
					geometryGrid = fanned?.geometry,
					channelGrids = fanned?.channels ?: ChannelGrids.Empty,
					// CMO3: ACParameterControllableSource.isLocked (inverted: Cubism lock = not selectable).
					isSelectable = !source.isLocked,
					// CMO3: ACParameterControllableSource.isVisible - the deformer's own eyeball.  CMO3 has
					// no analogue of the MOC3's second flag, so isEnabled keeps its default.
					isVisible = source.isVisible,
					blendShapes =
						blendShapeBindingsOf(source.keyformMorphTargetSet, source.keyforms, context.paramIdByUuid, ::warpForm),
				)
			}

			is CRotationDeformerSource -> {
				val fanned = buildGrid(source.keyformGridSource, source.keyforms, context.paramIdByUuid, ::rotationForm)?.fanOutRotation()
				Deformer.Rotation(
					id,
					name,
					parent,
					partId,
					baseAngle = source.baseAngle,
					geometryGrid = fanned?.geometry,
					channelGrids = fanned?.channels ?: ChannelGrids.Empty,
					// CMO3: ACParameterControllableSource.isLocked (inverted: Cubism lock = not selectable).
					isSelectable = !source.isLocked,
					// CMO3: ACParameterControllableSource.isVisible - the deformer's own eyeball.  CMO3 has
					// no analogue of the MOC3's second flag, so isEnabled keeps its default.
					isVisible = source.isVisible,
					blendShapes =
						blendShapeBindingsOf(source.keyformMorphTargetSet, source.keyforms, context.paramIdByUuid, ::rotationForm),
				)
			}

			else -> {
				null
			}
		}
	}

/**
 * Warp cell payload: the form's absolute FFD control-point positions plus its render channels.
 *
 * The channels live on the shared `ACDeformerForm` base, so warp and rotation read them the same
 * way.  They cascade onto every drawable under the deformer - see `DeformerCascade`.
 *
 * @param Any form The form object (expected CWarpDeformerForm).
 * @return WarpForm? The control points, or null if absent.
 */
private fun warpForm(form: Any): WarpForm? {
	val warp = form as? CWarpDeformerForm ?: return null
	val positions = warp.positions as? FloatArray ?: return null

	// CMO3: ACDeformerForm opacity / multiplyColor / screenColor.
	return WarpForm(
		positions,
		opacity = warp.opacity,
		multiplyColor = colorRgbOf(warp.multiplyColor) ?: ColorRgb.MultiplyIdentity,
		screenColor = colorRgbOf(warp.screenColor) ?: ColorRgb.ScreenIdentity,
	)
}

/**
 * Rotation cell payload: the form's absolute pivot transform plus its render channels.
 *
 * @param Any form The form object (expected CRotationDeformerForm).
 * @return RotationForm? The transform, or null if absent.
 */
private fun rotationForm(form: Any): RotationForm? {
	val rotation = form as? CRotationDeformerForm ?: return null

	// CMO3: ACDeformerForm opacity / multiplyColor / screenColor.
	return RotationForm(
		rotation.originX,
		rotation.originY,
		rotation.angle,
		rotation.scale,
		rotation.isReflectX,
		rotation.isReflectY,
		opacity = rotation.opacity,
		multiplyColor = colorRgbOf(rotation.multiplyColor) ?: ColorRgb.MultiplyIdentity,
		screenColor = colorRgbOf(rotation.screenColor) ?: ColorRgb.ScreenIdentity,
	)
}