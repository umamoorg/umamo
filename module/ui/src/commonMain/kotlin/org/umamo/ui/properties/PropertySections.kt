package org.umamo.ui.properties

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.SelectionTarget
import org.umamo.edit.setCanvasSize
import org.umamo.edit.setDeformerBaseAngle
import org.umamo.edit.setDeformerQuadTransform
import org.umamo.edit.setDrawableAlphaBlendMode
import org.umamo.edit.setDrawableBlendMode
import org.umamo.edit.setDrawableCulling
import org.umamo.edit.setDrawableInvertMask
import org.umamo.edit.setPartDrawOrder
import org.umamo.edit.setPartGroupMode
import org.umamo.edit.setPartSketch
import org.umamo.edit.setWorldOrigin
import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.PartGroupMode
import org.umamo.runtime.model.partByDrawable
import org.umamo.ui.kit.Checkbox
import org.umamo.ui.kit.FieldStack
import org.umamo.ui.kit.NumberField
import org.umamo.ui.kit.SelectField
import org.umamo.ui.kit.Text
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoColors
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

/** An unbounded float range: a numeric field that clamps nothing and draws no magnitude fill. */
private val UNBOUNDED_RANGE = Float.NEGATIVE_INFINITY..Float.POSITIVE_INFINITY

/** A half-open float range (min set, no max): clamps below and draws no fill (needs both bounds). */
private val POSITIVE_RANGE = 1f..Float.POSITIVE_INFINITY

/**
 * A labelled Properties field row, Blender-style: the right-aligned label takes the left half and the
 * control fills the right half, so a column of rows aligns and every field spans a consistent width.  The
 * control should [Modifier.fillMaxWidth] so it fills its half.
 *
 * @param String label The localized field label.
 * @param Function control The editable control (a fillMaxWidth NumberField, SelectField, etc.).
 */
@Composable
private fun PropertyFieldRow(label: String, control: @Composable () -> Unit) {
	Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
		Text(
			text = label,
			style = LocalUmamoTypography.current.bodySmall,
			color = LocalUmamoColors.current.text,
			textAlign = TextAlign.End,
			modifier = Modifier.weight(1f).padding(end = 8.dp),
		)
		Box(modifier = Modifier.weight(1f)) {
			control()
		}
	}
}

/**
 * A Properties checkbox row: the checkbox (box plus its own label) sits in the right half like every other
 * field, with the left label column left empty - matching Blender, where a lone toggle occupies the field
 * column.  A group of related toggles can carry a left-column heading later.
 *
 * @param Boolean checked The current state.
 * @param Function onCheckedChange The toggle callback.
 * @param String label The checkbox's own label (drawn to the right of the box).
 */
@Composable
private fun PropertyCheckboxRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit, label: String) {
	Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
		Spacer(modifier = Modifier.weight(1f))
		Box(modifier = Modifier.weight(1f)) {
			Checkbox(checked = checked, onCheckedChange = onCheckedChange, label = label)
		}
	}
}

/** Document > Canvas: the document canvas size and world origin. */
internal val CanvasSection =
	PropertySection(
		id = "document.canvas",
		title = Res.string.properties_section_canvas,
		searchTerms =
			listOf(
				Res.string.properties_field_canvas_width,
				Res.string.properties_field_canvas_height,
				Res.string.properties_field_origin_x,
				Res.string.properties_field_origin_y,
			),
		content = { context ->
			val puppet = context.puppet
			val session = context.session
			val pixels = stringResource(Res.string.unit_pixels)
			// Width + height joined into one stacked group; the origin x / y into a second below it.
			FieldStack(
				listOf(
					{ position ->
						PropertyFieldRow(stringResource(Res.string.properties_field_canvas_width)) {
							NumberField(
								value = puppet.canvasWidth,
								onValueChange = { newWidth -> session?.setCanvasSize(newWidth, puppet.canvasHeight) },
								modifier = Modifier.fillMaxWidth(),
								range = POSITIVE_RANGE,
								decimals = 0,
								unitSuffix = pixels,
								stackPosition = position,
							)
						}
					},
					{ position ->
						PropertyFieldRow(stringResource(Res.string.properties_field_canvas_height)) {
							NumberField(
								value = puppet.canvasHeight,
								onValueChange = { newHeight -> session?.setCanvasSize(puppet.canvasWidth, newHeight) },
								modifier = Modifier.fillMaxWidth(),
								range = POSITIVE_RANGE,
								decimals = 0,
								unitSuffix = pixels,
								stackPosition = position,
							)
						}
					},
				),
			)
			FieldStack(
				listOf(
					{ position ->
						PropertyFieldRow(stringResource(Res.string.properties_field_origin_x)) {
							NumberField(
								value = puppet.worldOriginX,
								onValueChange = { newX -> session?.setWorldOrigin(newX, puppet.worldOriginY) },
								modifier = Modifier.fillMaxWidth(),
								range = UNBOUNDED_RANGE,
								decimals = 1,
								stackPosition = position,
							)
						}
					},
					{ position ->
						PropertyFieldRow(stringResource(Res.string.properties_field_origin_y)) {
							NumberField(
								value = puppet.worldOriginY,
								onValueChange = { newY -> session?.setWorldOrigin(puppet.worldOriginX, newY) },
								modifier = Modifier.fillMaxWidth(),
								range = UNBOUNDED_RANGE,
								decimals = 1,
								stackPosition = position,
							)
						}
					},
				),
			)
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
			val session = context.session
			val mesh = drawable?.mesh
			if (drawable != null && mesh != null && mesh.vertexCount > 0) {
				// A drawable has no scalar transform - its placement is its rest geometry - so bounds stay read-only.
				val bounds = computeMeshBounds(mesh.positions)
				PropertyLine(stringResource(Res.string.properties_transform_position, formatDimension(bounds.centerX), formatDimension(bounds.centerY)))
				PropertyLine(stringResource(Res.string.properties_transform_size, formatDimension(bounds.width), formatDimension(bounds.height)))
			} else if (deformer is Deformer.Rotation) {
				PropertyFieldRow(stringResource(Res.string.properties_field_base_angle)) {
					NumberField(
						value = deformer.baseAngle,
						onValueChange = { newAngle -> session?.setDeformerBaseAngle(deformer.id, newAngle) },
						modifier = Modifier.fillMaxWidth(),
						range = UNBOUNDED_RANGE,
						decimals = 1,
						unitSuffix = stringResource(Res.string.unit_degrees),
					)
				}
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
		searchTerms =
			listOf(
				Res.string.properties_field_blend_mode,
				Res.string.properties_field_alpha_mode,
				Res.string.properties_field_culling,
				Res.string.properties_field_invert_mask,
			),
		content = { context ->
			val drawable = context.activeDrawable()
			if (drawable != null) {
				val session = context.session
				val blendLabels = blendModeLabels()
				val alphaLabels = alphaBlendModeLabels()
				PropertyFieldRow(stringResource(Res.string.properties_field_blend_mode)) {
					SelectField(
						selected = drawable.blendMode,
						modifier = Modifier.fillMaxWidth(),
						options = BlendMode.entries,
						label = { mode -> blendLabels[mode] ?: mode.name },
						onSelect = { mode -> session?.setDrawableBlendMode(drawable.id, mode) },
					)
				}
				PropertyFieldRow(stringResource(Res.string.properties_field_alpha_mode)) {
					SelectField(
						selected = drawable.alphaBlendMode,
						modifier = Modifier.fillMaxWidth(),
						options = AlphaBlendMode.entries,
						label = { mode -> alphaLabels[mode] ?: mode.name },
						onSelect = { mode -> session?.setDrawableAlphaBlendMode(drawable.id, mode) },
					)
				}
				PropertyCheckboxRow(
					checked = drawable.culling,
					onCheckedChange = { culling -> session?.setDrawableCulling(drawable.id, culling) },
					label = stringResource(Res.string.properties_field_culling),
				)
				PropertyCheckboxRow(
					checked = drawable.invertMask,
					onCheckedChange = { invert -> session?.setDrawableInvertMask(drawable.id, invert) },
					label = stringResource(Res.string.properties_field_invert_mask),
				)
			}
		},
	)

/** Data (deformer): the warp lattice or the rotation base angle. */
internal val DeformerSection =
	PropertySection(
		id = "data.deformer",
		title = Res.string.properties_section_deformer,
		searchTerms = listOf(Res.string.inspector_warp_grid, Res.string.properties_field_quad_transform, Res.string.properties_field_base_angle),
		content = { context ->
			val session = context.session
			when (val deformer = context.activeDeformer()) {
				is Deformer.Warp -> {
					// The lattice dimensions resize the control grid + every keyform, so they stay read-only here.
					PropertyLine(stringResource(Res.string.inspector_warp_grid, deformer.rows, deformer.columns))
					PropertyCheckboxRow(
						checked = deformer.isQuadTransform,
						onCheckedChange = { quad -> session?.setDeformerQuadTransform(deformer.id, quad) },
						label = stringResource(Res.string.properties_field_quad_transform),
					)
				}

				is Deformer.Rotation -> {
					PropertyFieldRow(stringResource(Res.string.properties_field_base_angle)) {
						NumberField(
							value = deformer.baseAngle,
							onValueChange = { newAngle -> session?.setDeformerBaseAngle(deformer.id, newAngle) },
							modifier = Modifier.fillMaxWidth(),
							range = UNBOUNDED_RANGE,
							decimals = 1,
							unitSuffix = stringResource(Res.string.unit_degrees),
						)
					}
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
		searchTerms =
			listOf(
				Res.string.inspector_child_count,
				Res.string.properties_field_draw_order,
				Res.string.properties_field_sketch,
				Res.string.properties_field_group_mode,
				Res.string.properties_field_opacity,
			),
		content = { context ->
			val part = context.activePart()
			if (part != null) {
				val session = context.session
				val groupLabels = partGroupModeLabels()
				PropertyLine(stringResource(Res.string.inspector_child_count, part.children.size))
				PropertyCheckboxRow(
					checked = part.isSketch,
					onCheckedChange = { sketch -> session?.setPartSketch(part.id, sketch) },
					label = stringResource(Res.string.properties_field_sketch),
				)
				PropertyFieldRow(stringResource(Res.string.properties_field_draw_order)) {
					NumberField(
						value = part.drawOrder,
						onValueChange = { order -> session?.setPartDrawOrder(part.id, order) },
						range = 0..1000,
						modifier = Modifier.fillMaxWidth(),
					)
				}
				PropertyFieldRow(stringResource(Res.string.properties_field_group_mode)) {
					SelectField(
						selected = part.groupMode.kind(),
						modifier = Modifier.fillMaxWidth(),
						options = PartGroupModeKind.entries,
						label = { kind -> groupLabels[kind] ?: kind.name },
						onSelect = { kind -> session?.setPartGroupMode(part.id, partGroupModeOf(kind, part.groupMode)) },
					)
				}
				// An isolated part composites its subtree as one layer; expose the composite's scalar channels
				// (colors need a color-picker field, deferred).  Rebuilding the whole Isolated mode on each edit
				// keeps one Change / session method covering the mode swap and every sub-field alike.
				val composite = (part.groupMode as? PartGroupMode.Isolated)?.composite
				if (composite != null) {
					val blendLabels = blendModeLabels()
					val alphaLabels = alphaBlendModeLabels()
					PropertyFieldRow(stringResource(Res.string.properties_field_opacity)) {
						NumberField(
							value = composite.opacity,
							onValueChange = { opacity -> session?.setPartGroupMode(part.id, PartGroupMode.Isolated(composite.copy(opacity = opacity))) },
							modifier = Modifier.fillMaxWidth(),
							range = 0f..1f,
							decimals = 2,
							step = 0.05f,
						)
					}
					PropertyFieldRow(stringResource(Res.string.properties_field_blend_mode)) {
						SelectField(
							selected = composite.blendMode,
							modifier = Modifier.fillMaxWidth(),
							options = BlendMode.entries,
							label = { mode -> blendLabels[mode] ?: mode.name },
							onSelect = { mode -> session?.setPartGroupMode(part.id, PartGroupMode.Isolated(composite.copy(blendMode = mode))) },
						)
					}
					PropertyFieldRow(stringResource(Res.string.properties_field_alpha_mode)) {
						SelectField(
							selected = composite.alphaBlendMode,
							modifier = Modifier.fillMaxWidth(),
							options = AlphaBlendMode.entries,
							label = { mode -> alphaLabels[mode] ?: mode.name },
							onSelect = { mode -> session?.setPartGroupMode(part.id, PartGroupMode.Isolated(composite.copy(alphaBlendMode = mode))) },
						)
					}
					PropertyCheckboxRow(
						checked = composite.invertMask,
						onCheckedChange = { invert -> session?.setPartGroupMode(part.id, PartGroupMode.Isolated(composite.copy(invertMask = invert))) },
						label = stringResource(Res.string.properties_field_invert_mask),
					)
				}
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
