package org.umamo.ui.properties

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.kit.field.FieldStack
import org.umamo.ui.kit.field.NumberField
import org.umamo.ui.kit.field.PropertyFieldRow
import org.umamo.ui.resources.*
import org.umamo.ui.viewport.uv.TexelAxis

/**
 * The joined Position X / Position Y pair over a position on a UV surface, in texels measured from the surface's
 * top-left with y down, as an image editor, the operation strip's UV rows, and a tile's placement read one.  The
 * UV editor's counterpart of [WorldPositionFields]: a typed, stepped, or scrubbed value reaches the caller on the
 * axis edited alone, so the caller can leave the other axis exactly as it was.
 *
 * @param Float texelX The position's x in texels.
 * @param Float texelY The position's y in texels, down from the top row.
 * @param StringResource xDescription What Position X measures, for its tooltip.
 * @param StringResource yDescription What Position Y measures, for its tooltip.
 * @param Boolean enabled Whether the fields accept input; disabled, they only show the position.
 * @param Function onCommit Called with the axis edited and its new texel value: a typed value, a chevron step, or a
 *   scrub's release.
 * @param Function? onPreview Called with the axis and its texel value on each frame of a scrub, or null when only
 *   the field shows the scrub until its release commits.
 * @param Function? onScrubCancel Called when a scrub is cancelled, or null with nothing to put back.
 */
@Composable
internal fun TexelPositionFields(
	texelX: Float,
	texelY: Float,
	xDescription: StringResource,
	yDescription: StringResource,
	enabled: Boolean = true,
	onCommit: (TexelAxis, Float) -> Unit = { _, _ -> },
	onPreview: ((TexelAxis, Float) -> Unit)? = null,
	onScrubCancel: (() -> Unit)? = null,
) {
	FieldStack(
		listOf(
			{ position ->
				PropertyFieldRow(stringResource(Res.string.properties_field_position_x), description = stringResource(xDescription)) {
					NumberField(
						value = texelX,
						onValueChange = { newX -> onCommit(TexelAxis.X, newX) },
						onPreview = onPreview?.let { preview -> { newX -> preview(TexelAxis.X, newX) } },
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
				PropertyFieldRow(stringResource(Res.string.properties_field_position_y), description = stringResource(yDescription)) {
					NumberField(
						value = texelY,
						onValueChange = { newY -> onCommit(TexelAxis.Y, newY) },
						onPreview = onPreview?.let { preview -> { newY -> preview(TexelAxis.Y, newY) } },
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