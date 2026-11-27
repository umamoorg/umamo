package org.umamo.interop.cmo3.export

import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.cmo3.model.gen.GTransform2
import org.umamo.format.cmo3.model.gen.ModelImageEntry
import org.umamo.format.cmo3.model.type.CAffine
import org.umamo.interop.AtlasTileField
import org.umamo.interop.EntityDiff
import org.umamo.interop.ExportEntityCategory
import org.umamo.interop.ExportNoticeReason
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.lineageRoot

/**
 * The field pass for atlas tiles: a moved tile's packed entry, and the notices a tile's
 * changed metadata or pixels are owed.
 */
internal class Cmo3AtlasTileLowering(
	context: Cmo3ExportContext,
	private val index: Cmo3GraphIndex,
	private val notices: Cmo3ExportNotices,
	// True when the caller replaced the stored page images with pages recomposed for the edited
	// placements (the atlas-web reconcile ran); the stale-page notices below are then false and stay
	// quiet.
	private val pagesRecomposed: Boolean,
	// The lineage roots whose layer web the atlas-web reconcile minted or rewrote to the edited art;
	// their metadata (name, size, binding) is reconciled there, so no notice is owed for it.
	private val reconciledTileIds: Set<String>,
) {
	private val editor = context.editor
	private val edited = context.edited

	/**
	 * Lowers each moved atlas tile onto its packed entry - the packing transform AND the inverse the
	 * file composes it against.
	 *
	 * A model image is UPRIGHT CANVAS-SPACE art, and the format states the invariant as a composition
	 * (docs/format/CMO3.md §6): `atlasLocalToCanvasTransform` composed with
	 * `materialLocalToAtlasTransform` reproduces `_materialLocalToCanvasTransform`, a pure translation
	 * every one of the corpus's model images obeys.  A repack moves art on the PAGE, never on the
	 * CANVAS, so the canvas placement is deliberately left alone and the atlas-to-canvas half is
	 * recomputed to hold the composition.  Writing only the packing transform would break an invariant
	 * the official editor's own reader depends on.
	 *
	 * What this does NOT do is redraw the page.  When the caller has not patched the stored page
	 * images (pagesRecomposed), they still show the art where it used to be, so a moved placement
	 * takes [ExportNoticeReason.AtlasPageNotRecomposed]; a patched export clears it.
	 *
	 * @param List diffs The per-tile diffs.
	 */
	fun lowerAtlasTiles(diffs: List<EntityDiff<AtlasTileId, AtlasTileField>>) {
		if (diffs.isEmpty()) {
			return
		}

		// CMO3: CModelSource field textureManager -> CTextureManager field _textureAtlases.
		val textureManager = index.modelSource.textureManager as? CTextureManager

		if (textureManager == null) {
			notices.unsupported(ExportEntityCategory.Document, null, ExportNoticeReason.NoTextureManagerToReconcile)

			return
		}

		// Indexed fresh here rather than shared with the web reconcile, which runs first and moves
		// entries between atlases.  The canvas placement it carries is the fixed point every rewritten
		// transform pair has to keep composing to (see indexAtlasWeb).
		val web = indexAtlasWeb(textureManager)
		// Keyed by lineage root: the diff names the baseline's tile, and a reloaded tile (`<guid>~<n>`)
		// is that tile's art to the graph, so its placement is what the root's entry takes.
		val editedTileById = edited.atlas.tiles.associateBy { tile -> tile.id.lineageRoot }

		for (diff in diffs) {
			when (diff) {
				// The diff compares shared tiles only, because art arrives and leaves with an import
				// rather than with an edit - so these arms are unreachable.  They stay defensive rather
				// than silent: an appearance here is a bug worth reporting.
				is EntityDiff.Created, is EntityDiff.Deleted -> {
					notices.unsupported(ExportEntityCategory.Document, null, ExportNoticeReason.AtlasTileMetadataNotReconcilable)
				}

				is EntityDiff.Changed -> {
					// The tile's own art - its pixels, name, size, or source - reaches the file only through the
					// atlas-web reconcile's layer rewrite.  A tile it did not reconcile keeps the retained art,
					// which is reported rather than passed over: a repaint alone changes nothing else in the model.
					val artChanged = AtlasTileField.METADATA in diff.fields || AtlasTileField.PIXELS in diff.fields

					if (artChanged && diff.id.raw !in reconciledTileIds) {
						notices.unsupported(ExportEntityCategory.Document, null, ExportNoticeReason.AtlasTileMetadataNotReconcilable)
					}

					if (AtlasTileField.PLACEMENT !in diff.fields) {
						continue
					}

					val placement = editedTileById[diff.id]?.placement

					if (placement == null && pagesRecomposed) {
						// A pack-out under the full web reconcile: the entry already left its page,
						// which is the whole lowering a null placement takes.
						continue
					}

					val entry = web.siteByTileId[diff.id.raw]?.entry

					if (placement == null || entry == null) {
						notices.unsupported(ExportEntityCategory.Document, null, ExportNoticeReason.NoAtlasEntryToReconcile)

						continue
					}

					val canvasAffine = web.canvasAffineByTileId[diff.id.raw]

					if (canvasAffine == null) {
						notices.unsupported(ExportEntityCategory.Document, null, ExportNoticeReason.NoAtlasEntryToReconcile)

						continue
					}

					lowerAtlasEntry(entry, placement, canvasAffine)

					if (!pagesRecomposed) {
						notices.unsupported(ExportEntityCategory.Document, null, ExportNoticeReason.AtlasPageNotRecomposed)
					}
				}
			}
		}
	}

	/**
	 * Writes one packed entry's transform pair.
	 *
	 * @param ModelImageEntry entry        The packed entry to move.
	 * @param AtlasPlacement  placement    Where the art now sits on its page.
	 * @param FloatArray      canvasAffine Where the art sits on the CANVAS, which does not move.
	 */
	private fun lowerAtlasEntry(entry: ModelImageEntry, placement: AtlasPlacement, canvasAffine: FloatArray) {
		// CMO3: ModelImageEntry field materialLocalToAtlasTransform - written into the entry's EXISTING
		// GTransform2 when it has one, so the element keeps its instance.
		val transform = (entry.materialLocalToAtlasTransform as? GTransform2) ?: GTransform2()
		entry.materialLocalToAtlasTransform = Cmo3PageWeb.writePacking(transform, placement)
		editor.ensureChildSlot(entry, "ModelImageEntry", "materialLocalToAtlasTransform", "atlasLocalToCanvasTransform")

		// CMO3: ModelImageEntry field atlasLocalToCanvasTransform - the other half of the composition.
		//
		// NOT simply the placement's inverse: the pair must compose to the art's CANVAS placement,
		// which does not move when the art is repacked.  The full composition canvas-affine after
		// placement-inverse serves both corpus shapes: an official file's pure-translation canvas
		// placement reduces it to the inverse plus that origin (dropping the origin term would slide
		// every model image to the canvas corner), and a converted graph's canvas placement carries
		// the original packing's linear part, which a translation-only write would drop.
		val canvasTransform = entry.atlasLocalToCanvasTransform as? CAffine

		if (canvasTransform == null) {
			notices.unsupported(ExportEntityCategory.Document, null, ExportNoticeReason.NoAtlasEntryToReconcile)

			return
		}

		val composed = atlasLocalToCanvasFor(canvasAffine, placement)

		if (composed == null) {
			notices.unsupported(ExportEntityCategory.Document, null, ExportNoticeReason.NoAtlasEntryToReconcile)

			return
		}

		canvasTransform.setFromAffineArray(composed)
		editor.ensureChildSlot(entry, "ModelImageEntry", "atlasLocalToCanvasTransform", null)
	}
}