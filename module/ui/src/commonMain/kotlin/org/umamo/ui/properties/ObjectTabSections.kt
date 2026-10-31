package org.umamo.ui.properties

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.Pose
import org.umamo.edit.property.previewDeformerBaseAngle
import org.umamo.edit.property.setDeformerBaseAngle
import org.umamo.edit.property.setDeformerPart
import org.umamo.edit.shownPose
import org.umamo.edit.structure.moveOrgChild
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.deformerSelfAndDescendants
import org.umamo.runtime.model.parentPartByPart
import org.umamo.runtime.model.partByDrawable
import org.umamo.runtime.model.partSelfAndDescendants
import org.umamo.ui.kit.field.AspectLockFieldPair
import org.umamo.ui.kit.field.NumberField
import org.umamo.ui.kit.field.PropertyFieldRow
import org.umamo.ui.resources.*
import org.umamo.ui.transform.ItemWorldTransform
import org.umamo.ui.transform.drawableWorldTransform
import org.umamo.ui.transform.moveDeformerKeepingRest
import org.umamo.ui.transform.moved
import org.umamo.ui.transform.previewDrawableWorldCenter
import org.umamo.ui.transform.previewDrawableWorldSize
import org.umamo.ui.transform.previewWarpWorldCenter
import org.umamo.ui.transform.previewWarpWorldSize
import org.umamo.ui.transform.setDrawableParentKeepingRest
import org.umamo.ui.transform.warpWorldTransform
import org.umamo.ui.workspace.operationstrip.LocalOperationStripArea

/*
 * The Object tab's sections: the universal properties of the shown item - where it sits and what it is bound
 * to - as opposed to the type-specific data in DataTabSections.kt.
 */

/**
 * Object > Transform: the shown item's placement.  A drawable has no scalar transform - its placement IS
 * its geometry - so the rows measure its WORLD bounds and write back through the deformer chain (see
 * DrawableWorldTransform.kt for why world, and not the raw base mesh array).  A warp's rows measure and write
 * its lattice's world box the same way (WarpTransformRows).  A part and a rotation show where they sit too,
 * read-only for now (ReadOnlyTransformRows.kt): a part by the drawables it holds, and a rotation by its pivot
 * with its own angle and scale, above its editable Base Angle.
 *
 * Axes are labelled by the DISPLAYED convention (Y+ forward, Z+ up), so the vertical field is Z and maps to
 * world y, which grows upward - the same naming the G / S / R axis lock uses.
 *
 * A mesh-less drawable has no transform to show, so it contributes NO rows, and sectionVisibility then hides
 * the whole section rather than drawing an empty card.
 */
internal val TransformSection =
	PropertySection(
		id = "object.transform",
		title = Res.string.properties_section_transform,
		rows = { context ->
			val drawable = context.activeDrawable()
			val deformer = context.activeDeformer()
			val part = context.activePart()
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
					PropertyRow(
						terms = listOf(Res.string.properties_field_position_x, Res.string.properties_field_position_z),
					) { rowContext ->
						RotationTransformRows(rowContext, deformer.id, showPivot = true)
					},
					PropertyRow(
						terms = listOf(Res.string.properties_field_angle, Res.string.properties_field_scale),
					) { rowContext ->
						RotationTransformRows(rowContext, deformer.id, showPivot = false)
					},
					PropertyRow(terms = listOf(Res.string.properties_field_base_angle)) { _ ->
						DeformerBaseAngleField(deformer, session)
					},
				)
			} else if (deformer is Deformer.Warp) {
				listOf(
					PropertyRow(
						terms = listOf(Res.string.properties_field_position_x, Res.string.properties_field_position_z),
					) { rowContext ->
						WarpTransformRows(rowContext, deformer.id, showSize = false)
					},
					PropertyRow(
						terms = listOf(Res.string.properties_field_size_x, Res.string.properties_field_size_z),
					) { rowContext ->
						WarpTransformRows(rowContext, deformer.id, showSize = true)
					},
				)
			} else if (part != null) {
				listOf(
					PropertyRow(
						terms = listOf(Res.string.properties_field_position_x, Res.string.properties_field_position_z),
					) { rowContext ->
						PartTransformRows(rowContext, part.id, showSize = false)
					},
					PropertyRow(
						terms = listOf(Res.string.properties_field_size_x, Res.string.properties_field_size_z),
					) { rowContext ->
						PartTransformRows(rowContext, part.id, showSize = true)
					},
				)
			} else {
				// Nothing transformable: contribute no rows so the section hides entirely (see the docblock).
				emptyList()
			}
		},
	)

/**
 * The writes one item's Transform rows make, each in world values: a Position commit (registering the operation strip
 * in the area given) and its scrub preview, and a Size commit and its preview.  A drawable's rows and a warp's differ
 * only in these and in their descriptions.
 *
 * @property Function commitCenter Moves the item's bounds center to (x, z), registering the strip in the area.
 * @property Function previewCenter The model that move would record, or null.
 * @property Function commitSize Resizes the item's bounds to (width, height), registering the strip in the area.
 * @property Function previewSize The model that resize would record, or null.
 */
private class TransformRowWrites(
	val commitCenter: EditorSession.(Float, Float, String?) -> Unit,
	val previewCenter: EditorSession.(Float, Float) -> PuppetModel?,
	val commitSize: EditorSession.(Float, Float, String?) -> Unit,
	val previewSize: EditorSession.(Float, Float) -> PuppetModel?,
)

/**
 * The four field descriptions of one item kind's Transform rows.
 *
 * @property StringResource sizeX The Size X description.
 * @property StringResource sizeZ The Size Z description.
 * @property StringResource positionX The Position X description.
 * @property StringResource positionZ The Position Z description.
 */
private class TransformRowDescriptions(
	val sizeX: StringResource,
	val sizeZ: StringResource,
	val positionX: StringResource,
	val positionZ: StringResource,
)

/** A drawable's Transform row descriptions. */
private val DRAWABLE_ROW_DESCRIPTIONS =
	TransformRowDescriptions(
		Res.string.properties_field_size_x_description,
		Res.string.properties_field_size_z_description,
		Res.string.properties_field_position_x_description,
		Res.string.properties_field_position_z_description,
	)

/** A warp's Transform row descriptions. */
private val WARP_ROW_DESCRIPTIONS =
	TransformRowDescriptions(
		Res.string.properties_warp_size_x_description,
		Res.string.properties_warp_size_z_description,
		Res.string.properties_warp_position_x_description,
		Res.string.properties_warp_position_z_description,
	)

/**
 * The editor mode the Transform rows read, collected so a mode switch reaches them; Object mode with no session.
 *
 * @param PropertyContext context The row's context.
 * @return EditorMode The mode.
 */
@Composable
private fun rememberRowMode(context: PropertyContext): EditorMode = context.session?.mode?.collectAsState()?.value ?: EditorMode.Object

/**
 * One of a drawable's two Transform rows - Position X/Z, or Size X/Z with its aspect lock - over its art's world box
 * ([TransformRows]).  A drawable without a mesh has no rows.
 *
 * @param PropertyContext context The row's context (its session supplies the live pose and mode).
 * @param DrawableId drawableId The shown drawable.
 * @param Boolean showSize False for the Position pair, true for the Size pair.
 */
@Composable
private fun DrawableTransformRows(context: PropertyContext, drawableId: DrawableId, showSize: Boolean) {
	val shownPose = rememberShownPose(context)
	val mode = rememberRowMode(context)
	// Keyed, because this is a full posed evaluation of the drawable's deformer chain - not something to
	// redo when an unrelated recomposition happens to sweep the panel.  (Both Transform rows still evaluate
	// once each when the model or pose genuinely changes; sharing one evaluation across them would mean
	// merging them into a single row and giving up per-row search.)
	val transform = remember(context.puppet, shownPose, mode, drawableId) { drawableWorldTransform(context.puppet, shownPose, drawableId, mode) } ?: return
	val writes =
		remember(drawableId) {
			TransformRowWrites(
				commitCenter = { centerX, centerZ, areaId -> setDrawableWorldCenterAdjustable(drawableId, centerX, centerZ, areaId) },
				previewCenter = { centerX, centerZ -> previewDrawableWorldCenter(drawableId, centerX, centerZ) },
				commitSize = { width, height, areaId -> setDrawableWorldSizeAdjustable(drawableId, width, height, areaId) },
				previewSize = { width, height -> previewDrawableWorldSize(drawableId, width, height) },
			)
		}

	TransformRows(context, transform, DRAWABLE_ROW_DESCRIPTIONS, writes, showSize)
}

/**
 * One of a warp's two Transform rows over its lattice's world box ([TransformRows]).  An edit moves the warp as a
 * whole, every form it carries, so its art follows.  When the pose does not draw the warp there is no box: the
 * Position row shows one note for the pair.
 *
 * @param PropertyContext context The row's context (its session supplies the live pose and mode).
 * @param DeformerId deformerId The shown warp.
 * @param Boolean showSize False for the Position pair, true for the Size pair.
 */
@Composable
private fun WarpTransformRows(context: PropertyContext, deformerId: DeformerId, showSize: Boolean) {
	val shownPose = rememberShownPose(context)
	val mode = rememberRowMode(context)
	val transform = remember(context.puppet, shownPose, mode, deformerId) { warpWorldTransform(context.puppet, shownPose, deformerId, mode) }

	if (transform == null) {
		// One note for the pair of rows, not one each.
		if (!showSize) {
			MutedNote(Res.string.properties_transform_unavailable)
		}

		return
	}

	val writes =
		remember(deformerId) {
			TransformRowWrites(
				commitCenter = { centerX, centerZ, areaId -> setWarpWorldCenterAdjustable(deformerId, centerX, centerZ, areaId) },
				previewCenter = { centerX, centerZ -> previewWarpWorldCenter(deformerId, centerX, centerZ) },
				commitSize = { width, height, areaId -> setWarpWorldSizeAdjustable(deformerId, width, height, areaId) },
				previewSize = { width, height -> previewWarpWorldSize(deformerId, width, height) },
			)
		}

	TransformRows(context, transform, WARP_ROW_DESCRIPTIONS, writes, showSize)
}

/**
 * One of an item's two Transform rows - Position X/Z, or Size X/Z with its aspect lock - over its world box: the rows
 * a drawable and a warp share.
 *
 * The box is resolved IN the composition by the caller, at the shown pose (EditorMode.shownPose, the pose the setters
 * evaluate at), so the fields follow a pose scrub and a mode switch; the section's rows() lambda cannot, since it is
 * not composable and re-runs only when the model changes.  The rows write only in Object mode at the neutral pose
 * ([writesWholeObjects]): elsewhere they show the current world numbers and are inert, the panel's face of the guard
 * that blocks a viewport object transform there.
 *
 * A scrub previews: each drag frame's model goes to the renderer through the row's [FieldScrubPreview], built by the
 * setter's preview twin from the same arguments the release commits, so the viewport follows the drag and the whole
 * drag is still one undo step.  The commit registers the operation strip the way the viewport's G / S do (see
 * TransformRowAdjust.kt), in the area the shell's routing names for a panel.
 *
 * Position reads from the world axes, so it converts at this boundary: shown values subtract the world origin, and an
 * edited value adds it back.  Only the edited axis converts - the other passes its world value through untouched,
 * because a subtract-then-add can land one float step off and record a spurious move on an axis nobody edited.  Size
 * is a difference and needs no conversion.
 *
 * @param PropertyContext context The row's context.
 * @param ItemWorldTransform transform The item's world box and whether the rows may write it.
 * @param TransformRowDescriptions descriptions The fields' descriptions.
 * @param TransformRowWrites writes The item's writes.
 * @param Boolean showSize False for the Position pair, true for the Size pair.
 */
@Composable
private fun TransformRows(
	context: PropertyContext,
	transform: ItemWorldTransform,
	descriptions: TransformRowDescriptions,
	writes: TransformRowWrites,
	showSize: Boolean,
) {
	val session = context.session
	val bounds = transform.bounds
	val editable = session != null && transform.editable
	val scrub = rememberFieldScrubPreview(editable)
	// Read here, called in the commit: the answer is the pointer's state as the release lands.
	val stripArea = LocalOperationStripArea.current

	if (showSize) {
		// A degenerate axis (zero extent) has no ratio to preserve, so a locked edit against one changes only the
		// edited axis - and the resize then leaves the degenerate one alone.
		AspectLockFieldPair(
			firstLabel = stringResource(Res.string.properties_field_size_x),
			firstDescription = stringResource(descriptions.sizeX),
			firstValue = bounds.width,
			secondLabel = stringResource(Res.string.properties_field_size_z),
			secondDescription = stringResource(descriptions.sizeZ),
			secondValue = bounds.height,
			range = DRAWABLE_EXTENT_RANGE,
			decimals = 1,
			enabled = editable,
			onCommit = { newWidth, newHeight ->
				scrub.commit { session?.let { live -> writes.commitSize(live, newWidth, newHeight, stripArea()) } }
			},
			onPreview = { newWidth, newHeight ->
				scrub.preview(session?.let { live -> writes.previewSize(live, newWidth, newHeight) })
			},
			onScrubCancel = { scrub.end() },
		)
	} else {
		WorldPositionFields(
			puppet = context.puppet,
			worldX = bounds.centerX,
			worldZ = bounds.centerY,
			xDescription = descriptions.positionX,
			zDescription = descriptions.positionZ,
			enabled = editable,
			onCommit = { axis, worldValue ->
				val (centerX, centerZ) = axis.moved(worldValue, bounds.centerX, bounds.centerY)
				scrub.commit { session?.let { live -> writes.commitCenter(live, centerX, centerZ, stripArea()) } }
			},
			onPreview = { axis, worldValue ->
				val (centerX, centerZ) = axis.moved(worldValue, bounds.centerX, bounds.centerY)
				scrub.preview(session?.let { live -> writes.previewCenter(live, centerX, centerZ) })
			},
			onScrubCancel = { scrub.end() },
		)
	}
}

/**
 * The pose the Transform rows measure at: the rig's pose, or the rest pose while Edit mode pins it
 * (EditorMode.shownPose), which is what the viewport draws.  Collected, not read: the pose and the mode change
 * without the model changing, and both move what the rows show - the pose their numbers (and a drawable's
 * disabled state), the mode whether that pose is pinned to rest.
 *
 * @param PropertyContext context The row's context (its session supplies the live pose and mode).
 * @return Pose The shown pose; the rest pose with no session.
 */
@Composable
internal fun rememberShownPose(context: PropertyContext): Pose {
	val session = context.session
	val pose: Pose = session?.pose?.collectAsState()?.value ?: emptyMap()
	val mode: EditorMode = session?.mode?.collectAsState()?.value ?: EditorMode.Object

	return remember(context.puppet, pose, mode) { mode.shownPose(context.puppet, pose) }
}

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
			onScrubCancel = { scrub.end() },
			modifier = Modifier.fillMaxWidth(),
			range = UNBOUNDED_RANGE,
			decimals = 1,
			unitSuffix = stringResource(Res.string.unit_degrees),
		)
	}
}

/** Object > Relations: owning part, parent deformer, masks / children of the shown item. */
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
						// Read here, called in the commit: the answer is the pointer's state as the pick lands.
						val stripArea = LocalOperationStripArea.current
						DeformerRelationRow(
							labelRes = Res.string.properties_parent_deformer,
							descriptionRes = Res.string.properties_parent_deformer_description,
							context = context,
							selectedDeformerId = drawable.parentDeformerId,
							owner = "drawable.parentDeformer:${drawable.id.raw}",
						) { deformerId -> session?.setDrawableParentKeepingRest(drawable.id, deformerId, stripArea()) }
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
						val stripArea = LocalOperationStripArea.current
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
						) { parentId -> session?.moveDeformerKeepingRest(deformer.id, parentId, null, stripArea()) }
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