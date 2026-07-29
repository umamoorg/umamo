package org.umamo.ui.properties

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.Change
import org.umamo.edit.DeformerChange
import org.umamo.edit.DrawableChange
import org.umamo.edit.EditorSession
import org.umamo.edit.PartChange
import org.umamo.edit.SelectionTarget
import org.umamo.edit.editKeyedChannel
import org.umamo.edit.previewChannelEdit
import org.umamo.edit.setDeformerBaseAngle
import org.umamo.edit.setDeformerFlipX
import org.umamo.edit.setDeformerFlipY
import org.umamo.edit.setDeformerMultiplyColor
import org.umamo.edit.setDeformerOpacity
import org.umamo.edit.setDeformerQuadTransform
import org.umamo.edit.setDeformerScreenColor
import org.umamo.edit.setDrawableAlphaBlendMode
import org.umamo.edit.setDrawableBlendMode
import org.umamo.edit.setDrawableCulling
import org.umamo.edit.setDrawableDrawOrder
import org.umamo.edit.setDrawableInvertMask
import org.umamo.edit.setDrawableMultiplyColor
import org.umamo.edit.setDrawableOpacity
import org.umamo.edit.setDrawableScreenColor
import org.umamo.edit.setPartComposite
import org.umamo.edit.setPartDrawOrder
import org.umamo.edit.setPartGroupMode
import org.umamo.edit.setPartSketch
import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyableTarget
import org.umamo.runtime.model.KeyformOwner
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.displayMultiplyColor
import org.umamo.runtime.model.displayScreenColor
import org.umamo.runtime.model.multiplyColor
import org.umamo.runtime.model.opacity
import org.umamo.runtime.model.screenColor
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
import kotlin.math.roundToInt

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
					// Opacity and draw order are keyable channels like the colours below, each with its own
					// static and its own optional track.  Draw order is a FLOAT here, unlike a part's int:
					// it blends per pose, so a fractional value between two keys is meaningful.
					PropertyRow(terms = listOf(Res.string.properties_field_draw_order)) { _ ->
						KeyableScalarChannelRow(
							label = stringResource(Res.string.properties_field_draw_order),
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
					// The 5.3 per-art-mesh multiply/screen color: a channel with its own static and its own
					// optional keyform track, so the picker writes the static and `I` over the row keys it.
					PropertyRow(terms = listOf(Res.string.properties_field_multiply_color)) { _ ->
						KeyableColorChannelRow(
							label = stringResource(Res.string.properties_field_multiply_color),
							owner = KeyformOwner.Drawable(drawable.id),
							channel = FormChannel.MULTIPLY_COLOR,
							stored = drawable.displayMultiplyColor(),
							session = session,
							changeFor = { color -> DrawableChange.SetMultiplyColor(drawable.id, color) },
							writeStatic = { color -> session?.setDrawableMultiplyColor(drawable.id, color) },
						)
					},
					PropertyRow(terms = listOf(Res.string.properties_field_screen_color)) { _ ->
						KeyableColorChannelRow(
							label = stringResource(Res.string.properties_field_screen_color),
							owner = KeyformOwner.Drawable(drawable.id),
							channel = FormChannel.SCREEN_COLOR,
							stored = drawable.displayScreenColor(),
							session = session,
							changeFor = { color -> DrawableChange.SetScreenColor(drawable.id, color) },
							writeStatic = { color -> session?.setDrawableScreenColor(drawable.id, color) },
						)
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
					deformerRenderChannelRows(deformer, session) +
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
					deformerRenderChannelRows(deformer, session) +
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
							PropertyRow(terms = listOf(Res.string.properties_field_flip_x)) { _ ->
								KeyableFlagChannelRow(
									label = stringResource(Res.string.properties_field_flip_x),
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
									owner = KeyformOwner.Deformer(deformer.id),
									channel = FormChannel.FLIP_Y,
									stored = deformer.flipY,
									session = session,
									changeFor = { flip -> DeformerChange.SetFlipY(deformer.id, flip) },
									writeStatic = { flip -> session?.setDeformerFlipY(deformer.id, flip) },
								)
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
							KeyablePropertyRow(
								target = KeyableTarget(KeyformOwner.Part(part.id), FormChannel.DRAW_ORDER),
							) {
								PropertyFieldRow(stringResource(Res.string.properties_field_draw_order)) {
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
								KeyableScalarChannelRow(
									label = stringResource(Res.string.properties_field_opacity),
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
								KeyableColorChannelRow(
									label = stringResource(Res.string.properties_field_multiply_color),
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

/**
 * One keyable COLOR channel's field row: the label, the hex field with its keyed tint, the
 * track-resolved display value, and the keyed-goes-pending edit routing.
 *
 * ONE composable for every color channel row - drawable or part, multiply or screen.  The routing rule
 * used to be hand-copied per row, and the part rows shipped without it: typing a color on a keyed part
 * channel wrote a static the track shadowed, so the edit appeared to be silently rejected.
 *
 * @param String label The row's field label.
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
	owner: KeyformOwner,
	channel: FormChannel,
	stored: ColorRgb,
	session: EditorSession?,
	changeFor: (ColorRgb) -> Change,
	writeStatic: (ColorRgb) -> Unit,
) {
	val target = KeyableTarget(owner, channel)
	KeyablePropertyRow(target = target) {
		PropertyFieldRow(label) {
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
 * One keyable FLAG channel's row: the scalar and colour rows' counterpart for a checkbox.
 *
 * A flag snaps to the floor cell rather than blending, which is why it is a channel at all rather than a
 * field on the pivot form - but from the row's point of view it keys exactly like the others.
 *
 * @param String label The checkbox's label.
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
		)
	}
}

/**
 * A deformer's three keyable render channels: opacity, multiply color, screen color.
 *
 * Shared by both subtypes because both carry them and both cascade them.  Listed FIRST in the section, so
 * the channels a rigger keys sit above the structural fields (lattice dimensions, base angle) that are set
 * once and left alone.
 *
 * @param Deformer deformer The deformer the rows edit.
 * @param EditorSession? session The open document's session, or null.
 * @return List<PropertyRow> The three rows.
 */
private fun deformerRenderChannelRows(deformer: Deformer, session: EditorSession?): List<PropertyRow> {
	val owner = KeyformOwner.Deformer(deformer.id)
	return listOf(
		PropertyRow(terms = listOf(Res.string.properties_field_opacity)) { _ ->
			KeyableScalarChannelRow(
				label = stringResource(Res.string.properties_field_opacity),
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
		PropertyRow(terms = listOf(Res.string.properties_field_multiply_color)) { _ ->
			KeyableColorChannelRow(
				label = stringResource(Res.string.properties_field_multiply_color),
				owner = owner,
				channel = FormChannel.MULTIPLY_COLOR,
				stored = deformer.multiplyColor,
				session = session,
				changeFor = { color -> DeformerChange.SetMultiplyColor(deformer.id, color) },
				writeStatic = { color -> session?.setDeformerMultiplyColor(deformer.id, color) },
			)
		},
		PropertyRow(terms = listOf(Res.string.properties_field_screen_color)) { _ ->
			KeyableColorChannelRow(
				label = stringResource(Res.string.properties_field_screen_color),
				owner = owner,
				channel = FormChannel.SCREEN_COLOR,
				stored = deformer.screenColor,
				session = session,
				changeFor = { color -> DeformerChange.SetScreenColor(deformer.id, color) },
				writeStatic = { color -> session?.setDeformerScreenColor(deformer.id, color) },
			)
		},
	)
}

/**
 * One keyable SCALAR channel's field row: [KeyableColorChannelRow]'s counterpart for a number field.
 *
 * @param String label The row's field label.
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
		PropertyFieldRow(label) {
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
			)
		}
	}
}
