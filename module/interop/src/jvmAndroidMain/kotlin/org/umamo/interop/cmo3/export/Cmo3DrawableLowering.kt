package org.umamo.interop.cmo3.export

import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.type.CArrayList
import org.umamo.interop.DrawableField
import org.umamo.interop.EntityDiff
import org.umamo.interop.ExportEntityCategory
import org.umamo.interop.ExportNotice
import org.umamo.interop.ExportNoticeReason
import org.umamo.interop.alphaCompositionOf
import org.umamo.interop.cmo3.Cmo3TextureFrames
import org.umamo.interop.cmo3.editableMeshOf
import org.umamo.interop.colorCompositionOf
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.storedToArtAffineForTile

/**
 * The field pass for drawables: each changed drawable's name, flags, parent deformer, blend
 * modes, masks, canvas mesh, and UVs.  Its keyforms go to the keyform lowering, and a changed
 * triangulation to the topology lowering, which this pass drives from start to end.
 */
internal class Cmo3DrawableLowering(
	context: Cmo3ExportContext,
	private val index: Cmo3GraphIndex,
	private val notices: Cmo3ExportNotices,
	private val keyforms: Cmo3KeyformLowering,
	private val topology: Cmo3MeshTopologyLowering,
) {
	private val editor = context.editor
	private val baseline = context.baseline
	private val edited = context.edited
	private val textureFrames = context.textureFrames
	private val editedDrawableById = context.editedDrawableById
	private val baselineDrawableById = context.baselineDrawableById

	/** Drawables whose canvas mesh or UVs diverged from the imported weld - one aggregated notice. */
	private val weldDivergedDrawableNames = ArrayList<String>()

	/**
	 * Lowers each changed drawable's fields onto its graph source.
	 *
	 * @param List<EntityDiff<DrawableId, DrawableField>> diffs The drawable diffs.
	 */
	fun lowerDrawables(diffs: List<EntityDiff<DrawableId, DrawableField>>) {
		topology.expectTopologyRewrites(
			diffs.mapNotNull { diff -> if (diff is EntityDiff.Changed && DrawableField.MESH_TOPOLOGY in diff.fields) diff.id else null },
		)

		for (diff in diffs) {
			when (diff) {
				// Structural synthesis already failed and reported why; see the field pass in Cmo3Export.
				is EntityDiff.Created -> {}

				// Deletions are consumed by the structural pass and stripped from the forwarded diff.
				is EntityDiff.Deleted -> {}

				is EntityDiff.Changed -> {
					val source = index.drawableByIdStr[diff.id.raw]
					val editedDrawable = editedDrawableById[diff.id]

					if (source == null || editedDrawable == null) {
						notices.unsupported(ExportEntityCategory.Drawable, diff.id.raw, ExportNoticeReason.NoMatchingSourceToReconcile)

						continue
					}

					for (field in diff.fields) {
						when (field) {
							DrawableField.NAME -> {
								lowerLocalName(editor, source, editedDrawable.name)
							}

							DrawableField.VISIBLE -> {
								lowerIsVisible(editor, source, editedDrawable.isVisible)
							}

							DrawableField.SELECTABLE -> {
								lowerIsLocked(editor, source, editedDrawable.isSelectable)
							}

							DrawableField.PARENT_DEFORMER -> {
								// CMO3: ACDrawableSource field targetDeformerGuid - the deforming parent, or
								// the editor's fixed root-deformer sentinel when the drawable sits at the
								// deformer-tree root.  Never null: the editor's setter is non-null and NPEs
								// on absence, and no corpus drawable nulls it in any era.
								source.targetDeformerGuid =
									editedDrawable.parentDeformerId?.let { index.deformerByIdStr[it.raw]?.guid }
										?: rootDeformerSentinel()
								editor.ensureChildSlot(source, "ACDrawableSource", "targetDeformerGuid", "clipGuidList")
								// The forms are read in the new parent's space from now on, reused ones included.
								keyforms.formSpaces.retagFormSpaces(source, editedDrawable.parentDeformerId != null)
							}

							DrawableField.BLEND_MODE -> {
								// CMO3: CArtMeshSource field colorComposition.
								source.colorComposition = colorCompositionOf(editedDrawable.blendMode)
								editor.ensureChildSlot(source, "CArtMeshSource", "colorComposition", "culling")
							}

							DrawableField.ALPHA_BLEND_MODE -> {
								// CMO3: CArtMeshSource field alphaComposition (absent pre-5.3).
								source.alphaComposition = alphaCompositionOf(editedDrawable.alphaBlendMode)
								editor.ensureChildSlot(source, "CArtMeshSource", "alphaComposition")
							}

							DrawableField.MASKED_BY -> {
								// CMO3: ACDrawableSource field clipGuidList - always drawable GUIDs.
								editor.writeGraphList(
									owner = source,
									tag = "ACDrawableSource",
									property = "clipGuidList",
									beforeProperty = "invertClippingMask",
									current = source.clipGuidList,
									newElements = editedDrawable.maskedBy.mapNotNull { index.drawableByIdStr[it.raw]?.guid },
									kind = GraphListKind.CArrayList,
									assign = { list -> source.clipGuidList = list },
								)
							}

							DrawableField.INVERT_MASK -> {
								// CMO3: ACDrawableSource field invertClippingMask.
								source.invertClippingMask = editedDrawable.invertMask
								editor.ensureChildSlot(source, "ACDrawableSource", "invertClippingMask", "icon32")
							}

							DrawableField.CULLING -> {
								// CMO3: CArtMeshSource field culling - back-face culling.
								source.culling = editedDrawable.culling
								editor.ensureChildSlot(source, "CArtMeshSource", "culling", "textureState")
							}

							DrawableField.TEXTURE_SOURCE -> {
								notices.unsupported(ExportEntityCategory.Drawable, diff.id.raw, ExportNoticeReason.TextureSourceRebindingIsEditorOnly)
							}

							DrawableField.ATLAS_TILE -> {
								notices.unsupported(ExportEntityCategory.Drawable, diff.id.raw, ExportNoticeReason.AtlasTileRebindingNotLowered)
							}

							DrawableField.MESH_TOPOLOGY -> {
								// The weld notice means "the canvas mesh left the IMPORTED weld" - a drawable
								// with no baseline was never welded, so synthesis stays notice-free.
								val hadBaseline = baselineDrawableById[diff.id] != null

								if (topology.lowerMeshTopology(source, editedDrawable) && hadBaseline) {
									weldDivergedDrawableNames.add(editedDrawable.name)
								}
							}

							DrawableField.MESH_POSITIONS -> {
								// The canvas editable mesh only: the keyforms are absolutes rebuilt from the
								// keyform-space base, so a move of that base surfaces as GEOMETRY and BLEND_SHAPES
								// and is written by the keyform rebuild below.
								lowerMeshPositions(source, diff.id, editedDrawable)

								if (baselineDrawableById[diff.id] != null) {
									weldDivergedDrawableNames.add(editedDrawable.name)
								}
							}

							DrawableField.MESH_UVS -> {
								lowerMeshUvs(source, diff.id, editedDrawable)

								if (baselineDrawableById[diff.id] != null) {
									weldDivergedDrawableNames.add(editedDrawable.name)
								}
							}

							// Handled once per drawable by the keyform rebuild below.
							DrawableField.GEOMETRY,
							DrawableField.CHANNELS,
							DrawableField.STATICS,
							DrawableField.BLEND_SHAPES,
							-> {}
						}
					}

					val rebuildGrid =
						DrawableField.GEOMETRY in diff.fields ||
							DrawableField.CHANNELS in diff.fields ||
							DrawableField.STATICS in diff.fields ||
							DrawableField.MESH_TOPOLOGY in diff.fields
					val rebuildMorphs = DrawableField.BLEND_SHAPES in diff.fields

					if (rebuildGrid || rebuildMorphs) {
						keyforms.lowerDrawable(
							source = source,
							editedDrawable = editedDrawable,
							rebuildGrid = rebuildGrid,
							rebuildMorphs = rebuildMorphs,
						)
					}
				}
			}
		}

		topology.settleTopologyRewrites()
	}

	/**
	 * Flushes the aggregated weld-divergence notice.  Call after lowerDrawables: exported geometry
	 * and UVs are written as authored, a deliberate choice, and this tells the user which meshes
	 * Cubism's own mesh-edit / re-atlas operations could re-derive.
	 */
	fun flushWeldNotice() {
		if (weldDivergedDrawableNames.isNotEmpty()) {
			notices.add(ExportNotice.WeldDivergence(weldDivergedDrawableNames.toList()))
		}
	}

	/**
	 * Lowers an edited canvas editable mesh: rewrites CArtMeshSource.positions and mirrors the editable mesh's
	 * point array.  The forms are not touched: CMO3 stores them as absolutes in the keyforms' own space, which the
	 * keyform lowering rebuilds from the drawable's keyform-space base, so a canvas move leaves every form's floats
	 * as they are.
	 *
	 * @param CArtMeshSource source         The drawable's graph source.
	 * @param DrawableId     drawableId     The drawable's id (for notices).
	 * @param Drawable       editedDrawable The edited drawable.
	 */
	private fun lowerMeshPositions(source: CArtMeshSource, drawableId: DrawableId, editedDrawable: Drawable) {
		val origCanvas = source.positions as? FloatArray
		val newCanvas = editedDrawable.mesh?.positions

		if (origCanvas == null || newCanvas == null || origCanvas.size != newCanvas.size) {
			notices.unsupported(ExportEntityCategory.Drawable, drawableId.raw, ExportNoticeReason.BaseGeometryVertexCountMismatch)

			return
		}

		// CMO3: CArtMeshSource field positions - the canvas-space editable mesh.
		source.positions = newCanvas.copyOf()
		editor.ensureChildSlot(source, "CArtMeshSource", "positions", "uvs")
		// CMO3: GEditableMesh2 field point mirrors the positions as its OWN float-array (the corpus
		// never shares the two array objects, and a shared object would hoist as an xs.ref).
		val editableMesh = editableMeshOf(source)
		val pointArray = editableMesh?.point as? FloatArray

		if (editableMesh != null && (pointArray == null || pointArray.size == newCanvas.size)) {
			editableMesh.point = newCanvas.copyOf()
			editor.ensureChildSlot(editableMesh, "GEditableMesh2", "point", "pointPriority")
		}
	}

	/**
	 * Lowers edited UVs into the frame the drawable stores ([Cmo3TextureFrames.storedUvsOf]) - the import's
	 * remap run backward, off the edited tile's page through its EDITED placement when it has one.
	 * Unchanged texel pairs keep the stored values so the round trip cannot drift them, but only while the
	 * tile's placement is the one those values were read under: a moved placement moves every model pair.
	 *
	 * @param CArtMeshSource source         The drawable's graph source.
	 * @param DrawableId     drawableId     The drawable's id (for notices).
	 * @param Drawable       editedDrawable The edited drawable.
	 */
	private fun lowerMeshUvs(source: CArtMeshSource, drawableId: DrawableId, editedDrawable: Drawable) {
		val newUvs = editedDrawable.mesh?.uvs

		if (newUvs == null) {
			notices.unsupported(ExportEntityCategory.Drawable, drawableId.raw, ExportNoticeReason.NoUvsToReconcile)

			return
		}

		val baselineDrawable = baselineDrawableById[drawableId]
		val editedToArt = editedDrawable.atlasTileId?.let { tileId -> edited.atlas.storedToArtAffineForTile(tileId) }
		val baselineToArt = baselineDrawable?.atlasTileId?.let { tileId -> baseline.atlas.storedToArtAffineForTile(tileId) }
		val placementKept = editedToArt.contentEquals(baselineToArt)
		// CMO3: CArtMeshSource field uvs.
		source.uvs =
			textureFrames.storedUvsOf(
				source,
				newUvs,
				editedToArt,
				(source.uvs as? FloatArray).takeIf { placementKept },
				baselineDrawable?.mesh?.uvs.takeIf { placementKept },
			)
		editor.ensureChildSlot(source, "CArtMeshSource", "uvs", "texture")
	}
}