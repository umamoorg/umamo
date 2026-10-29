package org.umamo.ui.properties

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.Pose
import org.umamo.edit.transform.MeshBounds
import org.umamo.render.eval.DeformerPlacement
import org.umamo.render.eval.deformerPlacement
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.kit.field.FieldStack
import org.umamo.ui.kit.field.NumberField
import org.umamo.ui.kit.field.PropertyFieldRow
import org.umamo.ui.resources.*
import org.umamo.ui.transform.WorldExtent
import org.umamo.ui.transform.partWorldBounds

/*
 * The Transform rows of a part and of a rotation deformer: where each sits at the shown pose, read-only until they
 * can be moved from the panel.  A part is placed by the drawables it holds, as Position and Size like a drawable; a
 * rotation by its pivot, with its own Angle and Scale beside the Base Angle that stays editable
 * (DeformerBaseAngleField).  A warp's rows are editable (ObjectTabSections.kt).  Like the drawable rows, each row
 * resolves the pose in the composition, so a pose scrub or a mode switch moves the numbers without the model
 * changing.
 */

/**
 * The descriptions of one read-only Position / Size pair, which say what the numbers measure for its kind.
 *
 * @property StringResource positionX Position X's description.
 * @property StringResource positionZ Position Z's description.
 * @property StringResource sizeX Size X's description.
 * @property StringResource sizeZ Size Z's description.
 */
private class BoundsRowText(
	val positionX: StringResource,
	val positionZ: StringResource,
	val sizeX: StringResource,
	val sizeZ: StringResource,
)

/** A part's descriptions: the bounds of what it holds. */
private val PART_BOUNDS_TEXT =
	BoundsRowText(
		Res.string.properties_part_position_x_description,
		Res.string.properties_part_position_z_description,
		Res.string.properties_part_size_x_description,
		Res.string.properties_part_size_z_description,
	)

/**
 * One read-only field: its label, its description, and the value it shows.
 *
 * @property StringResource label The field's label.
 * @property StringResource description The field's description.
 * @property Float value The value shown.
 * @property Int decimals The decimals shown.
 * @property StringResource? unitSuffix The unit after the value, or null with none.
 */
private class ReadOnlyField(
	val label: StringResource,
	val description: StringResource,
	val value: Float,
	val decimals: Int = 1,
	val unitSuffix: StringResource? = null,
)

/**
 * One of a part's two Transform rows: the Position pair or the Size pair of everything it holds.
 *
 * @param PropertyContext context The row's context (its session supplies the live pose).
 * @param PartId partId The shown part.
 * @param Boolean showSize False for the Position pair, true for the Size pair.
 */
@Composable
internal fun PartTransformRows(context: PropertyContext, partId: PartId, showSize: Boolean) {
	val shownPose = rememberShownPose(context)
	// Keyed, because this captures every drawable the part holds through the deformer chain; the context's memo
	// lets the Position row and the Size row share that one capture.
	val bounds = remember(context.puppet, shownPose, partId) { context.partBoundsMemo.boundsOf(context.puppet, shownPose, partId) }
	ReadOnlyBoundsRows(context.puppet, bounds, showSize, PART_BOUNDS_TEXT)
}

/**
 * One of a rotation deformer's two read-only Transform rows: its pivot's Position pair, or its own Angle and
 * Scale.
 *
 * @param PropertyContext context The row's context (its session supplies the live pose).
 * @param DeformerId deformerId The shown rotation.
 * @param Boolean showPivot True for the pivot's Position pair, false for Angle and Scale.
 */
@Composable
internal fun RotationTransformRows(context: PropertyContext, deformerId: DeformerId, showPivot: Boolean) {
	val shownPose = rememberShownPose(context)
	val puppet = context.puppet
	val placement =
		remember(puppet, shownPose, deformerId) {
			deformerPlacement(puppet, shownPose, deformerId) as? DeformerPlacement.Rotation
		}

	if (placement == null) {
		// One note for the pair of rows, not one each.
		if (showPivot) {
			MutedNote(Res.string.properties_transform_unavailable)
		}

		return
	}

	if (showPivot) {
		WorldPositionFields(
			puppet = puppet,
			worldX = placement.worldPivotX,
			worldZ = placement.worldPivotZ,
			xDescription = Res.string.properties_rotation_pivot_x_description,
			zDescription = Res.string.properties_rotation_pivot_z_description,
			enabled = false,
		)
	} else {
		ReadOnlyFieldStack(
			ReadOnlyField(
				Res.string.properties_field_angle,
				Res.string.properties_field_angle_description,
				placement.angleDegrees,
				unitSuffix = Res.string.unit_degrees,
			),
			ReadOnlyField(Res.string.properties_field_scale, Res.string.properties_field_scale_description, placement.scale, decimals = 2),
		)
	}
}

/**
 * The Position pair or the Size pair of a read-only box, Position measured from the world axes.
 *
 * @param PuppetModel puppet The model, whose world origin Position is measured from.
 * @param MeshBounds? bounds The box (x across, y up, the panel's Z), or null when there is none to show.
 * @param Boolean showSize False for the Position pair, true for the Size pair.
 * @param BoundsRowText text What the fields measure for this kind.
 */
@Composable
private fun ReadOnlyBoundsRows(puppet: PuppetModel, bounds: MeshBounds?, showSize: Boolean, text: BoundsRowText) {
	if (bounds == null) {
		// One note for the pair of rows, not one each.
		if (!showSize) {
			MutedNote(Res.string.properties_transform_unavailable)
		}

		return
	}

	if (showSize) {
		ReadOnlyFieldStack(
			ReadOnlyField(Res.string.properties_field_size_x, text.sizeX, bounds.width),
			ReadOnlyField(Res.string.properties_field_size_z, text.sizeZ, bounds.height),
		)
	} else {
		WorldPositionFields(
			puppet = puppet,
			worldX = bounds.centerX,
			worldZ = bounds.centerY,
			xDescription = text.positionX,
			zDescription = text.positionZ,
			enabled = false,
		)
	}
}

/**
 * A joined stack of dimmed fields that show a value and take no input.
 *
 * @param ReadOnlyField fields The fields, top to bottom.
 */
@Composable
private fun ReadOnlyFieldStack(vararg fields: ReadOnlyField) {
	FieldStack(
		fields.map { field ->
			{ position ->
				PropertyFieldRow(stringResource(field.label), description = stringResource(field.description)) {
					NumberField(
						value = field.value,
						onValueChange = {},
						modifier = Modifier.fillMaxWidth(),
						range = UNBOUNDED_RANGE,
						decimals = field.decimals,
						unitSuffix = field.unitSuffix?.let { suffix -> stringResource(suffix) },
						enabled = false,
						stackPosition = position,
					)
				}
			}
		},
	)
}

/**
 * A single-slot cache of the last part bounds a Transform row measured, held on the [PropertyContext] both of a
 * part's rows are handed, so the Position row and the Size row share one capture of everything the part holds.
 * Each row recomposes on its own when the pose moves, and keeps the context it was given, so the second row to
 * ask for the same model, pose, and part gets the first one's answer.
 */
internal class PartBoundsMemo {
	private var model: PuppetModel? = null
	private var pose: Pose? = null
	private var partId: PartId? = null
	private var bounds: MeshBounds? = null

	/**
	 * The world bounds of everything [partId] holds at [pose] (see partWorldBounds), measured again only when
	 * the model, the pose, or the part differs from the last call's.
	 *
	 * @param PuppetModel model The document model.
	 * @param Pose pose The shown pose.
	 * @param PartId partId The part to measure.
	 * @return MeshBounds? The bounds, or null when nothing the part holds is on screen.
	 */
	fun boundsOf(model: PuppetModel, pose: Pose, partId: PartId): MeshBounds? {
		if (model === this.model && partId == this.partId && pose == this.pose) {
			return bounds
		}

		val measured = partWorldBounds(model, pose, partId)
		this.model = model
		this.pose = pose
		this.partId = partId
		bounds = measured

		return measured
	}
}