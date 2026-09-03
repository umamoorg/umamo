package org.umamo.edit

import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.AtlasComposition
import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PartComposite
import org.umamo.runtime.model.PartGroupMode
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RuntimeTarget
import org.umamo.runtime.model.applyUvAffine
import org.umamo.runtime.model.invertUvAffine
import org.umamo.runtime.model.multiplyColor
import org.umamo.runtime.model.opacity
import org.umamo.runtime.model.screenColor
import org.umamo.runtime.model.storedToArtAffineForTile
import org.umamo.runtime.model.withDerivedRenderRoot

/*
 * Pure transforms over the immutable PuppetModel: each returns a new model that structurally shares
 * every unchanged entity with its input (a data class copy replaces only the touched list element),
 * so producing a snapshot costs O(changed spine), not O(model). They never mutate their input, so they
 * are trivially unit-testable and safe to use as undo snapshots. This is the model-mutation half of the
 * editing core; the EditorSession wraps these with history and change events.
 */

/**
 * Returns a copy of [this] with the part [id]'s Parts-panel visibility set to [visible], sharing every
 * other part and the rest of the model. A no-op id (no such part, or the flag already matches) returns
 * the same instance, so callers can compare by reference to detect a real change.
 *
 * @param PartId id The part to retoggle.
 * @param Boolean visible The new visibility.
 * @return PuppetModel The model with that part's visibility updated, or [this] if nothing changed.
 */
fun PuppetModel.withPartVisibility(id: PartId, visible: Boolean): PuppetModel {
	val index = parts.indexOfFirst { part -> part.id == id }
	if (index < 0 || parts[index].isVisible == visible) {
		return this
	}
	val updated = parts.toMutableList()
	updated[index] = updated[index].copy(isVisible = visible)
	return copy(parts = updated)
}

/**
 * Returns a copy of [this] with the drawable [id]'s own Parts-panel visibility set to [visible], sharing
 * every other drawable and the rest of the model. A no-op id (no such drawable, or the flag already
 * matches) returns the same instance.
 *
 * @param DrawableId id The drawable to retoggle.
 * @param Boolean visible The new visibility.
 * @return PuppetModel The model with that drawable's visibility updated, or [this] if nothing changed.
 */
fun PuppetModel.withDrawableVisibility(id: DrawableId, visible: Boolean): PuppetModel {
	val index = drawables.indexOfFirst { drawable -> drawable.id == id }
	if (index < 0 || drawables[index].isVisible == visible) {
		return this
	}
	val updated = drawables.toMutableList()
	updated[index] = updated[index].copy(isVisible = visible)
	return copy(drawables = updated)
}

/**
 * Returns a copy of [this] with the part [id]'s display name set to [name], sharing every other entity.
 * A no-op id (no such part, or the name already matches) returns the same instance.
 *
 * @param PartId id The part to rename.
 * @param String name The new display name.
 * @return PuppetModel The model with that part renamed, or [this] if nothing changed.
 */
fun PuppetModel.withPartName(id: PartId, name: String): PuppetModel {
	val index = parts.indexOfFirst { part -> part.id == id }
	if (index < 0 || parts[index].name == name) {
		return this
	}
	val updated = parts.toMutableList()
	updated[index] = updated[index].copy(name = name)
	return copy(parts = updated)
}

/**
 * Returns a copy of [this] with the drawable [id]'s display name set to [name], sharing every other
 * entity. A no-op id (no such drawable, or the name already matches) returns the same instance.
 *
 * @param DrawableId id The drawable to rename.
 * @param String name The new display name.
 * @return PuppetModel The model with that drawable renamed, or [this] if nothing changed.
 */
fun PuppetModel.withDrawableName(id: DrawableId, name: String): PuppetModel {
	val index = drawables.indexOfFirst { drawable -> drawable.id == id }
	if (index < 0 || drawables[index].name == name) {
		return this
	}
	val updated = drawables.toMutableList()
	updated[index] = updated[index].copy(name = name)
	return copy(drawables = updated)
}

/**
 * Returns a copy of [this] with the deformer [id]'s display name set to [name], sharing every other
 * entity. Handles both deformer kinds. A no-op id (no such deformer, or the name already matches)
 * returns the same instance.
 *
 * @param DeformerId id The deformer to rename.
 * @param String name The new display name.
 * @return PuppetModel The model with that deformer renamed, or [this] if nothing changed.
 */
fun PuppetModel.withDeformerName(id: DeformerId, name: String): PuppetModel {
	val index = deformers.indexOfFirst { deformer -> deformer.id == id }
	if (index < 0 || deformers[index].name == name) {
		return this
	}
	val updated = deformers.toMutableList()
	updated[index] =
		when (val deformer = updated[index]) {
			is Deformer.Warp -> deformer.copy(name = name)
			is Deformer.Rotation -> deformer.copy(name = name)
		}
	return copy(deformers = updated)
}

/**
 * Returns a copy of [this] with the part [id]'s selectable flag set to [selectable], sharing every other
 * entity. A no-op id (no such part, or the flag already matches) returns the same instance.
 *
 * @param PartId id The part whose selectability to set.
 * @param Boolean selectable The new selectable state.
 * @return PuppetModel The model with that part's selectability updated, or [this] if nothing changed.
 */
fun PuppetModel.withPartSelectable(id: PartId, selectable: Boolean): PuppetModel {
	val index = parts.indexOfFirst { part -> part.id == id }
	if (index < 0 || parts[index].isSelectable == selectable) {
		return this
	}
	val updated = parts.toMutableList()
	updated[index] = updated[index].copy(isSelectable = selectable)
	return copy(parts = updated)
}

/**
 * Returns a copy of [this] with the drawable [id]'s selectable flag set to [selectable], sharing every
 * other entity. A no-op id (no such drawable, or the flag already matches) returns the same instance.
 *
 * @param DrawableId id The drawable whose selectability to set.
 * @param Boolean selectable The new selectable state.
 * @return PuppetModel The model with that drawable's selectability updated, or [this] if nothing changed.
 */
fun PuppetModel.withDrawableSelectable(id: DrawableId, selectable: Boolean): PuppetModel {
	val index = drawables.indexOfFirst { drawable -> drawable.id == id }
	if (index < 0 || drawables[index].isSelectable == selectable) {
		return this
	}
	val updated = drawables.toMutableList()
	updated[index] = updated[index].copy(isSelectable = selectable)
	return copy(drawables = updated)
}

/**
 * Returns a copy of [this] with the deformer [id]'s selectable flag set to [selectable], handling both
 * deformer kinds and sharing every other entity. A no-op id returns the same instance.
 *
 * @param DeformerId id The deformer whose selectability to set.
 * @param Boolean selectable The new selectable state.
 * @return PuppetModel The model with that deformer's selectability updated, or [this] if nothing changed.
 */
fun PuppetModel.withDeformerSelectable(id: DeformerId, selectable: Boolean): PuppetModel {
	val index = deformers.indexOfFirst { deformer -> deformer.id == id }
	if (index < 0 || deformers[index].isSelectable == selectable) {
		return this
	}
	val updated = deformers.toMutableList()
	updated[index] =
		when (val deformer = updated[index]) {
			is Deformer.Warp -> deformer.copy(isSelectable = selectable)
			is Deformer.Rotation -> deformer.copy(isSelectable = selectable)
		}
	return copy(deformers = updated)
}

/**
 * Returns a copy of [this] with the drawable [id]'s base art-mesh positions replaced by [newPositions],
 * sharing every other drawable and the rest of the model. Copy-on-write at the mesh leaf: it wraps
 * [newPositions] in a NEW [DrawableMesh] and shares the unchanged uvs / indices arrays by reference, so a
 * prior snapshot's positions array is never mutated. A no-op (no such drawable, no mesh, the same array
 * instance, or a length mismatch - vertex count is fixed in this slice) returns the same instance so the
 * session records nothing.
 *
 * The caller must pass a freshly built array (e.g. from [MeshTransforms]); never the live mesh array.
 *
 * @param DrawableId id The drawable whose mesh to retarget.
 * @param FloatArray newPositions The new interleaved (x, y) rest positions, same length as the current.
 * @return PuppetModel The model with that mesh updated, or [this] if nothing changed.
 */
fun PuppetModel.withMeshPositions(id: DrawableId, newPositions: FloatArray): PuppetModel {
	val index = drawables.indexOfFirst { drawable -> drawable.id == id }
	if (index < 0) {
		return this
	}
	val mesh = drawables[index].mesh
	if (mesh == null || newPositions === mesh.positions || newPositions.size != mesh.positions.size) {
		return this
	}
	val updated = drawables.toMutableList()
	updated[index] = updated[index].copy(mesh = DrawableMesh(newPositions, mesh.uvs, mesh.indices))
	return copy(drawables = updated)
}

/**
 * Returns a copy of [this] with the drawable [id]'s texture UVs replaced by [newUvs], sharing every
 * other drawable and the rest of the model. The mirror image of [withMeshPositions], copy-on-write at
 * the mesh leaf: it wraps [newUvs] in a NEW [DrawableMesh] and shares the unchanged positions / indices
 * arrays by reference, so retargeting which atlas texels a mesh samples never disturbs its rest
 * geometry - the mesh/UV decoupling invariant seen from the UV side. A no-op (no such drawable, no
 * mesh, the same array instance, or a length mismatch - vertex count never changes here) returns the
 * same instance so the session records nothing.
 *
 * The caller must pass a freshly built array (e.g. from [MeshTransforms]); never the live mesh array.
 *
 * @param DrawableId id The drawable whose texture mapping to retarget.
 * @param FloatArray newUvs The new interleaved (u, v) atlas coordinates, same length as the current.
 * @return PuppetModel The model with that mesh's UVs updated, or [this] if nothing changed.
 */
fun PuppetModel.withMeshUvs(id: DrawableId, newUvs: FloatArray): PuppetModel {
	val index = drawables.indexOfFirst { drawable -> drawable.id == id }
	if (index < 0) {
		return this
	}
	val mesh = drawables[index].mesh
	if (mesh == null || newUvs === mesh.uvs || newUvs.size != mesh.uvs.size) {
		return this
	}
	val updated = drawables.toMutableList()
	updated[index] = updated[index].copy(mesh = DrawableMesh(mesh.positions, newUvs, mesh.indices))
	return copy(drawables = updated)
}

/**
 * Returns a copy of [this] with the drawable [id]'s color blend mode set to [mode], sharing every other
 * entity. A no-op id (no such drawable, or the mode already matches) returns the same instance.
 *
 * @param DrawableId id The drawable to retarget.
 * @param BlendMode mode The new blend mode.
 * @return PuppetModel The model with that drawable's blend mode updated, or [this] if nothing changed.
 */
fun PuppetModel.withDrawableBlendMode(id: DrawableId, mode: BlendMode): PuppetModel {
	val index = drawables.indexOfFirst { drawable -> drawable.id == id }
	if (index < 0 || drawables[index].blendMode == mode) {
		return this
	}
	val updated = drawables.toMutableList()
	updated[index] = updated[index].copy(blendMode = mode)
	return copy(drawables = updated)
}

/**
 * Returns a copy of [this] with the drawable [id]'s alpha blend mode set to [mode], sharing every other
 * entity. A no-op id (no such drawable, or the mode already matches) returns the same instance.
 *
 * @param DrawableId id The drawable to retarget.
 * @param AlphaBlendMode mode The new alpha blend mode.
 * @return PuppetModel The model with that drawable's alpha blend mode updated, or [this] if nothing changed.
 */
fun PuppetModel.withDrawableAlphaBlendMode(id: DrawableId, mode: AlphaBlendMode): PuppetModel {
	val index = drawables.indexOfFirst { drawable -> drawable.id == id }
	if (index < 0 || drawables[index].alphaBlendMode == mode) {
		return this
	}
	val updated = drawables.toMutableList()
	updated[index] = updated[index].copy(alphaBlendMode = mode)
	return copy(drawables = updated)
}

/**
 * Returns a copy of [this] with the drawable [id]'s back-face culling set to [culling], sharing every
 * other entity. A no-op id (no such drawable, or the flag already matches) returns the same instance.
 *
 * @param DrawableId id The drawable to retarget.
 * @param Boolean culling The new culling state.
 * @return PuppetModel The model with that drawable's culling updated, or [this] if nothing changed.
 */
fun PuppetModel.withDrawableCulling(id: DrawableId, culling: Boolean): PuppetModel {
	val index = drawables.indexOfFirst { drawable -> drawable.id == id }
	if (index < 0 || drawables[index].culling == culling) {
		return this
	}
	val updated = drawables.toMutableList()
	updated[index] = updated[index].copy(culling = culling)
	return copy(drawables = updated)
}

/**
 * Returns a copy of [this] with the drawable [id]'s static opacity set to [opacity], sharing every other
 * entity. A no-op id (no such drawable, or the value already matches) returns the same instance.
 *
 * Writes the STATIC only. When the drawable keys opacity, the track shadows this and the edit is invisible
 * at any pose the track covers - which is why the Properties row routes a keyed channel's edit into the
 * pending-edit buffer instead of here.
 *
 * @param DrawableId id The drawable to retarget.
 * @param Float opacity The new opacity.
 * @return PuppetModel The model with that drawable's opacity updated, or [this] if nothing changed.
 */
fun PuppetModel.withDrawableOpacity(id: DrawableId, opacity: Float): PuppetModel {
	val index = drawables.indexOfFirst { drawable -> drawable.id == id }
	if (index < 0 || drawables[index].opacity == opacity) {
		return this
	}
	val updated = drawables.toMutableList()
	updated[index] = updated[index].copy(opacity = opacity)
	return copy(drawables = updated)
}

/**
 * Returns a copy of [this] with the drawable [id]'s static draw order set to [drawOrder], sharing every
 * other entity. A no-op id (no such drawable, or the value already matches) returns the same instance.
 *
 * Deliberately does NOT re-derive the render root, unlike [withPartDrawOrder]. A part's draw order is
 * baked into the derived group tree; a drawable's is resolved per POSE at render time as its draw-order
 * channel's static, so the tree is unaffected and re-deriving would be wasted work on every edit.
 *
 * @param DrawableId id The drawable to retarget.
 * @param Float drawOrder The new draw order.
 * @return PuppetModel The model with that drawable's draw order updated, or [this] if nothing changed.
 */
fun PuppetModel.withDrawableDrawOrder(id: DrawableId, drawOrder: Float): PuppetModel {
	val index = drawables.indexOfFirst { drawable -> drawable.id == id }
	if (index < 0 || drawables[index].drawOrder == drawOrder) {
		return this
	}
	val updated = drawables.toMutableList()
	updated[index] = updated[index].copy(drawOrder = drawOrder)
	return copy(drawables = updated)
}

/**
 * Returns a copy of [this] with drawable [id]'s static [FormChannel.MULTIPLY_COLOR] set to [color].
 * A no-op (missing drawable, or the color already set) returns the same instance.
 *
 * A single-field copy: the tint is its own track with its own static, so this writes just that field
 * rather than rewriting every keyform cell.  Rewriting the whole grid instead would flatten any authored
 * per-keyform color animation, and would trip diffModel's identity check into re-uploading the drawable's
 * geometry for a mere color change.
 *
 * @param DrawableId id The drawable to retint.
 * @param ColorRgb color The new multiply color.
 * @return PuppetModel The model with that drawable's multiply color updated, or [this] if nothing changed.
 */
fun PuppetModel.withDrawableMultiplyColor(id: DrawableId, color: ColorRgb): PuppetModel {
	val index = drawables.indexOfFirst { drawable -> drawable.id == id }
	if (index < 0 || drawables[index].multiplyColor == color) {
		return this
	}
	val updated = drawables.toMutableList()
	updated[index] = updated[index].copy(multiplyColor = color)
	return copy(drawables = updated)
}

/**
 * Returns a copy of [this] with drawable [id]'s static [FormChannel.SCREEN_COLOR] set to [color]; see
 * [withDrawableMultiplyColor].
 *
 * @param DrawableId id The drawable to retint.
 * @param ColorRgb color The new screen color.
 * @return PuppetModel The model with that drawable's screen color updated, or [this] if nothing changed.
 */
fun PuppetModel.withDrawableScreenColor(id: DrawableId, color: ColorRgb): PuppetModel {
	val index = drawables.indexOfFirst { drawable -> drawable.id == id }
	if (index < 0 || drawables[index].screenColor == color) {
		return this
	}
	val updated = drawables.toMutableList()
	updated[index] = updated[index].copy(screenColor = color)
	return copy(drawables = updated)
}

/**
 * Returns a copy of [this] with the drawable [id]'s mask-inversion flag set to [invert], sharing every
 * other entity. A no-op id (no such drawable, or the flag already matches) returns the same instance.
 *
 * @param DrawableId id The drawable to retarget.
 * @param Boolean invert The new inverted-mask state.
 * @return PuppetModel The model with that drawable's mask inversion updated, or [this] if nothing changed.
 */
fun PuppetModel.withDrawableInvertMask(id: DrawableId, invert: Boolean): PuppetModel {
	val index = drawables.indexOfFirst { drawable -> drawable.id == id }
	if (index < 0 || drawables[index].invertMask == invert) {
		return this
	}
	val updated = drawables.toMutableList()
	updated[index] = updated[index].copy(invertMask = invert)
	return copy(drawables = updated)
}

/**
 * Returns a copy of [this] with the drawable [id] bound to the deformer [parentDeformerId] (null unbinds),
 * sharing every other entity. A no-op id (no such drawable, or the binding already matches) returns the
 * same instance. A drawable is deformed by, but never a child of, a deformer - so this is a flat field
 * write with no tree surgery and no render-order rederive.
 *
 * @param DrawableId id The drawable to rebind.
 * @param DeformerId? parentDeformerId The deformer that deforms it, or null to unbind.
 * @return PuppetModel The model with that binding updated, or [this] if nothing changed.
 */
fun PuppetModel.withDrawableParentDeformer(id: DrawableId, parentDeformerId: DeformerId?): PuppetModel {
	val index = drawables.indexOfFirst { drawable -> drawable.id == id }
	if (index < 0 || drawables[index].parentDeformerId == parentDeformerId) {
		return this
	}
	val updated = drawables.toMutableList()
	updated[index] = updated[index].copy(parentDeformerId = parentDeformerId)
	return copy(drawables = updated)
}

/**
 * Returns a copy of [this] with the drawable [id]'s clip-mask list replaced by [maskedBy], sharing every
 * other entity. A no-op id (no such drawable, or the list already matches) returns the same instance.
 *
 * @param DrawableId id The drawable whose masks change.
 * @param List maskedBy The drawables whose alpha now clips it.
 * @return PuppetModel The model with that mask list updated, or [this] if nothing changed.
 */
fun PuppetModel.withDrawableMaskedBy(id: DrawableId, maskedBy: List<DrawableId>): PuppetModel {
	val index = drawables.indexOfFirst { drawable -> drawable.id == id }
	if (index < 0 || drawables[index].maskedBy == maskedBy) {
		return this
	}
	val updated = drawables.toMutableList()
	updated[index] = updated[index].copy(maskedBy = maskedBy)
	return copy(drawables = updated)
}

/**
 * Returns a copy of [this] with the deformer [id] bound to the organizational part [partId] (null clears
 * it), sharing every other entity. A no-op id (no such deformer, or the binding already matches) returns
 * the same instance. The part reference is loose - no Part.children entry corresponds to it - so this is a
 * flat field write; the copy is per-subtype because Deformer is a sealed interface.
 *
 * @param DeformerId id The deformer to rebind.
 * @param PartId? partId The part that owns it, or null to clear.
 * @return PuppetModel The model with that binding updated, or [this] if nothing changed.
 */
fun PuppetModel.withDeformerPart(id: DeformerId, partId: PartId?): PuppetModel {
	val index = deformers.indexOfFirst { deformer -> deformer.id == id }
	if (index < 0 || deformers[index].partId == partId) {
		return this
	}
	val updated = deformers.toMutableList()
	updated[index] =
		when (val deformer = updated[index]) {
			is Deformer.Warp -> deformer.copy(partId = partId)
			is Deformer.Rotation -> deformer.copy(partId = partId)
		}
	return copy(deformers = updated)
}

/**
 * Returns a copy of [this] with the deformer [id]'s static opacity set to [opacity], sharing every other
 * entity. A no-op id (no such deformer, or the value already matches) returns the same instance.
 *
 * A deformer's render channels CASCADE onto every drawable beneath it - they compose by product at render
 * time - so this is a different lever from setting each of those drawables' own opacity.
 *
 * @param DeformerId id The deformer to retarget.
 * @param Float opacity The new opacity.
 * @return PuppetModel The model with that deformer's opacity updated, or [this] if nothing changed.
 */
fun PuppetModel.withDeformerOpacity(id: DeformerId, opacity: Float): PuppetModel =
	withDeformerRewritten(id, { deformer -> deformer.opacity == opacity }) { deformer ->
		when (deformer) {
			is Deformer.Warp -> deformer.copy(opacity = opacity)
			is Deformer.Rotation -> deformer.copy(opacity = opacity)
		}
	}

/**
 * Returns a copy of [this] with the deformer [id]'s static multiply color set to [color], sharing every
 * other entity. A no-op id (no such deformer, or the value already matches) returns the same instance.
 *
 * @param DeformerId id The deformer to retarget.
 * @param ColorRgb color The new multiply color.
 * @return PuppetModel The model with that deformer's multiply color updated, or [this] if unchanged.
 */
fun PuppetModel.withDeformerMultiplyColor(id: DeformerId, color: ColorRgb): PuppetModel =
	withDeformerRewritten(id, { deformer -> deformer.multiplyColor == color }) { deformer ->
		when (deformer) {
			is Deformer.Warp -> deformer.copy(multiplyColor = color)
			is Deformer.Rotation -> deformer.copy(multiplyColor = color)
		}
	}

/**
 * Returns a copy of [this] with the deformer [id]'s static screen color set to [color], sharing every
 * other entity. A no-op id (no such deformer, or the value already matches) returns the same instance.
 *
 * @param DeformerId id The deformer to retarget.
 * @param ColorRgb color The new screen color.
 * @return PuppetModel The model with that deformer's screen color updated, or [this] if unchanged.
 */
fun PuppetModel.withDeformerScreenColor(id: DeformerId, color: ColorRgb): PuppetModel =
	withDeformerRewritten(id, { deformer -> deformer.screenColor == color }) { deformer ->
		when (deformer) {
			is Deformer.Warp -> deformer.copy(screenColor = color)
			is Deformer.Rotation -> deformer.copy(screenColor = color)
		}
	}

/**
 * Returns a copy of [this] with the ROTATION deformer [id]'s static horizontal reflection set to [flip].
 *
 * A no-op (no such deformer, a warp - which has no reflection - or the value already matches) returns the
 * same instance.
 *
 * @param DeformerId id The deformer to retarget.
 * @param Boolean flip The new reflection state.
 * @return PuppetModel The model with that deformer's flip updated, or [this] if nothing changed.
 */
fun PuppetModel.withDeformerFlipX(id: DeformerId, flip: Boolean): PuppetModel =
	withDeformerRewritten(id, { deformer -> deformer !is Deformer.Rotation || deformer.flipX == flip }) { deformer ->
		(deformer as Deformer.Rotation).copy(flipX = flip)
	}

/**
 * Returns a copy of [this] with the ROTATION deformer [id]'s static vertical reflection set to [flip].
 *
 * @param DeformerId id The deformer to retarget.
 * @param Boolean flip The new reflection state.
 * @return PuppetModel The model with that deformer's flip updated, or [this] if nothing changed.
 */
fun PuppetModel.withDeformerFlipY(id: DeformerId, flip: Boolean): PuppetModel =
	withDeformerRewritten(id, { deformer -> deformer !is Deformer.Rotation || deformer.flipY == flip }) { deformer ->
		(deformer as Deformer.Rotation).copy(flipY = flip)
	}

/**
 * The shared deformer-rewrite skeleton: find, refuse a no-op, copy-on-write.
 *
 * Deformer is a sealed interface with no shared copy, so every static setter would otherwise repeat the
 * same find / guard / toMutableList dance around a two-branch when.  Factored so adding a subtype is one
 * compile error per op rather than a silently unhandled branch.
 *
 * @param DeformerId id The deformer to rewrite.
 * @param Function isNoOp True when the edit would change nothing, or does not apply to this subtype.
 * @param Function rewrite Produces the replacement deformer.
 * @return PuppetModel The rewritten model, or [this] when the edit was a no-op.
 */
private inline fun PuppetModel.withDeformerRewritten(
	id: DeformerId,
	isNoOp: (Deformer) -> Boolean,
	rewrite: (Deformer) -> Deformer,
): PuppetModel {
	val index = deformers.indexOfFirst { deformer -> deformer.id == id }
	if (index < 0 || isNoOp(deformers[index])) {
		return this
	}
	val updated = deformers.toMutableList()
	updated[index] = rewrite(updated[index])
	return copy(deformers = updated)
}

/**
 * Returns a copy of [this] with the rotation deformer [id]'s base angle set to [angle], sharing every
 * other entity. A no-op (no such deformer, a warp deformer - which has no base angle - or the angle
 * already matches) returns the same instance.
 *
 * @param DeformerId id The deformer to retarget.
 * @param Float angle The new base angle in degrees.
 * @return PuppetModel The model with that deformer's base angle updated, or [this] if nothing changed.
 */
fun PuppetModel.withDeformerBaseAngle(id: DeformerId, angle: Float): PuppetModel {
	val index = deformers.indexOfFirst { deformer -> deformer.id == id }
	if (index < 0) {
		return this
	}
	val deformer = deformers[index]
	if (deformer !is Deformer.Rotation || deformer.baseAngle == angle) {
		return this
	}
	val updated = deformers.toMutableList()
	updated[index] = deformer.copy(baseAngle = angle)
	return copy(deformers = updated)
}

/**
 * Returns a copy of [this] with the warp deformer [id]'s FFD interpolation mode set to [quad], sharing
 * every other entity. A no-op (no such deformer, a rotation deformer - which has no lattice - or the
 * flag already matches) returns the same instance.
 *
 * @param DeformerId id The deformer to retarget.
 * @param Boolean quad The new quad-transform state.
 * @return PuppetModel The model with that deformer's interpolation mode updated, or [this] if nothing changed.
 */
fun PuppetModel.withDeformerQuadTransform(id: DeformerId, quad: Boolean): PuppetModel {
	val index = deformers.indexOfFirst { deformer -> deformer.id == id }
	if (index < 0) {
		return this
	}
	val deformer = deformers[index]
	if (deformer !is Deformer.Warp || deformer.isQuadTransform == quad) {
		return this
	}
	val updated = deformers.toMutableList()
	updated[index] = deformer.copy(isQuadTransform = quad)
	return copy(deformers = updated)
}

/**
 * Returns a copy of [this] with the part [id]'s guide-image (sketch) flag set to [sketch], sharing every
 * other entity. A no-op id (no such part, or the flag already matches) returns the same instance.
 *
 * @param PartId id The part to retarget.
 * @param Boolean sketch The new sketch state.
 * @return PuppetModel The model with that part's sketch flag updated, or [this] if nothing changed.
 */
fun PuppetModel.withPartSketch(id: PartId, sketch: Boolean): PuppetModel {
	val index = parts.indexOfFirst { part -> part.id == id }
	if (index < 0 || parts[index].isSketch == sketch) {
		return this
	}
	val updated = parts.toMutableList()
	updated[index] = updated[index].copy(isSketch = sketch)
	return copy(parts = updated)
}

/**
 * Returns a copy of [this] with the part [id]'s own draw order set to [order], sharing every other
 * entity. A no-op id (no such part, or the value already matches) returns the same instance.
 *
 * @param PartId id The part to retarget.
 * @param Int order The new draw order.
 * @return PuppetModel The model with that part's draw order updated, or [this] if nothing changed.
 */
fun PuppetModel.withPartDrawOrder(id: PartId, order: Int): PuppetModel {
	val index = parts.indexOfFirst { part -> part.id == id }
	if (index < 0 || parts[index].drawOrder == order) {
		return this
	}
	val updated = parts.toMutableList()
	updated[index] = updated[index].copy(drawOrder = order)
	// Draw order feeds the derived render tree (a part's group slot), so re-derive renderRoot or the
	// renderer keeps sorting by the pre-edit order - the same reason every structural edit re-derives.
	return copy(parts = updated).withDerivedRenderRoot()
}

/**
 * Returns a copy of [this] with the part [id]'s rendering group mode set to [mode], sharing every other
 * entity. Carries the whole mode value, so an Isolated switch and any composite sub-field edit go
 * through here alike. A no-op id (no such part, or the mode already matches) returns the same instance.
 *
 * @param PartId id The part to retarget.
 * @param PartGroupMode mode The new group mode.
 * @return PuppetModel The model with that part's group mode updated, or [this] if nothing changed.
 */
fun PuppetModel.withPartGroupMode(id: PartId, mode: PartGroupMode): PuppetModel {
	val index = parts.indexOfFirst { part -> part.id == id }
	if (index < 0 || parts[index].groupMode == mode) {
		return this
	}
	val updated = parts.toMutableList()
	updated[index] = updated[index].copy(groupMode = mode)
	// Group mode decides whether the part is a render-tree boundary (Isolated/Grouped) or transparent
	// (PassThrough hoists its children), so re-derive renderRoot or the plan keeps the old structure.
	return copy(parts = updated).withDerivedRenderRoot()
}

/**
 * Returns a copy of [this] with the part [id]'s latent compositing settings set to [composite], sharing
 * every other entity.  Stored independent of the part's group mode (so it survives a mode round-trip) and
 * applied only while the part is Isolated.  A no-op id (no such part, or the composite already matches)
 * returns the same instance.
 *
 * @param PartId id The part to retarget.
 * @param PartComposite composite The new composite settings.
 * @return PuppetModel The model with that part's composite updated, or [this] if nothing changed.
 */
fun PuppetModel.withPartComposite(id: PartId, composite: PartComposite): PuppetModel {
	val index = parts.indexOfFirst { part -> part.id == id }
	if (index < 0 || parts[index].composite == composite) {
		return this
	}
	val updated = parts.toMutableList()
	updated[index] = updated[index].copy(composite = composite)
	// resolvedComposite bakes the composite into RenderGroup.composite at derive time (masked-by parts
	// expanded), so re-derive renderRoot or the renderer re-reads the pre-edit blend/opacity/colors/masks.
	return copy(parts = updated).withDerivedRenderRoot()
}

/**
 * Returns a copy of [this] with the document canvas size set to [width] x [height] in world units,
 * sharing the rest of the model. A no-op (both dimensions already match) returns the same instance.
 *
 * @param Float width The new canvas width.
 * @param Float height The new canvas height.
 * @return PuppetModel The model with the canvas resized, or [this] if nothing changed.
 */
fun PuppetModel.withCanvasSize(width: Float, height: Float): PuppetModel {
	if (canvasWidth == width && canvasHeight == height) {
		return this
	}
	return copy(canvasWidth = width, canvasHeight = height)
}

/**
 * Returns a copy of [this] with the world origin set to ([x], [y]) in world space, sharing the rest of
 * the model. A no-op (both coordinates already match) returns the same instance.
 *
 * @param Float x The new world-origin x.
 * @param Float y The new world-origin y.
 * @return PuppetModel The model with the world origin moved, or [this] if nothing changed.
 */
fun PuppetModel.withWorldOrigin(x: Float, y: Float): PuppetModel {
	if (worldOriginX == x && worldOriginY == y) {
		return this
	}
	return copy(worldOriginX = x, worldOriginY = y)
}

/**
 * Returns a copy of [this] with the runtime-compatibility target set to [target], sharing the rest
 * of the model. A no-op (the target already matches) returns the same instance.
 *
 * @param RuntimeTarget target The new runtime target.
 * @return PuppetModel The model with the target set, or [this] if nothing changed.
 */
fun PuppetModel.withRuntimeTarget(target: RuntimeTarget): PuppetModel {
	if (runtimeTarget == target) {
		return this
	}
	return copy(runtimeTarget = target)
}

/**
 * This model displaying from its source artwork rather than from the packed atlas, or itself when the
 * mode already matches.
 *
 * @param Boolean fromSourceLayers True to display from the source artwork, false from the atlas.
 * @return PuppetModel The model with the display mode applied.
 */
fun PuppetModel.withSourceLayerDisplay(fromSourceLayers: Boolean): PuppetModel {
	if (rendersFromSourceLayers == fromSourceLayers) {
		return this
	}
	return copy(rendersFromSourceLayers = fromSourceLayers)
}

/**
 * This model with one atlas tile packed at [placement], every drawable over that art re-mapped so it
 * keeps sampling the same pixels.
 *
 * Moving art around a page must not change what the art means.  Stored texture coordinates address the
 * PAGE, so a placement that moves without them moving with it silently re-points every mesh at whatever
 * now occupies the old spot; that is why this rewrites both together, and why a repack is an edit on
 * the tile rather than on each drawable.  The re-mapping is the composition of the old mapping into the
 * art's own frame with the inverse of the new one, so the vertex-to-art-pixel binding comes out
 * bit-identical wherever the affines are exact.
 *
 * The mappings are built from the placements directly: stored coordinates mean the same thing whichever
 * surface is being displayed, so a document whose coordinates address the packed pages re-maps here and
 * one whose coordinates already address the art does not.
 *
 * PIXELS ARE NOT MOVED.  A page's bytes belong to the document, not the model, so a caller that moves a
 * placement must recompose the page for the result to render; the session's page resolver does that
 * from the committed model, which is what lets the placement gizmo commit here and let the pixels
 * follow.
 *
 * A no-op returns the same instance.  So does an edit that cannot be expressed: an unknown tile, a
 * placement naming a page the document does not have, or a mapping that will not invert (a zero scale,
 * a zero-sized tile).  Refusing beats writing a placement whose coordinates cannot follow it.
 *
 * @param AtlasTileId     tileId    The tile to place.
 * @param AtlasPlacement? placement Where its art now sits, or null to mark it unpacked.
 * @return PuppetModel The model with the placement and the re-derived coordinates, or [this].
 */
fun PuppetModel.withAtlasPlacement(tileId: AtlasTileId, placement: AtlasPlacement?): PuppetModel =
	withAtlasPlacements(mapOf(tileId to placement))

/**
 * This model with several tiles placed anew in one pass - [withAtlasPlacement] over a whole gesture's
 * worth of tiles, every drawable over each moved tile re-mapped so it keeps sampling the same pixels.
 *
 * One pass rather than a fold of single-tile edits so the result is one instance for one history
 * push, and so the whole edit is refused together: an unknown tile, a placement naming a page the
 * document does not have, or a mapping that will not invert anywhere in the map returns [this]
 * untouched rather than moving the tiles that happened to precede the fault.  The page inventory
 * never changes here - that is the repack's op, [withAtlasRepack].
 *
 * @param Map placementByTile Each tile's new placement, keyed by tile, null to mark it unpacked.
 * @return PuppetModel The model with the placements and the re-derived coordinates, or [this] when
 *   nothing changes or the edit cannot be expressed.
 */
fun PuppetModel.withAtlasPlacements(placementByTile: Map<AtlasTileId, AtlasPlacement?>): PuppetModel {
	if (placementByTile.isEmpty()) {
		return this
	}
	val movedTiles = atlas.tiles.toMutableList()
	val changedTileIds = ArrayList<AtlasTileId>()
	for ((tileId, placement) in placementByTile) {
		val tileIndex = movedTiles.indexOfFirst { tile -> tile.id == tileId }
		if (tileIndex < 0) {
			return this
		}
		if (placement != null && placement.pageIndex !in atlas.pages.indices) {
			return this
		}
		val tile = movedTiles[tileIndex]
		if (tile.placement == placement) {
			continue
		}
		movedTiles[tileIndex] = tile.copy(placement = placement)
		changedTileIds.add(tileId)
	}
	if (changedTileIds.isEmpty()) {
		return this
	}
	val newAtlas = atlas.copy(tiles = movedTiles)
	val remapByTile = HashMap<AtlasTileId, AtlasTileRemap>()
	for (tileId in changedTileIds) {
		when (val outcome = tileRemap(atlas, newAtlas, tileId)) {
			TileRemapOutcome.Unchanged -> Unit
			TileRemapOutcome.Inexpressible -> return this
			is TileRemapOutcome.Remap -> remapByTile[tileId] = outcome.remap
		}
	}
	return copy(atlas = newAtlas, drawables = drawables.remappedOver(remapByTile))
}

/**
 * One tile's coordinate re-derivation across a placement change: out of the old mapping's frame, into
 * the new.
 *
 * @property FloatArray storedToArt The old stored-to-art mapping.
 * @property FloatArray artToStored The inverse of the new one.
 */
private class AtlasTileRemap(
	val storedToArt: FloatArray,
	val artToStored: FloatArray,
)

/** What a tile's placement change means for the texture coordinates over it. */
private sealed interface TileRemapOutcome {
	/** The mapping is the same under both atlases, so the coordinates are left alone. */
	data object Unchanged : TileRemapOutcome

	/** One side has no mapping: a page the atlas lacks, a degenerate placement, or a zero-sized tile. */
	data object Inexpressible : TileRemapOutcome

	/**
	 * The coordinates move through [remap].
	 *
	 * @property AtlasTileRemap remap The re-derivation to apply.
	 */
	class Remap(val remap: AtlasTileRemap) : TileRemapOutcome
}

/**
 * How [tileId]'s texture coordinates move between [oldAtlas] and [newAtlas] - the one resolution both
 * the per-tile edit and the whole-atlas repack read.
 *
 * Each side reads its mapping against ITS OWN page inventory: the page a placement names supplies the
 * mapping's normalization, so the two reads must not share pages.  That is also why the repack is a
 * distinct op from the per-tile edit - only it can change the inventory the new side reads.
 *
 * @param PuppetAtlas oldAtlas The atlas the coordinates currently address.
 * @param PuppetAtlas newAtlas The atlas they will address, with the tile already moved.
 * @param AtlasTileId tileId   The tile whose mapping changed.
 * @return TileRemapOutcome Unchanged, inexpressible, or the remap to apply.
 */
private fun tileRemap(oldAtlas: PuppetAtlas, newAtlas: PuppetAtlas, tileId: AtlasTileId): TileRemapOutcome {
	val oldAffine = oldAtlas.storedToArtAffineForTile(tileId)
	val newAffine = newAtlas.storedToArtAffineForTile(tileId)
	if (oldAffine != null && newAffine != null && oldAffine.contentEquals(newAffine)) {
		return TileRemapOutcome.Unchanged
	}
	val artToStored = newAffine?.let { affine -> invertUvAffine(affine) }
	if (oldAffine == null || artToStored == null) {
		return TileRemapOutcome.Inexpressible
	}
	return TileRemapOutcome.Remap(AtlasTileRemap(oldAffine, artToStored))
}

/**
 * These drawables with every one over a tile in [remapByTile] carried through that tile's remap.  Every
 * other drawable, and every mesh's positions, passes through by reference: moving art on a page never
 * moves the mesh, and an untouched coordinate array must not pick up float round-trip noise.
 *
 * @param Map remapByTile The re-derivation per moved tile.
 * @return List<Drawable> The drawables, the same list when nothing moved.
 */
private fun List<Drawable>.remappedOver(remapByTile: Map<AtlasTileId, AtlasTileRemap>): List<Drawable> {
	if (remapByTile.isEmpty()) {
		return this
	}
	return map { drawable ->
		val remap = drawable.atlasTileId?.let { tileId -> remapByTile[tileId] } ?: return@map drawable
		val mesh = drawable.mesh ?: return@map drawable
		val artUvs = applyUvAffine(mesh.uvs, remap.storedToArt)
		drawable.copy(mesh = DrawableMesh(mesh.positions, applyUvAffine(artUvs, remap.artToStored), mesh.indices))
	}
}

/**
 * This model repacked: the page inventory replaced by [pages], every tile's placement restated from
 * [placementsByTile], and every bound drawable's texture coordinates re-derived, in one pass.
 *
 * The whole-atlas twin of [withAtlasPlacement], and a distinct op because the per-tile edit cannot
 * change the page list: a tile's old mapping normalizes against the CURRENT pages and its new one
 * against the REPLACEMENT pages, so folding per-tile edits would read the new mapping off a stale
 * inventory.  One call is also one history push - a repack is one gesture, not one step per tile.
 *
 * Input is validated up front instead of refused tile-by-tile: the placement map must restate every
 * tile (an explicit null marks it unpacked), every placement must name one of [pages], and a tile
 * with drawables bound must carry an expressible re-derivation.  The orchestrating repack guarantees
 * all three - it aborts before mutating when a bound tile cannot come along - so a violation here is
 * a caller bug, not document state to absorb.  A tile with NO drawables bound may have an
 * inexpressible mapping (a degenerate imported placement); its placement still installs, and there
 * are no coordinates to move.
 *
 * A tile whose mapping comes out unchanged is left alone entirely, so an untouched drawable's
 * coordinate arrays pass through by reference rather than picking up float round-trip noise.
 *
 * PIXELS ARE NOT MOVED - [withAtlasPlacement]'s rule, unchanged: the caller must compose the new
 * pages for the result to render.
 *
 * @param List             pages            The new page inventory.
 * @param Map              placementsByTile Every tile's new placement, keyed by tile, null for unpacked.
 * @param AtlasComposition composition      The trim and extrusion policy the pack composed under, recorded
 *   so the pages derive the same way; defaults to the atlas's current policy.
 * @return PuppetModel The repacked model, or [this] when nothing changes.
 */
fun PuppetModel.withAtlasRepack(
	pages: List<AtlasPage>,
	placementsByTile: Map<AtlasTileId, AtlasPlacement?>,
	composition: AtlasComposition = atlas.composition,
): PuppetModel {
	require(placementsByTile.keys == atlas.tiles.map { tile -> tile.id }.toSet()) {
		"a repack must restate every tile's placement exactly once"
	}
	for ((tileId, placement) in placementsByTile) {
		if (placement != null) {
			require(placement.pageIndex in pages.indices) {
				"tile '${tileId.raw}' names page ${placement.pageIndex} of ${pages.size}"
			}
		}
	}
	val movedTiles = atlas.tiles.map { tile -> tile.copy(placement = placementsByTile.getValue(tile.id)) }
	val newAtlas = atlas.copy(pages = pages, tiles = movedTiles, composition = composition)
	if (newAtlas == atlas) {
		return this
	}

	val boundTileIds = drawables.mapNotNullTo(HashSet()) { drawable -> drawable.atlasTileId }
	val remapByTile = HashMap<AtlasTileId, AtlasTileRemap>()
	for (tile in atlas.tiles) {
		when (val outcome = tileRemap(atlas, newAtlas, tile.id)) {
			TileRemapOutcome.Unchanged -> Unit
			TileRemapOutcome.Inexpressible ->
				require(tile.id !in boundTileIds) {
					"tile '${tile.id.raw}' has drawables bound but no expressible re-derivation"
				}
			is TileRemapOutcome.Remap -> remapByTile[tile.id] = outcome.remap
		}
	}
	return copy(atlas = newAtlas, drawables = drawables.remappedOver(remapByTile))
}