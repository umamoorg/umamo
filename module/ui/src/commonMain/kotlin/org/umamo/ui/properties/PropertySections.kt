package org.umamo.ui.properties

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.partByDrawable
import org.umamo.ui.kit.Text
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.theme.UmamoIcon
import kotlin.math.round

/*
 * The read-only property sections for the first cut.  Each is a stable, top-level [PropertySection]
 * singleton so its id is the catalog key for search and expanded state, and the Data tab can reference the
 * same instances it hands back per selection type.  Rendering reuses the Inspector's label:value idiom and
 * its existing `inspector_*` strings; this duplication resolves when the Inspector space is retired.
 */

/**
 * One read-only "label: value" property line.
 *
 * @param String text The composed line text.
 */
@Composable
internal fun PropertyLine(text: String) {
	Text(text = text, style = LocalUmamoTypography.current.bodySmall, modifier = Modifier.padding(top = 1.dp, bottom = 1.dp))
}

/**
 * Resolves a boolean to the localized Yes / No string (shared with the Inspector's vocabulary).
 *
 * @param Boolean value The flag.
 * @return String The localized "Yes" or "No".
 */
@Composable
internal fun yesNo(value: Boolean): String = stringResource(if (value) Res.string.inspector_yes else Res.string.inspector_no)

/**
 * Formats a canvas / transform dimension: two decimals at most, with a whole number shown without a
 * trailing ".0" so canvas sizes read cleanly.
 *
 * @param Float value The dimension in canvas units.
 * @return String The trimmed number.
 */
private fun formatDimension(value: Float): String {
	val rounded = round(value * 100f) / 100f
	val asLong = rounded.toLong()
	return if (rounded == asLong.toFloat()) asLong.toString() else rounded.toString()
}

/** The axis-aligned bounds of a mesh in canvas space: its center and extents. */
private class MeshBounds(val centerX: Float, val centerY: Float, val width: Float, val height: Float)

/**
 * Computes the axis-aligned bounds of an interleaved (x, y) position array.  A drawable has no scalar
 * transform - its placement is its rest-mesh geometry - so "Transform" is derived from these bounds.
 *
 * @param FloatArray positions Interleaved x, y vertex positions.
 * @return MeshBounds The center and extents; a zero box when there are no vertices.
 */
private fun computeMeshBounds(positions: FloatArray): MeshBounds {
	if (positions.size < 2) {
		return MeshBounds(0f, 0f, 0f, 0f)
	}
	var minX = Float.POSITIVE_INFINITY
	var minY = Float.POSITIVE_INFINITY
	var maxX = Float.NEGATIVE_INFINITY
	var maxY = Float.NEGATIVE_INFINITY
	var index = 0
	while (index + 1 < positions.size) {
		val x = positions[index]
		val y = positions[index + 1]
		if (x < minX) {
			minX = x
		}
		if (x > maxX) {
			maxX = x
		}
		if (y < minY) {
			minY = y
		}
		if (y > maxY) {
			maxY = y
		}
		index += 2
	}
	return MeshBounds((minX + maxX) / 2f, (minY + maxY) / 2f, maxX - minX, maxY - minY)
}

/** Document > Canvas: the document canvas size and world origin. */
internal val CanvasSection =
	PropertySection(
		id = "document.canvas",
		title = Res.string.properties_section_canvas,
		searchTerms = listOf(Res.string.properties_canvas_size, Res.string.properties_canvas_origin),
		content = { context ->
			val puppet = context.puppet
			if (puppet.canvasWidth > 0f || puppet.canvasHeight > 0f) {
				PropertyLine(stringResource(Res.string.properties_canvas_size, formatDimension(puppet.canvasWidth), formatDimension(puppet.canvasHeight)))
				PropertyLine(stringResource(Res.string.properties_canvas_origin, formatDimension(puppet.worldOriginX), formatDimension(puppet.worldOriginY)))
			} else {
				PropertyLine(stringResource(Res.string.properties_canvas_none))
			}
		},
	)

/**
 * Document > Runtime: runtime / export-target API compatibility options (Cubism, Ayagami, and others).
 * This is document-level configuration with no model backing yet, so the first cut renders a placeholder;
 * the runtime-compatibility target data model is a dedicated follow-up.
 */
internal val RuntimeSection =
	PropertySection(
		id = "document.runtime",
		title = Res.string.properties_section_runtime,
		searchTerms = listOf(Res.string.properties_runtime_placeholder),
		content = {
			PropertyLine(stringResource(Res.string.properties_runtime_placeholder))
		},
	)

/** Object > Transform: derived placement of the active item (drawable bounds, rotation base angle). */
internal val TransformSection =
	PropertySection(
		id = "object.transform",
		title = Res.string.properties_section_transform,
		searchTerms = listOf(Res.string.properties_transform_position, Res.string.properties_transform_size, Res.string.inspector_base_angle),
		content = { context ->
			val drawable = context.activeDrawable()
			val deformer = context.activeDeformer()
			val mesh = drawable?.mesh
			if (drawable != null && mesh != null && mesh.vertexCount > 0) {
				val bounds = computeMeshBounds(mesh.positions)
				PropertyLine(stringResource(Res.string.properties_transform_position, formatDimension(bounds.centerX), formatDimension(bounds.centerY)))
				PropertyLine(stringResource(Res.string.properties_transform_size, formatDimension(bounds.width), formatDimension(bounds.height)))
			} else if (deformer is Deformer.Rotation) {
				PropertyLine(stringResource(Res.string.inspector_base_angle, deformer.baseAngle.toString()))
			} else {
				PropertyLine(stringResource(Res.string.properties_transform_none))
			}
		},
	)

/** Object > Relations: owning part, parent deformer, masks / children of the active item. */
internal val RelationsSection =
	PropertySection(
		id = "object.relations",
		title = Res.string.properties_section_relations,
		searchTerms = listOf(Res.string.inspector_part_ref, Res.string.inspector_parent_deformer, Res.string.inspector_mask_count),
		content = { context ->
			val drawable = context.activeDrawable()
			val deformer = context.activeDeformer()
			val part = context.activePart()
			if (drawable != null) {
				val ownerPartId = context.puppet.partByDrawable()[drawable.id]
				val partLabel = ownerPartId?.let { id -> context.puppet.parts.firstOrNull { it.id == id }?.name ?: id.raw } ?: stringResource(Res.string.inspector_none)
				PropertyLine(stringResource(Res.string.inspector_part_ref, partLabel))
				PropertyLine(stringResource(Res.string.inspector_parent_deformer, drawable.parentDeformerId?.raw ?: stringResource(Res.string.inspector_none)))
				PropertyLine(stringResource(Res.string.inspector_mask_count, drawable.maskedBy.size))
				PropertyLine(stringResource(Res.string.inspector_invert_mask, yesNo(drawable.invertMask)))
			} else if (deformer != null) {
				PropertyLine(stringResource(Res.string.inspector_part_ref, deformer.partId?.raw ?: stringResource(Res.string.inspector_none)))
				PropertyLine(stringResource(Res.string.inspector_parent_deformer, deformer.parent?.raw ?: stringResource(Res.string.inspector_none)))
			} else if (part != null) {
				PropertyLine(stringResource(Res.string.inspector_child_count, part.children.size))
			}
		},
	)

/** Data (drawable): mesh vertex / triangle counts. */
internal val MeshSection =
	PropertySection(
		id = "data.mesh",
		title = Res.string.properties_section_mesh,
		searchTerms = listOf(Res.string.inspector_mesh),
		content = { context ->
			val mesh = context.activeDrawable()?.mesh
			if (mesh != null) {
				PropertyLine(stringResource(Res.string.inspector_mesh, mesh.vertexCount, mesh.triangleCount))
			} else {
				PropertyLine(stringResource(Res.string.inspector_no_mesh))
			}
		},
	)

/** Data (drawable): the atlas texture binding. */
internal val TextureSection =
	PropertySection(
		id = "data.texture",
		title = Res.string.properties_section_texture,
		searchTerms = listOf(Res.string.properties_texture_source),
		content = { context ->
			val source = context.activeDrawable()?.textureSourceId
			if (source != null) {
				PropertyLine(stringResource(Res.string.properties_texture_source, source.raw))
			} else {
				PropertyLine(stringResource(Res.string.properties_texture_own))
			}
		},
	)

/** Data (drawable): blend mode, alpha composition, and back-face culling. */
internal val BlendSection =
	PropertySection(
		id = "data.blend",
		title = Res.string.properties_section_blend,
		searchTerms = listOf(Res.string.inspector_blend_mode, Res.string.properties_blend_alpha, Res.string.properties_blend_culling),
		content = { context ->
			val drawable = context.activeDrawable()
			if (drawable != null) {
				PropertyLine(stringResource(Res.string.inspector_blend_mode, drawable.blendMode.name))
				PropertyLine(stringResource(Res.string.properties_blend_alpha, drawable.alphaBlendMode.name))
				PropertyLine(stringResource(Res.string.properties_blend_culling, yesNo(drawable.culling)))
			}
		},
	)

/** Data (deformer): the warp lattice or the rotation base angle. */
internal val DeformerSection =
	PropertySection(
		id = "data.deformer",
		title = Res.string.properties_section_deformer,
		searchTerms = listOf(Res.string.inspector_warp_grid, Res.string.inspector_base_angle),
		content = { context ->
			when (val deformer = context.activeDeformer()) {
				is Deformer.Warp -> {
					PropertyLine(stringResource(Res.string.inspector_warp_grid, deformer.rows, deformer.columns))
					PropertyLine(stringResource(Res.string.inspector_quad_transform, yesNo(deformer.isQuadTransform)))
				}

				is Deformer.Rotation -> {
					PropertyLine(stringResource(Res.string.inspector_base_angle, deformer.baseAngle.toString()))
				}

				null -> {
				}
			}
		},
	)

/** Data (part): child count, draw order, and the guide-image flag. */
internal val PartSection =
	PropertySection(
		id = "data.part",
		title = Res.string.properties_section_part,
		searchTerms = listOf(Res.string.inspector_child_count, Res.string.properties_part_draw_order, Res.string.inspector_sketch),
		content = { context ->
			val part = context.activePart()
			if (part != null) {
				PropertyLine(stringResource(Res.string.inspector_child_count, part.children.size))
				PropertyLine(stringResource(Res.string.properties_part_draw_order, part.drawOrder))
				PropertyLine(stringResource(Res.string.inspector_sketch, yesNo(part.isSketch)))
			}
		},
	)

/**
 * Every property section that can appear, in a stable order.  The Properties space resolves this whole
 * catalog's localized labels once per recomposition (a fixed-length loop, so Compose-safe) to build the
 * header search index; the tabs hand back subsets of these same instances.
 */
internal val PROPERTY_SECTION_CATALOG: List<PropertySection> =
	listOf(
		CanvasSection,
		RuntimeSection,
		TransformSection,
		RelationsSection,
		MeshSection,
		TextureSection,
		BlendSection,
		DeformerSection,
		PartSection,
	)

/**
 * The Data tab's sections for the active item's type: mesh / texture / blend for a drawable, the lattice
 * or rotation for a deformer, the part fields for a part, and none when nothing is active.
 *
 * @param PropertyContext context The current context.
 * @return List The Data tab's sections, top to bottom.
 */
internal fun dataTabSections(context: PropertyContext): List<PropertySection> =
	when (context.activeTarget) {
		is SelectionTarget.Drawable -> listOf(MeshSection, TextureSection, BlendSection)
		is SelectionTarget.Deformer -> listOf(DeformerSection)
		is SelectionTarget.Part -> listOf(PartSection)
		null -> emptyList()
	}

/**
 * The Data tab's glyph for the active item's type - the mesh, warp / rotation deformer, or part icon (the
 * warp-versus-rotation split needs the model, hence the context rather than just the target).
 *
 * @param PropertyContext context The current context.
 * @return UmamoIcon The Data tab's adaptive icon.
 */
internal fun dataTabIcon(context: PropertyContext): UmamoIcon =
	when (val target = context.activeTarget) {
		is SelectionTarget.Drawable -> LocalUmamoIcons.mesh
		is SelectionTarget.Deformer ->
			when (context.puppet.deformers.firstOrNull { it.id == target.id }) {
				is Deformer.Rotation -> LocalUmamoIcons.rotationDeformer
				else -> LocalUmamoIcons.warpDeformer
			}

		is SelectionTarget.Part -> LocalUmamoIcons.part
		null -> LocalUmamoIcons.mesh
	}
