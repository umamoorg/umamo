package org.umamo.ui.properties

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.MeshBounds
import org.umamo.edit.Pose
import org.umamo.edit.moveDeformer
import org.umamo.edit.moveOrgChild
import org.umamo.edit.setDeformerBaseAngle
import org.umamo.edit.setDeformerPart
import org.umamo.edit.setDrawableParentDeformer
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.deformerSelfAndDescendants
import org.umamo.runtime.model.parentPartByPart
import org.umamo.runtime.model.partByDrawable
import org.umamo.runtime.model.partSelfAndDescendants
import org.umamo.ui.kit.FieldStack
import org.umamo.ui.kit.NumberField
import org.umamo.ui.kit.Tooltip
import org.umamo.ui.kit.button.IconButton
import org.umamo.ui.kit.button.IconButtonAppearance
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.transform.drawableWorldTransform
import org.umamo.ui.transform.setDrawableWorldCenter
import org.umamo.ui.transform.setDrawableWorldSize

/*
 * The Object tab's sections: the universal properties of whatever single item is active - where it sits and
 * what it is bound to - as opposed to the type-specific data in DataTabSections.kt.
 */

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
	// Keyed, because this is a full posed evaluation of the drawable's deformer chain - not something to
	// redo when an unrelated recomposition happens to sweep the panel.  (Both Transform rows still evaluate
	// once each when the model or pose genuinely changes; sharing one evaluation across them would mean
	// merging them into a single row and giving up per-row search.)
	val transform =
		remember(context.puppet, pose, drawableId) {
			drawableWorldTransform(context.puppet, pose, drawableId)
		} ?: return
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
							range = DRAWABLE_EXTENT_RANGE,
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
							range = DRAWABLE_EXTENT_RANGE,
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
				// Computed once for the row, not per candidate: withOrgChildMoved refuses a move into this set
				// anyway, and filtering on the same query keeps an illegal target out of the list entirely.
				val cyclicOwners = context.puppet.partSelfAndDescendants(part.id)
				listOf(
					PropertyRow(terms = listOf(Res.string.properties_part_ref)) { _ ->
						PartRelationRow(
							labelRes = Res.string.properties_part_ref,
							context = context,
							selectedPartId = parentOf[part.id],
							excluding = { candidate -> candidate.id in cyclicOwners },
							owner = "part.parent:${part.id.raw}",
						) { parentId -> session?.moveOrgChild(OrgChild.Part(part.id), parentId, null) }
					},
				)
			} else {
				emptyList()
			}
		},
	)
