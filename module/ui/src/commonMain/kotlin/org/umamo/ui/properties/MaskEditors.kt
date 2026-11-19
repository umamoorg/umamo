package org.umamo.ui.properties

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.SelectionTarget
import org.umamo.edit.relation.RelationSlot
import org.umamo.edit.relation.masksOf
import org.umamo.edit.relation.relationAdmitter
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.Part
import org.umamo.ui.action.rememberCommandMenu
import org.umamo.ui.kit.field.RelationListField
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.UmamoIcon
import org.umamo.ui.transform.bindRelation
import org.umamo.ui.workspace.LocalRelationPick
import org.umamo.ui.workspace.operationstrip.LocalOperationStripArea
import org.umamo.ui.workspace.pickingFor

/*
 * The clip-mask relation lists - a part composite's masks and a drawable's own.  Both are add / remove lists over the
 * shared RelationListField, on one slot each: the add field and its eyedropper bind into the list's slot, and a row
 * removes through its own.  Every write goes through bindRelation, which reads the LIVE model, so an eyedropper that
 * resolves long after it was armed never reverts an edit made in between.
 */

/** The "Masked By" relation list's name in the editor entry's `listHeights` (UMA §7.3); a part's and a drawable's share it. */
private const val MASKED_BY_LIST_NAME = "maskedBy"

/**
 * One entry in a clip-mask list: the drawable or part that clips, with its display name.  A part stands for every
 * drawable in its subtree and is resolved to them only in the derived render tree, so the mask follows the part as
 * its children change (see [org.umamo.runtime.model.PartComposite.maskedByParts]).
 *
 * @property SelectionTarget target The drawable or part, which is also the row's stable identity.
 * @property String name The display name.
 */
private data class MaskEntry(val target: SelectionTarget, val name: String)

/**
 * The type glyph for a mask entry, so drawable and part masks read apart in the list.
 *
 * @return UmamoIcon The entry's type icon.
 */
private fun MaskEntry.glyph(): UmamoIcon = if (target is SelectionTarget.Part) LocalUmamoIcons.part else LocalUmamoIcons.mesh

/**
 * The entries a mask list offers to add for [slot]: every drawable, then every part, the slot admits.
 *
 * @param PropertyContext context The context supplying the model.
 * @param RelationSlot.Masks slot The list.
 * @return List<MaskEntry> The candidates, drawables first, each in model order.
 */
private fun maskCandidatesOf(context: PropertyContext, slot: RelationSlot.Masks): List<MaskEntry> {
	val admits = context.puppet.relationAdmitter(slot)
	val drawables = context.puppet.drawables.map { drawable -> MaskEntry(SelectionTarget.Drawable(drawable.id), drawable.name.ifBlank { drawable.id.raw }) }
	val parts = context.puppet.parts.map { part -> MaskEntry(SelectionTarget.Part(part.id), part.name.ifBlank { part.id.raw }) }

	return (drawables + parts).filter { entry -> admits(entry.target) }
}

/**
 * The entries [slot]'s owner is masked by, named from the model; an id naming nothing is left out.
 *
 * @param PropertyContext context The context supplying the model.
 * @param RelationSlot.Masks slot The list.
 * @return List<MaskEntry> The entries, in stored order.
 */
private fun maskEntriesOf(context: PropertyContext, slot: RelationSlot.Masks): List<MaskEntry> =
	context.puppet.masksOf(slot.owner).mapNotNull { target ->
		when (target) {
			is SelectionTarget.Drawable -> context.puppet.drawables.firstOrNull { drawable -> drawable.id == target.id }?.let { masker -> MaskEntry(target, masker.name.ifBlank { masker.id.raw }) }
			is SelectionTarget.Part -> context.puppet.parts.firstOrNull { part -> part.id == target.id }?.let { masker -> MaskEntry(target, masker.name.ifBlank { masker.id.raw }) }
			is SelectionTarget.Deformer -> null
		}
	}

/**
 * One clip-mask list over [slot]: a scrollable, resizable list of what clips the owner, over a search field whose
 * eyedropper takes a mask straight from the viewport or the outliner.  Each add or removal is one undo step.  Masks
 * are a set (order does not affect coverage), so the list is not reorderable.
 *
 * @param RelationSlot.Masks slot The list, which is also the add field's identity.
 * @param StringResource descriptionRes What the list means, as the label's tooltip.
 * @param PropertyContext context The context supplying the model and session.
 */
@Composable
private fun MaskList(slot: RelationSlot.Masks, descriptionRes: StringResource, context: PropertyContext) {
	val session = context.session
	val relationPick = LocalRelationPick.current
	// Asked as the binding lands; mask edits register no strip, but the write path takes the area all the same.
	val stripArea = LocalOperationStripArea.current
	val viewState = LocalPropertiesViewState.current
	RelationListBlock(stringResource(Res.string.properties_field_masked_by), description = stringResource(descriptionRes)) {
		RelationListField(
			entries = maskEntriesOf(context, slot),
			candidates = maskCandidatesOf(context, slot),
			label = { entry -> entry.name },
			keyOf = { entry -> entry.target },
			icon = { entry -> entry.glyph() },
			onAdd = { entry -> session?.bindRelation(slot, entry.target, stripArea()) },
			onRemove = { entry -> session?.bindRelation(RelationSlot.Mask(slot.owner, entry.target), null, stripArea()) },
			modifier = Modifier.fillMaxWidth(),
			height = viewState?.listHeights?.get(MASKED_BY_LIST_NAME),
			onHeightChange = { dragged -> viewState?.listHeights?.set(MASKED_BY_LIST_NAME, dragged) },
			onPick = session?.let { live -> { relationPick.armFor(slot, live, stripArea) } },
			picking = relationPick.pickingFor(slot),
			addPlaceholder = stringResource(Res.string.properties_mask_add),
			removeDescription = stringResource(Res.string.properties_mask_remove),
			pickDescription = stringResource(Res.string.properties_relation_pick),
			entryMenu = { entry -> rememberCommandMenu(relationEntryMenu(RelationSlot.Mask(slot.owner, entry.target))) },
		)
	}
}

/**
 * The isolated part composite's clip-mask editor: the drawables and parts clipping the layer.  A viewport click
 * resolves to the drawable it hits; picking a whole part is done from the list or the outliner, since a click on art
 * means the drawable here.
 *
 * @param Part part The part whose composite is edited (shown only while it is Isolated).
 * @param PropertyContext context The context supplying the model and session.
 */
@Composable
internal fun PartMaskEditor(part: Part, context: PropertyContext) {
	MaskList(RelationSlot.Masks(SelectionTarget.Part(part.id)), Res.string.properties_field_masked_by_part_description, context)
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
	MaskList(RelationSlot.Masks(SelectionTarget.Drawable(drawable.id)), Res.string.properties_field_masked_by_description, context)
}