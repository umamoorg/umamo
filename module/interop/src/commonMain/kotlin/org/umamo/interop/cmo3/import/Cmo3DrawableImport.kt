package org.umamo.interop.cmo3.import

import org.umamo.format.cmo3.model.gen.AlphaComposition
import org.umamo.format.cmo3.model.gen.CArtMeshForm
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.ColorComposition
import org.umamo.interop.alphaBlendOfToken
import org.umamo.interop.cmo3.Cmo3TextureFrames
import org.umamo.interop.cmo3.idStrOf
import org.umamo.interop.cmo3.uuidOf
import org.umamo.interop.colorBlendOfToken
import org.umamo.runtime.eval.keyformBaseOf
import org.umamo.runtime.keyform.fanOutMesh
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.deltasVsBase
import org.umamo.runtime.model.storedToArtAffineForTile

/*
 * The drawable producer of the CMO3 import: art meshes with their canvas geometry, keyform grids, blend
 * shapes, and atlas tile.
 */

/**
 * Builds the runtime drawables, back to front.
 *
 * @param Cmo3ImportContext context The import's shared reads.
 * @param List<Parameter> parameters The imported parameters, for the default pose the keyform base is taken at.
 * @param Cmo3AtlasIngest atlasIngest The layered-art web, for each drawable's tile.
 * @param Cmo3TextureFrames textureFrames Which image each drawable is shown from and which frame its stored coordinates are in.
 * @return List<Drawable> The drawables; a source with no id keeps an empty one.
 */
internal fun importDrawables(
	context: Cmo3ImportContext,
	parameters: List<Parameter>,
	atlasIngest: Cmo3AtlasIngest,
	textureFrames: Cmo3TextureFrames,
): List<Drawable> {
	// Render order: drawables painted back-to-front.  The parts-tree DFS (panel order, top = front)
	// is front-to-back, so the drawable list is its reverse - the last panel entry is the backmost,
	// drawn first. drawOrder (all 500 in the corpus) and its animation refine this later; the tree
	// walk is the param-independent base.  CMO3: CPartSource._childGuids holds child parts / deformers
	// / meshes in panel order; the drawableSourceSet is creation order, not stacking order.  The context's
	// renderIndexByUuid (the part tree's panel walk) is the source of both this order and panelOrder.
	val orderedDrawableSources = context.drawableSources.sortedByDescending { context.renderIndexByUuid[uuidOf(it.guid)] ?: Int.MAX_VALUE }
	val defaultByParameter = parameters.associate { parameter -> parameter.id to parameter.default }
	val defaultOf = { parameterId: ParameterId -> defaultByParameter[parameterId] ?: 0f }

	return orderedDrawableSources.map { source ->
		// A placed tile's drawable takes its coordinates on the tile's page (Cmo3TextureFrames).
		val storedToArt = atlasIngest.tileIdByDrawableId[idStrOf(source.id).orEmpty()]?.let { tileId -> atlasIngest.atlas.storedToArtAffineForTile(tileId) }
		// The forms as stored, absolute; the base they are measured from is the reference form's own
		// positions, in the forms' space, so every delta is a difference within one space.
		val formGrid =
			buildGrid(source.keyformGridSource, source.keyforms, context.paramIdByUuid) { form ->
				(form as? CArtMeshForm)?.takeIf { artForm -> artForm.positions is FloatArray }
			}
		val mesh =
			meshOf(source, textureFrames, storedToArt)?.let { canvasMesh ->
				val local = keyformBaseOf(canvasMesh.positions, formGrid, defaultOf) { artForm -> artForm.positions as FloatArray }
				DrawableMesh(positions = canvasMesh.positions, localPositions = local, uvs = canvasMesh.uvs, indices = canvasMesh.indices)
			}
		// One bundled grid, then split into per-vertex deltas and the render channels.
		val fannedMesh =
			formGrid?.let { grid ->
				KeyformGrid(grid.axes, grid.cells.mapNotNull { cell -> meshForm(cell.form, mesh?.localPositions)?.let { form -> KeyformCell(cell.coordinate, form) } })
			}?.fanOutMesh()
		Drawable(
			id = DrawableId(idStrOf(source.id).orEmpty()),
			// CMO3: ACParameterControllableSource.localName is the user-facing drawable name; an absent or empty one falls back to the id.
			name = displayNameOf(source.localName, source.id),
			parentDeformerId = context.deformerIdByUuid[uuidOf(source.targetDeformerGuid)],
			// CMO3: colorComposition/alphaComposition - the full 5.3 blend surface (bare legacy
			// tokens on pre-5.3 meshes; every mode token maps, see BlendModeMapping).
			blendMode = colorBlendOfToken((source.colorComposition as? ColorComposition)?.name),
			alphaBlendMode = alphaBlendOfToken((source.alphaComposition as? AlphaComposition)?.name),
			maskedBy = resolveGuids(source.clipGuidList, context.drawableIdByUuid),
			// CMO3: ACDrawableSource.invertClippingMask - show outside the mask instead of inside.
			invertMask = source.invertClippingMask,
			// CMO3: CArtMeshSource.culling - back-face culling (default false = double-sided).
			culling = source.culling,
			// CMO3: ACParameterControllableSource.isVisible - the drawable's own eyeball (ANDed with parents).
			isVisible = source.isVisible,
			// CMO3: ACParameterControllableSource.isLocked (inverted: Cubism lock = not selectable).
			isSelectable = !source.isLocked,
			mesh = mesh,
			geometryGrid = fannedMesh?.geometry,
			channelGrids = fannedMesh?.channels ?: ChannelGrids.Empty,
			blendShapes =
				blendShapeBindingsOf(source.keyformMorphTargetSet, source.keyforms, context.paramIdByUuid) { form ->
					meshForm(form, mesh?.localPositions)
				},
			atlasTileId = atlasIngest.tileIdByDrawableId[idStrOf(source.id).orEmpty()],
		)
	}
}

/**
 * Reads an art mesh's canvas geometry.  CMO3: `CArtMeshSource.positions`/`uvs` are `float-array`
 * (interleaved x,y), `indices` is an `int-array` (3 per triangle).  The positions are the canvas editable
 * mesh; the returned mesh shares them as its base until the caller sets the keyforms' own.  The stored
 * `uvs` reach the model through [Cmo3TextureFrames.modelUvsOf].
 *
 * @param CArtMeshSource    source        The art-mesh source.
 * @param Cmo3TextureFrames textureFrames The document's texture frames.
 * @param FloatArray?       storedToArt   The drawable's tile's model-to-art affine, or null without a tile.
 * @return DrawableMesh? The canvas mesh, or null when the source carries no positions.
 */
private fun meshOf(source: CArtMeshSource, textureFrames: Cmo3TextureFrames, storedToArt: FloatArray?): DrawableMesh? {
	val positions = source.positions as? FloatArray ?: return null
	val storedUvs = source.uvs as? FloatArray ?: FloatArray(0)
	val indices = source.indices as? IntArray ?: IntArray(0)

	return DrawableMesh.withLocalEqualToCanvas(positions, textureFrames.modelUvsOf(source, storedUvs, storedToArt), indices)
}

/**
 * Mesh cell payload: the form's absolute positions converted to deltas vs the keyform-space [base].
 *
 * @param Any         form The form object (expected CArtMeshForm).
 * @param FloatArray? base The mesh's keyform-space base (DrawableMesh.localPositions).
 * @return MeshForm? The delta form, or null if the form has no positions.
 */
private fun meshForm(form: Any, base: FloatArray?): MeshForm? {
	val artForm = form as? CArtMeshForm ?: return null
	val positions = artForm.positions as? FloatArray ?: return null

	// CMO3: ACDrawableForm.drawOrder (Cubism default 500) + opacity (0..1) + the 5.3 per-art-mesh
	// multiply/screen color ride on the art-mesh keyform (pre-5.3 forms carry no color -> identity).
	return MeshForm(
		deltasVsBase(base, positions),
		artForm.drawOrder.toFloat(),
		artForm.opacity,
		multiplyColor = colorRgbOf(artForm.multiplyColor) ?: ColorRgb.MultiplyIdentity,
		screenColor = colorRgbOf(artForm.screenColor) ?: ColorRgb.ScreenIdentity,
	)
}