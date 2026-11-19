package org.umamo.ui.properties

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.SelectionTarget
import org.umamo.edit.relation.RelationSlot
import org.umamo.edit.relation.relationAdmitter
import org.umamo.edit.relation.relationBound
import org.umamo.runtime.model.Deformer
import org.umamo.ui.action.rememberCommandMenu
import org.umamo.ui.kit.field.PropertyFieldRow
import org.umamo.ui.kit.field.RelationField
import org.umamo.ui.kit.menu.ContextMenuArea
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.UmamoIcon
import org.umamo.ui.transform.bindRelation
import org.umamo.ui.workspace.LocalRelationPick
import org.umamo.ui.workspace.operationstrip.LocalOperationStripArea
import org.umamo.ui.workspace.pickingFor

/*
 * The single-value relation rows - bind the active item to a part or to a deformer - plus the deformer type
 * glyph they and the Data tab share.  The multi-value lists live in MaskEditors.kt.  Each row is one relation slot:
 * its list, its eyedropper, and every write go through the slot's rule and its one write path (bindRelation).
 */

/**
 * The type glyph for a deformer, matching the outliner's warp-versus-rotation split.
 *
 * @param Deformer deformer The deformer to glyph.
 * @return UmamoIcon Its type icon.
 */
internal fun deformerIcon(deformer: Deformer): UmamoIcon =
	when (deformer) {
		is Deformer.Rotation -> LocalUmamoIcons.rotationDeformer
		is Deformer.Warp -> LocalUmamoIcons.warpDeformer
	}

/**
 * A labelled row binding the active item to an organizational part, through the shared relation picker:
 * the field lists every part the slot admits, and its eyedropper takes a part from a viewport click (resolved
 * through the clicked drawable's owner) or an outliner row.
 *
 * @param StringResource labelRes The row's localized label.
 * @param StringResource descriptionRes What the relation means, as the label's tooltip.
 * @param PropertyContext context The context supplying the model and the session.
 * @param RelationSlot.OwningPart slot The relation, which is also the field's identity.
 */
@Composable
internal fun PartRelationRow(
	labelRes: StringResource,
	descriptionRes: StringResource,
	context: PropertyContext,
	slot: RelationSlot.OwningPart,
) {
	val relationPick = LocalRelationPick.current
	// Asked as the binding lands, so the strip follows the pointer's state then.
	val stripArea = LocalOperationStripArea.current
	val session = context.session
	val admits = context.puppet.relationAdmitter(slot)
	val parts = context.puppet.parts.filter { part -> admits(SelectionTarget.Part(part.id)) }
	val boundId = (context.puppet.relationBound(slot) as? SelectionTarget.Part)?.id
	PropertyFieldRow(stringResource(labelRes), description = stringResource(descriptionRes)) {
		RelationMenuArea(slot) {
			RelationField(
				selected = parts.firstOrNull { candidate -> candidate.id == boundId },
				candidates = parts,
				label = { candidate -> candidate.name.ifBlank { candidate.id.raw } },
				icon = { LocalUmamoIcons.part },
				onSelect = { candidate -> session?.bindRelation(slot, candidate?.let { part -> SelectionTarget.Part(part.id) }, stripArea()) },
				modifier = Modifier.fillMaxWidth(),
				onPick = session?.let { live -> { relationPick.armFor(slot, live, stripArea) } },
				picking = relationPick.pickingFor(slot),
				clearDescription = stringResource(Res.string.properties_relation_clear),
				pickDescription = stringResource(Res.string.properties_relation_pick),
			)
		}
	}
}

/**
 * A labelled row binding the active item to a deformer, through the shared relation picker: the field lists
 * every deformer the slot admits (never the owner's own subtree), and its eyedropper takes one from a viewport
 * click (a drawn lattice, else the parent deformer of the clicked art) or an outliner row.  The binding keeps the
 * item where it rests and offers Keep in Place on the operation strip.
 *
 * @param StringResource labelRes The row's localized label.
 * @param StringResource descriptionRes What the relation means, as the label's tooltip.
 * @param PropertyContext context The context supplying the model and the session.
 * @param RelationSlot.ParentDeformer slot The relation, which is also the field's identity.
 */
@Composable
internal fun DeformerRelationRow(
	labelRes: StringResource,
	descriptionRes: StringResource,
	context: PropertyContext,
	slot: RelationSlot.ParentDeformer,
) {
	val relationPick = LocalRelationPick.current
	// Asked as the binding lands, so the strip follows the pointer's state then.
	val stripArea = LocalOperationStripArea.current
	val session = context.session
	val admits = context.puppet.relationAdmitter(slot)
	val deformers = context.puppet.deformers.filter { deformer -> admits(SelectionTarget.Deformer(deformer.id)) }
	val boundId = (context.puppet.relationBound(slot) as? SelectionTarget.Deformer)?.id
	PropertyFieldRow(stringResource(labelRes), description = stringResource(descriptionRes)) {
		RelationMenuArea(slot) {
			RelationField(
				selected = deformers.firstOrNull { candidate -> candidate.id == boundId },
				candidates = deformers,
				label = { candidate -> candidate.name.ifBlank { candidate.id.raw } },
				icon = { candidate -> deformerIcon(candidate) },
				onSelect = { candidate -> session?.bindRelation(slot, candidate?.let { deformer -> SelectionTarget.Deformer(deformer.id) }, stripArea()) },
				modifier = Modifier.fillMaxWidth(),
				onPick = session?.let { live -> { relationPick.armFor(slot, live, stripArea) } },
				picking = relationPick.pickingFor(slot),
				clearDescription = stringResource(Res.string.properties_relation_clear),
				pickDescription = stringResource(Res.string.properties_relation_pick),
			)
		}
	}
}

/**
 * Wraps a relation field so a right-click opens its relation menu ([relationFieldMenu]), over its trailing buttons as
 * well as its text, where the field's own text menu shows the same rows above the clipboard ones.
 *
 * @param RelationSlot slot The field's relation.
 * @param Function content The field.
 */
@Composable
private fun RelationMenuArea(slot: RelationSlot, content: @Composable () -> Unit) {
	ContextMenuArea(itemsOnOpen = rememberCommandMenu(relationFieldMenu(slot)), modifier = Modifier.fillMaxWidth(), content = content)
}