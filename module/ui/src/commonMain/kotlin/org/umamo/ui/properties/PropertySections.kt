package org.umamo.ui.properties

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.MeshBounds
import org.umamo.edit.Pose
import org.umamo.edit.SelectionTarget
import org.umamo.edit.moveDeformer
import org.umamo.edit.moveOrgChild
import org.umamo.edit.setCanvasSize
import org.umamo.edit.setDeformerBaseAngle
import org.umamo.edit.setDeformerPart
import org.umamo.edit.setDeformerQuadTransform
import org.umamo.edit.setDrawableAlphaBlendMode
import org.umamo.edit.setDrawableBlendMode
import org.umamo.edit.setDrawableCulling
import org.umamo.edit.setDrawableInvertMask
import org.umamo.edit.setDrawableMaskedBy
import org.umamo.edit.setDrawableParentDeformer
import org.umamo.edit.setPartComposite
import org.umamo.edit.setPartDrawOrder
import org.umamo.edit.setPartGroupMode
import org.umamo.edit.setPartSketch
import org.umamo.edit.setWorldOrigin
import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartComposite
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.deformerSelfAndDescendants
import org.umamo.runtime.model.parentPartByPart
import org.umamo.runtime.model.partByDrawable
import org.umamo.ui.graphics.formatHexColor
import org.umamo.ui.graphics.parseHexColor
import org.umamo.ui.graphics.toColorRgb
import org.umamo.ui.graphics.toComposeColor
import org.umamo.ui.kit.Checkbox
import org.umamo.ui.kit.FieldStack
import org.umamo.ui.kit.HexColorField
import org.umamo.ui.kit.NumberField
import org.umamo.ui.kit.RelationField
import org.umamo.ui.kit.RelationListField
import org.umamo.ui.kit.SelectField
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.Tooltip
import org.umamo.ui.kit.button.IconButton
import org.umamo.ui.kit.button.IconButtonAppearance
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.theme.UmamoIcon
import org.umamo.ui.workspace.LocalRelationPick
import org.umamo.ui.workspace.PickKind

/*
 * The property sections.  Each is a stable, top-level [PropertySection] singleton so its id is the catalog
 * key for search and expanded state, and the Data tab can reference the same instances it hands back per
 * selection type.  A section renders a list of [PropertyRow]s so the header search can hide the individual
 * rows that do not match, not just whole sections.
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

/** An unbounded float range: a numeric field that clamps nothing and draws no magnitude fill. */
private val UNBOUNDED_RANGE = Float.NEGATIVE_INFINITY..Float.POSITIVE_INFINITY

/** A half-open float range (min set, no max): clamps below and draws no fill (needs both bounds). */
private val POSITIVE_RANGE = 1f..Float.POSITIVE_INFINITY

/**
 * Space the Size rows reserve at their right edge for the aspect lock that overlays it: the 20dp icon
 * button plus a little breathing room.  Keeping it a named constant is what ties the reservation and the
 * overlaid control to the same width - if they drift, the lock either overlaps the field or floats away.
 */
private val ASPECT_LOCK_GUTTER = 24.dp

/**
 * A labelled Properties field row, Blender-style: the right-aligned label takes the left half and the
 * control fills the right half, so a column of rows aligns and every field spans a consistent width.  The
 * control should [Modifier.fillMaxWidth] so it fills its half.
 *
 * [trailingGutter] reserves space at the RIGHT EDGE OF THE CONTROL for an adornment that sits outside the
 * row (the Size stack's aspect lock).  It shrinks the control only - the label column keeps its half of the
 * full row width, so a row with a gutter still lines up with the plain rows above and below it.  Reserving
 * the space here rather than wrapping the whole row in a narrower box is the difference between the field
 * shrinking and the entire two-column grid shifting.
 *
 * @param String label The localized field label.
 * @param Dp trailingGutter Space reserved after the control for an out-of-row adornment (0 for none).
 * @param Function control The editable control (a fillMaxWidth NumberField, SelectField, etc.).
 */
@Composable
private fun PropertyFieldRow(label: String, trailingGutter: Dp = 0.dp, control: @Composable () -> Unit) {
	Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
		Text(
			text = label,
			style = LocalUmamoTypography.current.bodySmall,
			color = LocalUmamoColors.current.text,
			textAlign = TextAlign.End,
			modifier = Modifier.weight(1f).padding(end = 8.dp),
		)
		Box(modifier = Modifier.weight(1f).padding(end = trailingGutter)) {
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

/**
 * The drawables eligible to be added as clip masks for a composite: every drawable not already applied.
 * Any drawable is a valid mask target (the model has no is-mask flag), so the candidate set is simply the
 * complement of [current].  Pure, so the "add mask" menu contents are unit-testable.
 *
 * @param List drawables Every drawable in the model.
 * @param List current The mask ids already applied to the composite.
 * @return List The drawables not yet masking, in model order.
 */
internal fun maskCandidates(drawables: List<Drawable>, current: List<DrawableId>): List<Drawable> {
	val applied = current.toSet()
	return drawables.filter { drawable -> drawable.id !in applied }
}

/**
 * One entry in a composite's clip-mask list.  A drawable clips directly; a part stands for every drawable
 * in its subtree and is resolved to them only in the derived render tree, so the mask follows the part as
 * its children change (see [org.umamo.runtime.model.PartComposite.maskedByParts]).
 */
private sealed interface MaskEntry {
	/** The entry's display name. */
	val name: String

	/** A drawable whose alpha clips directly. */
	data class OfDrawable(val id: DrawableId, override val name: String) : MaskEntry

	/** A part standing for its descendant drawables. */
	data class OfPart(val id: PartId, override val name: String) : MaskEntry
}

/**
 * The type glyph for a mask entry, so drawable and part masks read apart in the list.
 *
 * @return UmamoIcon The entry's type icon.
 */
private fun MaskEntry.glyph(): UmamoIcon =
	when (this) {
		is MaskEntry.OfDrawable -> LocalUmamoIcons.mesh
		is MaskEntry.OfPart -> LocalUmamoIcons.part
	}

/**
 * A labelled block wrapping a relation list, since a tall list does not fit the two-column field row.
 *
 * @param String label The block's localized label.
 * @param Function content The list to draw beneath it.
 */
@Composable
private fun RelationListBlock(label: String, content: @Composable () -> Unit) {
	Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
		Text(text = label, style = LocalUmamoTypography.current.bodySmall, color = LocalUmamoColors.current.text)
		content()
	}
}

/**
 * The isolated part composite's clip-mask editor: a scrollable, resizable list of the drawables and parts
 * clipping the layer, over a search field whose eyedropper takes a mask straight from the viewport or the
 * outliner.  Every edit routes the whole updated composite through setPartComposite, so it is one undo step
 * and survives a mode round-trip like the other composite fields.  Masks are a set (order does not affect
 * coverage), so the list is not reorderable.
 *
 * A viewport click resolves to the drawable it hits; picking a whole part is done from the list or the
 * outliner, since the viewport hit test is drawable-only.
 *
 * @param Part part The part whose composite is edited.
 * @param PartComposite composite The active (Isolated) composite being edited.
 * @param PropertyContext context The context supplying the model and session.
 */
@Composable
private fun PartMaskEditor(part: Part, composite: PartComposite, context: PropertyContext) {
	val session = context.session
	val relationPick = LocalRelationPick.current
	val owner = "part.maskedBy:${part.id.raw}"
	val apply: (PartComposite) -> Unit = { updated -> session?.setPartComposite(part.id, updated) }

	val entries =
		buildList {
			for (maskId in composite.maskedBy) {
				context.puppet.drawables.firstOrNull { it.id == maskId }?.let { masker ->
					add(MaskEntry.OfDrawable(masker.id, masker.name.ifBlank { masker.id.raw }))
				}
			}
			for (maskPartId in composite.maskedByParts) {
				context.puppet.parts.firstOrNull { it.id == maskPartId }?.let { masker ->
					add(MaskEntry.OfPart(masker.id, masker.name.ifBlank { masker.id.raw }))
				}
			}
		}
	val candidates =
		buildList {
			for (candidate in maskCandidates(context.puppet.drawables, composite.maskedBy)) {
				add(MaskEntry.OfDrawable(candidate.id, candidate.name.ifBlank { candidate.id.raw }))
			}
			// A part cannot mask itself (it would clip the very layer it composites).
			for (candidate in context.puppet.parts) {
				if (candidate.id != part.id && candidate.id !in composite.maskedByParts) {
					add(MaskEntry.OfPart(candidate.id, candidate.name.ifBlank { candidate.id.raw }))
				}
			}
		}
	val addEntry: (MaskEntry) -> Unit = { entry ->
		when (entry) {
			is MaskEntry.OfDrawable -> apply(composite.copy(maskedBy = (composite.maskedBy + entry.id).distinct()))
			is MaskEntry.OfPart -> apply(composite.copy(maskedByParts = (composite.maskedByParts + entry.id).distinct()))
		}
	}

	RelationListBlock(stringResource(Res.string.properties_field_masked_by)) {
		RelationListField(
			entries = entries,
			candidates = candidates,
			label = { entry -> entry.name },
			icon = { entry -> entry.glyph() },
			onAdd = addEntry,
			onRemove = { entry ->
				when (entry) {
					is MaskEntry.OfDrawable -> apply(composite.copy(maskedBy = composite.maskedBy - entry.id))
					is MaskEntry.OfPart -> apply(composite.copy(maskedByParts = composite.maskedByParts - entry.id))
				}
			},
			modifier = Modifier.fillMaxWidth(),
			onPick = {
				relationPick.arm(setOf(PickKind.Drawable, PickKind.Part), owner) { target ->
					when (target) {
						is SelectionTarget.Drawable -> addEntry(MaskEntry.OfDrawable(target.id, target.id.raw))
						is SelectionTarget.Part -> addEntry(MaskEntry.OfPart(target.id, target.id.raw))
						is SelectionTarget.Deformer -> {}
					}
				}
			},
			picking = relationPick.isPickingFor(owner),
			addPlaceholder = stringResource(Res.string.properties_mask_add),
			removeDescription = stringResource(Res.string.properties_mask_remove),
		)
	}
}

/**
 * A drawable's own clip-mask editor, the same list over [org.umamo.runtime.model.Drawable.maskedBy].  Only
 * drawables may clip a drawable (the part extension applies to the composite layer alone), and a drawable
 * may not mask itself.
 *
 * @param Drawable drawable The drawable whose masks are edited.
 * @param PropertyContext context The context supplying the model and session.
 */
@Composable
private fun DrawableMaskEditor(drawable: Drawable, context: PropertyContext) {
	val session = context.session
	val relationPick = LocalRelationPick.current
	val owner = "drawable.maskedBy:${drawable.id.raw}"
	val entries = drawable.maskedBy.mapNotNull { maskId -> context.puppet.drawables.firstOrNull { it.id == maskId } }
	val candidates = maskCandidates(context.puppet.drawables, drawable.maskedBy).filterNot { it.id == drawable.id }
	val addMask: (DrawableId) -> Unit = { maskId ->
		session?.setDrawableMaskedBy(drawable.id, (drawable.maskedBy + maskId).distinct())
	}

	RelationListBlock(stringResource(Res.string.properties_field_masked_by)) {
		RelationListField(
			entries = entries,
			candidates = candidates,
			label = { masker -> masker.name.ifBlank { masker.id.raw } },
			icon = { LocalUmamoIcons.mesh },
			onAdd = { masker -> addMask(masker.id) },
			onRemove = { masker -> session?.setDrawableMaskedBy(drawable.id, drawable.maskedBy - masker.id) },
			modifier = Modifier.fillMaxWidth(),
			onPick = {
				relationPick.arm(setOf(PickKind.Drawable), owner) { target ->
					(target as? SelectionTarget.Drawable)?.let { picked ->
						if (picked.id != drawable.id) {
							addMask(picked.id)
						}
					}
				}
			},
			picking = relationPick.isPickingFor(owner),
			addPlaceholder = stringResource(Res.string.properties_mask_add),
			removeDescription = stringResource(Res.string.properties_mask_remove),
		)
	}
}

/** Document > Canvas: the document canvas size and world origin. */
internal val CanvasSection =
	PropertySection(
		id = "document.canvas",
		title = Res.string.properties_section_canvas,
		rows = { context ->
			val puppet = context.puppet
			val session = context.session
			listOf(
				// Width + height joined into one stacked group; the whole stack is one searchable row.
				PropertyRow(
					terms = listOf(Res.string.properties_field_canvas_width, Res.string.properties_field_canvas_height),
				) { _ ->
					val pixels = stringResource(Res.string.unit_pixels)
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
				},
				// The origin x / y into a second stacked group below it.
				PropertyRow(
					terms = listOf(Res.string.properties_field_origin_x, Res.string.properties_field_origin_z),
				) { _ ->
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
								PropertyFieldRow(stringResource(Res.string.properties_field_origin_z)) {
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
		rows = { _ ->
			listOf(
				PropertyRow(terms = listOf(Res.string.properties_runtime_placeholder)) { _ ->
					PropertyLine(stringResource(Res.string.properties_runtime_placeholder))
				},
			)
		},
	)

/**
 * Object > Transform: the active item's placement.  A drawable has no scalar transform - its placement IS
 * its geometry - so the rows measure its WORLD bounds and write back through the deformer chain (see
 * DrawableWorldTransform.kt for why world, and not the raw base mesh array).
 *
 * Axes are labelled by the DISPLAYED convention (Y+ forward, Z+ up), so the vertical field is Z and maps to
 * world y, which grows upward - the same naming the G / S / R axis lock uses.
 *
 * An item with no transform to show (a Part, a warp deformer, a mesh-less drawable) contributes NO rows,
 * and sectionVisibility then hides the whole section rather than drawing an empty card.
 */
internal val TransformSection =
	PropertySection(
		id = "object.transform",
		title = Res.string.properties_section_transform,
		rows = { context ->
			val drawable = context.activeDrawable()
			val deformer = context.activeDeformer()
			val session = context.session
			val mesh = drawable?.mesh
			if (drawable != null && mesh != null && mesh.vertexCount > 0) {
				listOf(
					PropertyRow(
						terms = listOf(Res.string.properties_field_position_x, Res.string.properties_field_position_z),
					) { rowContext ->
						DrawableTransformRows(rowContext, drawable.id, showSize = false)
					},
					PropertyRow(
						terms = listOf(Res.string.properties_field_size_x, Res.string.properties_field_size_z),
					) { rowContext ->
						DrawableTransformRows(rowContext, drawable.id, showSize = true)
					},
				)
			} else if (deformer is Deformer.Rotation) {
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
			} else {
				// Nothing transformable: contribute no rows so the section hides entirely (see the docblock).
				emptyList()
			}
		},
	)

/**
 * One of the two drawable Transform rows - Position X/Z, or Size X/Z with its aspect lock.
 *
 * Both live in one composable because both need the same two things resolved IN the composition: the live
 * pose (so the fields disable the moment a parameter leaves its default) and the world bounds derived from
 * it.  The section's rows() lambda cannot do that - it is not composable, and it only re-runs when the
 * MODEL changes, so a pose scrub would never reach it.
 *
 * While the pose is off neutral the fields show the current world numbers but are inert: the write path
 * inverts through the deformer chain, which is exact only at the neutral pose, so this is the panel's face
 * of the same guard that blocks a viewport object transform on a posed rig.
 *
 * @param PropertyContext context The row's context (its session supplies the live pose).
 * @param DrawableId drawableId The active drawable.
 * @param Boolean showSize False for the Position pair, true for the Size pair.
 */
@Composable
private fun DrawableTransformRows(context: PropertyContext, drawableId: DrawableId, showSize: Boolean) {
	val session = context.session
	// Collected, not read: the pose changes without the model changing, and the disabled state tracks it.
	val pose: Pose = session?.pose?.collectAsState()?.value ?: emptyMap()
	val transform = drawableWorldTransform(context.puppet, pose, drawableId) ?: return
	val bounds = transform.bounds
	val editable = session != null && transform.editable
	if (showSize) {
		SizeFieldsWithAspectLock(bounds, editable) { newWidth, newHeight ->
			session?.setDrawableWorldSize(drawableId, newWidth, newHeight)
		}
	} else {
		FieldStack(
			listOf(
				{ position ->
					PropertyFieldRow(stringResource(Res.string.properties_field_position_x)) {
						NumberField(
							value = bounds.centerX,
							onValueChange = { newX -> session?.setDrawableWorldCenter(drawableId, newX, bounds.centerY) },
							modifier = Modifier.fillMaxWidth(),
							range = UNBOUNDED_RANGE,
							decimals = 1,
							enabled = editable,
							stackPosition = position,
						)
					}
				},
				{ position ->
					PropertyFieldRow(stringResource(Res.string.properties_field_position_z)) {
						NumberField(
							value = bounds.centerY,
							onValueChange = { newZ -> session?.setDrawableWorldCenter(drawableId, bounds.centerX, newZ) },
							modifier = Modifier.fillMaxWidth(),
							range = UNBOUNDED_RANGE,
							decimals = 1,
							enabled = editable,
							stackPosition = position,
						)
					}
				},
			),
		)
	}
}

/**
 * The Size X / Size Z stack plus its aspect-ratio lock.  Its own composable rather than an inline pair of
 * rows because the lock is stateful and the two fields are coupled through it: while locked, editing one
 * axis scales the other by the same factor, so the mesh keeps its proportions.
 *
 * The lock is transient UI state (a tool preference, not document data), so it lives in a remember and
 * resets when the panel unmounts.  The ratio is read from the CURRENT bounds at commit time rather than
 * captured when the lock was engaged, so it always reflects what the fields are showing.
 *
 * A degenerate axis (zero extent) has no ratio to preserve, so a locked edit against one falls back to
 * changing only the edited axis - and [resizedAboutBoundsCenter] then leaves the degenerate one alone.
 *
 * @param MeshBounds bounds The active drawable's current world bounds (the displayed extents).
 * @param Boolean enabled Whether the fields and the lock accept input (false on a posed rig).
 * @param Function onResize Commits new (width, height) extents as one undo step.
 */
@Composable
private fun SizeFieldsWithAspectLock(bounds: MeshBounds, enabled: Boolean, onResize: (Float, Float) -> Unit) {
	var lockAspect by remember { mutableStateOf(false) }
	val icons = LocalUmamoIcons
	// A locked edit on one axis derives the other from the ratio the mesh currently has.
	val commitWidth: (Float) -> Unit = { newWidth ->
		val scaledHeight =
			if (lockAspect && bounds.width > 0f) {
				bounds.height * (newWidth / bounds.width)
			} else {
				bounds.height
			}
		onResize(newWidth, scaledHeight)
	}
	val commitHeight: (Float) -> Unit = { newHeight ->
		val scaledWidth =
			if (lockAspect && bounds.height > 0f) {
				bounds.width * (newHeight / bounds.height)
			} else {
				bounds.width
			}
		onResize(scaledWidth, newHeight)
	}
	// The lock overlays the gutter the rows reserve, so the fields shrink by exactly the lock's width while
	// the label column keeps its half of the FULL row width - that is what keeps these rows lined up with
	// the Position rows above.
	Box(modifier = Modifier.fillMaxWidth()) {
		FieldStack(
			listOf(
				{ position ->
					PropertyFieldRow(stringResource(Res.string.properties_field_size_x), trailingGutter = ASPECT_LOCK_GUTTER) {
						NumberField(
							value = bounds.width,
							onValueChange = commitWidth,
							modifier = Modifier.fillMaxWidth(),
							range = POSITIVE_RANGE,
							decimals = 1,
							enabled = enabled,
							stackPosition = position,
						)
					}
				},
				{ position ->
					PropertyFieldRow(stringResource(Res.string.properties_field_size_z), trailingGutter = ASPECT_LOCK_GUTTER) {
						NumberField(
							value = bounds.height,
							onValueChange = commitHeight,
							modifier = Modifier.fillMaxWidth(),
							range = POSITIVE_RANGE,
							decimals = 1,
							enabled = enabled,
							stackPosition = position,
						)
					}
				},
			),
		)
		// The chain glyph spans both fields.
		Tooltip(
			text = stringResource(Res.string.properties_transform_lock_aspect),
			modifier = Modifier.align(Alignment.CenterEnd),
		) {
			IconButton(
				icon = if (lockAspect) icons.linked else icons.unlinked,
				contentDescription = stringResource(Res.string.properties_transform_lock_aspect),
				onClick = { lockAspect = !lockAspect },
				active = lockAspect,
				enabled = enabled,
				appearance = IconButtonAppearance.Filled(LocalUmamoShapes.current.small),
			)
		}
	}
}

/**
 * Whether [candidateId] is [partId] itself or sits somewhere beneath it, walking up the parent chain.  Such
 * a part cannot become [partId]'s owner - that would make the tree a cycle.  The walk is bounded by the
 * chain it follows, and the visited guard keeps a malformed tree from looping forever.
 *
 * @param PartId candidateId The part being offered as an owner.
 * @param PartId partId The part being re-homed.
 * @param Map parentOf Nested part id to its owning part (see parentPartByPart).
 * @return Boolean True when the candidate is the part itself or one of its descendants.
 */
private fun isPartSelfOrDescendant(candidateId: PartId, partId: PartId, parentOf: Map<PartId, PartId>): Boolean {
	val visited = mutableSetOf<PartId>()
	var cursor: PartId? = candidateId
	while (cursor != null && visited.add(cursor)) {
		if (cursor == partId) {
			return true
		}
		cursor = parentOf[cursor]
	}
	return false
}

/**
 * The type glyph for a deformer, matching the outliner's warp-versus-rotation split.
 *
 * @param Deformer deformer The deformer to glyph.
 * @return UmamoIcon Its type icon.
 */
private fun deformerIcon(deformer: Deformer): UmamoIcon =
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
private fun PartRelationRow(
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
			picking = relationPick.isPickingFor(owner),
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
private fun DeformerRelationRow(
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
			picking = relationPick.isPickingFor(owner),
		)
	}
}

/** Object > Relations: owning part, parent deformer, masks / children of the active item. */
internal val RelationsSection =
	PropertySection(
		id = "object.relations",
		title = Res.string.properties_section_relations,
		rows = { context ->
			val drawable = context.activeDrawable()
			val deformer = context.activeDeformer()
			val part = context.activePart()
			val session = context.session
			if (drawable != null) {
				listOf(
					PropertyRow(terms = listOf(Res.string.properties_part_ref)) { _ ->
						// Part membership lives only in the org tree, so re-homing a drawable is a tree move.
						// Note it appends at the destination, which changes draw order (Cubism does the same).
						PartRelationRow(
							labelRes = Res.string.properties_part_ref,
							context = context,
							selectedPartId = context.puppet.partByDrawable()[drawable.id],
							owner = "drawable.part:${drawable.id.raw}",
						) { partId -> session?.moveOrgChild(OrgChild.Drawable(drawable.id), partId, null) }
					},
					PropertyRow(terms = listOf(Res.string.properties_parent_deformer)) { _ ->
						DeformerRelationRow(
							labelRes = Res.string.properties_parent_deformer,
							context = context,
							selectedDeformerId = drawable.parentDeformerId,
							owner = "drawable.parentDeformer:${drawable.id.raw}",
						) { deformerId -> session?.setDrawableParentDeformer(drawable.id, deformerId) }
					},
				)
			} else if (deformer != null) {
				// Computed once for the row rather than per candidate, so filtering the list stays linear.
				val cyclicParents = context.puppet.deformerSelfAndDescendants(deformer.id)
				listOf(
					PropertyRow(terms = listOf(Res.string.properties_part_ref)) { _ ->
						PartRelationRow(
							labelRes = Res.string.properties_part_ref,
							context = context,
							selectedPartId = deformer.partId,
							owner = "deformer.part:${deformer.id.raw}",
						) { partId -> session?.setDeformerPart(deformer.id, partId) }
					},
					PropertyRow(terms = listOf(Res.string.properties_parent_deformer)) { _ ->
						DeformerRelationRow(
							labelRes = Res.string.properties_parent_deformer,
							context = context,
							selectedDeformerId = deformer.parent,
							// Nesting a deformer inside its own subtree would make the hierarchy a cycle, so
							// those candidates are dropped rather than offered and then silently refused by
							// withDeformerMoved.  Same query the move guard uses, so the two cannot disagree.
							excluding = { candidate -> candidate.id in cyclicParents },
							owner = "deformer.parent:${deformer.id.raw}",
						) { parentId -> session?.moveDeformer(deformer.id, parentId, null) }
					},
				)
			} else if (part != null) {
				// A part nested under another part shows (and can rebind) its owner; a top-level part reads
				// as unbound, and clearing the field moves it back out to the root.
				val parentOf = context.puppet.parentPartByPart()
				listOf(
					PropertyRow(terms = listOf(Res.string.properties_part_ref)) { _ ->
						PartRelationRow(
							labelRes = Res.string.properties_part_ref,
							context = context,
							selectedPartId = parentOf[part.id],
							// A part may not be nested inside itself or its own descendants; withOrgChildMoved
							// refuses such a move anyway, this just keeps them out of the list.
							excluding = { candidate -> isPartSelfOrDescendant(candidate.id, part.id, parentOf) },
							owner = "part.parent:${part.id.raw}",
						) { parentId -> session?.moveOrgChild(OrgChild.Part(part.id), parentId, null) }
					},
				)
			} else {
				emptyList()
			}
		},
	)

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
		is SelectionTarget.Deformer ->
			when (context.puppet.deformers.firstOrNull { it.id == target.id }) {
				is Deformer.Rotation -> LocalUmamoIcons.rotationDeformer
				else -> LocalUmamoIcons.warpDeformer
			}

		is SelectionTarget.Part -> LocalUmamoIcons.part
		null -> LocalUmamoIcons.mesh
	}
