package org.umamo.ui.workspace

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.kit.FieldRow
import org.umamo.ui.kit.HexColorField
import org.umamo.ui.kit.NumberField
import org.umamo.ui.kit.SelectField
import org.umamo.ui.kit.Text
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.export_options_background_color
import org.umamo.ui.resources.export_options_background_grid
import org.umamo.ui.resources.export_options_background_solid
import org.umamo.ui.resources.export_options_background_transparent
import org.umamo.ui.resources.export_options_image_no_canvas
import org.umamo.ui.resources.export_options_image_no_viewport
import org.umamo.ui.resources.export_options_image_nothing_visible
import org.umamo.ui.resources.export_options_image_scale
import org.umamo.ui.resources.export_options_image_size
import org.umamo.ui.resources.export_options_image_too_large
import org.umamo.ui.resources.export_options_region_canvas
import org.umamo.ui.resources.export_options_region_content
import org.umamo.ui.resources.export_options_region_view
import org.umamo.ui.resources.export_options_section_background
import org.umamo.ui.resources.export_options_section_region
import org.umamo.ui.resources.export_options_title_image
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.viewport.ImageBackground
import org.umamo.ui.viewport.ImageFrameResult
import org.umamo.ui.viewport.ImageRegion
import org.umamo.ui.viewport.MAX_IMAGE_EDGE
import org.umamo.ui.viewport.resolveImageFrame

/** The smallest and largest scale the dialog accepts, in percent. */
private val IMAGE_SCALE_PERCENT_RANGE = 1f..1600f

/**
 * Export Image's option pane: which region to capture and at what scale, and what to draw it over, with a live
 * readout of the image size that the choices make.
 *
 * Only the regions the moment can capture are offered: View when a 2D viewport was the last surface touched,
 * Canvas when the document has a canvas, and Content always.  A remembered region the moment cannot offer
 * opens as Content.  Edits accumulate on a local copy and reach the export only through the request's
 * continuation when Export is pressed; Cancel discards them.
 *
 * @param ExportOptionsRequest.Image request   The pending request: initial values, the rectangles the readout
 *                                             frames, and the continuation.
 * @param Function                   onDismiss Clears the pending request; Export runs the continuation first.
 */
@Composable
internal fun ImageExportOptionsPane(
	request: ExportOptionsRequest.Image,
	onDismiss: () -> Unit,
) {
	val regions =
		listOfNotNull(
			ImageRegion.View.takeIf { request.viewFrame != null },
			ImageRegion.Canvas.takeIf { request.canvasBounds != null },
			ImageRegion.Content,
		)
	var edited by remember(request) {
		mutableStateOf(if (request.initial.region in regions) request.initial else request.initial.copy(region = ImageRegion.Content))
	}
	val regionLabels =
		mapOf(
			ImageRegion.View to stringResource(Res.string.export_options_region_view),
			ImageRegion.Canvas to stringResource(Res.string.export_options_region_canvas),
			ImageRegion.Content to stringResource(Res.string.export_options_region_content),
		)
	val backgroundLabels =
		mapOf(
			ImageBackground.Transparent to stringResource(Res.string.export_options_background_transparent),
			ImageBackground.Solid to stringResource(Res.string.export_options_background_solid),
			ImageBackground.Grid to stringResource(Res.string.export_options_background_grid),
		)
	ExportOptionsCard(
		title = stringResource(Res.string.export_options_title_image),
		onCancel = onDismiss,
		onExport = {
			request.onConfirm(edited)
			onDismiss()
		},
	) {
		ExportOptionsSectionLabel(stringResource(Res.string.export_options_section_region))
		FieldRow(label = stringResource(Res.string.export_options_section_region)) {
			SelectField(
				selected = edited.region,
				options = regions,
				label = { region -> regionLabels.getValue(region) },
				onSelect = { region -> edited = edited.copy(region = region) },
			)
		}
		FieldRow(label = stringResource(Res.string.export_options_image_scale)) {
			NumberField(
				value = edited.scalePercent,
				onValueChange = { value -> edited = edited.copy(scalePercent = value) },
				range = IMAGE_SCALE_PERCENT_RANGE,
				modifier = Modifier.width(120.dp),
				decimals = 0,
				unitSuffix = "%",
				showFill = false,
			)
		}
		ImageSizeReadout(
			resolveImageFrame(
				edited.region,
				edited.scalePercent.coerceIn(IMAGE_SCALE_PERCENT_RANGE) / 100f,
				request.viewFrame,
				request.canvasBounds,
				request.contentBounds,
			),
		)

		ExportOptionsSectionLabel(stringResource(Res.string.export_options_section_background))
		FieldRow(label = stringResource(Res.string.export_options_section_background)) {
			SelectField(
				selected = edited.background,
				options = ImageBackground.entries,
				label = { background -> backgroundLabels.getValue(background) },
				onSelect = { background -> edited = edited.copy(background = background) },
			)
		}
		if (edited.background == ImageBackground.Solid) {
			FieldRow(label = stringResource(Res.string.export_options_background_color)) {
				HexColorField(
					value = edited.solidColorHex,
					onValueChange = { hex -> edited = edited.copy(solidColorHex = hex) },
					modifier = Modifier.width(150.dp),
				)
			}
		}
	}
}

/**
 * The size the choices make, or - in the warning color - why they make no image at all.
 *
 * @param ImageFrameResult framing The framed choices.
 */
@Composable
private fun ImageSizeReadout(framing: ImageFrameResult) {
	val colors = LocalUmamoColors.current
	val text =
		when (framing) {
			is ImageFrameResult.Framed -> stringResource(Res.string.export_options_image_size, framing.frame.width, framing.frame.height)
			ImageFrameResult.NoViewport -> stringResource(Res.string.export_options_image_no_viewport)
			ImageFrameResult.NoCanvas -> stringResource(Res.string.export_options_image_no_canvas)
			ImageFrameResult.NothingVisible -> stringResource(Res.string.export_options_image_nothing_visible)
			is ImageFrameResult.TooLarge -> stringResource(Res.string.export_options_image_too_large, framing.width, framing.height, MAX_IMAGE_EDGE)
		}
	Text(
		text = text,
		style = LocalUmamoTypography.current.bodySmall,
		color = if (framing is ImageFrameResult.Framed) colors.textMuted else colors.signalBad,
	)
}