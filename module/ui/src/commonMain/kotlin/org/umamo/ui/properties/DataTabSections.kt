package org.umamo.ui.properties

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.Change
import org.umamo.edit.DeformerChange
import org.umamo.edit.DrawableChange
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.PartChange
import org.umamo.edit.SelectionTarget
import org.umamo.edit.atlas.setAtlasPins
import org.umamo.edit.keyform.cancelChannelPreview
import org.umamo.edit.keyform.editKeyedChannel
import org.umamo.edit.keyform.previewChannelEdit
import org.umamo.edit.mesh.LATTICE_DIVISION_MAX
import org.umamo.edit.property.setDeformerFlipX
import org.umamo.edit.property.setDeformerFlipY
import org.umamo.edit.property.setDeformerMultiplyColor
import org.umamo.edit.property.setDeformerOpacity
import org.umamo.edit.property.setDeformerScreenColor
import org.umamo.edit.property.setDrawableAlphaBlendMode
import org.umamo.edit.property.setDrawableBlendMode
import org.umamo.edit.property.setDrawableCulling
import org.umamo.edit.property.setDrawableDrawOrder
import org.umamo.edit.property.setDrawableInvertMask
import org.umamo.edit.property.setDrawableMultiplyColor
import org.umamo.edit.property.setDrawableOpacity
import org.umamo.edit.property.setDrawableScreenColor
import org.umamo.edit.property.setPartComposite
import org.umamo.edit.property.setPartDrawOrder
import org.umamo.edit.property.setPartGroupMode
import org.umamo.edit.property.setPartSketch
import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyableTarget
import org.umamo.runtime.model.KeyformOwner
import org.umamo.runtime.model.KeyformTrackRef
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RuntimeFeature
import org.umamo.runtime.model.RuntimeTarget
import org.umamo.runtime.model.displayMultiplyColor
import org.umamo.runtime.model.displayScreenColor
import org.umamo.runtime.model.multiplyColor
import org.umamo.runtime.model.opacity
import org.umamo.runtime.model.screenColor
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.graphics.formatHexColor
import org.umamo.ui.graphics.parseHexColor
import org.umamo.ui.graphics.toColorRgb
import org.umamo.ui.graphics.toComposeColor
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.button.Button
import org.umamo.ui.kit.field.AspectLockFieldPair
import org.umamo.ui.kit.field.HexColorField
import org.umamo.ui.kit.field.NumberField
import org.umamo.ui.kit.field.PropertyCheckboxRow
import org.umamo.ui.kit.field.PropertyFieldRow
import org.umamo.ui.kit.field.PropertyLine
import org.umamo.ui.kit.field.SelectField
import org.umamo.ui.kit.field.tint
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.theme.UmamoIcon
import org.umamo.ui.transform.setLatticeSizeAdjustable
import org.umamo.ui.transform.setWarpQuadTransform
import org.umamo.ui.workspace.operationstrip.LocalOperationStripArea
import kotlin.math.roundToInt

/*
 * The Data tab's sections and its per-type dispatch.  Unlike the other two tabs, both the icon and the
 * section list resolve from the ACTIVE ITEM'S TYPE (dataTabIcon / dataTabSections), so a drawable, a
 * deformer, and a part each get their own face of this tab.
 */

/** Data (drawable): mesh vertex / triangle counts, and Generate Mesh from Art and Generate Quad Mesh for a drawable with a mesh. */
internal val MeshSection =
	PropertySection(
		id = "data.mesh",
		title = Res.string.properties_section_mesh,
		rows = { context ->
			val mesh = context.activeDrawable()?.mesh
			buildList {
				add(
					PropertyRow(terms = listOf(Res.string.properties_mesh)) { _ ->
						if (mesh != null) {
							PropertyLine(stringResource(Res.string.properties_mesh, mesh.vertexCount, mesh.triangleCount))
						} else {
							PropertyLine(stringResource(Res.string.properties_no_mesh))
						}
					},
				)

				if (mesh != null) {
					add(PropertyRow(terms = listOf(Res.string.cmd_mesh_generate_from_art)) { _ -> CommandButton("mesh.generateFromArt", Res.string.cmd_mesh_generate_from_art) })
					add(PropertyRow(terms = listOf(Res.string.cmd_mesh_generate_quad)) { _ -> CommandButton("mesh.generateQuad", Res.string.cmd_mesh_generate_quad) })
				}
			}
		},
	)

/**
 * A button that runs one command: it dispatches the command (so the chord, the palette, and this button
 * run the same thing over the same selection) and is enabled exactly when the command is.
 *
 * @param String         commandId The command.
 * @param StringResource label     The button's label, the command's title.
 */
@Composable
private fun CommandButton(commandId: String, label: StringResource) {
	val commands = LocalCommands.current
	val available = commands[commandId]?.availability?.isAvailable() == true
	Button(
		label = stringResource(label),
		onClick = { commands.invoke(commandId) },
		primary = false,
		enabled = available,
	)
}

/**
 * Data (drawable): the atlas texture binding, and the pin on the tile's placement when the drawable's
 * art is packed on a page (the same flag the UV editor's P / Alt+P set).
 */
internal val TextureSection =
	PropertySection(
		id = "data.texture",
		title = Res.string.properties_section_texture,
		rows = { context ->
			val drawable = context.activeDrawable()
			val source = drawable?.textureSourceId
			val tile = drawable?.atlasTileId?.let { tileId -> context.puppet.atlas.tileById[tileId] }
			val session = context.session
			buildList {
				add(
					PropertyRow(terms = listOf(Res.string.properties_texture_source)) { _ ->
						if (source != null) {
							PropertyLine(stringResource(Res.string.properties_texture_source, source.raw))
						} else {
							PropertyLine(stringResource(Res.string.properties_texture_own))
						}
					},
				)

				if (tile != null && tile.placement != null) {
					add(
						PropertyRow(terms = listOf(Res.string.properties_field_pinned)) { _ ->
							PropertyCheckboxRow(
								checked = tile.pinned,
								onCheckedChange = { checked -> session?.setAtlasPins(listOf(tile.id), checked) },
								label = stringResource(Res.string.properties_field_pinned),
								description = stringResource(Res.string.properties_field_pinned_description),
							)
						},
					)
				}
			}
		},
	)

/** Data (drawable): draw order, opacity, blend mode, alpha composition, tint colors, back-face culling, and masking. */
internal val BlendSection =
	PropertySection(
		id = "data.blend",
		title = Res.string.properties_section_blend,
		rows = { context ->
			val drawable = context.activeDrawable()

			if (drawable != null) {
				val session = context.session
				val target = context.puppet.runtimeTarget
				listOfNotNull(
					// Opacity and draw order are keyable channels like the colors below, each with its own
					// static and its own optional track.  Draw order is a FLOAT here, unlike a part's int:
					// it blends per pose, so a fractional value between two keys is meaningful.
					PropertyRow(terms = listOf(Res.string.properties_field_draw_order)) { _ ->
						KeyableScalarChannelRow(
							label = stringResource(Res.string.properties_field_draw_order),
							description = stringResource(Res.string.properties_field_draw_order_description),
							owner = KeyformOwner.Drawable(drawable.id),
							channel = FormChannel.DRAW_ORDER,
							stored = drawable.drawOrder,
							session = session,
							range = 0f..1000f,
							decimals = 0,
							step = 1f,
							changeFor = { order -> DrawableChange.SetDrawOrder(drawable.id, order) },
							writeStatic = { order -> session?.setDrawableDrawOrder(drawable.id, order) },
						)
					},
					PropertyRow(terms = listOf(Res.string.properties_field_opacity)) { _ ->
						KeyableScalarChannelRow(
							label = stringResource(Res.string.properties_field_opacity),
							description = stringResource(Res.string.properties_field_opacity_description),
							owner = KeyformOwner.Drawable(drawable.id),
							channel = FormChannel.OPACITY,
							stored = drawable.opacity,
							session = session,
							range = 0f..1f,
							decimals = 3,
							step = 0.05f,
							changeFor = { opacity -> DrawableChange.SetOpacity(drawable.id, opacity) },
							writeStatic = { opacity -> session?.setDrawableOpacity(drawable.id, opacity) },
						)
					},
					PropertyRow(terms = listOf(Res.string.properties_field_blend_mode)) { _ ->
						val blendLabels = blendModeLabels()
						PropertyFieldRow(
							stringResource(Res.string.properties_field_blend_mode),
							description = stringResource(Res.string.properties_field_blend_mode_description),
						) {
							SelectField(
								selected = drawable.blendMode,
								modifier = Modifier.fillMaxWidth(),
								options = blendModeOptionsFor(target, drawable.blendMode),
								label = { mode -> blendLabels[mode] ?: mode.name },
								onSelect = { mode -> session?.setDrawableBlendMode(drawable.id, mode) },
							)
						}
					},
					if (!showsAlphaBlendRow(target, drawable.blendMode, drawable.alphaBlendMode)) {
						null
					} else {
						PropertyRow(terms = listOf(Res.string.properties_field_alpha_mode)) { _ ->
							val alphaLabels = alphaBlendModeLabels()
							PropertyFieldRow(
								stringResource(Res.string.properties_field_alpha_mode),
								description = stringResource(Res.string.properties_field_alpha_mode_description),
							) {
								SelectField(
									selected = drawable.alphaBlendMode,
									modifier = Modifier.fillMaxWidth(),
									options = alphaBlendModeOptionsFor(target, drawable.alphaBlendMode),
									label = { mode -> alphaLabels[mode] ?: mode.name },
									onSelect = { mode -> session?.setDrawableAlphaBlendMode(drawable.id, mode) },
								)
							}
						}
					},
					// The 4.2 per-art-mesh multiply/screen color: a channel with its own static and its own
					// optional keyform track, so the picker writes the static and `I` over the row keys it.
					if (!target.supports(RuntimeFeature.MultiplyColor)) {
						null
					} else {
						PropertyRow(terms = listOf(Res.string.properties_field_multiply_color)) { _ ->
							KeyableColorChannelRow(
								label = stringResource(Res.string.properties_field_multiply_color),
								description = stringResource(Res.string.properties_field_multiply_color_description),
								owner = KeyformOwner.Drawable(drawable.id),
								channel = FormChannel.MULTIPLY_COLOR,
								stored = drawable.displayMultiplyColor(),
								session = session,
								changeFor = { color -> DrawableChange.SetMultiplyColor(drawable.id, color) },
								writeStatic = { color -> session?.setDrawableMultiplyColor(drawable.id, color) },
							)
						}
					},
					if (!target.supports(RuntimeFeature.ScreenColor)) {
						null
					} else {
						PropertyRow(terms = listOf(Res.string.properties_field_screen_color)) { _ ->
							KeyableColorChannelRow(
								label = stringResource(Res.string.properties_field_screen_color),
								description = stringResource(Res.string.properties_field_screen_color_description),
								owner = KeyformOwner.Drawable(drawable.id),
								channel = FormChannel.SCREEN_COLOR,
								stored = drawable.displayScreenColor(),
								session = session,
								changeFor = { color -> DrawableChange.SetScreenColor(drawable.id, color) },
								writeStatic = { color -> session?.setDrawableScreenColor(drawable.id, color) },
							)
						}
					},
					PropertyRow(terms = listOf(Res.string.properties_field_culling)) { _ ->
						PropertyCheckboxRow(
							checked = drawable.culling,
							onCheckedChange = { culling -> session?.setDrawableCulling(drawable.id, culling) },
							label = stringResource(Res.string.properties_field_culling),
							description = stringResource(Res.string.properties_field_culling_description),
						)
					},
					// Clipping lives with the blend data, not under Relations: it is how the drawable is
					// composited, and it pairs with the invert toggle right below it.
					PropertyRow(terms = listOf(Res.string.properties_field_masked_by, Res.string.properties_mask_count)) { _ ->
						DrawableMaskEditor(drawable, context)
					},
					// Reversed masking is a 4.0 feature (official target dialog), gated separately from the
					// always-available masks above it.
					if (!target.supports(RuntimeFeature.ReversedMask)) {
						null
					} else {
						PropertyRow(terms = listOf(Res.string.properties_field_invert_mask)) { _ ->
							PropertyCheckboxRow(
								checked = drawable.invertMask,
								onCheckedChange = { invert -> session?.setDrawableInvertMask(drawable.id, invert) },
								label = stringResource(Res.string.properties_field_invert_mask),
								description = stringResource(Res.string.properties_field_invert_mask_description),
							)
						}
					},
				)
			} else {
				emptyList()
			}
		},
	)

/**
 * The active drawable's or deformer's blend shapes, read-only: one line per binding naming the parameter that drives
 * it and how many keys it has.  Editing blend shapes belongs to their own workflow; this lists what the rig carries.
 * Hidden for an item with none.
 */
internal val BlendShapesSection =
	PropertySection(
		id = "data.blendShapes",
		title = Res.string.properties_section_blend_shapes,
		rows = { context ->
			val bindings =
				context.activeDrawable()?.blendShapes?.map { binding -> binding.parameterId to binding.keys.size }
					?: when (val deformer = context.activeDeformer()) {
						is Deformer.Warp -> deformer.blendShapes.map { binding -> binding.parameterId to binding.keys.size }
						is Deformer.Rotation -> deformer.blendShapes.map { binding -> binding.parameterId to binding.keys.size }
						null -> emptyList()
					}

			bindings.map { (parameterId, keyCount) ->
				PropertyRow(terms = listOf(Res.string.properties_section_blend_shapes)) { rowContext ->
					// The parameter's name is the rig's own data, shown as authored.
					val parameterName = rowContext.puppet.parameters.firstOrNull { parameter -> parameter.id == parameterId }?.name ?: parameterId.raw
					PropertyLine(stringResource(Res.string.properties_blend_shape_binding, parameterName, keyCount))
				}
			}
		},
	)

/**
 * Data (deformer): opacity and tint colors, plus the warp's lattice key state, its Rows / Columns, and its
 * quad-transform flag, or the rotation's base angle and flip flags.
 */
internal val DeformerSection =
	PropertySection(
		id = "data.deformer",
		title = Res.string.properties_section_deformer,
		rows = { context ->
			val session = context.session
			val target = context.puppet.runtimeTarget

			when (val deformer = context.activeDeformer()) {
				is Deformer.Warp -> {
					deformerRenderChannelRows(deformer, session, target) +
						listOfNotNull(
							PropertyRow(terms = listOf(Res.string.properties_warp_lattice)) { rowContext ->
								WarpLatticeKeyRow(deformer, rowContext.puppet)
							},
							PropertyRow(terms = listOf(Res.string.properties_field_lattice_rows, Res.string.properties_field_lattice_columns)) { rowContext ->
								WarpLatticeSizeRows(deformer, rowContext)
							},
							// The bilinear mode is the official dialog's "new warp deformer method" (3.3+).
							if (!target.supports(RuntimeFeature.WarpQuadTransform)) {
								null
							} else {
								PropertyRow(terms = listOf(Res.string.properties_field_quad_transform)) { rowContext ->
									PropertyCheckboxRow(
										checked = deformer.isQuadTransform,
										onCheckedChange = { quad -> session?.setWarpQuadTransform(deformer.id, quad) },
										label = stringResource(Res.string.properties_field_quad_transform),
										description = stringResource(Res.string.properties_field_quad_transform_description),
										enabled = rewritesLattices(rowContext),
									)
								}
							},
						)
				}

				is Deformer.Rotation -> {
					deformerRenderChannelRows(deformer, session, target) +
						listOf(
							PropertyRow(terms = listOf(Res.string.properties_field_base_angle)) { _ ->
								DeformerBaseAngleField(deformer, session)
							},
							PropertyRow(terms = listOf(Res.string.properties_field_flip_x)) { _ ->
								KeyableFlagChannelRow(
									label = stringResource(Res.string.properties_field_flip_x),
									description = stringResource(Res.string.properties_field_flip_x_description),
									owner = KeyformOwner.Deformer(deformer.id),
									channel = FormChannel.FLIP_X,
									stored = deformer.flipX,
									session = session,
									changeFor = { flip -> DeformerChange.SetFlipX(deformer.id, flip) },
									writeStatic = { flip -> session?.setDeformerFlipX(deformer.id, flip) },
								)
							},
							PropertyRow(terms = listOf(Res.string.properties_field_flip_y)) { _ ->
								KeyableFlagChannelRow(
									label = stringResource(Res.string.properties_field_flip_y),
									description = stringResource(Res.string.properties_field_flip_y_description),
									owner = KeyformOwner.Deformer(deformer.id),
									channel = FormChannel.FLIP_Y,
									stored = deformer.flipY,
									session = session,
									changeFor = { flip -> DeformerChange.SetFlipY(deformer.id, flip) },
									writeStatic = { flip -> session?.setDeformerFlipY(deformer.id, flip) },
								)
							},
						)
				}

				null -> {
					emptyList()
				}
			}
		},
	)

/** Data (part): the guide-image flag, draw order, group mode, and, while isolated, the composite's render channels and masks. */
internal val PartSection =
	PropertySection(
		id = "data.part",
		title = Res.string.properties_section_part,
		rows = { context ->
			val part = context.activePart()

			if (part != null) {
				val session = context.session
				val target = context.puppet.runtimeTarget
				buildList {
					add(
						PropertyRow(terms = listOf(Res.string.properties_field_sketch)) { _ ->
							PropertyCheckboxRow(
								checked = part.isSketch,
								onCheckedChange = { sketch -> session?.setPartSketch(part.id, sketch) },
								label = stringResource(Res.string.properties_field_sketch),
								description = stringResource(Res.string.properties_field_sketch_description),
							)
						},
					)
					add(
						PropertyRow(terms = listOf(Res.string.properties_field_draw_order)) { _ ->
							KeyablePropertyRow(
								target = KeyableTarget(KeyformOwner.Part(part.id), FormChannel.DRAW_ORDER),
							) {
								PropertyFieldRow(
									stringResource(Res.string.properties_field_draw_order),
									description = stringResource(Res.string.properties_field_draw_order_part_description),
								) {
									val target = KeyableTarget(KeyformOwner.Part(part.id), FormChannel.DRAW_ORDER)
									NumberField(
										keyState = keyedFieldStateOf(KeyformOwner.Part(part.id), FormChannel.DRAW_ORDER),
										// What is APPLIED: a keyed channel's track shadows the Int static.
										value =
											displayedChannelScalar(
												KeyformOwner.Part(part.id),
												FormChannel.DRAW_ORDER,
												part.drawOrder.toFloat(),
											).roundToInt(),
										onValueChange = { order: Int ->
											session?.editKeyedChannel(
												target,
												ChannelValue.Scalar(order.toFloat()),
												PartChange.SetDrawOrder(part.id, order),
											) {
												session.setPartDrawOrder(part.id, order)
											}
										},
										onPreview = { order: Int ->
											session?.previewChannelEdit(target, ChannelValue.Scalar(order.toFloat()))
										},
										onScrubCancel = { session?.cancelChannelPreview(target) },
										range = 0..1000,
										modifier = Modifier.fillMaxWidth(),
									)
								}
							}
						},
					)
					add(
						PropertyRow(terms = listOf(Res.string.properties_field_group_mode)) { _ ->
							val groupLabels = partGroupModeLabels()
							PropertyFieldRow(
								stringResource(Res.string.properties_field_group_mode),
								description = stringResource(Res.string.properties_field_group_mode_description),
							) {
								SelectField(
									selected = part.groupMode.kind(),
									modifier = Modifier.fillMaxWidth(),
									options = partGroupModeOptionsFor(target, part.groupMode.kind()),
									label = { kind -> groupLabels[kind] ?: kind.name },
									onSelect = { kind -> session?.setPartGroupMode(part.id, partGroupModeOf(kind)) },
								)
							}
						},
					)
					// An isolated part composites its subtree as one layer; expose the composite's scalar channels,
					// tint colors, and clip masks.  The composite is stored latently on the part, so each sub-field
					// edits it via setPartComposite - it survives a mode round-trip and is shown only while the part
					// is Isolated (activeComposite is non-null exactly then).  The whole block additionally gates on
					// the 5.3 offscreen feature: an out-of-target isolated part keeps rendering, but its composite
					// stops being editable.  No inner gates - 5.3 support implies every lower tier (colors, alpha,
					// reversed mask).
					val composite = part.activeComposite

					if (composite != null && target.supports(RuntimeFeature.PartComposite)) {
						add(
							PropertyRow(terms = listOf(Res.string.properties_field_opacity)) { _ ->
								KeyableScalarChannelRow(
									label = stringResource(Res.string.properties_field_opacity),
									description = stringResource(Res.string.properties_field_opacity_part_description),
									owner = KeyformOwner.Part(part.id),
									channel = FormChannel.OPACITY,
									stored = composite.opacity,
									session = session,
									range = 0f..1f,
									decimals = 3,
									step = 0.05f,
									changeFor = { opacity -> PartChange.SetComposite(part.id, composite.copy(opacity = opacity)) },
									writeStatic = { opacity ->
										session?.setPartComposite(part.id, composite.copy(opacity = opacity))
									},
								)
							},
						)
						add(
							PropertyRow(terms = listOf(Res.string.properties_field_blend_mode)) { _ ->
								val blendLabels = blendModeLabels()
								PropertyFieldRow(
									stringResource(Res.string.properties_field_blend_mode),
									description = stringResource(Res.string.properties_field_blend_mode_part_description),
								) {
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
									PropertyFieldRow(
										stringResource(Res.string.properties_field_alpha_mode),
										description = stringResource(Res.string.properties_field_alpha_mode_part_description),
									) {
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
								KeyableColorChannelRow(
									label = stringResource(Res.string.properties_field_multiply_color),
									description = stringResource(Res.string.properties_field_multiply_color_part_description),
									owner = KeyformOwner.Part(part.id),
									channel = FormChannel.MULTIPLY_COLOR,
									stored = composite.multiplyColor,
									session = session,
									changeFor = { color -> PartChange.SetComposite(part.id, composite.copy(multiplyColor = color)) },
									writeStatic = { color ->
										session?.setPartComposite(part.id, composite.copy(multiplyColor = color))
									},
								)
							},
						)
						add(
							PropertyRow(terms = listOf(Res.string.properties_field_screen_color)) { _ ->
								KeyableColorChannelRow(
									label = stringResource(Res.string.properties_field_screen_color),
									description = stringResource(Res.string.properties_field_screen_color_part_description),
									owner = KeyformOwner.Part(part.id),
									channel = FormChannel.SCREEN_COLOR,
									stored = composite.screenColor,
									session = session,
									changeFor = { color -> PartChange.SetComposite(part.id, composite.copy(screenColor = color)) },
									writeStatic = { color ->
										session?.setPartComposite(part.id, composite.copy(screenColor = color))
									},
								)
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
									description = stringResource(Res.string.properties_field_invert_mask_part_description),
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
		is SelectionTarget.Drawable -> listOf(MeshSection, TextureSection, BlendSection, BlendShapesSection)
		is SelectionTarget.Deformer -> listOf(DeformerSection, BlendShapesSection)
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
		is SelectionTarget.Drawable -> {
			LocalUmamoIcons.mesh
		}

		// Through the shared glyph so the tab icon and the Relations row can never disagree about a deformer.
		is SelectionTarget.Deformer -> {
			context.puppet.deformers.firstOrNull { it.id == target.id }
				?.let { deformer -> deformerIcon(deformer) }
				?: LocalUmamoIcons.warpDeformer
		}

		is SelectionTarget.Part -> {
			LocalUmamoIcons.part
		}

		null -> {
			LocalUmamoIcons.mesh
		}
	}

/**
 * One keyable COLOR channel's field row: the label, the hex field with its keyed tint, the
 * track-resolved display value, and the keyed-goes-pending edit routing.
 *
 * ONE composable for every color channel row - drawable or part, multiply or screen - rather than the
 * routing rule hand-copied per row: a rule duplicated across every row is exactly the kind of thing that
 * drifts, and a row missing it writes a static the track shadows, so the edit appears to be silently
 * rejected.
 *
 * @param String label The row's field label.
 * @param String description What the channel does, as the label's tooltip.
 * @param KeyformOwner owner The entity the row edits.
 * @param FormChannel channel The color channel the row edits.
 * @param ColorRgb stored The owner's static color.
 * @param EditorSession? session The open document's session, or null.
 * @param Function changeFor The history descriptor for a committed color, whichever branch stores it.
 * @param Function writeStatic Writes the owner's static color (the unkeyed path).
 */
@Composable
private fun KeyableColorChannelRow(
	label: String,
	description: String,
	owner: KeyformOwner,
	channel: FormChannel,
	stored: ColorRgb,
	session: EditorSession?,
	changeFor: (ColorRgb) -> Change,
	writeStatic: (ColorRgb) -> Unit,
) {
	val target = KeyableTarget(owner, channel)
	KeyablePropertyRow(target = target) {
		PropertyFieldRow(label, description = description) {
			HexColorField(
				keyState = keyedFieldStateOf(owner, channel),
				// What is APPLIED, not what is stored: on a keyed channel the static is shadowed by the
				// track, and a pending unkeyed edit lives outside the model altogether.
				value = formatHexColor(displayedChannelColor(owner, channel, stored).toComposeColor()),
				onValueChange = { hex ->
					parseHexColor(hex)?.let { picked ->
						val color = picked.toColorRgb()
						session?.editKeyedChannel(target, ChannelValue.Color(color), changeFor(color)) {
							writeStatic(color)
						}
					}
				},
				modifier = Modifier.fillMaxWidth(),
			)
		}
	}
}

/**
 * One keyable FLAG channel's row: the scalar and color rows' counterpart for a checkbox.
 *
 * A flag snaps to the floor cell rather than blending, which is why it is a channel at all rather than a
 * field on the pivot form - but from the row's point of view it keys exactly like the others.
 *
 * @param String label The checkbox's label.
 * @param String description What the channel does, as the label's tooltip.
 * @param KeyformOwner owner The entity the row edits.
 * @param FormChannel channel The flag channel the row edits.
 * @param Boolean stored The owner's static value.
 * @param EditorSession? session The open document's session, or null.
 * @param Function changeFor The history descriptor for a committed value, whichever branch stores it.
 * @param Function writeStatic Writes the owner's static value (the unkeyed path).
 */
@Composable
private fun KeyableFlagChannelRow(
	label: String,
	description: String,
	owner: KeyformOwner,
	channel: FormChannel,
	stored: Boolean,
	session: EditorSession?,
	changeFor: (Boolean) -> Change,
	writeStatic: (Boolean) -> Unit,
) {
	val target = KeyableTarget(owner, channel)
	KeyablePropertyRow(target = target) {
		PropertyCheckboxRow(
			keyState = keyedFieldStateOf(owner, channel),
			// What is APPLIED: a keyed channel's track shadows the static.
			checked = displayedChannelFlag(owner, channel, stored),
			onCheckedChange = { flag ->
				session?.editKeyedChannel(target, ChannelValue.Flag(flag), changeFor(flag)) { writeStatic(flag) }
			},
			label = label,
			description = description,
		)
	}
}

/**
 * A warp's lattice resolution as an aspect-lockable Rows / Columns pair.  A commit resizes the lattice with its art
 * kept in place and registers the resize on the operation strip; the pair is inert in Pose mode, which shapes the
 * lattice at a pose rather than rewriting every form.
 *
 * @param Deformer.Warp warp The warp the rows describe.
 * @param PropertyContext context The current context.
 */
@Composable
private fun WarpLatticeSizeRows(warp: Deformer.Warp, context: PropertyContext) {
	val session = context.session
	// Read here, called in the commit: the answer is the pointer's state as the release lands.
	val stripArea = LocalOperationStripArea.current
	AspectLockFieldPair(
		firstLabel = stringResource(Res.string.properties_field_lattice_rows),
		firstDescription = stringResource(Res.string.properties_field_lattice_rows_description),
		firstValue = warp.rows,
		secondLabel = stringResource(Res.string.properties_field_lattice_columns),
		secondDescription = stringResource(Res.string.properties_field_lattice_columns_description),
		secondValue = warp.columns,
		range = 1..LATTICE_DIVISION_MAX,
		enabled = session != null && rewritesLattices(context),
		onCommit = { rows, columns -> session?.setLatticeSizeAdjustable(warp.id, rows, columns, stripArea()) },
	)
}

/**
 * Whether the session's mode may rewrite a lattice's every form - resize it or switch its mapping: Object and Edit
 * mode, which edit the rest state, and not Pose mode, which shapes at a pose.
 *
 * @param PropertyContext context The current context.
 * @return Boolean True outside Pose mode.
 */
@Composable
private fun rewritesLattices(context: PropertyContext): Boolean = (context.session?.mode?.collectAsState()?.value ?: EditorMode.Object) != EditorMode.Pose

/**
 * A warp's lattice as a keyable row: the parameters its geometry grid is keyed on, tinted by its keyed state at the
 * displayed pose (on a key, between keys, or holding an unkeyed Pose-mode shape).  The row publishes the warp's
 * geometry track on hover and carries the keyframe menu, so insert and delete over it key the lattice at the pose -
 * the pending shape when there is one - as they do over the keyform sheet's geometry row.
 *
 * @param Deformer.Warp warp The warp the row describes.
 * @param PuppetModel puppet The rig, whose parameters name the axes.
 */
@Composable
private fun WarpLatticeKeyRow(
	warp: Deformer.Warp,
	puppet: PuppetModel,
) {
	val state = keyedLatticeStateOf(warp)
	val colors = LocalUmamoColors.current
	val parameterNameById = remember(puppet) { puppet.parameters.associate { parameter -> parameter.id to parameter.name } }
	val keyedOn = warp.geometryGrid?.axes?.map { axis -> parameterNameById[axis.parameterId] ?: axis.parameterId.raw }.orEmpty()
	val separator = stringResource(Res.string.properties_warp_lattice_separator)
	val summary =
		if (keyedOn.isEmpty()) {
			stringResource(Res.string.properties_warp_lattice_unkeyed)
		} else {
			stringResource(Res.string.properties_warp_lattice_keyed_on, keyedOn.joinToString(separator))
		}
	KeyablePropertyRow(track = KeyformTrackRef.Geometry(KeyformOwner.Deformer(warp.id))) {
		PropertyFieldRow(stringResource(Res.string.properties_warp_lattice), description = stringResource(Res.string.properties_warp_lattice_description)) {
			Text(
				text = summary,
				style = LocalUmamoTypography.current.bodySmall,
				color = state.tint(colors) ?: colors.text,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}

/**
 * A deformer's keyable render channels: opacity, multiply color, screen color.
 *
 * Shared by both subtypes because both carry them and both cascade them.  Listed FIRST in the section, so
 * the channels a rigger keys sit above the structural fields (lattice dimensions, base angle) that are set
 * once and left alone.  Opacity is always shown; the two color rows are each gated on [target] supporting
 * their feature, so the result can be fewer than three rows.
 *
 * @param Deformer deformer The deformer the rows edit.
 * @param EditorSession? session The open document's session, or null.
 * @param RuntimeTarget target The document's runtime-compatibility target, gating the color rows.
 * @return List<PropertyRow> The opacity row, plus each color row the target still supports.
 */
private fun deformerRenderChannelRows(
	deformer: Deformer,
	session: EditorSession?,
	target: RuntimeTarget,
): List<PropertyRow> {
	val owner = KeyformOwner.Deformer(deformer.id)

	return listOfNotNull(
		PropertyRow(terms = listOf(Res.string.properties_field_opacity)) { _ ->
			KeyableScalarChannelRow(
				label = stringResource(Res.string.properties_field_opacity),
				description = stringResource(Res.string.properties_field_opacity_deformer_description),
				owner = owner,
				channel = FormChannel.OPACITY,
				stored = deformer.opacity,
				session = session,
				range = 0f..1f,
				decimals = 3,
				step = 0.05f,
				changeFor = { opacity -> DeformerChange.SetOpacity(deformer.id, opacity) },
				writeStatic = { opacity -> session?.setDeformerOpacity(deformer.id, opacity) },
			)
		},
		// The deformer color cascade is a 4.2 feature (official target dialog), like the drawable tints.
		if (!target.supports(RuntimeFeature.MultiplyColor)) {
			null
		} else {
			PropertyRow(terms = listOf(Res.string.properties_field_multiply_color)) { _ ->
				KeyableColorChannelRow(
					label = stringResource(Res.string.properties_field_multiply_color),
					description = stringResource(Res.string.properties_field_multiply_color_deformer_description),
					owner = owner,
					channel = FormChannel.MULTIPLY_COLOR,
					stored = deformer.multiplyColor,
					session = session,
					changeFor = { color -> DeformerChange.SetMultiplyColor(deformer.id, color) },
					writeStatic = { color -> session?.setDeformerMultiplyColor(deformer.id, color) },
				)
			}
		},
		if (!target.supports(RuntimeFeature.ScreenColor)) {
			null
		} else {
			PropertyRow(terms = listOf(Res.string.properties_field_screen_color)) { _ ->
				KeyableColorChannelRow(
					label = stringResource(Res.string.properties_field_screen_color),
					description = stringResource(Res.string.properties_field_screen_color_deformer_description),
					owner = owner,
					channel = FormChannel.SCREEN_COLOR,
					stored = deformer.screenColor,
					session = session,
					changeFor = { color -> DeformerChange.SetScreenColor(deformer.id, color) },
					writeStatic = { color -> session?.setDeformerScreenColor(deformer.id, color) },
				)
			}
		},
	)
}

/**
 * One keyable SCALAR channel's field row: [KeyableColorChannelRow]'s counterpart for a number field.
 *
 * @param String label The row's field label.
 * @param String description What the channel does, as the label's tooltip.
 * @param KeyformOwner owner The entity the row edits.
 * @param FormChannel channel The scalar channel the row edits.
 * @param Float stored The owner's static value.
 * @param EditorSession? session The open document's session, or null.
 * @param ClosedFloatingPointRange range The field's legal range.
 * @param Int decimals The field's display precision.
 * @param Float step The field's chevron step.
 * @param Function changeFor The history descriptor for a committed value, whichever branch stores it.
 * @param Function writeStatic Writes the owner's static value (the unkeyed path).
 */
@Composable
private fun KeyableScalarChannelRow(
	label: String,
	description: String,
	owner: KeyformOwner,
	channel: FormChannel,
	stored: Float,
	session: EditorSession?,
	range: ClosedFloatingPointRange<Float>,
	decimals: Int,
	step: Float,
	changeFor: (Float) -> Change,
	writeStatic: (Float) -> Unit,
) {
	val target = KeyableTarget(owner, channel)
	KeyablePropertyRow(target = target) {
		PropertyFieldRow(label, description = description) {
			NumberField(
				keyState = keyedFieldStateOf(owner, channel),
				// What is APPLIED: a keyed channel's track shadows the static.
				value = displayedChannelScalar(owner, channel, stored),
				onValueChange = { value: Float ->
					session?.editKeyedChannel(target, ChannelValue.Scalar(value), changeFor(value)) {
						writeStatic(value)
					}
				},
				modifier = Modifier.fillMaxWidth(),
				range = range,
				decimals = decimals,
				step = step,
				// A drag-scrub previews through the pending buffer so the viewport follows the pointer, and
				// commits once on release - the same two-phase contract the parameter sliders have.
				onPreview = { previewed: Float ->
					session?.previewChannelEdit(target, ChannelValue.Scalar(previewed))
				},
				// A cancelled scrub retires its preview the way a commit would, or the last frame would stay
				// pending - the viewport stuck on it and the field tinted as an edit nobody made.
				onScrubCancel = { session?.cancelChannelPreview(target) },
			)
		}
	}
}