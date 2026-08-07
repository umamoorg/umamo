package org.umamo.ui.workspace

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.umamo.interop.ExportEntityCategory
import org.umamo.interop.ExportNoticeReason
import org.umamo.interop.KeyformBundleRejection
import org.umamo.runtime.model.FormChannel
import org.umamo.ui.properties.formChannelLabelRes
import org.umamo.ui.resources.*

/*
 * Localized text for the export report's findings.  :interop carries only the structured facts (see
 * ExportNoticeReason); the sentences a rigger reads are assembled here, so the exporters stay
 * presentation-free and every locale gets the same coverage.
 *
 * Each mapping is an exhaustive `when` with no `else`, so a new reason is a compile error here until
 * it gets a sentence - the same compile-time completeness EnumLabels.kt relies on.
 */

/**
 * One localizable sentence: the resource to format, plus the arguments to format it with.
 *
 * A carrier rather than a bare [StringResource] because a third of the reasons take format arguments,
 * and two of those arguments are themselves localized.  Binding the arguments in the SAME `when` arm
 * that picks the resource is what makes a mismatch unrepresentable: there is no second, `else`-bearing
 * pass where a new parameterized reason could slip through and render a literal `%1$d`.
 *
 * @property StringResource resource  The sentence template.
 * @property List           arguments Its format arguments, in placeholder order.
 */
class ExportNoticePhrase(
	val resource: StringResource,
	val arguments: List<ExportNoticeArgument> = emptyList(),
)

/** One format argument of an [ExportNoticePhrase]. */
sealed interface ExportNoticeArgument {
	/**
	 * A value substituted as-is: a count, or document data (an id) that must never be translated.
	 *
	 * @property Any value The value to format.
	 */
	data class Literal(val value: Any) : ExportNoticeArgument

	/**
	 * Another phrase, resolved and substituted as text.
	 *
	 * @property ExportNoticePhrase phrase The nested sentence.
	 */
	data class Nested(val phrase: ExportNoticePhrase) : ExportNoticeArgument
}

/**
 * The [StringResource] naming an entity category, the bracketed prefix on a notice line.
 *
 * @param ExportEntityCategory category The category to label.
 * @return StringResource The localized label resource.
 */
fun exportEntityCategoryLabelRes(category: ExportEntityCategory): StringResource =
	when (category) {
		ExportEntityCategory.Parameter -> Res.string.export_category_parameter
		ExportEntityCategory.ParameterGroup -> Res.string.export_category_parameter_group
		ExportEntityCategory.Part -> Res.string.export_category_part
		ExportEntityCategory.Deformer -> Res.string.export_category_deformer
		ExportEntityCategory.Drawable -> Res.string.export_category_drawable
		ExportEntityCategory.Glue -> Res.string.export_category_glue
		ExportEntityCategory.Document -> Res.string.export_category_document
		ExportEntityCategory.Keyform -> Res.string.export_category_keyform
	}

/**
 * The sentence explaining why one export notice fired.
 *
 * @param ExportNoticeReason reason The reason to phrase.
 * @return ExportNoticePhrase The sentence and its arguments.
 */
fun exportNoticeReasonPhrase(reason: ExportNoticeReason): ExportNoticePhrase =
	when (reason) {
		// Shared by both export paths.
		ExportNoticeReason.DrawableHasNoMesh ->
			ExportNoticePhrase(Res.string.export_reason_drawable_has_no_mesh)

		// MOC3 lowering.
		is ExportNoticeReason.ChannelDemotedToStatic ->
			ExportNoticePhrase(
				Res.string.export_reason_channel_demoted_to_static,
				listOf(ExportNoticeArgument.Nested(channelPhrase(reason.channel))),
			)
		ExportNoticeReason.SketchPartIsNotRuntimeContent ->
			ExportNoticePhrase(Res.string.export_reason_sketch_part_not_runtime_content)
		ExportNoticeReason.HiddenPartOmittedByExportOption ->
			ExportNoticePhrase(Res.string.export_reason_hidden_part_omitted_by_option)
		ExportNoticeReason.HiddenDrawableOmittedByExportOption ->
			ExportNoticePhrase(Res.string.export_reason_hidden_drawable_omitted_by_option)
		ExportNoticeReason.UnkeyedDrawableUnderDeformerHasNoParentGeometry ->
			ExportNoticePhrase(Res.string.export_reason_unkeyed_drawable_under_deformer)
		is ExportNoticeReason.RestMeshConversionSizeMismatch ->
			ExportNoticePhrase(
				Res.string.export_reason_rest_mesh_conversion_size_mismatch,
				listOf(
					ExportNoticeArgument.Literal(reason.convertedCoordinateCount),
					ExportNoticeArgument.Literal(reason.expectedCoordinateCount),
				),
			)
		ExportNoticeReason.NoAtlasPageBound ->
			ExportNoticePhrase(Res.string.export_reason_no_atlas_page_bound)
		is ExportNoticeReason.ClippingMaskNotInExport ->
			ExportNoticePhrase(
				Res.string.export_reason_clipping_mask_not_in_export,
				listOf(ExportNoticeArgument.Literal(reason.maskDrawableIds.joinToString())),
			)
		ExportNoticeReason.OffscreenMaskNotInExport ->
			ExportNoticePhrase(Res.string.export_reason_offscreen_mask_not_in_export)
		ExportNoticeReason.WarpDeformerHasNoLattice ->
			ExportNoticePhrase(Res.string.export_reason_warp_has_no_lattice)
		ExportNoticeReason.RotationDeformerHasNoPivot ->
			ExportNoticePhrase(Res.string.export_reason_rotation_has_no_pivot)
		ExportNoticeReason.GlueNamesAnUnknownDrawable ->
			ExportNoticePhrase(Res.string.export_reason_glue_names_unknown_drawable)
		is ExportNoticeReason.IdTruncated ->
			ExportNoticePhrase(
				Res.string.export_reason_id_truncated,
				listOf(
					ExportNoticeArgument.Literal(reason.recordByteWidth),
					ExportNoticeArgument.Literal(reason.writtenId),
				),
			)
		is ExportNoticeReason.IdTruncatedAndDisambiguated ->
			ExportNoticePhrase(
				Res.string.export_reason_id_truncated_disambiguated,
				listOf(
					ExportNoticeArgument.Literal(reason.recordByteWidth),
					ExportNoticeArgument.Literal(reason.writtenId),
				),
			)

		// CMO3 reconcile.
		ExportNoticeReason.NoMatchingSourceToReconcile ->
			ExportNoticePhrase(Res.string.export_reason_no_matching_source)
		ExportNoticeReason.ParameterKindChangeNotLowered ->
			ExportNoticePhrase(Res.string.export_reason_parameter_kind_change)
		ExportNoticeReason.DeformerKindChangeNotLowered ->
			ExportNoticePhrase(Res.string.export_reason_deformer_kind_change)
		ExportNoticeReason.DeformerEditNeedsWarpSource ->
			ExportNoticePhrase(Res.string.export_reason_deformer_needs_warp_source)
		ExportNoticeReason.DeformerEditNeedsRotationSource ->
			ExportNoticePhrase(Res.string.export_reason_deformer_needs_rotation_source)
		ExportNoticeReason.TextureSourceRebindingIsEditorOnly ->
			ExportNoticePhrase(Res.string.export_reason_texture_rebinding_editor_only)
		ExportNoticeReason.BaseGeometryVertexCountMismatch ->
			ExportNoticePhrase(Res.string.export_reason_base_geometry_vertex_mismatch)
		ExportNoticeReason.NoUvsToReconcile ->
			ExportNoticePhrase(Res.string.export_reason_no_uvs_to_reconcile)
		ExportNoticeReason.DeformerHasNoPartToMoveTo ->
			ExportNoticePhrase(Res.string.export_reason_deformer_no_part_to_move_to)
		ExportNoticeReason.StaticDrawOrderShadowedByKeyforms ->
			ExportNoticePhrase(Res.string.export_reason_static_draw_order_shadowed)
		ExportNoticeReason.CompositeStaticsShadowedByKeyforms ->
			ExportNoticePhrase(Res.string.export_reason_composite_statics_shadowed)
		ExportNoticeReason.PartMasksFlattenToDrawables ->
			ExportNoticePhrase(Res.string.export_reason_part_masks_flatten)

		// CMO3 structural creation.
		ExportNoticeReason.NoSourceSetToCreateInto ->
			ExportNoticePhrase(Res.string.export_reason_no_source_set_to_create_into)
		ExportNoticeReason.CreatedEntityHasNoSourceYet ->
			ExportNoticePhrase(Res.string.export_reason_created_entity_has_no_source)
		ExportNoticeReason.CreatedGroupIsNotInTheEditedTree ->
			ExportNoticePhrase(Res.string.export_reason_created_group_not_in_tree)
		ExportNoticeReason.CreatedDrawableHasNoTextureSource ->
			ExportNoticePhrase(Res.string.export_reason_created_drawable_no_texture_source)
		ExportNoticeReason.GluedDrawableHasNoSource ->
			ExportNoticePhrase(Res.string.export_reason_glued_drawable_has_no_source)

		// CMO3 mesh topology and glue re-binding.
		is ExportNoticeReason.VertexCountExceedsEdgeTable ->
			ExportNoticePhrase(
				Res.string.export_reason_vertex_count_exceeds_edge_table,
				listOf(
					ExportNoticeArgument.Literal(reason.vertexCount),
					ExportNoticeArgument.Literal(reason.maximumVertexCount),
				),
			)
		ExportNoticeReason.GluedMeshHasNoUidTable ->
			ExportNoticePhrase(Res.string.export_reason_glued_mesh_no_uid_table)
		ExportNoticeReason.GluePairIndexesPastUidTable ->
			ExportNoticePhrase(Res.string.export_reason_glue_pair_past_uid_table)

		// CMO3 keyforms.
		is ExportNoticeReason.KeyformCannotBundle ->
			ExportNoticePhrase(
				Res.string.export_reason_keyform_cannot_bundle,
				listOf(ExportNoticeArgument.Nested(keyformBundleRejectionPhrase(reason.rejection))),
			)
		is ExportNoticeReason.AxisParameterHasNoSource ->
			ExportNoticePhrase(
				Res.string.export_reason_axis_parameter_no_source,
				listOf(ExportNoticeArgument.Literal(reason.parameterId)),
			)
		is ExportNoticeReason.BlendShapeParameterHasNoSource ->
			ExportNoticePhrase(
				Res.string.export_reason_blend_shape_parameter_no_source,
				listOf(ExportNoticeArgument.Literal(reason.parameterId)),
			)
		ExportNoticeReason.KeyformsWithoutBaseMesh ->
			ExportNoticePhrase(Res.string.export_reason_keyforms_without_base_mesh)
		ExportNoticeReason.FractionalDrawOrderNotStorable ->
			ExportNoticePhrase(Res.string.export_reason_fractional_draw_order)
		ExportNoticeReason.StaticGlueIntensityWithoutKeyforms ->
			ExportNoticePhrase(Res.string.export_reason_static_glue_intensity_without_keyforms)

		// CMO3 document fields.
		ExportNoticeReason.NoAuthoredWorldOrigin ->
			ExportNoticePhrase(Res.string.export_reason_no_authored_world_origin)
		ExportNoticeReason.NoRootParameterGroup ->
			ExportNoticePhrase(Res.string.export_reason_no_root_parameter_group)
		ExportNoticeReason.NoRootPart ->
			ExportNoticePhrase(Res.string.export_reason_no_root_part)
		ExportNoticeReason.NoParameterSourceSet ->
			ExportNoticePhrase(Res.string.export_reason_no_parameter_source_set)
		is ExportNoticeReason.CombinedPairReordered ->
			ExportNoticePhrase(
				Res.string.export_reason_combined_pair_reordered,
				listOf(
					ExportNoticeArgument.Literal(reason.horizontalParameterId),
					ExportNoticeArgument.Literal(reason.verticalParameterId),
				),
			)
		ExportNoticeReason.NoCanvasToReconcile ->
			ExportNoticePhrase(Res.string.export_reason_no_canvas_to_reconcile)
		ExportNoticeReason.FractionalCanvasSizeNotStorable ->
			ExportNoticePhrase(Res.string.export_reason_fractional_canvas_size)
	}

/**
 * The sentence naming which bundling rule a keyform set broke.
 *
 * @param KeyformBundleRejection rejection The rejection to phrase.
 * @return ExportNoticePhrase The sentence and its arguments.
 */
private fun keyformBundleRejectionPhrase(rejection: KeyformBundleRejection): ExportNoticePhrase =
	when (rejection) {
		KeyformBundleRejection.KeysOutsideGeometrySpan ->
			ExportNoticePhrase(Res.string.export_bundle_keys_outside_geometry_span)
		KeyformBundleRejection.ChannelKeysWithoutGeometry ->
			ExportNoticePhrase(Res.string.export_bundle_channel_keys_without_geometry)
		is KeyformBundleRejection.KeysOutsideChannelSpan ->
			ExportNoticePhrase(
				Res.string.export_bundle_keys_outside_channel_span,
				listOf(ExportNoticeArgument.Nested(channelPhrase(rejection.channel))),
			)
	}

/**
 * A channel's own label as a phrase, so it can nest as another sentence's argument.
 *
 * @param FormChannel channel The channel to name.
 * @return ExportNoticePhrase The label, argument-free.
 */
private fun channelPhrase(channel: FormChannel): ExportNoticePhrase =
	ExportNoticePhrase(formChannelLabelRes(channel))

/**
 * Resolves a phrase and its arguments into display text.
 *
 * Nesting bottoms out at depth two - a reason wraps a bundle rejection or a channel label, and neither
 * nests further - so the recursion needs no depth guard.
 *
 * @param ExportNoticePhrase phrase The phrase to resolve.
 * @return String The localized sentence.
 */
@Composable
fun exportNoticePhraseText(phrase: ExportNoticePhrase): String {
	if (phrase.arguments.isEmpty()) {
		return stringResource(phrase.resource)
	}
	val resolved = ArrayList<Any>(phrase.arguments.size)
	for (argument in phrase.arguments) {
		resolved.add(
			when (argument) {
				is ExportNoticeArgument.Literal -> argument.value
				is ExportNoticeArgument.Nested -> exportNoticePhraseText(argument.phrase)
			},
		)
	}
	return stringResource(phrase.resource, *resolved.toTypedArray())
}
