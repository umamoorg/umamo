package org.umamo.interop.cmo3.import

import org.umamo.format.cmo3.model.gen.AlphaComposition
import org.umamo.format.cmo3.model.gen.CPartForm
import org.umamo.format.cmo3.model.gen.CPartSource
import org.umamo.format.cmo3.model.gen.ColorComposition
import org.umamo.interop.alphaBlendOfToken
import org.umamo.interop.cmo3.displayNameOf
import org.umamo.interop.cmo3.elementsOf
import org.umamo.interop.cmo3.idStrOf
import org.umamo.interop.cmo3.uuidOf
import org.umamo.interop.colorBlendOfToken
import org.umamo.runtime.keyform.asChannelTrack
import org.umamo.runtime.keyform.channelGridsOf
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartComposite
import org.umamo.runtime.model.PartForm
import org.umamo.runtime.model.PartGroupMode
import org.umamo.runtime.model.PartId

/*
 * The part producers of the CMO3 import: the user-facing parts with their channels and composites, and
 * the org tree's top level.
 */

/**
 * Builds the runtime parts, in the part set's order, without the synthetic root.
 *
 * @param Cmo3ImportContext context The import's shared reads.
 * @return List<Part> The parts; a source with no id keeps an empty one.
 */
internal fun importParts(context: Cmo3ImportContext): List<Part> =
	context.userPartSources.map { source ->
		Part(
			id = PartId(idStrOf(source.id).orEmpty()),
			// CMO3: localName is the user-facing part name; an absent or empty one falls back to the id.
			name = displayNameOf(source.localName, idStrOf(source.id)),
			children = context.orgChildrenOf(source._childGuids),
			// CMO3: ACParameterControllableSource.isVisible = the Parts-panel eyeball (cascades to children);
			// CPartSource.isSketch = the Inspector's "Guide Image" checkbox (reference-only, non-export).
			isVisible = source.isVisible,
			isSketch = source.isSketch,
			// CMO3: ACParameterControllableSource.isLocked (inverted: Cubism lock = not selectable).
			isSelectable = !source.isLocked,
			// CMO3: CPartSource.useOffscreen = "offscreen drawing" (forces grouping on);
			// enableDrawOrderGroup = "Group by Draw Order"; CPartForm.drawOrder = the slot.
			groupMode =
				when {
					source.useOffscreen -> PartGroupMode.Isolated
					source.enableDrawOrderGroup -> PartGroupMode.Grouped
					else -> PartGroupMode.PassThrough
				},
			drawOrder = partStaticDrawOrder(source),
			channelGrids = partChannelsOf(context, source),
			// The composite is stored latently on every part (not just offscreen ones): the CMO3
			// composition/clip fields survive unchecking offscreen, so we capture them regardless of the
			// mode and apply them only while Isolated.  partCompositeOf defaults gracefully when absent.
			composite = partCompositeOf(context, source),
		)
	}

/**
 * Reads the id of the synthetic root part.
 *
 * The root is intentionally absent from the parts, and entities under it carry no part id; its own id
 * is kept for tree reconstruction and write-back.
 *
 * @param Cmo3ImportContext context The import's shared reads.
 * @return PartId? The root part's id, or null when the model has no root part source.
 */
internal fun importRootPartId(context: Cmo3ImportContext): PartId? =
	context.partSources.firstOrNull { uuidOf(it.guid) == context.rootPartUuid }
		?.let { idStrOf(it.id) }
		?.let(::PartId)

/**
 * Builds the org tree's top level.
 *
 * @param Cmo3ImportContext context The import's shared reads.
 * @param List<Part> parts The imported parts.
 * @param List<Drawable> drawables The imported drawables, in their flat order.
 * @return List<OrgChild> The root's children in panel order, then every drawable no part places.
 */
internal fun importRootChildren(context: Cmo3ImportContext, parts: List<Part>, drawables: List<Drawable>): List<OrgChild> {
	// The org tree's top level: the synthetic root part's _childGuids (parts + drawables interleaved).
	val rootChildrenFromTree = context.orgChildrenOf((context.rootPart as? CPartSource)?._childGuids)
	// Safety net: any drawable the panel walk never placed (e.g. one reachable only under a deformer) is
	// appended at the root, so no mesh is dropped from the outliner or the derived render order.
	val placedDrawableIds =
		buildSet {
			rootChildrenFromTree.forEach { child ->
				if (child is OrgChild.Drawable) {
					add(child.id)
				}
			}
			parts.forEach { part ->
				part.children.forEach { child ->
					if (child is OrgChild.Drawable) {
						add(child.id)
					}
				}
			}
		}

	return rootChildrenFromTree + drawables.mapNotNull { drawable -> if (drawable.id in placedDrawableIds) null else OrgChild.Drawable(drawable.id) }
}

/**
 * Reads a part's static draw order.
 *
 * CMO3: part draw order is CPartForm.drawOrder (a keyform); the static first-cell value (else
 * defaultOrder_forEditor) is the fallback, the grid carries the per-pose blend for a group part.
 *
 * @param CPartSource part The part's graph source.
 * @return Int The first form's draw order, else the editor default.
 */
private fun partStaticDrawOrder(part: CPartSource): Int =
	elementsOf(part.keyforms).filterIsInstance<CPartForm>().firstOrNull()?.drawOrder ?: part.defaultOrder_forEditor

/**
 * Tells whether a part was authored before Cubism 5.3.
 *
 * A 5.3-authored part is marked by a present colorComposition (the offscreen probe pins this on every
 * corpus file); a pre-5.3 part has it null.
 *
 * @param CPartSource part The part's graph source.
 * @return Boolean True when the part carries no color composition.
 */
private fun isPre53Part(part: CPartSource): Boolean = part.colorComposition == null

/**
 * Resolves a part form's stored opacity to the opacity it means.
 *
 * CMO3: CPartForm.opacity is @DontSerializeIfDefault with a 0f default, and pre-5.3 parts never author
 * the float, so it parses as 0f meaning "unset -> fully opaque" (Cubism's semantic default is 100%),
 * NOT 0%.  A 5.3 part authors opacity explicitly, so a genuine 0 there (e.g. a draw-order crossfade
 * keyform) is a real value and is left alone - only pre-5.3's unset 0f is remapped to opaque.  Without
 * this a pre-5.3 model's whole part subtree renders invisible under the part-opacity cascade.
 *
 * @param Float rawOpacity The opacity as stored.
 * @param Boolean isPre53 Whether the part predates Cubism 5.3.
 * @return Float The opacity to use.
 */
private fun resolvedPartOpacity(rawOpacity: Float, isPre53: Boolean): Float =
	if (isPre53 && rawOpacity == 0f) {
		1f
	} else {
		rawOpacity
	}

/**
 * Builds a part's channel tracks from its keyform grid.
 *
 * @param Cmo3ImportContext context The import's shared reads.
 * @param CPartSource part The part's graph source.
 * @return ChannelGrids The tracks, empty when the grid has no parameter axes.
 */
private fun partChannelsOf(context: Cmo3ImportContext, part: CPartSource): ChannelGrids {
	val bundled =
		buildGrid(part.keyformGridSource, part.keyforms, context.paramIdByUuid) { form ->
			// CMO3: CPartForm carries the part's keyformed channels - drawOrder always, and (5.3)
			// opacity/multiplyColor/screenColor for the layer composite.
			(form as? CPartForm)?.let {
				PartForm(
					drawOrder = it.drawOrder.toFloat(),
					opacity = resolvedPartOpacity(it.opacity, isPre53Part(part)),
					multiplyColor = colorRgbOf(it.multiplyColor) ?: ColorRgb.MultiplyIdentity,
					screenColor = colorRgbOf(it.screenColor) ?: ColorRgb.ScreenIdentity,
				)
			}
		}
			// A grid with no parameter axes is a single static cell - it carries nothing the eval reads
			// beyond Part.drawOrder (kept separately) and the opacity/color channels, which then fall back
			// to the part's PartComposite.  Dropping it means an isolated static part honors an edited
			// composite opacity/color, and it matches Moc3Import, which yields no track for an unbound
			// part.  A part with real animation keeps its tracks (non-empty axes).
			?.takeIf { it.axes.isNotEmpty() } ?: return ChannelGrids.Empty

	// Fan the one bundled grid out into per-channel tracks sharing its axes: a pure re-shape, so the
	// blended values are bit-identical to what the bundled cell produced.
	return channelGridsOf(
		FormChannel.DRAW_ORDER to bundled.asChannelTrack { form -> ChannelValue.Scalar(form.drawOrder) },
		FormChannel.OPACITY to bundled.asChannelTrack { form -> ChannelValue.Scalar(form.opacity) },
		FormChannel.MULTIPLY_COLOR to bundled.asChannelTrack { form -> ChannelValue.Color(form.multiplyColor) },
		FormChannel.SCREEN_COLOR to bundled.asChannelTrack { form -> ChannelValue.Color(form.screenColor) },
	)
}

/**
 * Reads a part's composite settings: blend modes, masks, opacity, and colors.
 *
 * CMO3: the composition/clip fields are latent - they survive unchecking offscreen, so this is
 * called for every part (not just offscreen ones) to capture the settings regardless of mode; the
 * composite is only applied while the part is Isolated.  Each field defaults gracefully when absent
 * (colorComposition/alphaComposition -> Normal/Over via the token maps; pre-5.3 CPartForms carry no
 * opacity/color elements, so firstForm's channels fall back to identity).
 *
 * @param Cmo3ImportContext context The import's shared reads.
 * @param CPartSource source The part's graph source.
 * @return PartComposite The composite.
 */
private fun partCompositeOf(context: Cmo3ImportContext, source: CPartSource): PartComposite {
	val firstForm = elementsOf(source.keyforms).filterIsInstance<CPartForm>().firstOrNull()

	return PartComposite(
		blendMode = colorBlendOfToken((source.colorComposition as? ColorComposition)?.name),
		alphaBlendMode = alphaBlendOfToken((source.alphaComposition as? AlphaComposition)?.name),
		// CMO3: CPartSource.clipGuidList - always drawable GUIDs (a part picked as Clipping ID is
		// expanded to its drawables at authoring time).
		maskedBy = resolveGuids(source.clipGuidList, context.drawableIdByUuid),
		// CMO3: CPartSource.invertClippingMask - show outside the mask instead of inside.
		invertMask = source.invertClippingMask,
		// No form at all is already opaque; a pre-5.3 form's unset 0f is remapped to opaque too.
		opacity = firstForm?.let { resolvedPartOpacity(it.opacity, isPre53Part(source)) } ?: 1f,
		multiplyColor = colorRgbOf(firstForm?.multiplyColor) ?: ColorRgb.MultiplyIdentity,
		screenColor = colorRgbOf(firstForm?.screenColor) ?: ColorRgb.ScreenIdentity,
	)
}