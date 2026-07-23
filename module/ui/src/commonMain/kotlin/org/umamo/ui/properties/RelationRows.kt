package org.umamo.ui.properties

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.ui.kit.RelationField
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.UmamoIcon
import org.umamo.ui.workspace.LocalRelationPick
import org.umamo.ui.workspace.PickKind
import org.umamo.ui.workspace.pickingFor

/*
 * The single-value relation rows - bind the active item to a part or to a deformer - plus the deformer type
 * glyph they and the Data tab share.  The multi-value lists live in MaskEditors.kt.
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
 * A labelled row binding the active item to an organisational part, through the shared relation picker:
 * the field lists every part, and its eyedropper takes a part from a viewport click (resolved through the
 * clicked drawable's owner) or an outliner row.
 *
 * @param StringResource labelRes The row's localized label.
 * @param PropertyContext context The context supplying the model.
 * @param PartId? selectedPartId The currently bound part, or null when unbound.
 * @param Function excluding Drops ineligible candidates (a part may not be nested inside itself).
 * @param Any owner This field's identity, so only it lights its eyedropper.
 * @param Function onSelect Applies the new binding (null clears it).
 */
@Composable
internal fun PartRelationRow(
	labelRes: StringResource,
	context: PropertyContext,
	selectedPartId: PartId?,
	excluding: (Part) -> Boolean = { false },
	owner: Any,
	onSelect: (PartId?) -> Unit,
) {
	val relationPick = LocalRelationPick.current
	val parts = context.puppet.parts.filterNot(excluding)
	PropertyFieldRow(stringResource(labelRes)) {
		RelationField(
			selected = parts.firstOrNull { candidate -> candidate.id == selectedPartId },
			candidates = parts,
			label = { candidate -> candidate.name.ifBlank { candidate.id.raw } },
			icon = { LocalUmamoIcons.part },
			onSelect = { candidate -> onSelect(candidate?.id) },
			modifier = Modifier.fillMaxWidth(),
			onPick = {
				relationPick.arm(setOf(PickKind.Part), owner) { target ->
					(target as? SelectionTarget.Part)?.let { picked -> onSelect(picked.id) }
				}
			},
			picking = relationPick.pickingFor(owner),
			clearDescription = stringResource(Res.string.properties_relation_clear),
			pickDescription = stringResource(Res.string.properties_relation_pick),
		)
	}
}

/**
 * A labelled row binding the active item to a deformer, through the shared relation picker.  A viewport
 * click cannot hit a deformer (the hit test is drawable-only), so this field's eyedropper resolves from
 * the outliner.
 *
 * @param StringResource labelRes The row's localized label.
 * @param PropertyContext context The context supplying the model.
 * @param DeformerId? selectedDeformerId The currently bound deformer, or null when unbound.
 * @param Function excluding Drops ineligible candidates (a deformer may not parent itself).
 * @param Any owner This field's identity, so only it lights its eyedropper.
 * @param Function onSelect Applies the new binding (null clears it).
 */
@Composable
internal fun DeformerRelationRow(
	labelRes: StringResource,
	context: PropertyContext,
	selectedDeformerId: DeformerId?,
	excluding: (Deformer) -> Boolean = { false },
	owner: Any,
	onSelect: (DeformerId?) -> Unit,
) {
	val relationPick = LocalRelationPick.current
	val deformers = context.puppet.deformers.filterNot(excluding)
	PropertyFieldRow(stringResource(labelRes)) {
		RelationField(
			selected = deformers.firstOrNull { candidate -> candidate.id == selectedDeformerId },
			candidates = deformers,
			label = { candidate -> candidate.name.ifBlank { candidate.id.raw } },
			icon = { candidate -> deformerIcon(candidate) },
			onSelect = { candidate -> onSelect(candidate?.id) },
			modifier = Modifier.fillMaxWidth(),
			onPick = {
				relationPick.arm(setOf(PickKind.Deformer), owner) { target ->
					(target as? SelectionTarget.Deformer)?.let { picked -> onSelect(picked.id) }
				}
			},
			picking = relationPick.pickingFor(owner),
			clearDescription = stringResource(Res.string.properties_relation_clear),
			pickDescription = stringResource(Res.string.properties_relation_pick),
		)
	}
}
