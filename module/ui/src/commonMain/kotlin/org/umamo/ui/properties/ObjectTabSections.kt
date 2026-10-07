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
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.Pose
import org.umamo.edit.property.previewDeformerBaseAngle
import org.umamo.edit.property.setDeformerBaseAngle
import org.umamo.edit.property.setDeformerPart
import org.umamo.edit.shownPose
import org.umamo.edit.structure.moveDeformer
import org.umamo.edit.structure.moveOrgChild
import org.umamo.edit.transform.MeshBounds
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.deformerSelfAndDescendants
import org.umamo.runtime.model.originRelativeX
import org.umamo.runtime.model.originRelativeZ
import org.umamo.runtime.model.parentPartByPart
import org.umamo.runtime.model.partByDrawable
import org.umamo.runtime.model.partSelfAndDescendants
import org.umamo.runtime.model.worldXFromOriginRelative
import org.umamo.runtime.model.worldZFromOriginRelative
import org.umamo.ui.kit.button.IconButton
import org.umamo.ui.kit.button.IconButtonAppearance
import org.umamo.ui.kit.field.FieldStack
import org.umamo.ui.kit.field.NumberField
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.transform.drawableWorldTransform
import org.umamo.ui.transform.previewDrawableWorldCenter
import org.umamo.ui.transform.previewDrawableWorldSize
import org.umamo.ui.transform.setDrawableParentDeformerKeepingRest
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
						DeformerBaseAngleField(deformer, session)
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
 * Both live in one composable because both need the same things resolved IN the composition: the shown
 * pose (so the fields disable the moment a parameter leaves its default, and show the rest shape the
 * moment Edit mode pins the pose) and the world bounds derived from it.  The section's rows() lambda cannot
 * do that - it is not composable, and it only re-runs when the MODEL changes, so neither a pose scrub nor
 * a mode switch would reach it.
 *
 * The pose is the SHOWN one (EditorMode.shownPose), the same pose the setters evaluate at: in Edit mode
 * the rows measure and edit the rig at rest, which is what the viewport draws there.  While the shown pose
 * is off neutral the fields show the current world numbers but are inert: the write path inverts through
 * the deformer chain, which is exact only at the neutral pose, so this is the panel's face of the same
 * guard that blocks a viewport object transform on a posed rig.
 *
 * A scrub previews: each drag frame's model goes to the renderer through the row's [FieldScrubPreview],
 * built by the setter's preview twin from the same arguments the release commits, so the viewport follows
 * the drag and the whole drag is still one undo step.
 *
 * Position reads from the world axes, so it converts at this boundary: shown values subtract the world
 * origin, and an edited value adds it back.  Only the edited axis converts - the other passes its world
 * value through untouched, because a subtract-then-add can land one float step off and record a spurious
 * move on an axis nobody edited.  Size is a difference and needs no conversion.
 *
 * @param PropertyContext context The row's context (its session supplies the live pose).
 * @param DrawableId drawableId The active drawable.
 * @param Boolean showSize False for the Position pair, true for the Size pair.
 */
@Composable
private fun DrawableTransformRows(context: PropertyContext, drawableId: DrawableId, showSize: Boolean) {
	val session = context.session
	// Collected, not read: the pose and the mode change without the model changing, and both move what the
	// rows show - the pose their numbers and disabled state, the mode whether that pose is pinned to rest.
	val pose: Pose = session?.pose?.collectAsState()?.value ?: emptyMap()
	val mode: EditorMode = session?.mode?.collectAsState()?.value ?: EditorMode.Object
	val shownPose = remember(context.puppet, pose, mode) { mode.shownPose(context.puppet, pose) }
	// Keyed, because this is a full posed evaluation of the drawable's deformer chain - not something to
	// redo when an unrelated recomposition happens to sweep the panel.  (Both Transform rows still evaluate
	// once each when the model or pose genuinely changes; sharing one evaluation across them would mean
	// merging them into a single row and giving up per-row search.)
	val transform =
		remember(context.puppet, shownPose, drawableId) {
			drawableWorldTransform(context.puppet, shownPose, drawableId)
		} ?: return
	val bounds = transform.bounds
	val editable = session != null && transform.editable
	val scrub = rememberFieldScrubPreview(editable)
	if (showSize) {
		SizeFieldsWithAspectLock(
			bounds = bounds,
			enabled = editable,
			onPreviewResize = { newWidth, newHeight ->
				scrub.preview(session?.previewDrawableWorldSize(drawableId, newWidth, newHeight))
			},
			onResize = { newWidth, newHeight ->
				scrub.commit { session?.setDrawableWorldSize(drawableId, newWidth, newHeight) }
			},
		)
	} else {
		val puppet = context.puppet
		FieldStack(
			listOf(
				{ position ->
					PropertyFieldRow(
						stringResource(Res.string.properties_field_position_x),
						description = stringResource(Res.string.properties_field_position_x_description),
					) {
						NumberField(
							value = puppet.originRelativeX(bounds.centerX),
							onValueChange = { newX ->
								scrub.commit {
									session?.setDrawableWorldCenter(drawableId, puppet.worldXFromOriginRelative(newX), bounds.centerY)
								}
							},
							onPreview = { newX ->
								scrub.preview(
									session?.previewDrawableWorldCenter(drawableId, puppet.worldXFromOriginRelative(newX), bounds.centerY),
								)
							},
							modifier = Modifier.fillMaxWidth(),
							range = UNBOUNDED_RANGE,
							decimals = 1,
							enabled = editable,
							stackPosition = position,
						)
					}
				},
				{ position ->
					PropertyFieldRow(
						stringResource(Res.string.properties_field_position_z),
						description = stringResource(Res.string.properties_field_position_z_description),
					) {
						NumberField(
							value = puppet.originRelativeZ(bounds.centerY),
							onValueChange = { newZ ->
								scrub.commit {
									session?.setDrawableWorldCenter(drawableId, bounds.centerX, puppet.worldZFromOriginRelative(newZ))
								}
							},
							onPreview = { newZ ->
								scrub.preview(
									session?.previewDrawableWorldCenter(drawableId, bounds.centerX, puppet.worldZFromOriginRelative(newZ)),
								)
							},
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
 * resets when the panel unmounts.  The ratio is read from the CURRENT bounds rather than captured when the
 * lock was engaged, so it always reflects what the fields are showing.  Nothing commits mid-scrub, so
 * during one those bounds are still the ones the scrub started from, and every frame scales against the
 * same ratio.
 *
 * While one field is scrubbed the other follows it: the extents the scrub implies are held here, and the
 * field not being dragged shows its half (the dragged one shows its own draft).  That is what keeps the
 * locked partner live, and it needs no renderer to do it.
 *
 * A degenerate axis (zero extent) has no ratio to preserve, so a locked edit against one falls back to
 * changing only the edited axis - and [resizedAboutBoundsCenter] then leaves the degenerate one alone.
 *
 * @param MeshBounds bounds The active drawable's current world bounds (the displayed extents).
 * @param Boolean enabled Whether the fields and the lock accept input (false on a posed rig).
 * @param Function onPreviewResize Previews new (width, height) extents for one scrub frame, recording nothing.
 * @param Function onResize Commits new (width, height) extents as one undo step.
 */
@Composable
private fun SizeFieldsWithAspectLock(
	bounds: MeshBounds,
	enabled: Boolean,
	onPreviewResize: (Float, Float) -> Unit,
	onResize: (Float, Float) -> Unit,
) {
	var lockAspect by remember { mutableStateOf(false) }
	// The extents of the scrub in flight, or null with none.  Keyed on enabled: a field disabled mid-scrub
	// drops its draft without committing, and the extents it implied go with it.
	var scrubbedExtents by remember(enabled) { mutableStateOf<WorldExtents?>(null) }
	val icons = LocalUmamoIcons
	// A locked edit on one axis derives the other from the ratio the mesh currently has.  The preview and the
	// commit both resolve through these, so the release lands exactly the extents the last frame showed.
	val extentsForWidth: (Float) -> WorldExtents = { newWidth ->
		val scaledHeight =
			if (lockAspect && bounds.width > 0f) {
				bounds.height * (newWidth / bounds.width)
			} else {
				bounds.height
			}
		WorldExtents(newWidth, scaledHeight)
	}
	val extentsForHeight: (Float) -> WorldExtents = { newHeight ->
		val scaledWidth =
			if (lockAspect && bounds.height > 0f) {
				bounds.width * (newHeight / bounds.height)
			} else {
				bounds.width
			}
		WorldExtents(scaledWidth, newHeight)
	}
	val previewExtents: (WorldExtents) -> Unit = { extents ->
		scrubbedExtents = extents
		onPreviewResize(extents.width, extents.height)
	}
	val commitExtents: (WorldExtents) -> Unit = { extents ->
		scrubbedExtents = null
		onResize(extents.width, extents.height)
	}
	val shownExtents = scrubbedExtents ?: WorldExtents(bounds.width, bounds.height)
	// The lock overlays the gutter the rows reserve, so the fields shrink by exactly the lock's width while
	// the label column keeps its half of the FULL row width - that is what keeps these rows lined up with
	// the Position rows above.
	Box(modifier = Modifier.fillMaxWidth()) {
		FieldStack(
			listOf(
				{ position ->
					PropertyFieldRow(
						stringResource(Res.string.properties_field_size_x),
						description = stringResource(Res.string.properties_field_size_x_description),
						trailingGutter = ASPECT_LOCK_GUTTER,
					) {
						NumberField(
							value = shownExtents.width,
							onValueChange = { newWidth -> commitExtents(extentsForWidth(newWidth)) },
							onPreview = { newWidth -> previewExtents(extentsForWidth(newWidth)) },
							modifier = Modifier.fillMaxWidth(),
							range = DRAWABLE_EXTENT_RANGE,
							decimals = 1,
							enabled = enabled,
							stackPosition = position,
						)
					}
				},
				{ position ->
					PropertyFieldRow(
						stringResource(Res.string.properties_field_size_z),
						description = stringResource(Res.string.properties_field_size_z_description),
						trailingGutter = ASPECT_LOCK_GUTTER,
					) {
						NumberField(
							value = shownExtents.height,
							onValueChange = { newHeight -> commitExtents(extentsForHeight(newHeight)) },
							onPreview = { newHeight -> previewExtents(extentsForHeight(newHeight)) },
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
		// The chain glyph spans both fields.  IconButton tooltips itself from contentDescription, and its own
		// modifier already rides on that tooltip wrapper, so the alignment goes here rather than into a second
		// enclosing Tooltip.
		IconButton(
			icon = if (lockAspect) icons.linked else icons.unlinked,
			contentDescription = stringResource(Res.string.properties_transform_lock_aspect),
			onClick = { lockAspect = !lockAspect },
			modifier = Modifier.align(Alignment.CenterEnd),
			active = lockAspect,
			enabled = enabled,
			appearance = IconButtonAppearance.Filled(LocalUmamoShapes.current.small),
		)
	}
}

/**
 * A drawable's world extents as the Size rows edit them: x across, y up (the panel's Z).
 *
 * @property Float width The world x extent.
 * @property Float height The world y extent.
 */
private class WorldExtents(val width: Float, val height: Float)

/**
 * A rotation deformer's Base Angle field, shared by Object > Transform and the Data tab's deformer
 * section so the two cannot drift apart.
 *
 * A scrub previews through the row's [FieldScrubPreview], so the viewport turns the deformer with the drag,
 * and the release commits once.
 *
 * @param Deformer.Rotation deformer The deformer the field edits.
 * @param EditorSession? session The editing session, or null with none (the field then edits nothing).
 */
@Composable
internal fun DeformerBaseAngleField(deformer: Deformer.Rotation, session: EditorSession?) {
	val scrub = rememberFieldScrubPreview(enabled = true)
	PropertyFieldRow(
		stringResource(Res.string.properties_field_base_angle),
		description = stringResource(Res.string.properties_field_base_angle_description),
	) {
		NumberField(
			value = deformer.baseAngle,
			onValueChange = { newAngle -> scrub.commit { session?.setDeformerBaseAngle(deformer.id, newAngle) } },
			onPreview = { newAngle -> scrub.preview(session?.previewDeformerBaseAngle(deformer.id, newAngle)) },
			modifier = Modifier.fillMaxWidth(),
			range = UNBOUNDED_RANGE,
			decimals = 1,
			unitSuffix = stringResource(Res.string.unit_degrees),
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
							descriptionRes = Res.string.properties_part_ref_description,
							context = context,
							selectedPartId = context.puppet.partByDrawable()[drawable.id],
							owner = "drawable.part:${drawable.id.raw}",
						) { partId -> session?.moveOrgChild(OrgChild.Drawable(drawable.id), partId, null) }
					},
					PropertyRow(terms = listOf(Res.string.properties_parent_deformer)) { _ ->
						DeformerRelationRow(
							labelRes = Res.string.properties_parent_deformer,
							descriptionRes = Res.string.properties_parent_deformer_description,
							context = context,
							selectedDeformerId = drawable.parentDeformerId,
							owner = "drawable.parentDeformer:${drawable.id.raw}",
						) { deformerId -> session?.setDrawableParentDeformerKeepingRest(drawable.id, deformerId) }
					},
				)
			} else if (deformer != null) {
				// Computed once for the row rather than per candidate, so filtering the list stays linear.
				val cyclicParents = context.puppet.deformerSelfAndDescendants(deformer.id)
				listOf(
					PropertyRow(terms = listOf(Res.string.properties_part_ref)) { _ ->
						PartRelationRow(
							labelRes = Res.string.properties_part_ref,
							descriptionRes = Res.string.properties_part_ref_description,
							context = context,
							selectedPartId = deformer.partId,
							owner = "deformer.part:${deformer.id.raw}",
						) { partId -> session?.setDeformerPart(deformer.id, partId) }
					},
					PropertyRow(terms = listOf(Res.string.properties_parent_deformer)) { _ ->
						DeformerRelationRow(
							labelRes = Res.string.properties_parent_deformer,
							descriptionRes = Res.string.properties_parent_deformer_description,
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
							descriptionRes = Res.string.properties_part_ref_description,
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