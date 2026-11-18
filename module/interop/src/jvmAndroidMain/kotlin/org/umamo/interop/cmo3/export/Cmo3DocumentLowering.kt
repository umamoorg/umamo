package org.umamo.interop.cmo3.export

import org.umamo.format.cmo3.Cmo3Model
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CImageCanvas
import org.umamo.format.cmo3.model.gen.CLayeredImage
import org.umamo.format.cmo3.model.gen.CParameterSourceSet
import org.umamo.format.cmo3.model.gen.CTextureInputExtension
import org.umamo.format.cmo3.model.gen.CTextureInput_ModelImage
import org.umamo.format.cmo3.model.gen.CTextureInput_TextureAtlasRegion
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.cmo3.model.gen.LayeredImageWrapper
import org.umamo.format.cmo3.model.type.FileRef
import org.umamo.format.cmo3.type.CArrayList
import org.umamo.interop.DocumentField
import org.umamo.interop.ExportEntityCategory
import org.umamo.interop.ExportNoticeReason
import org.umamo.interop.cmo3.elementsOf
import org.umamo.interop.cmo3.idStrOf
import org.umamo.interop.cmo3.uuidOf
import org.umamo.interop.cmo3TargetVersionNo
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.isOriginAtCanvasCenter
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The field pass for the document: the runtime target, the parameter order and links, the two
 * panel trees' roots, the source files, the source-layer display, and the canvas.
 */
internal class Cmo3DocumentLowering(
	context: Cmo3ExportContext,
	private val index: Cmo3GraphIndex,
	private val notices: Cmo3ExportNotices,
	private val panelTree: Cmo3PanelTreeLowering,
	// True when the caller replaced the stored page images with pages recomposed for the edited
	// placements (the atlas-web reconcile ran); the stale-page notices below are then false and stay
	// quiet.
	private val pagesRecomposed: Boolean,
) {
	private val target = context.target
	private val editor = context.editor
	private val baseline = context.baseline
	private val edited = context.edited

	/**
	 * Lowers the changed document-level fields.
	 *
	 * @param Set<DocumentField> fields The document fields that changed.
	 */
	fun lowerDocument(fields: Set<DocumentField>) {
		// Order and links rewrite the same _sources list; run the shared lowering once.
		if (DocumentField.PARAMETER_ORDER in fields || DocumentField.PARAMETER_LINKS in fields) {
			lowerParameterOrderAndLinks()
		}

		for (field in fields) {
			when (field) {
				// CMO3: CModelSource field targetVersionNo (via the Cmo3Model facade, which also
				// records the slot on documents that never carried the element).
				DocumentField.RUNTIME_TARGET -> {
					target.setTargetVersionNo(edited.runtimeTarget.cmo3TargetVersionNo())
				}

				DocumentField.CANVAS_SIZE -> {
					lowerCanvasSize()
				}

				DocumentField.SOURCE_LAYER_DISPLAY -> {
					lowerSourceLayerDisplay()
				}

				DocumentField.SOURCE_FILES -> {
					lowerSourceFiles()
				}

				// A page appearing, vanishing, or resizing is a repack's output, and repacking rewrites
				// the page IMAGES too.  When the caller patched them (pagesRecomposed) the page set IS
				// reconciled and nothing is owed; otherwise the per-tile placements still lower and this
				// says the page set itself was not.
				DocumentField.ATLAS_PAGES -> {
					if (!pagesRecomposed) {
						notices.unsupported(ExportEntityCategory.Document, null, ExportNoticeReason.AtlasPageNotRecomposed)
					}
				}

				DocumentField.WORLD_ORIGIN -> {
					// An origin AT the canvas center survives implicitly (import derives exactly that),
					// so only an off-center origin is unrepresentable and worth a notice.
					if (!edited.isOriginAtCanvasCenter()) {
						notices.unsupported(ExportEntityCategory.Document, null, ExportNoticeReason.NoAuthoredWorldOrigin)
					}
				}

				DocumentField.PARAMETER_ORDER, DocumentField.PARAMETER_LINKS -> {}

				DocumentField.PARAMETER_TREE -> {
					val rootGroup = index.rootParameterGroup

					if (rootGroup == null) {
						notices.unsupported(ExportEntityCategory.Document, null, ExportNoticeReason.NoRootParameterGroup)
					} else {
						panelTree.rebuildGroupChildren(rootGroup, edited.parameterTree)
					}
				}

				DocumentField.ROOT_CHILDREN -> {
					val rootPart = index.rootPartSource

					if (rootPart == null) {
						notices.unsupported(ExportEntityCategory.Document, null, ExportNoticeReason.NoRootPart)
					} else {
						panelTree.rebuildOrgChildren(rootPart, edited.rootChildren)
					}
				}
			}
		}
	}

	/**
	 * Rewrites the parameter source order and combined flags from the edited model.  Document order
	 * is semantic: a combined (2D) pair is encoded positionally, X flagged and Y immediately next, so
	 * a link's Y is moved directly after its X when not already adjacent (reported, since the
	 * reimported order will then differ).
	 */
	private fun lowerParameterOrderAndLinks() {
		val sourceSet = index.modelSource.parameterSourceSet as? CParameterSourceSet

		if (sourceSet == null) {
			notices.unsupported(ExportEntityCategory.Document, null, ExportNoticeReason.NoParameterSourceSet)

			return
		}

		val orderedSources = ArrayList<Any?>()
		val placed = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

		for (parameter in edited.parameters) {
			val source = index.parameterByIdStr[parameter.id.raw] ?: continue
			orderedSources.add(source)
			placed.add(source)
		}

		for (source in index.parameterSources) {
			if (source !in placed) {
				orderedSources.add(source)
			}
		}

		// CMO3: CParameterSource field combined - true only on the X member of a linked pair.
		val linkByHorizontal = edited.parameterLinks.associateBy { link -> link.horizontal }

		for (source in index.parameterSources) {
			val idStr = idStrOf(source.id) ?: continue
			val shouldCombine = ParameterId(idStr) in linkByHorizontal

			if (source.combined != shouldCombine) {
				source.combined = shouldCombine
				editor.ensureChildSlot(source, "CParameterSource", "combined", "parentGroupGuid")
			}
		}

		for ((horizontal, vertical) in edited.parameterLinks) {
			val horizontalSource = index.parameterByIdStr[horizontal.raw] ?: continue
			val verticalSource = index.parameterByIdStr[vertical.raw] ?: continue
			val horizontalIndex = orderedSources.indexOfFirst { it === horizontalSource }
			val verticalIndex = orderedSources.indexOfFirst { it === verticalSource }

			if (horizontalIndex < 0 || verticalIndex < 0 || verticalIndex == horizontalIndex + 1) {
				continue
			}

			orderedSources.removeAt(verticalIndex)
			val anchorIndex = orderedSources.indexOfFirst { it === horizontalSource }
			orderedSources.add(anchorIndex + 1, verticalSource)
			notices.unsupported(
				ExportEntityCategory.Document,
				null,
				ExportNoticeReason.CombinedPairReordered(horizontal.raw, vertical.raw),
			)
		}

		editor.writeGraphList(
			owner = sourceSet,
			tag = "CParameterSourceSet",
			property = "_sources",
			beforeProperty = null,
			current = sourceSet._sources,
			newElements = orderedSources,
			kind = GraphListKind.CArrayList,
			assign = { list -> sourceSet._sources = list },
		)
	}

	/**
	 * Lowers each listed file's name, path, and modification time onto the layered image the editor
	 * decomposed it into, so a document reopened in either editor points at the file the rigger last
	 * relinked and knows when it was read.
	 *
	 * CMO3: CModelSource field textureManager -> CTextureManager field _rawImages -> LayeredImageWrapper
	 * field image -> CLayeredImage fields name / psdFile / psdFileLastModified.  The image is keyed by
	 * the guid the import minted the source id from; psdFile is the external-reference file shape, its
	 * text the absolute path on the machine that linked it, and psdFileLastModified the source's
	 * modification time in epoch milliseconds (docs/format/CMO3.md section 4).  Only a record the
	 * baseline also lists is a repointed file; one it lacks is art added this session, which the atlas
	 * web exports instead.
	 */
	private fun lowerSourceFiles() {
		val textureManager = index.modelSource.textureManager as? CTextureManager

		if (textureManager == null) {
			notices.unsupported(ExportEntityCategory.Document, null, ExportNoticeReason.NoTextureManagerToReconcile)

			return
		}

		val imageByGuid = HashMap<String, CLayeredImage>()

		for (wrapper in elementsOf(textureManager._rawImages)) {
			val image = (wrapper as? LayeredImageWrapper)?.image as? CLayeredImage ?: continue
			val guid = uuidOf(image.guid) ?: continue
			imageByGuid.putIfAbsent(guid, image)
		}

		val baselineById = baseline.sources.associateBy { source -> source.id }

		for (source in edited.sources) {
			val before = baselineById[source.id] ?: continue

			if (before.name == source.name && before.path == source.path && before.lastModified == source.lastModified) {
				continue
			}

			val image = imageByGuid[source.id.raw]

			if (image == null) {
				notices.unsupported(ExportEntityCategory.Document, source.name, ExportNoticeReason.NoMatchingSourceToReconcile)

				continue
			}

			image.name = source.name
			val fileRef =
				image.psdFile as? FileRef
					?: FileRef().also { created ->
						image.psdFile = created
						editor.ensureChildSlot(image, "CLayeredImage", "psdFile", "description")
					}
			fileRef.archivePath = null
			fileRef.textPath = source.path.orEmpty()
			// CMO3: CLayeredImage field psdFileLastModified - epoch milliseconds; a record with no known
			// time leaves the image's as it was.
			val lastModified = source.lastModified

			if (lastModified != null) {
				image.psdFileLastModified = lastModified
				editor.ensureChildSlot(image, "CLayeredImage", "psdFileLastModified", "_rootLayer")
			}
		}
	}

	/**
	 * Lowers the source-artwork display mode: the texture manager's flag AND the per-drawable input
	 * pointer it selects.
	 *
	 * The flag alone is not the whole state.  Flipping the mode in the official editor also retargets
	 * each art mesh's currentTextureInputData between its model-image input and its atlas-region one
	 * (docs/format/CMO3.md §4), so writing only the flag would leave every drawable pointing at the
	 * input the OTHER mode samples - a file that says one thing and points at another.
	 */
	private fun lowerSourceLayerDisplay() {
		// CMO3: CModelSource field textureManager -> CTextureManager field isTextureInputModelImageMode.
		val textureManager = index.modelSource.textureManager as? CTextureManager

		if (textureManager == null) {
			notices.unsupported(ExportEntityCategory.Document, null, ExportNoticeReason.NoTextureManagerToReconcile)

			return
		}

		val fromSourceLayers = edited.rendersFromSourceLayers
		textureManager.isTextureInputModelImageMode = fromSourceLayers
		editor.ensureChildSlot(textureManager, "CTextureManager", "isTextureInputModelImageMode", "previewReductionRatio")

		for ((id, name) in edited.drawables) {
			val source = index.drawableByIdStr[id.raw] ?: continue
			// CMO3: CArtMeshSource field _extensions -> CTextureInputExtension fields _textureInputs /
			// currentTextureInputData - the pointer names which of the drawable's inputs is live.
			val extension = elementsOf(source._extensions).filterIsInstance<CTextureInputExtension>().firstOrNull() ?: continue
			val inputs = elementsOf(extension._textureInputs)
			val wanted =
				if (fromSourceLayers) {
					inputs.filterIsInstance<CTextureInput_ModelImage>().firstOrNull()
				} else {
					inputs.filterIsInstance<CTextureInput_TextureAtlasRegion>().firstOrNull()
				}

			if (wanted == null) {
				// A drawable the editor never packed has no atlas region, and it samples its model image in
				// either mode: every corpus drawable without a region keeps that input current, atlas-mode
				// files included, so there is nothing to retarget and nothing to report.
				val neverPacked = inputs.none { input -> input is CTextureInput_TextureAtlasRegion } && inputs.any { input -> input is CTextureInput_ModelImage }

				if (!neverPacked) {
					// One with no model image in layer mode.  Leaving the live pointer alone keeps the file
					// self-consistent; the notice names the gap.
					notices.unsupported(ExportEntityCategory.Drawable, name, ExportNoticeReason.NoTextureInputForDisplayMode)
				}

				continue
			}

			extension.currentTextureInputData = wanted
			editor.ensureChildSlot(extension, "CTextureInputExtension", "currentTextureInputData", null)
		}
	}

	/** CMO3: CModelSource.canvas -> CImageCanvas fields pixelWidth / pixelHeight. */
	private fun lowerCanvasSize() {
		val canvas = index.modelSource.canvas as? CImageCanvas

		if (canvas == null) {
			notices.unsupported(ExportEntityCategory.Document, null, ExportNoticeReason.NoCanvasToReconcile)

			return
		}

		val width = edited.canvasWidth
		val height = edited.canvasHeight

		if (width != width.toInt().toFloat() || height != height.toInt().toFloat()) {
			notices.unsupported(ExportEntityCategory.Document, null, ExportNoticeReason.FractionalCanvasSizeNotStorable)

			return
		}

		canvas.pixelWidth = width.toInt()
		canvas.pixelHeight = height.toInt()
		editor.ensureChildSlot(canvas, "CImageCanvas", "pixelWidth", "pixelHeight")
		editor.ensureChildSlot(canvas, "CImageCanvas", "pixelHeight", "background")
	}
}