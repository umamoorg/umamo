package org.umamo.ui.workspace

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.resources.*

/**
 * Maps a [org.umamo.edit.Change.labelKey] (or null, the seed entry) to its localized label - the
 * history panel's step label and the operation settings strip's title, from ONE mapping so the two
 * can never name the same change differently.  The keys mirror those declared in :edit's Change
 * taxonomy; an unmapped key falls back to a generic "Edit" so a newly added change kind never renders
 * blank.
 *
 * @param String? labelKey The change's stable label key, or null for the initial-open step.
 * @return String The localized step label.
 */
@Composable
internal fun changeLabel(labelKey: String?): String =
	when (labelKey) {
		null -> stringResource(Res.string.history_open)
		"change.selection" -> stringResource(Res.string.history_selection)
		"change.parameter.select" -> stringResource(Res.string.history_parameter_select)
		"change.keyform.insert" -> stringResource(Res.string.history_keyform_insert)
		"change.keyform.move" -> stringResource(Res.string.history_keyform_move)
		"change.keyform.delete" -> stringResource(Res.string.history_keyform_remove)
		"change.mode" -> stringResource(Res.string.history_mode)
		"change.part.visibility" -> stringResource(Res.string.history_part_visibility)
		"change.part.rename" -> stringResource(Res.string.history_part_rename)
		"change.part.selectable" -> stringResource(Res.string.history_part_selectable)
		"change.part.move" -> stringResource(Res.string.history_part_move)
		"change.drawable.visibility" -> stringResource(Res.string.history_drawable_visibility)
		"change.drawable.rename" -> stringResource(Res.string.history_drawable_rename)
		"change.drawable.selectable" -> stringResource(Res.string.history_drawable_selectable)
		"change.drawable.move" -> stringResource(Res.string.history_drawable_move)
		"change.deformer.rename" -> stringResource(Res.string.history_deformer_rename)
		"change.deformer.selectable" -> stringResource(Res.string.history_deformer_selectable)
		"change.deformer.move" -> stringResource(Res.string.history_deformer_move)
		"change.parameter.value" -> stringResource(Res.string.history_parameter_value)
		"change.parameter.range" -> stringResource(Res.string.history_parameter_range)
		"change.parameter.link" -> stringResource(Res.string.history_parameter_link)
		"change.parameter.unlink" -> stringResource(Res.string.history_parameter_unlink)
		"change.parameter.move" -> stringResource(Res.string.history_parameter_move)
		"change.parameter.groupCreate" -> stringResource(Res.string.history_parameter_group_create)
		"change.parameter.groupDelete" -> stringResource(Res.string.history_parameter_group_delete)
		"change.parameter.groupRename" -> stringResource(Res.string.history_parameter_group_rename)
		"change.parameter.create" -> stringResource(Res.string.history_parameter_create)
		"change.parameter.rename" -> stringResource(Res.string.history_parameter_rename)
		"change.parameter.delete" -> stringResource(Res.string.history_parameter_delete)
		"change.mesh.move" -> stringResource(Res.string.history_mesh_move)
		"change.mesh.scale" -> stringResource(Res.string.history_mesh_scale)
		"change.mesh.rotate" -> stringResource(Res.string.history_mesh_rotate)
		"change.mesh.slide" -> stringResource(Res.string.history_mesh_slide)
		"change.uv.move" -> stringResource(Res.string.history_uv_move)
		"change.uv.scale" -> stringResource(Res.string.history_uv_scale)
		"change.uv.rotate" -> stringResource(Res.string.history_uv_rotate)
		"change.uv.mirror" -> stringResource(Res.string.history_uv_mirror)
		"change.mesh.duplicate" -> stringResource(Res.string.history_mesh_duplicate)
		"change.mesh.merge" -> stringResource(Res.string.history_mesh_merge)
		"change.mesh.rip" -> stringResource(Res.string.history_mesh_rip)
		"change.mesh.connect" -> stringResource(Res.string.history_mesh_connect)
		"change.drawable.duplicate" -> stringResource(Res.string.history_drawable_duplicate)
		"change.object.move" -> stringResource(Res.string.history_object_move)
		"change.object.scale" -> stringResource(Res.string.history_object_scale)
		"change.object.rotate" -> stringResource(Res.string.history_object_rotate)
		"change.mesh.select" -> stringResource(Res.string.history_mesh_select)
		"change.keyform.select" -> stringResource(Res.string.history_keyform_select)
		"change.mesh.selectMode" -> stringResource(Res.string.history_mesh_select_mode)
		"change.part.delete" -> stringResource(Res.string.history_part_delete)
		"change.drawable.delete" -> stringResource(Res.string.history_drawable_delete)
		"change.deformer.delete" -> stringResource(Res.string.history_deformer_delete)
		"change.part.sketch" -> stringResource(Res.string.history_part_sketch)
		"change.part.drawOrder" -> stringResource(Res.string.history_part_draw_order)
		"change.part.groupMode" -> stringResource(Res.string.history_part_group_mode)
		"change.part.composite" -> stringResource(Res.string.history_part_composite)
		"change.drawable.blendMode" -> stringResource(Res.string.history_drawable_blend_mode)
		"change.drawable.alphaBlendMode" -> stringResource(Res.string.history_drawable_alpha_blend_mode)
		"change.drawable.culling" -> stringResource(Res.string.history_drawable_culling)
		"change.drawable.invertMask" -> stringResource(Res.string.history_drawable_invert_mask)
		"change.drawable.parentDeformer" -> stringResource(Res.string.history_drawable_parent_deformer)
		"change.drawable.maskedBy" -> stringResource(Res.string.history_drawable_masked_by)
		"change.drawable.multiplyColor" -> stringResource(Res.string.history_drawable_multiply_color)
		"change.drawable.screenColor" -> stringResource(Res.string.history_drawable_screen_color)
		"change.drawable.opacity" -> stringResource(Res.string.history_drawable_opacity)
		"change.drawable.drawOrder" -> stringResource(Res.string.history_drawable_draw_order)
		"change.deformer.opacity" -> stringResource(Res.string.history_deformer_opacity)
		"change.deformer.multiplyColor" -> stringResource(Res.string.history_deformer_multiply_color)
		"change.deformer.screenColor" -> stringResource(Res.string.history_deformer_screen_color)
		"change.deformer.flipX" -> stringResource(Res.string.history_deformer_flip_x)
		"change.deformer.flipY" -> stringResource(Res.string.history_deformer_flip_y)
		"change.deformer.part" -> stringResource(Res.string.history_deformer_part)
		"change.deformer.baseAngle" -> stringResource(Res.string.history_deformer_base_angle)
		"change.deformer.quadTransform" -> stringResource(Res.string.history_deformer_quad_transform)
		"change.document.canvasSize" -> stringResource(Res.string.history_document_canvas_size)
		"change.document.worldOrigin" -> stringResource(Res.string.history_document_world_origin)
		"change.document.runtimeTarget" -> stringResource(Res.string.history_document_runtime_target)
		"change.document.sourceLayerDisplay" -> stringResource(Res.string.history_document_source_layer_display)
		"change.document.atlasPlacement" -> stringResource(Res.string.history_document_atlas_placement)
		"change.document.atlasRepack" -> stringResource(Res.string.history_document_atlas_repack)
		else -> stringResource(Res.string.history_unknown)
	}