package org.umamo.ui.workspace

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.ParameterUnit
import org.umamo.ui.model.RepackParameterKeys
import org.umamo.ui.resources.*

/**
 * Maps an [org.umamo.edit.OperatorParameter.labelKey] to its localized row label.  The keys are the
 * ones each adjustable operation's parameter list declares; an unmapped key renders verbatim so a
 * newly added parameter never renders blank.
 *
 * @param String labelKey The parameter's stable label key.
 * @return String The localized label.
 */
@Composable
internal fun operatorParameterLabel(labelKey: String): String =
	when (labelKey) {
		RepackParameterKeys.PAGE_SIZE -> stringResource(Res.string.repack_options_page_size)
		RepackParameterKeys.GUTTER -> stringResource(Res.string.repack_options_gutter)
		RepackParameterKeys.EXTRUDE -> stringResource(Res.string.repack_options_extrude)
		RepackParameterKeys.ALLOW_ROTATION -> stringResource(Res.string.repack_options_allow_rotation)
		RepackParameterKeys.POWER_OF_TWO -> stringResource(Res.string.repack_options_power_of_two)
		RepackParameterKeys.SQUARE_PAGES -> stringResource(Res.string.repack_options_square_pages)
		RepackParameterKeys.SHRINK_PAGES -> stringResource(Res.string.repack_options_shrink_pages)
		RepackParameterKeys.ALPHA_THRESHOLD -> stringResource(Res.string.repack_options_alpha_threshold)
		else -> labelKey
	}

/**
 * The suffix a numeric row shows after its value for [unit], or null for a unitless one.
 *
 * @param ParameterUnit unit The parameter's display unit.
 * @return String? The localized unit suffix.
 */
@Composable
internal fun parameterUnitSuffix(unit: ParameterUnit): String? =
	when (unit) {
		ParameterUnit.None -> null
		ParameterUnit.Pixels -> stringResource(Res.string.unit_pixels)
		ParameterUnit.Degrees -> stringResource(Res.string.unit_degrees)
		ParameterUnit.Percent -> stringResource(Res.string.unit_percent)
	}