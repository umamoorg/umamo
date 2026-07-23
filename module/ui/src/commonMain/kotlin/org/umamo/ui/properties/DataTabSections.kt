package org.umamo.ui.properties

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.SelectionTarget
import org.umamo.edit.setDeformerBaseAngle
import org.umamo.edit.setDeformerQuadTransform
import org.umamo.edit.setDrawableAlphaBlendMode
import org.umamo.edit.setDrawableBlendMode
import org.umamo.edit.setDrawableCulling
import org.umamo.edit.setDrawableInvertMask
import org.umamo.edit.setDrawableMultiplyColor
import org.umamo.edit.setDrawableScreenColor
import org.umamo.edit.setPartComposite
import org.umamo.edit.setPartDrawOrder
import org.umamo.edit.setPartGroupMode
import org.umamo.edit.setPartSketch
import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.displayMultiplyColor
import org.umamo.runtime.model.displayScreenColor
import org.umamo.ui.graphics.formatHexColor
import org.umamo.ui.graphics.parseHexColor
import org.umamo.ui.graphics.toColorRgb
import org.umamo.ui.graphics.toComposeColor
import org.umamo.ui.kit.HexColorField
import org.umamo.ui.kit.NumberField
import org.umamo.ui.kit.SelectField
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.UmamoIcon

/*
 * The Data tab's sections and its per-type dispatch.  Unlike the other two tabs, both the icon and the
 * section list resolve from the ACTIVE ITEM'S TYPE (dataTabIcon / dataTabSections), so a drawable, a
 * deformer, and a part each get their own face of this tab.
 */

/** Data (drawable): mesh vertex / triangle counts. */
internal val MeshSection =
	PropertySection(
		id = "data.mesh",
		title = Res.string.properties_section_mesh,
		rows = { context ->
			val mesh = context.activeDrawable()?.mesh
			listOf(
				PropertyRow(terms = listOf(Res.string.properties_mesh)) { _ ->
					if (mesh != null) {
						PropertyLine(stringResource(Res.string.properties_mesh, mesh.vertexCount, mesh.triangleCount))
					} else {
						PropertyLine(stringResource(Res.string.properties_no_mesh))
					}
				},
			)
		},
	)

/** Data (drawable): the atlas texture binding. */
internal val TextureSection =
	PropertySection(
		id = "data.texture",
		title = Res.string.properties_section_texture,
		rows = { context ->
			val source = context.activeDrawable()?.textureSourceId
			listOf(
				PropertyRow(terms = listOf(Res.string.properties_texture_source)) { _ ->
					if (source != null) {
						PropertyLine(stringResource(Res.string.properties_texture_source, source.raw))
					} else {
						PropertyLine(stringResource(Res.string.properties_texture_own))
					}
				},
			)
		},
	)

/** Data (drawable): blend mode, alpha composition, and back-face culling. */
internal val BlendSection =
	PropertySection(
		id = "data.blend",
		title = Res.string.properties_section_blend,
		rows = { context ->
			val drawable = context.activeDrawable()
			if (drawable != null) {
				val session = context.session
				listOfNotNull(
					PropertyRow(terms = listOf(Res.string.properties_field_blend_mode)) { _ ->
						val blendLabels = blendModeLabels()
						PropertyFieldRow(stringResource(Res.string.properties_field_blend_mode)) {
							SelectField(
								selected = drawable.blendMode,
								modifier = Modifier.fillMaxWidth(),
								options = blendModeDisplayOrder(),
								label = { mode -> blendLabels[mode] ?: mode.name },
								onSelect = { mode -> session?.setDrawableBlendMode(drawable.id, mode) },
							)
						}
					},
					if (drawable.blendMode.ignoresAlphaBlend) {
						null
					} else {
						PropertyRow(terms = listOf(Res.string.properties_field_alpha_mode)) { _ ->
							val alphaLabels = alphaBlendModeLabels()
							PropertyFieldRow(stringResource(Res.string.properties_field_alpha_mode)) {
								SelectField(
									selected = drawable.alphaBlendMode,
									modifier = Modifier.fillMaxWidth(),
									options = AlphaBlendMode.entries,
									label = { mode -> alphaLabels[mode] ?: mode.name },
									onSelect = { mode -> session?.setDrawableAlphaBlendMode(drawable.id, mode) },
								)
							}
						}
					},
					// The 5.3 per-art-mesh multiply/screen color.  It is a keyformed channel, so the picker
					// sets it uniformly across the drawable's whole keyform grid (see setDrawableMultiplyColor).
					PropertyRow(terms = listOf(Res.string.properties_field_multiply_color)) { _ ->
						PropertyFieldRow(stringResource(Res.string.properties_field_multiply_color)) {
							HexColorField(
								value = formatHexColor(drawable.displayMultiplyColor().toComposeColor()),
								onValueChange = { hex ->
									parseHexColor(hex)?.let { picked ->
										session?.setDrawableMultiplyColor(drawable.id, picked.toColorRgb())
									}
								},
								modifier = Modifier.fillMaxWidth(),
							)
						}
					},
					PropertyRow(terms = listOf(Res.string.properties_field_screen_color)) { _ ->
						PropertyFieldRow(stringResource(Res.string.properties_field_screen_color)) {
							HexColorField(
								value = formatHexColor(drawable.displayScreenColor().toComposeColor()),
								onValueChange = { hex ->
									parseHexColor(hex)?.let { picked ->
										session?.setDrawableScreenColor(drawable.id, picked.toColorRgb())
									}
								},
								modifier = Modifier.fillMaxWidth(),
							)
						}
					},
					PropertyRow(terms = listOf(Res.string.properties_field_culling)) { _ ->
						PropertyCheckboxRow(
							checked = drawable.culling,
							onCheckedChange = { culling -> session?.setDrawableCulling(drawable.id, culling) },
							label = stringResource(Res.string.properties_field_culling),
						)
					},
					// Clipping lives with the blend data, not under Relations: it is how the drawable is
					// composited, and it pairs with the invert toggle right below it.
					PropertyRow(terms = listOf(Res.string.properties_field_masked_by, Res.string.properties_mask_count)) { _ ->
						DrawableMaskEditor(drawable, context)
					},
					PropertyRow(terms = listOf(Res.string.properties_field_invert_mask)) { _ ->
						PropertyCheckboxRow(
							checked = drawable.invertMask,
							onCheckedChange = { invert -> session?.setDrawableInvertMask(drawable.id, invert) },
							label = stringResource(Res.string.properties_field_invert_mask),
						)
					},
				)
			} else {
				emptyList()
			}
		},
	)

/** Data (deformer): the warp lattice or the rotation base angle. */
internal val DeformerSection =
	PropertySection(
		id = "data.deformer",
		title = Res.string.properties_section_deformer,
		rows = { context ->
			val session = context.session
			when (val deformer = context.activeDeformer()) {
				is Deformer.Warp ->
					listOf(
						PropertyRow(terms = listOf(Res.string.properties_warp_grid)) { _ ->
							// The lattice dimensions resize the control grid + every keyform, so they stay read-only here.
							PropertyLine(stringResource(Res.string.properties_warp_grid, deformer.rows, deformer.columns))
						},
						PropertyRow(terms = listOf(Res.string.properties_field_quad_transform)) { _ ->
							PropertyCheckboxRow(
								checked = deformer.isQuadTransform,
								onCheckedChange = { quad -> session?.setDeformerQuadTransform(deformer.id, quad) },
								label = stringResource(Res.string.properties_field_quad_transform),
							)
						},
					)

				is Deformer.Rotation ->
					listOf(
						PropertyRow(terms = listOf(Res.string.properties_field_base_angle)) { _ ->
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
						},
					)

				null -> emptyList()
			}
		},
	)

/** Data (part): child count, draw order, and the guide-image flag. */
internal val PartSection =
	PropertySection(
		id = "data.part",
		title = Res.string.properties_section_part,
		rows = { context ->
			val part = context.activePart()
			if (part != null) {
				val session = context.session
				buildList {
					add(
						PropertyRow(terms = listOf(Res.string.properties_field_sketch)) { _ ->
							PropertyCheckboxRow(
								checked = part.isSketch,
								onCheckedChange = { sketch -> session?.setPartSketch(part.id, sketch) },
								label = stringResource(Res.string.properties_field_sketch),
							)
						},
					)
					add(
						PropertyRow(terms = listOf(Res.string.properties_field_draw_order)) { _ ->
							PropertyFieldRow(stringResource(Res.string.properties_field_draw_order)) {
								NumberField(
									value = part.drawOrder,
									onValueChange = { order -> session?.setPartDrawOrder(part.id, order) },
									range = 0..1000,
									modifier = Modifier.fillMaxWidth(),
								)
							}
						},
					)
					add(
						PropertyRow(terms = listOf(Res.string.properties_field_group_mode)) { _ ->
							val groupLabels = partGroupModeLabels()
							PropertyFieldRow(stringResource(Res.string.properties_field_group_mode)) {
								SelectField(
									selected = part.groupMode.kind(),
									modifier = Modifier.fillMaxWidth(),
									options = PartGroupModeKind.entries,
									label = { kind -> groupLabels[kind] ?: kind.name },
									onSelect = { kind -> session?.setPartGroupMode(part.id, partGroupModeOf(kind)) },
								)
							}
						},
					)
					// An isolated part composites its subtree as one layer; expose the composite's scalar channels,
					// tint colors, and clip masks.  The composite is stored latently on the part, so each sub-field
					// edits it via setPartComposite - it survives a mode round-trip and is shown only while the part
					// is Isolated (activeComposite is non-null exactly then).
					val composite = part.activeComposite
					if (composite != null) {
						add(
							PropertyRow(terms = listOf(Res.string.properties_field_opacity)) { _ ->
								PropertyFieldRow(stringResource(Res.string.properties_field_opacity)) {
									NumberField(
										value = composite.opacity,
										onValueChange = { opacity -> session?.setPartComposite(part.id, composite.copy(opacity = opacity)) },
										modifier = Modifier.fillMaxWidth(),
										range = 0f..1f,
										decimals = 2,
										step = 0.05f,
									)
								}
							},
						)
						add(
							PropertyRow(terms = listOf(Res.string.properties_field_blend_mode)) { _ ->
								val blendLabels = blendModeLabels()
								PropertyFieldRow(stringResource(Res.string.properties_field_blend_mode)) {
									SelectField(
										selected = composite.blendMode,
										modifier = Modifier.fillMaxWidth(),
										options = blendModeDisplayOrder(),
										label = { mode -> blendLabels[mode] ?: mode.name },
										onSelect = { mode -> session?.setPartComposite(part.id, composite.copy(blendMode = mode)) },
									)
								}
							},
						)
						if (!composite.blendMode.ignoresAlphaBlend) {
							add(
								PropertyRow(terms = listOf(Res.string.properties_field_alpha_mode)) { _ ->
									val alphaLabels = alphaBlendModeLabels()
									PropertyFieldRow(stringResource(Res.string.properties_field_alpha_mode)) {
										SelectField(
											selected = composite.alphaBlendMode,
											modifier = Modifier.fillMaxWidth(),
											options = AlphaBlendMode.entries,
											label = { mode -> alphaLabels[mode] ?: mode.name },
											onSelect = { mode -> session?.setPartComposite(part.id, composite.copy(alphaBlendMode = mode)) },
										)
									}
								},
							)
						}
						add(
							PropertyRow(terms = listOf(Res.string.properties_field_multiply_color)) { _ ->
								PropertyFieldRow(stringResource(Res.string.properties_field_multiply_color)) {
									HexColorField(
										value = formatHexColor(composite.multiplyColor.toComposeColor()),
										onValueChange = { hex ->
											parseHexColor(hex)?.let { picked ->
												session?.setPartComposite(part.id, composite.copy(multiplyColor = picked.toColorRgb()))
											}
										},
										modifier = Modifier.fillMaxWidth(),
									)
								}
							},
						)
						add(
							PropertyRow(terms = listOf(Res.string.properties_field_screen_color)) { _ ->
								PropertyFieldRow(stringResource(Res.string.properties_field_screen_color)) {
									HexColorField(
										value = formatHexColor(composite.screenColor.toComposeColor()),
										onValueChange = { hex ->
											parseHexColor(hex)?.let { picked ->
												session?.setPartComposite(part.id, composite.copy(screenColor = picked.toColorRgb()))
											}
										},
										modifier = Modifier.fillMaxWidth(),
									)
								}
							},
						)
						add(
							PropertyRow(terms = listOf(Res.string.properties_field_masked_by)) { rowContext ->
								PartMaskEditor(part, composite, rowContext)
							},
						)
						add(
							PropertyRow(terms = listOf(Res.string.properties_field_invert_mask)) { _ ->
								PropertyCheckboxRow(
									checked = composite.invertMask,
									onCheckedChange = { invert -> session?.setPartComposite(part.id, composite.copy(invertMask = invert)) },
									label = stringResource(Res.string.properties_field_invert_mask),
								)
							},
						)
					}
				}
			} else {
				emptyList()
			}
		},
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
		// Through the shared glyph so the tab icon and the Relations row can never disagree about a deformer.
		is SelectionTarget.Deformer ->
			context.puppet.deformers.firstOrNull { it.id == target.id }
				?.let { deformer -> deformerIcon(deformer) }
				?: LocalUmamoIcons.warpDeformer

		is SelectionTarget.Part -> LocalUmamoIcons.part
		null -> LocalUmamoIcons.mesh
	}
