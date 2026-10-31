package org.umamo.ui.workspace.operationstrip

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.ParameterUnit
import org.umamo.edit.ProportionalFalloff
import org.umamo.edit.mesh.LatticeResizeParameterKeys
import org.umamo.edit.mesh.MergeParameterKeys
import org.umamo.edit.mesh.MergeTarget
import org.umamo.edit.mesh.parameterKey
import org.umamo.edit.transform.TransformParameterKeys
import org.umamo.edit.transform.choiceKey
import org.umamo.interop.art.ArtworkAnchor
import org.umamo.interop.art.mesh.ArtMeshPreset
import org.umamo.ui.model.artwork.ImportParameterKeys
import org.umamo.ui.model.artwork.MatchParameterKeys
import org.umamo.ui.model.mesh.MeshGenerationParameterKeys
import org.umamo.ui.model.mesh.QuadMeshParameterKeys
import org.umamo.ui.model.repack.RepackParameterKeys
import org.umamo.ui.resources.*
import org.umamo.ui.viewport.falloffLabel
import org.umamo.ui.viewport.uv.PlacementParameterKeys

/**
 * Maps an [org.umamo.edit.OperatorParameter.labelKey] to its localized row label.  The keys are the
 * ones each adjustable operation's parameter list declares (a choice's entries carry prefixed keys,
 * resolved by [choiceLabel]); an unmapped key renders verbatim so a newly added parameter never
 * renders blank.
 *
 * @param String labelKey The parameter's stable label key.
 * @return String The localized label.
 */
@Composable
internal fun operatorParameterLabel(labelKey: String): String =
	when (labelKey) {
		RepackParameterKeys.PAGE_SIZE -> stringResource(Res.string.repack_options_page_size)

		RepackParameterKeys.GUTTER -> stringResource(Res.string.repack_options_gutter)

		RepackParameterKeys.EXTRUDE -> stringResource(Res.string.repack_options_extrude)

		RepackParameterKeys.ALLOW_ROTATION -> stringResource(Res.string.repack_options_allow_rotation)

		RepackParameterKeys.KEEP_PINNED -> stringResource(Res.string.repack_options_keep_pinned)

		RepackParameterKeys.POWER_OF_TWO -> stringResource(Res.string.repack_options_power_of_two)

		RepackParameterKeys.SQUARE_PAGES -> stringResource(Res.string.repack_options_square_pages)

		RepackParameterKeys.SHRINK_PAGES -> stringResource(Res.string.repack_options_shrink_pages)

		RepackParameterKeys.ALPHA_THRESHOLD -> stringResource(Res.string.repack_options_alpha_threshold)

		PlacementParameterKeys.DELTA_X -> stringResource(Res.string.placement_options_move_x)

		PlacementParameterKeys.DELTA_Y -> stringResource(Res.string.placement_options_move_y)

		PlacementParameterKeys.ANGLE -> stringResource(Res.string.placement_options_angle)

		PlacementParameterKeys.SCALE_X -> stringResource(Res.string.placement_options_scale_x)

		PlacementParameterKeys.SCALE_Y -> stringResource(Res.string.placement_options_scale_y)

		ImportParameterKeys.ALIGN -> stringResource(Res.string.import_options_align)

		ImportParameterKeys.OFFSET_X -> stringResource(Res.string.import_options_offset_x)

		ImportParameterKeys.OFFSET_Z -> stringResource(Res.string.import_options_offset_z)

		ImportParameterKeys.ALPHA_THRESHOLD -> stringResource(Res.string.import_options_alpha_threshold)

		ImportParameterKeys.MARGIN -> stringResource(Res.string.import_options_margin)

		MatchParameterKeys.THRESHOLD -> stringResource(Res.string.match_options_threshold)

		// The transform rows share the placement rows' Move / Angle / Scale labels where the text is the
		// same; the viewport's vertical axis is Z (Y+ forward, Z+ up), so it has labels of its own.
		TransformParameterKeys.MOVE_X -> stringResource(Res.string.placement_options_move_x)

		TransformParameterKeys.MOVE_Y -> stringResource(Res.string.placement_options_move_y)

		TransformParameterKeys.MOVE_Z -> stringResource(Res.string.transform_options_move_z)

		TransformParameterKeys.ANGLE -> stringResource(Res.string.placement_options_angle)

		TransformParameterKeys.SCALE_X -> stringResource(Res.string.placement_options_scale_x)

		TransformParameterKeys.SCALE_Y -> stringResource(Res.string.placement_options_scale_y)

		TransformParameterKeys.SCALE_Z -> stringResource(Res.string.transform_options_scale_z)

		TransformParameterKeys.PROPORTIONAL -> stringResource(Res.string.transform_options_proportional)

		TransformParameterKeys.FALLOFF -> stringResource(Res.string.transform_options_falloff)

		TransformParameterKeys.PROPORTIONAL_SIZE -> stringResource(Res.string.transform_options_proportional_size)

		TransformParameterKeys.CONNECTED_ONLY -> stringResource(Res.string.transform_options_connected)

		TransformParameterKeys.SLIDE_FACTOR -> stringResource(Res.string.transform_options_slide_factor)

		MergeParameterKeys.TARGET -> stringResource(Res.string.merge_options_target)

		LatticeResizeParameterKeys.ROWS -> stringResource(Res.string.lattice_options_rows)

		LatticeResizeParameterKeys.COLUMNS -> stringResource(Res.string.lattice_options_columns)

		LatticeResizeParameterKeys.ROW_SPLITS -> stringResource(Res.string.lattice_options_row_splits)

		LatticeResizeParameterKeys.COLUMN_SPLITS -> stringResource(Res.string.lattice_options_column_splits)

		MeshGenerationParameterKeys.PRESET -> stringResource(Res.string.mesh_options_preset)

		MeshGenerationParameterKeys.ALPHA_THRESHOLD -> stringResource(Res.string.mesh_options_alpha_threshold)

		MeshGenerationParameterKeys.OUTLINE_SPACING -> stringResource(Res.string.mesh_options_outline_spacing)

		MeshGenerationParameterKeys.INTERIOR_SPACING -> stringResource(Res.string.mesh_options_interior_spacing)

		MeshGenerationParameterKeys.OUTER_MARGIN -> stringResource(Res.string.mesh_options_outer_margin)

		MeshGenerationParameterKeys.INNER_MARGIN -> stringResource(Res.string.mesh_options_inner_margin)

		MeshGenerationParameterKeys.MINIMUM_MARGIN -> stringResource(Res.string.mesh_options_minimum_margin)

		MeshGenerationParameterKeys.MINIMUM_OUTLINE_POINTS -> stringResource(Res.string.mesh_options_minimum_outline_points)

		MeshGenerationParameterKeys.MINIMUM_ANGLE -> stringResource(Res.string.mesh_options_minimum_angle)

		MeshGenerationParameterKeys.VERTEX_BUDGET -> stringResource(Res.string.mesh_options_vertex_budget)

		MeshGenerationParameterKeys.FILL_HOLES -> stringResource(Res.string.mesh_options_fill_holes)

		MeshGenerationParameterKeys.STRAND_RUNGS -> stringResource(Res.string.mesh_options_strand_rungs)

		MeshGenerationParameterKeys.STRAND_WIDTH -> stringResource(Res.string.mesh_options_strand_width)

		MeshGenerationParameterKeys.DEFORMATION_TOLERANCE -> stringResource(Res.string.mesh_options_deformation_tolerance)

		MeshGenerationParameterKeys.INTERIOR_THINNING_LIMIT -> stringResource(Res.string.mesh_options_interior_thinning_limit)

		QuadMeshParameterKeys.MARGIN -> stringResource(Res.string.mesh_options_quad_margin)

		else -> choiceLabel(labelKey) ?: labelKey
	}

/**
 * Maps an [org.umamo.edit.OperatorParameter.labelKey] to the description its row shows as a tooltip over
 * the label: what the parameter did to the operation it adjusts.
 *
 * Keyed by the parameter rather than by its label, because two operations may share a label and still mean
 * different things by it: a placement's Move Y is page pixels running down the page, while a viewport
 * transform's vertical move is Move Z running up.  Not composable, so a test can pin that every key the
 * strip can show has one.
 *
 * @param String labelKey The parameter's stable label key.
 * @return StringResource? The description, or null for a choice entry's key or a key with none.
 */
internal fun operatorParameterDescriptionRes(labelKey: String): StringResource? =
	when (labelKey) {
		RepackParameterKeys.PAGE_SIZE -> Res.string.repack_options_page_size_description
		RepackParameterKeys.GUTTER -> Res.string.repack_options_gutter_description
		RepackParameterKeys.EXTRUDE -> Res.string.repack_options_extrude_description
		RepackParameterKeys.ALLOW_ROTATION -> Res.string.repack_options_allow_rotation_description
		RepackParameterKeys.KEEP_PINNED -> Res.string.repack_options_keep_pinned_description
		RepackParameterKeys.POWER_OF_TWO -> Res.string.repack_options_power_of_two_description
		RepackParameterKeys.SQUARE_PAGES -> Res.string.repack_options_square_pages_description
		RepackParameterKeys.SHRINK_PAGES -> Res.string.repack_options_shrink_pages_description
		RepackParameterKeys.ALPHA_THRESHOLD -> Res.string.repack_options_alpha_threshold_description
		PlacementParameterKeys.DELTA_X -> Res.string.placement_options_move_x_description
		PlacementParameterKeys.DELTA_Y -> Res.string.placement_options_move_y_description
		PlacementParameterKeys.ANGLE -> Res.string.placement_options_angle_description
		PlacementParameterKeys.SCALE_X -> Res.string.placement_options_scale_x_description
		PlacementParameterKeys.SCALE_Y -> Res.string.placement_options_scale_y_description
		ImportParameterKeys.ALIGN -> Res.string.import_options_align_description
		ImportParameterKeys.OFFSET_X -> Res.string.import_options_offset_x_description
		ImportParameterKeys.OFFSET_Z -> Res.string.import_options_offset_z_description
		ImportParameterKeys.ALPHA_THRESHOLD -> Res.string.import_options_alpha_threshold_description
		ImportParameterKeys.MARGIN -> Res.string.import_options_margin_description
		MatchParameterKeys.THRESHOLD -> Res.string.match_options_threshold_description
		TransformParameterKeys.MOVE_X -> Res.string.transform_options_move_x_description
		TransformParameterKeys.MOVE_Y -> Res.string.transform_options_move_y_description
		TransformParameterKeys.MOVE_Z -> Res.string.transform_options_move_z_description
		TransformParameterKeys.ANGLE -> Res.string.transform_options_angle_description
		TransformParameterKeys.SCALE_X -> Res.string.transform_options_scale_x_description
		TransformParameterKeys.SCALE_Y -> Res.string.transform_options_scale_y_description
		TransformParameterKeys.SCALE_Z -> Res.string.transform_options_scale_z_description
		TransformParameterKeys.PROPORTIONAL -> Res.string.transform_options_proportional_description
		TransformParameterKeys.FALLOFF -> Res.string.transform_options_falloff_description
		TransformParameterKeys.PROPORTIONAL_SIZE -> Res.string.transform_options_proportional_size_description
		TransformParameterKeys.CONNECTED_ONLY -> Res.string.transform_options_connected_description
		TransformParameterKeys.SLIDE_FACTOR -> Res.string.transform_options_slide_factor_description
		MergeParameterKeys.TARGET -> Res.string.merge_options_target_description
		LatticeResizeParameterKeys.ROWS -> Res.string.lattice_options_rows_description
		LatticeResizeParameterKeys.COLUMNS -> Res.string.lattice_options_columns_description
		LatticeResizeParameterKeys.ROW_SPLITS -> Res.string.lattice_options_row_splits_description
		LatticeResizeParameterKeys.COLUMN_SPLITS -> Res.string.lattice_options_column_splits_description
		MeshGenerationParameterKeys.PRESET -> Res.string.mesh_options_preset_description
		MeshGenerationParameterKeys.ALPHA_THRESHOLD -> Res.string.mesh_options_alpha_threshold_description
		MeshGenerationParameterKeys.OUTLINE_SPACING -> Res.string.mesh_options_outline_spacing_description
		MeshGenerationParameterKeys.INTERIOR_SPACING -> Res.string.mesh_options_interior_spacing_description
		MeshGenerationParameterKeys.OUTER_MARGIN -> Res.string.mesh_options_outer_margin_description
		MeshGenerationParameterKeys.INNER_MARGIN -> Res.string.mesh_options_inner_margin_description
		MeshGenerationParameterKeys.MINIMUM_MARGIN -> Res.string.mesh_options_minimum_margin_description
		MeshGenerationParameterKeys.MINIMUM_OUTLINE_POINTS -> Res.string.mesh_options_minimum_outline_points_description
		MeshGenerationParameterKeys.MINIMUM_ANGLE -> Res.string.mesh_options_minimum_angle_description
		MeshGenerationParameterKeys.VERTEX_BUDGET -> Res.string.mesh_options_vertex_budget_description
		MeshGenerationParameterKeys.FILL_HOLES -> Res.string.mesh_options_fill_holes_description
		MeshGenerationParameterKeys.STRAND_RUNGS -> Res.string.mesh_options_strand_rungs_description
		MeshGenerationParameterKeys.STRAND_WIDTH -> Res.string.mesh_options_strand_width_description
		MeshGenerationParameterKeys.DEFORMATION_TOLERANCE -> Res.string.mesh_options_deformation_tolerance_description
		MeshGenerationParameterKeys.INTERIOR_THINNING_LIMIT -> Res.string.mesh_options_interior_thinning_limit_description
		QuadMeshParameterKeys.MARGIN -> Res.string.mesh_options_quad_margin_description
		else -> null
	}

/**
 * The localized description of a parameter's row, or blank when it has none (which attaches no tooltip).
 *
 * @param String labelKey The parameter's stable label key.
 * @return String The description, or an empty string.
 */
@Composable
internal fun operatorParameterDescription(labelKey: String): String = operatorParameterDescriptionRes(labelKey)?.let { resource -> stringResource(resource) } ?: ""

/**
 * The localized label of a choice entry's prefixed key: a falloff curve's, a merge target's, an
 * import anchor's, or a mesh preset's.
 *
 * @param String labelKey The entry's label key.
 * @return String? The label, or null when the key carries none of the prefixes.
 */
@Composable
private fun choiceLabel(labelKey: String): String? {
	if (labelKey.startsWith(TransformParameterKeys.FALLOFF_CHOICE_PREFIX)) {
		val key = labelKey.removePrefix(TransformParameterKeys.FALLOFF_CHOICE_PREFIX)

		return ProportionalFalloff.entries.firstOrNull { falloff -> falloff.choiceKey == key }?.let { falloff -> falloffLabel(falloff) }
	}

	if (labelKey.startsWith(ImportParameterKeys.ANCHOR_CHOICE_PREFIX)) {
		val key = labelKey.removePrefix(ImportParameterKeys.ANCHOR_CHOICE_PREFIX)

		return ArtworkAnchor.entries.firstOrNull { anchor -> anchor.key == key }?.let { anchor -> artworkAnchorLabel(anchor) }
	}

	if (labelKey.startsWith(MergeParameterKeys.TARGET_CHOICE_PREFIX)) {
		val key = labelKey.removePrefix(MergeParameterKeys.TARGET_CHOICE_PREFIX)

		return when (MergeTarget.entries.firstOrNull { target -> target.parameterKey == key }) {
			MergeTarget.AtCenter -> stringResource(Res.string.merge_target_center)
			MergeTarget.AtFirst -> stringResource(Res.string.merge_target_first)
			MergeTarget.AtLast -> stringResource(Res.string.merge_target_last)
			null -> null
		}
	}

	if (labelKey.startsWith(MeshGenerationParameterKeys.PRESET_CHOICE_PREFIX)) {
		val key = labelKey.removePrefix(MeshGenerationParameterKeys.PRESET_CHOICE_PREFIX)

		return when (ArtMeshPreset.entries.firstOrNull { preset -> preset.key == key }) {
			ArtMeshPreset.Standard -> stringResource(Res.string.mesh_preset_standard)
			ArtMeshPreset.Fine -> stringResource(Res.string.mesh_preset_fine)
			ArtMeshPreset.Coarse -> stringResource(Res.string.mesh_preset_coarse)
			null -> null
		}
	}

	return null
}

/**
 * The localized name of an import anchor, shared by the strip's Align row and the Import settings
 * row that seeds it.
 *
 * @param ArtworkAnchor anchor The anchor.
 * @return String The label.
 */
@Composable
internal fun artworkAnchorLabel(anchor: ArtworkAnchor): String =
	when (anchor) {
		ArtworkAnchor.TopLeft -> stringResource(Res.string.import_anchor_top_left)
		ArtworkAnchor.Top -> stringResource(Res.string.import_anchor_top)
		ArtworkAnchor.TopRight -> stringResource(Res.string.import_anchor_top_right)
		ArtworkAnchor.Left -> stringResource(Res.string.import_anchor_left)
		ArtworkAnchor.Center -> stringResource(Res.string.import_anchor_center)
		ArtworkAnchor.Right -> stringResource(Res.string.import_anchor_right)
		ArtworkAnchor.BottomLeft -> stringResource(Res.string.import_anchor_bottom_left)
		ArtworkAnchor.Bottom -> stringResource(Res.string.import_anchor_bottom)
		ArtworkAnchor.BottomRight -> stringResource(Res.string.import_anchor_bottom_right)
	}

/**
 * The suffix a numeric row shows after its value for [unit], or null for a unitless one.
 *
 * @param ParameterUnit unit The parameter's display unit.
 * @return String? The localized unit suffix.
 */
@Composable
internal fun parameterUnitSuffix(unit: ParameterUnit): String? =
	when (unit) {
		ParameterUnit.None -> null
		ParameterUnit.Pixels -> stringResource(Res.string.unit_pixels)
		ParameterUnit.Degrees -> stringResource(Res.string.unit_degrees)
		ParameterUnit.Percent -> stringResource(Res.string.unit_percent)
	}