package org.umamo.edit

import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.AtlasComposition
import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PartComposite
import org.umamo.runtime.model.PartGroupMode
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RuntimeTarget
import org.umamo.runtime.model.SourceLayerRef

/*
 * Scalar property edits on an EditorSession, mostly driven by the Properties panel's editable controls
 * (the atlas edits at the end of the file are not: commitAtlasRepack is driven by the atlas repack
 * flow, setAtlasPlacements by the UV editor's placement gizmo, and setAtlasPins by its pin commands).  Each
 * applies one field change as a single undo step via mutate, dispatching the typed Change plus its
 * PuppetModelEdits transform, and short-circuits to nothing on a no-op (the builder returns the same
 * model instance).  These are the write half of the Properties panel: a checkbox / dropdown / numeric
 * field commit calls exactly one of these, and a registry command flipping the same document field
 * (document.toggleSourceArtworkDisplay) goes through the same entry point rather than mutating in
 * parallel, so both routes produce the one identical undo step.  Continuous numeric scrubbing previews
 * in the widget and commits one of these on release, so there is no per-frame mutation and no
 * history-side coalescing (the same single-commit-per-gesture granularity the parameter scrub documents
 * in ParameterChange.SetValue).
 */

/**
 * Sets drawable [id]'s color blend mode as one undo step.
 *
 * @param DrawableId id The drawable to retarget.
 * @param BlendMode mode The new blend mode.
 */
fun EditorSession.setDrawableBlendMode(id: DrawableId, mode: BlendMode) {
	mutate(DrawableChange.SetBlendMode(id, mode)) { model -> model.withDrawableBlendMode(id, mode) }
}

/**
 * Sets drawable [id]'s alpha blend mode as one undo step.
 *
 * @param DrawableId id The drawable to retarget.
 * @param AlphaBlendMode mode The new alpha blend mode.
 */
fun EditorSession.setDrawableAlphaBlendMode(id: DrawableId, mode: AlphaBlendMode) {
	mutate(DrawableChange.SetAlphaBlendMode(id, mode)) { model -> model.withDrawableAlphaBlendMode(id, mode) }
}

/**
 * Sets drawable [id]'s back-face culling as one undo step.
 *
 * @param DrawableId id The drawable to retarget.
 * @param Boolean culling The new culling state.
 */
fun EditorSession.setDrawableCulling(id: DrawableId, culling: Boolean) {
	mutate(DrawableChange.SetCulling(id, culling)) { model -> model.withDrawableCulling(id, culling) }
}

/**
 * Sets drawable [id]'s mask-inversion flag as one undo step.
 *
 * @param DrawableId id The drawable to retarget.
 * @param Boolean invert The new inverted-mask state.
 */
fun EditorSession.setDrawableInvertMask(id: DrawableId, invert: Boolean) {
	mutate(DrawableChange.SetInvertMask(id, invert)) { model -> model.withDrawableInvertMask(id, invert) }
}

/**
 * Sets drawable [id]'s static opacity as one undo step.
 *
 * @param DrawableId id The drawable to retarget.
 * @param Float opacity The new opacity.
 */
fun EditorSession.setDrawableOpacity(id: DrawableId, opacity: Float) {
	mutate(DrawableChange.SetOpacity(id, opacity)) { model -> model.withDrawableOpacity(id, opacity) }
}

/**
 * Sets drawable [id]'s static draw order as one undo step.
 *
 * @param DrawableId id The drawable to retarget.
 * @param Float drawOrder The new draw order.
 */
fun EditorSession.setDrawableDrawOrder(id: DrawableId, drawOrder: Float) {
	mutate(DrawableChange.SetDrawOrder(id, drawOrder)) { model -> model.withDrawableDrawOrder(id, drawOrder) }
}

/**
 * Sets deformer [id]'s static opacity as one undo step.  Cascades onto every drawable beneath it.
 *
 * @param DeformerId id The deformer to retarget.
 * @param Float opacity The new opacity.
 */
fun EditorSession.setDeformerOpacity(id: DeformerId, opacity: Float) {
	mutate(DeformerChange.SetOpacity(id, opacity)) { model -> model.withDeformerOpacity(id, opacity) }
}

/**
 * Sets deformer [id]'s static multiply color as one undo step.  Cascades onto every drawable beneath it.
 *
 * @param DeformerId id The deformer to retint.
 * @param ColorRgb color The new multiply color.
 */
fun EditorSession.setDeformerMultiplyColor(id: DeformerId, color: ColorRgb) {
	mutate(DeformerChange.SetMultiplyColor(id, color)) { model -> model.withDeformerMultiplyColor(id, color) }
}

/**
 * Sets deformer [id]'s static screen color as one undo step.  Cascades onto every drawable beneath it.
 *
 * @param DeformerId id The deformer to retint.
 * @param ColorRgb color The new screen color.
 */
fun EditorSession.setDeformerScreenColor(id: DeformerId, color: ColorRgb) {
	mutate(DeformerChange.SetScreenColor(id, color)) { model -> model.withDeformerScreenColor(id, color) }
}

/**
 * Sets rotation deformer [id]'s static horizontal reflection as one undo step.
 *
 * @param DeformerId id The deformer to retarget.
 * @param Boolean flip The new reflection state.
 */
fun EditorSession.setDeformerFlipX(id: DeformerId, flip: Boolean) {
	mutate(DeformerChange.SetFlipX(id, flip)) { model -> model.withDeformerFlipX(id, flip) }
}

/**
 * Sets rotation deformer [id]'s static vertical reflection as one undo step.
 *
 * @param DeformerId id The deformer to retarget.
 * @param Boolean flip The new reflection state.
 */
fun EditorSession.setDeformerFlipY(id: DeformerId, flip: Boolean) {
	mutate(DeformerChange.SetFlipY(id, flip)) { model -> model.withDeformerFlipY(id, flip) }
}

/**
 * Sets drawable [id]'s multiply color (the static value) as one undo step.
 *
 * @param DrawableId id The drawable to retint.
 * @param ColorRgb color The new multiply color.
 */
fun EditorSession.setDrawableMultiplyColor(id: DrawableId, color: ColorRgb) {
	mutate(DrawableChange.SetMultiplyColor(id, color)) { model -> model.withDrawableMultiplyColor(id, color) }
}

/**
 * Sets drawable [id]'s screen color (the static value) as one undo step.
 *
 * @param DrawableId id The drawable to retint.
 * @param ColorRgb color The new screen color.
 */
fun EditorSession.setDrawableScreenColor(id: DrawableId, color: ColorRgb) {
	mutate(DrawableChange.SetScreenColor(id, color)) { model -> model.withDrawableScreenColor(id, color) }
}

/**
 * Binds drawable [id] to the deformer that deforms it (null unbinds) as one undo step.
 *
 * @param DrawableId id The drawable to rebind.
 * @param DeformerId? parentDeformerId The deformer that deforms it, or null to unbind.
 */
fun EditorSession.setDrawableParentDeformer(id: DrawableId, parentDeformerId: DeformerId?) {
	mutate(DrawableChange.SetParentDeformer(id, parentDeformerId)) { model ->
		model.withDrawableParentDeformer(id, parentDeformerId)
	}
}

/**
 * Replaces drawable [id]'s clip-mask list as one undo step, so adding or removing a single mask is one
 * step.
 *
 * @param DrawableId id The drawable whose masks change.
 * @param List maskedBy The drawables whose alpha now clips it.
 */
fun EditorSession.setDrawableMaskedBy(id: DrawableId, maskedBy: List<DrawableId>) {
	mutate(DrawableChange.SetMaskedBy(id, maskedBy)) { model -> model.withDrawableMaskedBy(id, maskedBy) }
}

/**
 * Binds deformer [id] to the organizational part that owns it (null clears it) as one undo step.
 *
 * @param DeformerId id The deformer to rebind.
 * @param PartId? partId The part that owns it, or null to clear.
 */
fun EditorSession.setDeformerPart(id: DeformerId, partId: PartId?) {
	mutate(DeformerChange.SetPart(id, partId)) { model -> model.withDeformerPart(id, partId) }
}

/**
 * Sets rotation deformer [id]'s base angle as one undo step. A no-op on a warp deformer (it has no base
 * angle), so the commit short-circuits.
 *
 * @param DeformerId id The deformer to retarget.
 * @param Float angle The new base angle in degrees.
 */
fun EditorSession.setDeformerBaseAngle(id: DeformerId, angle: Float) {
	mutate(DeformerChange.SetBaseAngle(id, angle)) { model -> model.withDeformerBaseAngle(id, angle) }
}

/**
 * Sets warp deformer [id]'s FFD interpolation mode as one undo step. A no-op on a rotation deformer (it
 * has no lattice), so the commit short-circuits.
 *
 * @param DeformerId id The deformer to retarget.
 * @param Boolean quad The new quad-transform state.
 */
fun EditorSession.setDeformerQuadTransform(id: DeformerId, quad: Boolean) {
	mutate(DeformerChange.SetQuadTransform(id, quad)) { model -> model.withDeformerQuadTransform(id, quad) }
}

/**
 * Sets part [id]'s guide-image (sketch) flag as one undo step.
 *
 * @param PartId id The part to retarget.
 * @param Boolean sketch The new sketch state.
 */
fun EditorSession.setPartSketch(id: PartId, sketch: Boolean) {
	mutate(PartChange.SetSketch(id, sketch)) { model -> model.withPartSketch(id, sketch) }
}

/**
 * Sets part [id]'s own draw order as one undo step.
 *
 * @param PartId id The part to retarget.
 * @param Int order The new draw order.
 */
fun EditorSession.setPartDrawOrder(id: PartId, order: Int) {
	mutate(PartChange.SetDrawOrder(id, order)) { model -> model.withPartDrawOrder(id, order) }
}

/**
 * Sets part [id]'s rendering group mode as one undo step. The [mode] carries the whole value, so a mode
 * switch and any Isolated-composite sub-field edit both flow through here.
 *
 * @param PartId id The part to retarget.
 * @param PartGroupMode mode The new group mode.
 */
fun EditorSession.setPartGroupMode(id: PartId, mode: PartGroupMode) {
	mutate(PartChange.SetGroupMode(id, mode)) { model -> model.withPartGroupMode(id, mode) }
}

/**
 * Sets part [id]'s latent compositing settings as one undo step.  Stored independent of the group mode,
 * so an isolated part's composite survives leaving and re-entering Isolated; applied only while Isolated.
 *
 * @param PartId id The part to retarget.
 * @param PartComposite composite The new composite settings.
 */
fun EditorSession.setPartComposite(id: PartId, composite: PartComposite) {
	mutate(PartChange.SetComposite(id, composite)) { model -> model.withPartComposite(id, composite) }
}

/**
 * Sets the document canvas size (world units) as one undo step.
 *
 * @param Float width The new canvas width.
 * @param Float height The new canvas height.
 */
fun EditorSession.setCanvasSize(width: Float, height: Float) {
	mutate(DocumentChange.SetCanvasSize(width, height)) { model -> model.withCanvasSize(width, height) }
}

/**
 * Sets the world origin (world space) as one undo step.
 *
 * @param Float x The new world-origin x.
 * @param Float y The new world-origin y.
 */
fun EditorSession.setWorldOrigin(x: Float, y: Float) {
	mutate(DocumentChange.SetWorldOrigin(x, y)) { model -> model.withWorldOrigin(x, y) }
}

/**
 * Sets the document's runtime-compatibility target as one undo step.
 *
 * @param RuntimeTarget target The new runtime target.
 */
fun EditorSession.setRuntimeTarget(target: RuntimeTarget) {
	mutate(DocumentChange.SetRuntimeTarget(target)) { model -> model.withRuntimeTarget(target) }
}

/**
 * Switches the puppet between displaying from its source artwork and from the packed atlas, as one
 * undo step.
 *
 * A display choice that is document content rather than an app preference: the source formats author
 * it, so it round-trips and it marks the document dirty like any other authored value.
 *
 * @param Boolean fromSourceLayers True to display from the source artwork, false from the atlas.
 */
fun EditorSession.setSourceLayerDisplay(fromSourceLayers: Boolean) {
	mutate(DocumentChange.SetSourceLayerDisplay(fromSourceLayers)) { model -> model.withSourceLayerDisplay(fromSourceLayers) }
}

/**
 * Packs one piece of source art at [placement] as a single undo step, re-mapping every drawable over it
 * so the art keeps meaning what it did - [setAtlasPlacements] over one tile.
 *
 * @param AtlasTileId     tileId    The tile to place.
 * @param AtlasPlacement? placement Where its art now sits, or null to mark it unpacked.
 */
fun EditorSession.setAtlasPlacement(tileId: AtlasTileId, placement: AtlasPlacement?) {
	setAtlasPlacements(mapOf(tileId to placement))
}

/**
 * Packs several pieces of source art at once as ONE undo step - a placement gesture over a
 * multi-tile selection is one edit, not one step per tile - re-mapping every drawable over them so
 * the art keeps meaning what it did.
 *
 * The pages' PIXELS are session state derived from the model, not part of it: the session's page
 * resolver composes the pages the new placements denote once this commit publishes (and re-composes
 * them on undo), so nothing here touches a pixel and no snapshot ever carries one.
 *
 * @param Map               placementByTile Each tile's new placement, keyed by tile, null to mark it unpacked.
 * @param MeshOperatorKind? kind            The placement operator that produced the move (it names the
 *   step), or null for a placement written by no gesture.
 */
fun EditorSession.setAtlasPlacements(
	placementByTile: Map<AtlasTileId, AtlasPlacement?>,
	kind: MeshOperatorKind? = null,
) {
	mutate(DocumentChange.SetAtlasPlacement(placementByTile.keys.toList(), kind)) { model -> model.withAtlasPlacements(placementByTile) }
}

/**
 * Pins or unpins the placed tiles among [tileIds] as one undo step, so a repack keeps (or may move)
 * them - withAtlasPins under the one history push a pin command or checkbox should be.
 *
 * @param Collection<AtlasTileId> tileIds The tiles to pin or unpin.
 * @param Boolean                 pinned  True to pin, false to unpin.
 */
fun EditorSession.setAtlasPins(tileIds: Collection<AtlasTileId>, pinned: Boolean) {
	mutate(DocumentChange.SetAtlasPin(tileIds.toList(), pinned)) { model -> model.withAtlasPins(tileIds, pinned) }
}

/**
 * Rebinds one tile to a layer of a listed artwork file, or unbinds it, as one undo step -
 * withTileSource under the one history push a relink should be.
 *
 * @param AtlasTileId     tileId The tile to rebind.
 * @param SourceLayerRef? source The new binding, or null to unbind.
 */
fun EditorSession.setTileSource(tileId: AtlasTileId, source: SourceLayerRef?) {
	mutate(DocumentChange.SetTileSource(tileId, bound = source != null)) { model -> model.withTileSource(tileId, source) }
}

/**
 * Commits a model that already carries an artwork file's additions and their pack as ONE undo step.
 *
 * The orchestrator builds [added] off the UI thread (the bridge, the pack around the existing art,
 * the re-derivation) from the model current when it started, checks nothing moved underneath it, and
 * lands it here; the page pixels are session state it swaps in beside this commit, which is why the
 * committed model comes back for the resolver's pre-warm.
 *
 * @param String      sourceName    The added file's display name, for the history label.
 * @param Int         drawableCount How many drawables it added.
 * @param PuppetModel added         The model with the additions and their pack applied.
 * @return PuppetModel The committed model.
 */
fun EditorSession.commitArtworkAdded(sourceName: String, drawableCount: Int, added: PuppetModel): PuppetModel {
	mutate(DocumentChange.AddArtwork(sourceName, drawableCount)) { added }
	return added
}

/**
 * Repacks the whole atlas as a single undo step: the new page inventory and every tile's placement
 * land together, with every bound drawable's coordinates re-derived over them - withAtlasRepack's
 * one-pass edit under the one history push a repack should be.
 *
 * The page PIXELS are session state the caller swaps in beside this commit, which is why the
 * committed model comes back: the orchestrator pre-warms the session's page resolver with the SAME
 * atlas instance this publishes (the resolver memoizes by identity), so the commit resolves its
 * pages by cache hit - and undo re-resolves them the same way.
 *
 * @param List             pages            The new page inventory.
 * @param Map              placementsByTile Every tile's new placement, keyed by tile, null for unpacked.
 * @param AtlasComposition composition      The trim and extrusion policy the pack composed under.
 * @return PuppetModel? The committed model, or null when the repack restated the atlas exactly.
 */
fun EditorSession.commitAtlasRepack(
	pages: List<AtlasPage>,
	placementsByTile: Map<AtlasTileId, AtlasPlacement?>,
	composition: AtlasComposition = model.value.atlas.composition,
): PuppetModel? {
	val current = model.value
	val repacked = current.withAtlasRepack(pages, placementsByTile, composition)
	if (repacked === current) {
		return null
	}
	val placedCount = placementsByTile.count { entry -> entry.value != null }
	mutate(DocumentChange.RepackAtlas(placedCount, pages.size)) { repacked }
	return repacked
}