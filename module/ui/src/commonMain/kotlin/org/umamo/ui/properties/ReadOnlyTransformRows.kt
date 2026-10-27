package org.umamo.ui.properties

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.transform.MeshBounds
import org.umamo.render.eval.DeformerPlacement
import org.umamo.render.eval.deformerPlacement
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.originRelativeX
import org.umamo.runtime.model.originRelativeZ
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.field.FieldStack
import org.umamo.ui.kit.field.NumberField
import org.umamo.ui.kit.field.PropertyFieldRow
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.transform.partWorldBounds

/*
 * The Transform rows of a part and of a deformer: where each sits at the shown pose, read-only until parts and
 * deformers can be moved from the panel.  A part is placed by the drawables it holds and a warp by its lattice,
 * each as Position and Size like a drawable; a rotation by its pivot, with its own Angle and Scale beside the
 * Base Angle that stays editable (DeformerBaseAngleField).  Like the drawable rows, each row resolves the pose
 * in the composition, so a pose scrub or a mode switch moves the numbers without the model changing.
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

/** A warp's descriptions: the bounds of its lattice. */
private val WARP_BOUNDS_TEXT =
	BoundsRowText(
		Res.string.properties_warp_position_x_description,
		Res.string.properties_warp_position_z_description,
		Res.string.properties_warp_size_x_description,
		Res.string.properties_warp_size_z_description,
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
	// Keyed, because this captures every drawable the part holds through the deformer chain.
	val bounds = remember(context.puppet, shownPose, partId) { partWorldBounds(context.puppet, shownPose, partId) }
	ReadOnlyBoundsRows(context.puppet, bounds, showSize, PART_BOUNDS_TEXT)
}

/**
 * One of a warp deformer's two Transform rows: the Position pair or the Size pair of its lattice.
 *
 * @param PropertyContext context The row's context (its session supplies the live pose).
 * @param DeformerId deformerId The shown warp.
 * @param Boolean showSize False for the Position pair, true for the Size pair.
 */
@Composable
internal fun WarpTransformRows(context: PropertyContext, deformerId: DeformerId, showSize: Boolean) {
	val shownPose = rememberShownPose(context)
	val bounds =
		remember(context.puppet, shownPose, deformerId) {
			(deformerPlacement(context.puppet, shownPose, deformerId) as? DeformerPlacement.Warp)?.let { lattice ->
				MeshBounds(
					(lattice.worldMinX + lattice.worldMaxX) / 2f,
					(lattice.worldMinZ + lattice.worldMaxZ) / 2f,
					lattice.worldMaxX - lattice.worldMinX,
					lattice.worldMaxZ - lattice.worldMinZ,
				)
			}
		}
	ReadOnlyBoundsRows(context.puppet, bounds, showSize, WARP_BOUNDS_TEXT)
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
			UnavailableNote()
		}

		return
	}

	if (showPivot) {
		ReadOnlyFieldStack(
			ReadOnlyField(
				Res.string.properties_field_position_x,
				Res.string.properties_rotation_pivot_x_description,
				puppet.originRelativeX(placement.worldPivotX),
			),
			ReadOnlyField(
				Res.string.properties_field_position_z,
				Res.string.properties_rotation_pivot_z_description,
				puppet.originRelativeZ(placement.worldPivotZ),
			),
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
			UnavailableNote()
		}

		return
	}

	if (showSize) {
		ReadOnlyFieldStack(
			ReadOnlyField(Res.string.properties_field_size_x, text.sizeX, bounds.width),
			ReadOnlyField(Res.string.properties_field_size_z, text.sizeZ, bounds.height),
		)
	} else {
		ReadOnlyFieldStack(
			ReadOnlyField(Res.string.properties_field_position_x, text.positionX, puppet.originRelativeX(bounds.centerX)),
			ReadOnlyField(Res.string.properties_field_position_z, text.positionZ, puppet.originRelativeZ(bounds.centerY)),
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
 * The muted line standing in for a transform the item has none of at the shown pose.
 */
@Composable
private fun UnavailableNote() {
	Text(
		text = stringResource(Res.string.properties_transform_unavailable),
		style = LocalUmamoTypography.current.labelSmall,
		color = LocalUmamoColors.current.textMuted,
	)
}