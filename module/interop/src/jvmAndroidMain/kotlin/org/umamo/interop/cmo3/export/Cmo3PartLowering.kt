package org.umamo.interop.cmo3.export

import org.umamo.format.cmo3.model.gen.CPartForm
import org.umamo.format.cmo3.model.gen.CPartSource
import org.umamo.format.cmo3.type.CArrayList
import org.umamo.interop.EntityDiff
import org.umamo.interop.ExportEntityCategory
import org.umamo.interop.ExportNoticeReason
import org.umamo.interop.PartField
import org.umamo.interop.alphaCompositionOf
import org.umamo.interop.cmo3.elementsOf
import org.umamo.interop.colorCompositionOf
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartGroupMode
import org.umamo.runtime.model.PartId

/**
 * The field pass for parts: each changed part's name, flags, group mode, draw order,
 * composite, and children, with its keyed channels handed to the keyform lowering.
 */
internal class Cmo3PartLowering(
	context: Cmo3ExportContext,
	private val index: Cmo3GraphIndex,
	private val notices: Cmo3ExportNotices,
	private val keyforms: Cmo3KeyformLowering,
	private val panelTree: Cmo3PanelTreeLowering,
) {
	private val editor = context.editor
	private val editedPartById = context.editedPartById
	private val baselinePartById = context.baselinePartById

	/**
	 * Lowers each changed part's fields onto its graph source.
	 *
	 * @param List<EntityDiff<PartId, PartField>> diffs The part diffs.
	 */
	fun lowerParts(diffs: List<EntityDiff<PartId, PartField>>) {
		for (diff in diffs) {
			when (diff) {
				// Structural synthesis already failed and reported why; see the field pass in Cmo3Export.
				is EntityDiff.Created -> {}

				// Deletions are consumed by the structural pass and stripped from the forwarded diff.
				is EntityDiff.Deleted -> {}

				is EntityDiff.Changed -> {
					val source = index.partByIdStr[diff.id.raw]
					val editedPart = editedPartById[diff.id]
					// A synthesized shell has no baseline; a default part stands in so the composite
					// lowering diffs the edited state against defaults (the shell's own values).
					val baselinePart = baselinePartById[diff.id] ?: Part(id = diff.id, name = "", children = emptyList())

					if (source == null || editedPart == null) {
						notices.unsupported(ExportEntityCategory.Part, diff.id.raw, ExportNoticeReason.NoMatchingSourceToReconcile)

						continue
					}

					for (field in diff.fields) {
						when (field) {
							PartField.NAME -> {
								lowerLocalName(editor, source, editedPart.name)
							}

							PartField.VISIBLE -> {
								lowerIsVisible(editor, source, editedPart.isVisible)
							}

							PartField.SELECTABLE -> {
								lowerIsLocked(editor, source, editedPart.isSelectable)
							}

							PartField.SKETCH -> {
								// CMO3: CPartSource field isSketch - the "Guide Image" reference-only flag.
								source.isSketch = editedPart.isSketch
								editor.ensureChildSlot(source, "CPartSource", "isSketch", "partsEditColor")
							}

							PartField.GROUP_MODE -> {
								// CMO3: CPartSource fields useOffscreen / enableDrawOrderGroup - Cubism's two
								// entangled checkboxes; offscreen forces grouping on, so Isolated writes both.
								source.useOffscreen = editedPart.groupMode is PartGroupMode.Isolated
								source.enableDrawOrderGroup = editedPart.groupMode !is PartGroupMode.PassThrough
								editor.ensureChildSlot(source, "CPartSource", "useOffscreen", "clipGuidList")
								editor.ensureChildSlot(source, "CPartSource", "enableDrawOrderGroup", "defaultOrder_forEditor")
							}

							PartField.DRAW_ORDER -> {
								lowerPartDrawOrder(source, editedPart)
							}

							PartField.CHILDREN -> {
								panelTree.rebuildOrgChildren(source, editedPart.children)
							}

							PartField.CHANNELS -> {
								keyforms.lowerPart(source, editedPart)
							}

							PartField.COMPOSITE -> {
								lowerPartComposite(source, diff.id, baselinePart, editedPart)
							}
						}
					}
				}
			}
		}
	}

	/**
	 * Lowers a part's static draw order.  With no DRAW_ORDER track the value lives in
	 * defaultOrder_forEditor and every CPartForm cell (import reads the first form), so both are
	 * written; with a track the static is shadowed by the grid and has no independent CMO3 home.
	 *
	 * @param CPartSource source     The part's graph source.
	 * @param Part        editedPart The edited part.
	 */
	private fun lowerPartDrawOrder(source: CPartSource, editedPart: Part) {
		val drawOrderTrack = editedPart.channelGrids[FormChannel.DRAW_ORDER]

		if (drawOrderTrack != null) {
			// Import re-derives the static from the FIRST grid form, so a static that matches the
			// track's head cell survives on its own; only a disagreeing static is actually lost.
			val headDrawOrder = (drawOrderTrack.cells.firstOrNull()?.form as? ChannelValue.Scalar)?.value?.toInt()

			if (headDrawOrder != editedPart.drawOrder) {
				notices.unsupported(ExportEntityCategory.Part, editedPart.id.raw, ExportNoticeReason.StaticDrawOrderShadowedByKeyforms)
			}

			return
		}

		// CMO3: CPartSource field defaultOrder_forEditor + CPartForm field drawOrder.
		source.defaultOrder_forEditor = editedPart.drawOrder
		editor.ensureChildSlot(source, "CPartSource", "defaultOrder_forEditor", "isSketch")

		for (form in elementsOf(source.keyforms).filterIsInstance<CPartForm>()) {
			form.drawOrder = editedPart.drawOrder
			editor.ensureChildSlot(form, "CPartForm", "drawOrder", "opacity")
		}
	}

	/**
	 * Lowers a part composite's flat fields (blend modes, masks, invert); the keyformed statics
	 * (opacity, colors) live in CPartForm cells and land with keyform lowering.  Part-typed masks
	 * (an Umamo extension) flatten to their descendant drawables - CMO3 has nowhere to keep the
	 * part reference, so the grouping itself is reported as not carried.
	 *
	 * @param CPartSource source       The part's graph source.
	 * @param PartId      partId       The part's id (for notices).
	 * @param Part        baselinePart The baseline part.
	 * @param Part        editedPart   The edited part.
	 */
	private fun lowerPartComposite(source: CPartSource, partId: PartId, baselinePart: Part, editedPart: Part) {
		val baselineComposite = baselinePart.composite
		val editedComposite = editedPart.composite

		if (baselineComposite.blendMode != editedComposite.blendMode) {
			// CMO3: CPartSource field colorComposition.
			source.colorComposition = colorCompositionOf(editedComposite.blendMode)
			editor.ensureChildSlot(source, "CPartSource", "colorComposition", "alphaComposition")
		}

		if (baselineComposite.alphaBlendMode != editedComposite.alphaBlendMode) {
			// CMO3: CPartSource field alphaComposition.
			source.alphaComposition = alphaCompositionOf(editedComposite.alphaBlendMode)
			editor.ensureChildSlot(source, "CPartSource", "alphaComposition", "targetDeformerGuid")
		}

		if (
			baselineComposite.maskedBy != editedComposite.maskedBy ||
			baselineComposite.maskedByParts != editedComposite.maskedByParts
		) {
			if (editedComposite.maskedByParts.isNotEmpty()) {
				notices.unsupported(ExportEntityCategory.Part, partId.raw, ExportNoticeReason.PartMasksFlattenToDrawables)
			}

			// CMO3: CPartSource field clipGuidList - always drawable GUIDs, so part-typed masks are
			// expanded to the part's descendant drawables (the same expansion the render tree applies).
			val expandedMask = editedComposite.maskedBy + editedComposite.maskedByParts.flatMap(::descendantDrawables)
			editor.writeGraphList(
				owner = source,
				tag = "CPartSource",
				property = "clipGuidList",
				beforeProperty = "invertClippingMask",
				current = source.clipGuidList,
				newElements = expandedMask.distinct().mapNotNull { index.drawableByIdStr[it.raw]?.guid },
				kind = GraphListKind.CArrayList,
				assign = { list -> source.clipGuidList = list },
			)
		}

		if (baselineComposite.invertMask != editedComposite.invertMask) {
			// CMO3: CPartSource field invertClippingMask.
			source.invertClippingMask = editedComposite.invertMask
			editor.ensureChildSlot(source, "CPartSource", "invertClippingMask", "colorComposition")
		}

		val staticsChanged =
			baselineComposite.opacity != editedComposite.opacity ||
				baselineComposite.multiplyColor != editedComposite.multiplyColor ||
				baselineComposite.screenColor != editedComposite.screenColor

		if (staticsChanged) {
			val opacityTrack = editedPart.channelGrids[FormChannel.OPACITY]
			val multiplyTrack = editedPart.channelGrids[FormChannel.MULTIPLY_COLOR]
			val screenTrack = editedPart.channelGrids[FormChannel.SCREEN_COLOR]

			if (opacityTrack != null || multiplyTrack != null || screenTrack != null) {
				// With tracks present the statics live in the grid cells; import re-derives each
				// from the FIRST form, so a static matching its track's head cell survives on its
				// own and only a disagreeing (or track-less-but-changed) one is actually lost.
				val opacitySurvives = (opacityTrack?.cells?.firstOrNull()?.form as? ChannelValue.Scalar)?.value == editedComposite.opacity
				val multiplySurvives =
					(multiplyTrack?.cells?.firstOrNull()?.form as? ChannelValue.Color)?.color == editedComposite.multiplyColor
				val screenSurvives =
					(screenTrack?.cells?.firstOrNull()?.form as? ChannelValue.Color)?.color == editedComposite.screenColor

				if (!opacitySurvives || !multiplySurvives || !screenSurvives) {
					notices.unsupported(ExportEntityCategory.Part, partId.raw, ExportNoticeReason.CompositeStaticsShadowedByKeyforms)
				}
			} else {
				keyforms.writePartCompositeStatics(source, editedPart)
			}
		}
	}

	/**
	 * The drawables under a part in the edited model's org tree, in panel order.
	 *
	 * @param PartId partId The part whose subtree is walked.
	 * @return List<DrawableId> The subtree's drawables, empty when the part is unknown.
	 */
	private fun descendantDrawables(partId: PartId): List<DrawableId> {
		val part = editedPartById[partId] ?: return emptyList()
		val drawableIds = ArrayList<DrawableId>()

		/**
		 * Adds the drawables in a child list and under its parts.
		 *
		 * @param List<OrgChild> children The child list to walk.
		 */
		fun walk(children: List<OrgChild>) {
			for (child in children) {
				when (child) {
					is OrgChild.Drawable -> drawableIds.add(child.id)
					is OrgChild.Part -> editedPartById[child.id]?.let { walk(it.children) }
				}
			}
		}
		walk(part.children)

		return drawableIds
	}
}