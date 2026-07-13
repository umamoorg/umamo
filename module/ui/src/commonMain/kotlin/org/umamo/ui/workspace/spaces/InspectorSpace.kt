package org.umamo.ui.workspace.spaces

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.partByDrawable
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.VerticalScrollbarOverlay
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.model.LocalSelection
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoTypography

/**
 * The inspector space: a read-only property view of the current object-mode selection. With one entity
 * selected it shows that part's, drawable's, or deformer's properties; with several, a count summary
 * featuring the breakdown by kind; with nothing selected (or no model), the empty state. Reads
 * [LocalPuppet] for the entities and [LocalSelection] for what is selected.
 *
 * インスペクター空間。選択中の要素（パーツ／描画オブジェクト／デフォーマ）のプロパティを読み取り専用で表示する。
 *
 * @param Modifier modifier The layout modifier.
 */
@Composable
fun InspectorSpace(modifier: Modifier = Modifier) {
	val puppet = LocalPuppet.current
	val selection = LocalSelection.current?.selection ?: Selection()
	if (puppet == null || selection.isEmpty) {
		PlaceholderSpace(stringResource(Res.string.inspector_nothing_selected), modifier)
		return
	}
	val partsById = remember(puppet) { puppet.parts.associateBy { it.id } }
	val drawablesById = remember(puppet) { puppet.drawables.associateBy { it.id } }
	val deformersById = remember(puppet) { puppet.deformers.associateBy { it.id } }
	// A drawable's owning part is derived from the org tree, the sole source of part membership.
	val drawableOwners = remember(puppet) { puppet.partByDrawable() }
	val scrollState = rememberScrollState()
	Box(modifier = modifier.fillMaxSize()) {
		Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(12.dp)) {
			if (selection.size == 1) {
				when (val target = selection.active ?: selection.targets.first()) {
					is SelectionTarget.Part -> partsById[target.id]?.let { PartProperties(it) }
					is SelectionTarget.Drawable -> drawablesById[target.id]?.let { DrawableProperties(it, drawableOwners[it.id], partsById) }
					is SelectionTarget.Deformer -> deformersById[target.id]?.let { DeformerProperties(it) }
				}
			} else {
				MultiSelectionSummary(selection)
			}
		}
		VerticalScrollbarOverlay(scrollState)
	}
}

/**
 * Renders a selected part's properties.
 *
 * @param Part part The selected part.
 */
@Composable
private fun PartProperties(part: Part) {
	PropertyTitle(stringResource(Res.string.inspector_part))
	PropertyLine(stringResource(Res.string.inspector_name, part.name))
	PropertyLine(stringResource(Res.string.inspector_id, part.id.raw))
	PropertyLine(stringResource(Res.string.inspector_visible, yesNo(part.isVisible)))
	PropertyLine(stringResource(Res.string.inspector_sketch, yesNo(part.isSketch)))
	PropertyLine(stringResource(Res.string.inspector_child_count, part.children.size))
}

/**
 * Renders a selected drawable's properties.
 *
 * @param Drawable drawable The selected drawable.
 * @param PartId? ownerPartId The owning part (from the org tree), or null at the root level.
 * @param Map<PartId, Part> partsById The model's parts, for resolving the owning part's name.
 */
@Composable
private fun DrawableProperties(drawable: Drawable, ownerPartId: PartId?, partsById: Map<PartId, Part>) {
	PropertyTitle(stringResource(Res.string.inspector_drawable))
	PropertyLine(stringResource(Res.string.inspector_id, drawable.id.raw))
	val partLabel = ownerPartId?.let { partsById[it]?.name ?: it.raw } ?: stringResource(Res.string.inspector_none)
	PropertyLine(stringResource(Res.string.inspector_part_ref, partLabel))
	val deformerLabel = drawable.parentDeformerId?.raw ?: stringResource(Res.string.inspector_none)
	PropertyLine(stringResource(Res.string.inspector_parent_deformer, deformerLabel))
	PropertyLine(stringResource(Res.string.inspector_blend_mode, drawable.blendMode.name))
	PropertyLine(stringResource(Res.string.inspector_visible, yesNo(drawable.isVisible)))
	PropertyLine(stringResource(Res.string.inspector_mask_count, drawable.maskedBy.size))
	PropertyLine(stringResource(Res.string.inspector_invert_mask, yesNo(drawable.invertMask)))
	val mesh = drawable.mesh
	if (mesh != null) {
		PropertyLine(stringResource(Res.string.inspector_mesh, mesh.vertexCount, mesh.triangleCount))
	} else {
		PropertyLine(stringResource(Res.string.inspector_no_mesh))
	}
}

/**
 * Renders a selected deformer's properties, branching over the warp / rotation kinds.
 *
 * @param Deformer deformer The selected deformer.
 */
@Composable
private fun DeformerProperties(deformer: Deformer) {
	PropertyTitle(stringResource(Res.string.inspector_deformer))
	PropertyLine(stringResource(Res.string.inspector_id, deformer.id.raw))
	val kindLabel =
		when (deformer) {
			is Deformer.Warp -> stringResource(Res.string.inspector_deformer_warp)
			is Deformer.Rotation -> stringResource(Res.string.inspector_deformer_rotation)
		}
	PropertyLine(stringResource(Res.string.inspector_deformer_kind, kindLabel))
	val partLabel = deformer.partId?.raw ?: stringResource(Res.string.inspector_none)
	PropertyLine(stringResource(Res.string.inspector_part_ref, partLabel))
	val parentLabel = deformer.parent?.raw ?: stringResource(Res.string.inspector_none)
	PropertyLine(stringResource(Res.string.inspector_parent_deformer, parentLabel))
	when (deformer) {
		is Deformer.Warp -> {
			PropertyLine(stringResource(Res.string.inspector_warp_grid, deformer.rows, deformer.columns))
			PropertyLine(stringResource(Res.string.inspector_quad_transform, yesNo(deformer.isQuadTransform)))
		}

		is Deformer.Rotation -> {
			PropertyLine(stringResource(Res.string.inspector_base_angle, deformer.baseAngle.toString()))
		}
	}
}

/**
 * Renders a summary for a multi-entity selection: the total count and a breakdown by kind.
 *
 * @param Selection selection The current (multi-entity) selection.
 */
@Composable
private fun MultiSelectionSummary(selection: Selection) {
	val parts = selection.targets.count { it is SelectionTarget.Part }
	val drawables = selection.targets.count { it is SelectionTarget.Drawable }
	val deformers = selection.targets.count { it is SelectionTarget.Deformer }
	PropertyTitle(stringResource(Res.string.inspector_multi_selected, selection.size))
	PropertyLine(stringResource(Res.string.inspector_multi_breakdown, parts, drawables, deformers))
}

/**
 * A section heading inside the inspector.
 *
 * @param String text The heading text.
 */
@Composable
private fun PropertyTitle(text: String) {
	Text(text = text, style = LocalUmamoTypography.current.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
}

/**
 * One read-only property line inside the inspector.
 *
 * @param String text The "label: value" line.
 */
@Composable
private fun PropertyLine(text: String) {
	Text(text = text, style = LocalUmamoTypography.current.bodySmall, modifier = Modifier.padding(top = 1.dp, bottom = 1.dp))
}

/**
 * Resolves a boolean to a localized Yes / No string.
 *
 * @param Boolean value The flag.
 * @return String The localized "Yes" or "No".
 */
@Composable
private fun yesNo(value: Boolean): String = stringResource(if (value) Res.string.inspector_yes else Res.string.inspector_no)
