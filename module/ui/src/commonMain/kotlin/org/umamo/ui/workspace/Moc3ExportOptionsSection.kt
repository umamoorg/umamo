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
import org.umamo.ui.kit.Checkbox
import org.umamo.ui.kit.FieldRow
import org.umamo.ui.kit.NumberField
import org.umamo.ui.kit.Text
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.export_options_canvas_in_units
import org.umamo.ui.resources.export_options_guide_parts
import org.umamo.ui.resources.export_options_hidden_drawables
import org.umamo.ui.resources.export_options_hidden_parts
import org.umamo.ui.resources.export_options_include_displayinfo
import org.umamo.ui.resources.export_options_include_physics
import org.umamo.ui.resources.export_options_include_userdata
import org.umamo.ui.resources.export_options_pixels_per_unit
import org.umamo.ui.resources.export_options_section_content
import org.umamo.ui.resources.export_options_section_scale
import org.umamo.ui.resources.export_options_section_sidecars
import org.umamo.ui.resources.export_options_title_moc3
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoTypography
import kotlin.math.floor
import kotlin.math.round

/**
 * The MOC3 export's option pane: which objects the bake includes, which sidecar files ride along,
 * and the bake scale.
 *
 * This file is the MOC3 format's whole contribution to the dialog - its option list, its layout,
 * and (beside the session store) its defaults - so a future format's pane is a sibling of this
 * file, not an edit to shared chrome.  Edits accumulate on a local copy and reach the export only
 * through the request's continuation when Export is pressed; Cancel discards them.
 *
 * @param ExportOptionsRequest.Moc3 request The pending request: initial values, availability facts,
 *                                          and the continuation.
 * @param Function onDismiss Clears the pending request; Export runs the continuation first.
 */
@Composable
internal fun Moc3ExportOptionsPane(
	request: ExportOptionsRequest.Moc3,
	onDismiss: () -> Unit,
) {
	var edited by remember(request) { mutableStateOf(request.initial) }
	ExportOptionsCard(
		title = stringResource(Res.string.export_options_title_moc3),
		onCancel = onDismiss,
		onExport = {
			request.onConfirm(edited)
			onDismiss()
		},
	) {
		ExportOptionsSectionLabel(stringResource(Res.string.export_options_section_content))
		Checkbox(
			checked = edited.exportHiddenParts,
			onCheckedChange = { checked -> edited = edited.copy(exportHiddenParts = checked) },
			label = stringResource(Res.string.export_options_hidden_parts),
		)
		Checkbox(
			checked = edited.exportHiddenDrawables,
			onCheckedChange = { checked -> edited = edited.copy(exportHiddenDrawables = checked) },
			label = stringResource(Res.string.export_options_hidden_drawables),
		)
		Checkbox(
			checked = edited.exportGuideImageParts,
			onCheckedChange = { checked -> edited = edited.copy(exportGuideImageParts = checked) },
			label = stringResource(Res.string.export_options_guide_parts),
		)

		ExportOptionsSectionLabel(stringResource(Res.string.export_options_section_sidecars))
		// A pass-through sidecar can only be carried when the import retained one; with nothing to
		// carry the toggle is disabled and shown clear rather than pretending a file will appear.
		Checkbox(
			checked = request.physicsAvailable && edited.includePhysics,
			onCheckedChange = { checked -> edited = edited.copy(includePhysics = checked) },
			label = stringResource(Res.string.export_options_include_physics),
			enabled = request.physicsAvailable,
		)
		Checkbox(
			checked = request.userDataAvailable && edited.includeUserData,
			onCheckedChange = { checked -> edited = edited.copy(includeUserData = checked) },
			label = stringResource(Res.string.export_options_include_userdata),
			enabled = request.userDataAvailable,
		)
		Checkbox(
			checked = edited.includeDisplayInfo,
			onCheckedChange = { checked -> edited = edited.copy(includeDisplayInfo = checked) },
			label = stringResource(Res.string.export_options_include_displayinfo),
		)

		ExportOptionsSectionLabel(stringResource(Res.string.export_options_section_scale))
		val pixelsPerUnit = edited.pixelsPerUnitOverride ?: request.canvasWidth
		FieldRow(label = stringResource(Res.string.export_options_pixels_per_unit)) {
			NumberField(
				value = pixelsPerUnit,
				onValueChange = { value -> edited = edited.copy(pixelsPerUnitOverride = value) },
				range = 1f..1_000_000f,
				modifier = Modifier.width(120.dp),
				decimals = 2,
				showFill = false,
			)
		}
		// A live sanity readout: what the canvas measures in model units at the edited scale, which
		// is the number a runtime's stage works in.
		Text(
			text =
				stringResource(
					Res.string.export_options_canvas_in_units,
					formatCanvasUnits(request.canvasWidth / pixelsPerUnit),
					formatCanvasUnits(request.canvasHeight / pixelsPerUnit),
				),
			style = LocalUmamoTypography.current.bodySmall,
			color = LocalUmamoColors.current.textMuted,
		)
	}
}

/**
 * Formats a canvas dimension in model units: two decimals, trimmed when whole.
 *
 * @param Float value The dimension in units.
 * @return String The display form.
 */
private fun formatCanvasUnits(value: Float): String {
	val rounded = round(value * 100f) / 100f
	return if (rounded == floor(rounded)) {
		rounded.toInt().toString()
	} else {
		rounded.toString()
	}
}
