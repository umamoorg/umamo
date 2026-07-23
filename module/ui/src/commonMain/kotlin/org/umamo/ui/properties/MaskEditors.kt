package org.umamo.ui.properties

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.SelectionTarget
import org.umamo.edit.setDrawableMaskedBy
import org.umamo.edit.setPartComposite
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartComposite
import org.umamo.runtime.model.PartId
import org.umamo.ui.kit.RelationListField
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.UmamoIcon
import org.umamo.ui.workspace.LocalRelationPick
import org.umamo.ui.workspace.PickKind
import org.umamo.ui.workspace.pickingFor

/*
 * The clip-mask relation lists - a part composite's masks and a drawable's own.  Both are add / remove
 * lists over the shared RelationListField, and both resolve their picks against the LIVE model rather than
 * the value captured in composition (an armed eyedropper resolves long after it was armed).
 */

/**
 * The drawables eligible to be added as clip masks for a composite: every drawable not already applied.
 * Any drawable is a valid mask target (the model has no is-mask flag), so the candidate set is simply the
 * complement of [current].  Pure, so the "add mask" menu contents are unit-testable.
 *
 * @param List drawables Every drawable in the model.
 * @param List current The mask ids already applied to the composite.
 * @return List The drawables not yet masking, in model order.
 */
internal fun maskCandidates(drawables: List<Drawable>, current: List<DrawableId>): List<Drawable> {
	val applied = current.toSet()
	return drawables.filter { drawable -> drawable.id !in applied }
}

/**
 * One entry in a composite's clip-mask list.  A drawable clips directly; a part stands for every drawable
 * in its subtree and is resolved to them only in the derived render tree, so the mask follows the part as
 * its children change (see [org.umamo.runtime.model.PartComposite.maskedByParts]).
 */
private sealed interface MaskEntry {
	/** The entry's display name. */
	val name: String

	/** A drawable whose alpha clips directly. */
	data class OfDrawable(val id: DrawableId, override val name: String) : MaskEntry

	/** A part standing for its descendant drawables. */
	data class OfPart(val id: PartId, override val name: String) : MaskEntry
}

/**
 * A mask entry's stable row identity.  The wrapping entry is rebuilt every recomposition, and the display
 * name is source-art data that repeats, so the underlying id is the only thing that identifies a row.
 *
 * @return Any The entry's stable key.
 */
private fun MaskEntry.key(): Any =
	when (this) {
		is MaskEntry.OfDrawable -> id
		is MaskEntry.OfPart -> id
	}

/**
 * The type glyph for a mask entry, so drawable and part masks read apart in the list.
 *
 * @return UmamoIcon The entry's type icon.
 */
private fun MaskEntry.glyph(): UmamoIcon =
	when (this) {
		is MaskEntry.OfDrawable -> LocalUmamoIcons.mesh
		is MaskEntry.OfPart -> LocalUmamoIcons.part
	}

/**
 * The isolated part composite's clip-mask editor: a scrollable, resizable list of the drawables and parts
 * clipping the layer, over a search field whose eyedropper takes a mask straight from the viewport or the
 * outliner.  Every edit routes the whole updated composite through setPartComposite, so it is one undo step
 * and survives a mode round-trip like the other composite fields.  Masks are a set (order does not affect
 * coverage), so the list is not reorderable.
 *
 * A viewport click resolves to the drawable it hits; picking a whole part is done from the list or the
 * outliner, since the viewport hit test is drawable-only.
 *
 * @param Part part The part whose composite is edited.
 * @param PartComposite composite The active (Isolated) composite being edited.
 * @param PropertyContext context The context supplying the model and session.
 */
@Composable
internal fun PartMaskEditor(part: Part, composite: PartComposite, context: PropertyContext) {
	val session = context.session
	val relationPick = LocalRelationPick.current
	val owner = "part.maskedBy:${part.id.raw}"
	// Every write re-reads the part's CURRENT composite rather than the one captured in this composition.
	// An armed eyedropper resolves LONG after it was armed and the panel stays editable in between, so
	// closing over `composite` would silently revert any opacity / colour edit made while the pick was in
	// flight - one undo step that quietly undoes two others.
	val mutateComposite: (PartComposite.() -> PartComposite) -> Unit = { change ->
		val live = session?.model?.value?.parts?.firstOrNull { it.id == part.id }?.activeComposite
		if (live != null) {
			session.setPartComposite(part.id, live.change())
		}
	}

	val entries =
		buildList {
			for (maskId in composite.maskedBy) {
				context.puppet.drawables.firstOrNull { it.id == maskId }?.let { masker ->
					add(MaskEntry.OfDrawable(masker.id, masker.name.ifBlank { masker.id.raw }))
				}
			}
			for (maskPartId in composite.maskedByParts) {
				context.puppet.parts.firstOrNull { it.id == maskPartId }?.let { masker ->
					add(MaskEntry.OfPart(masker.id, masker.name.ifBlank { masker.id.raw }))
				}
			}
		}
	val candidates =
		buildList {
			for (candidate in maskCandidates(context.puppet.drawables, composite.maskedBy)) {
				add(MaskEntry.OfDrawable(candidate.id, candidate.name.ifBlank { candidate.id.raw }))
			}
			// A part cannot mask itself (it would clip the very layer it composites).
			for (candidate in context.puppet.parts) {
				if (candidate.id != part.id && candidate.id !in composite.maskedByParts) {
					add(MaskEntry.OfPart(candidate.id, candidate.name.ifBlank { candidate.id.raw }))
				}
			}
		}
	val addEntry: (MaskEntry) -> Unit = { entry ->
		when (entry) {
			is MaskEntry.OfDrawable -> mutateComposite { copy(maskedBy = (maskedBy + entry.id).distinct()) }
			// A part masking itself would clip the very layer it composites; the candidate list excludes it,
			// and the eyedropper has to refuse it too or the outliner becomes a way around the guard.
			is MaskEntry.OfPart -> if (entry.id != part.id) mutateComposite { copy(maskedByParts = (maskedByParts + entry.id).distinct()) }
		}
	}

	RelationListBlock(stringResource(Res.string.properties_field_masked_by)) {
		RelationListField(
			entries = entries,
			candidates = candidates,
			label = { entry -> entry.name },
			keyOf = { entry -> entry.key() },
			icon = { entry -> entry.glyph() },
			onAdd = addEntry,
			onRemove = { entry ->
				when (entry) {
					is MaskEntry.OfDrawable -> mutateComposite { copy(maskedBy = maskedBy - entry.id) }
					is MaskEntry.OfPart -> mutateComposite { copy(maskedByParts = maskedByParts - entry.id) }
				}
			},
			modifier = Modifier.fillMaxWidth(),
			onPick = {
				relationPick.arm(setOf(PickKind.Drawable, PickKind.Part), owner) { target ->
					when (target) {
						is SelectionTarget.Drawable -> addEntry(MaskEntry.OfDrawable(target.id, target.id.raw))
						is SelectionTarget.Part -> addEntry(MaskEntry.OfPart(target.id, target.id.raw))
						is SelectionTarget.Deformer -> {}
					}
				}
			},
			picking = relationPick.pickingFor(owner),
			addPlaceholder = stringResource(Res.string.properties_mask_add),
			removeDescription = stringResource(Res.string.properties_mask_remove),
			pickDescription = stringResource(Res.string.properties_relation_pick),
		)
	}
}

/**
 * A drawable's own clip-mask editor, the same list over [org.umamo.runtime.model.Drawable.maskedBy].  Only
 * drawables may clip a drawable (the part extension applies to the composite layer alone), and a drawable
 * may not mask itself.
 *
 * @param Drawable drawable The drawable whose masks are edited.
 * @param PropertyContext context The context supplying the model and session.
 */
@Composable
internal fun DrawableMaskEditor(drawable: Drawable, context: PropertyContext) {
	val session = context.session
	val relationPick = LocalRelationPick.current
	val owner = "drawable.maskedBy:${drawable.id.raw}"
	val entries = drawable.maskedBy.mapNotNull { maskId -> context.puppet.drawables.firstOrNull { it.id == maskId } }
	val candidates = maskCandidates(context.puppet.drawables, drawable.maskedBy).filterNot { it.id == drawable.id }
	// Live read for the same reason as PartMaskEditor's mutateComposite: an armed pick resolves later, and
	// the captured list would revert any mask added in the meantime.
	val addMask: (DrawableId) -> Unit = { maskId ->
		val live = session?.model?.value?.drawables?.firstOrNull { it.id == drawable.id }?.maskedBy
		if (live != null) {
			session.setDrawableMaskedBy(drawable.id, (live + maskId).distinct())
		}
	}

	RelationListBlock(stringResource(Res.string.properties_field_masked_by)) {
		RelationListField(
			entries = entries,
			candidates = candidates,
			label = { masker -> masker.name.ifBlank { masker.id.raw } },
			keyOf = { masker -> masker.id },
			icon = { LocalUmamoIcons.mesh },
			onAdd = { masker -> addMask(masker.id) },
			onRemove = { masker -> session?.setDrawableMaskedBy(drawable.id, drawable.maskedBy - masker.id) },
			modifier = Modifier.fillMaxWidth(),
			onPick = {
				relationPick.arm(setOf(PickKind.Drawable), owner) { target ->
					(target as? SelectionTarget.Drawable)?.let { picked ->
						if (picked.id != drawable.id) {
							addMask(picked.id)
						}
					}
				}
			},
			picking = relationPick.pickingFor(owner),
			addPlaceholder = stringResource(Res.string.properties_mask_add),
			removeDescription = stringResource(Res.string.properties_mask_remove),
			pickDescription = stringResource(Res.string.properties_relation_pick),
		)
	}
}
