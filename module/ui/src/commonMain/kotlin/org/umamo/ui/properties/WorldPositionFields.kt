package org.umamo.ui.properties

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.originRelativeX
import org.umamo.runtime.model.originRelativeZ
import org.umamo.runtime.model.worldXFromOriginRelative
import org.umamo.runtime.model.worldZFromOriginRelative
import org.umamo.ui.kit.field.FieldStack
import org.umamo.ui.kit.field.NumberField
import org.umamo.ui.kit.field.PropertyFieldRow
import org.umamo.ui.resources.*
import org.umamo.ui.transform.WorldAxis

/**
 * The joined Position X / Position Z pair over a world position, each shown measured from the world axes as
 * every shown coordinate is.  A typed, stepped, or scrubbed value reaches the caller as world space on the
 * axis edited alone: the other axis's world value passes through untouched, since converting it out and back
 * can land one float step off and move a point on an axis nobody edited.
 *
 * @param PuppetModel puppet The model whose world origin the fields measure from.
 * @param Float worldX The position's world x.
 * @param Float worldZ The position's world z (up).
 * @param StringResource xDescription What Position X measures, for its tooltip.
 * @param StringResource zDescription What Position Z measures, for its tooltip.
 * @param Boolean enabled Whether the fields accept input; disabled, they only show the position.
 * @param Function onCommit Called with the axis edited and its new world value: a typed value, a chevron step,
 *   or a scrub's release.
 * @param Function? onPreview Called with the axis and its world value on each frame of a scrub, or null when a
 *   scrub commits as it goes.
 * @param Function? onScrubCancel Called when a scrub is cancelled, or null with nothing to put back.
 */
@Composable
internal fun WorldPositionFields(
	puppet: PuppetModel,
	worldX: Float,
	worldZ: Float,
	xDescription: StringResource,
	zDescription: StringResource,
	enabled: Boolean = true,
	onCommit: (WorldAxis, Float) -> Unit = { _, _ -> },
	onPreview: ((WorldAxis, Float) -> Unit)? = null,
	onScrubCancel: (() -> Unit)? = null,
) {
	FieldStack(
		listOf(
			{ position ->
				PropertyFieldRow(stringResource(Res.string.properties_field_position_x), description = stringResource(xDescription)) {
					NumberField(
						value = puppet.originRelativeX(worldX),
						onValueChange = { newX -> onCommit(WorldAxis.X, puppet.worldXFromOriginRelative(newX)) },
						onPreview = onPreview?.let { preview -> { newX -> preview(WorldAxis.X, puppet.worldXFromOriginRelative(newX)) } },
						onScrubCancel = onScrubCancel,
						modifier = Modifier.fillMaxWidth(),
						range = UNBOUNDED_RANGE,
						decimals = 1,
						enabled = enabled,
						stackPosition = position,
					)
				}
			},
			{ position ->
				PropertyFieldRow(stringResource(Res.string.properties_field_position_z), description = stringResource(zDescription)) {
					NumberField(
						value = puppet.originRelativeZ(worldZ),
						onValueChange = { newZ -> onCommit(WorldAxis.Z, puppet.worldZFromOriginRelative(newZ)) },
						onPreview = onPreview?.let { preview -> { newZ -> preview(WorldAxis.Z, puppet.worldZFromOriginRelative(newZ)) } },
						onScrubCancel = onScrubCancel,
						modifier = Modifier.fillMaxWidth(),
						range = UNBOUNDED_RANGE,
						decimals = 1,
						enabled = enabled,
						stackPosition = position,
					)
				}
			},
		),
	)
}