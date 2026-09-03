package org.umamo.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.umamo.edit.AdjustableOperation
import org.umamo.edit.EditorSession
import org.umamo.edit.OperatorParameter
import org.umamo.edit.withParameter
import org.umamo.ui.kit.NumberField
import org.umamo.ui.kit.SectionHeader
import org.umamo.ui.kit.SelectField
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.properties.PropertyCheckboxRow
import org.umamo.ui.properties.PropertyFieldRow
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes

/*
 * The operation settings strip - Blender's "Adjust Last Operation" panel.  A collapsed header in the
 * bottom-left of the area the last operation ran in (or above the status bar for one that ran
 * nowhere in particular); expanded, one row per parameter, and editing a row re-runs the operation
 * over its own history step.  The rows are rendered from the parameter KINDS, never from the
 * operation, so a new adjustable operation adds a parameter list and label strings, not a pane.
 */

/**
 * The strip's disclosure state, one per window: Blender keeps a single redo panel, and so does the
 * shell.  Session-remembered rather than per area or per operation, so a rigger who opened the
 * strip once finds the next operation's open too.
 */
class OperationStripState {
	/** Whether the strip shows its rows or only its header. */
	var expanded: Boolean by mutableStateOf(false)
}

/** The window's strip state; the shell provides one instance. */
val LocalOperationStrip = staticCompositionLocalOf { OperationStripState() }

/**
 * How far an area's own bottom-left chrome must lift to clear the strip: the strip's height plus its
 * margin while one shows in that area, else zero.  Provided by the strip host around the space
 * body; the viewport's zoom badge reads it, so the two never overlap.
 */
val LocalOperationStripInset = compositionLocalOf { 0.dp }

/** The strip's inset from the area's bottom and left edges. */
private val STRIP_MARGIN = 8.dp

/**
 * The strip's minimum width: a narrow Properties section, so its half-and-half rows read exactly like
 * the panel's while the strip stays a strip.  The strip grows past it to its widest row - a Row's
 * intrinsic width under equal weights is twice its most demanding half - so a long checkbox label
 * widens the strip rather than wrapping inside the field column.
 */
private val STRIP_MIN_WIDTH = 300.dp

/**
 * Hosts the strip for one area over [content]: the space body renders under the strip's inset, and
 * the strip itself draws in the bottom-left whenever the session's adjustable operation names
 * [areaId].  Mounted by the area leaf, so every space kind is covered by the one host.
 *
 * @param String?  areaId  The hosting area's id.
 * @param Function content The space body.
 */
@Composable
internal fun OperationStripHost(areaId: String?, content: @Composable () -> Unit) {
	val session = LocalEditorSession.current
	// Only a record naming THIS area shows here; one naming another area, or none, is that area's.
	val record = session?.adjustableOperation?.collectAsState()?.value?.takeIf { candidate -> candidate.areaId == areaId }
	var stripHeight by remember { mutableStateOf(0.dp) }
	val density = LocalDensity.current
	val inset: Dp = if (record != null) stripHeight + STRIP_MARGIN else 0.dp
	CompositionLocalProvider(LocalOperationStripInset provides inset) {
		content()
	}
	if (session != null && record != null) {
		Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
			OperationStrip(
				record = record,
				session = session,
				modifier =
					Modifier
						.padding(start = STRIP_MARGIN, bottom = STRIP_MARGIN)
						.onSizeChanged { size -> stripHeight = with(density) { size.height.toDp() } },
			)
		}
	}
}

/**
 * The strip for an operation that ran in no particular area: the shell mounts it above the status
 * bar.  Draws nothing while the adjustable operation names an area (the area's host shows it) or
 * while there is none.
 *
 * @param Modifier modifier The layout modifier.
 */
@Composable
internal fun ShellOperationStrip(modifier: Modifier = Modifier) {
	val session = LocalEditorSession.current ?: return
	val record = session.adjustableOperation.collectAsState().value ?: return
	if (record.areaId != null) {
		return
	}
	Box(modifier = modifier.fillMaxWidth().padding(start = STRIP_MARGIN, bottom = 4.dp), contentAlignment = Alignment.BottomStart) {
		OperationStrip(record = record, session = session)
	}
}

/**
 * The strip itself: the operation's label as a disclosure header, and its parameter rows when
 * expanded.  Editing a row publishes the whole parameter list back through
 * [EditorSession.adjustLastOperation], which re-runs the operation.
 *
 * @param AdjustableOperation record   The live record.
 * @param EditorSession       session  The session the record belongs to.
 * @param Modifier            modifier The layout modifier.
 */
@Composable
internal fun OperationStrip(
	record: AdjustableOperation,
	session: EditorSession,
	modifier: Modifier = Modifier,
) {
	val strip = LocalOperationStrip.current
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	Column(
		modifier =
			modifier
				.widthIn(min = STRIP_MIN_WIDTH)
				.width(IntrinsicSize.Max)
				.background(colors.tabBackground, shapes.medium)
				.border(width = 1.dp, color = colors.panelBorder, shape = shapes.medium),
	) {
		SectionHeader(
			label = changeLabel(record.change.labelKey),
			expanded = strip.expanded,
			onToggle = { strip.expanded = !strip.expanded },
		)
		if (strip.expanded) {
			// The Properties section body's own insets and row spacing, so a strip row and a panel row
			// sit identically inside their card.
			Column(
				modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 2.dp, bottom = 8.dp),
				verticalArrangement = Arrangement.spacedBy(4.dp),
			) {
				for (parameter in record.parameters) {
					key(parameter.key) {
						OperationParameterRow(parameter) { updated ->
							session.adjustLastOperation(record.parameters.withParameter(parameter.key, updated))
						}
					}
				}
			}
		}
	}
}

/**
 * One row of the strip, rendered by the parameter's kind through the Properties panel's own row
 * primitives - a right-aligned label over a control filling the right half for the numeric and
 * choice kinds, a lone checkbox in the field column for a flag - so a strip row and a panel row are
 * the same row.  The number fields commit once per edit (a scrub's release, a typed value), which is
 * the one run per adjustment the design promises.
 *
 * @param OperatorParameter parameter The row's parameter.
 * @param Function          onChange  Receives the parameter with its new value.
 */
@Composable
private fun OperationParameterRow(
	parameter: OperatorParameter,
	onChange: (OperatorParameter) -> Unit,
) {
	val label = operatorParameterLabel(parameter.labelKey)
	when (parameter) {
		is OperatorParameter.IntParameter -> {
			val unitSuffix = parameterUnitSuffix(parameter.unit)
			PropertyFieldRow(label) {
				NumberField(
					value = parameter.value,
					onValueChange = { value -> onChange(parameter.copy(value = value)) },
					range = parameter.min..parameter.max,
					modifier = Modifier.fillMaxWidth(),
					step = parameter.step,
					unitSuffix = unitSuffix,
				)
			}
		}
		is OperatorParameter.FloatParameter -> {
			val unitSuffix = parameterUnitSuffix(parameter.unit)
			PropertyFieldRow(label) {
				NumberField(
					value = parameter.value,
					onValueChange = { value -> onChange(parameter.copy(value = value)) },
					range = parameter.min..parameter.max,
					modifier = Modifier.fillMaxWidth(),
					decimals = 2,
					step = parameter.step,
					unitSuffix = unitSuffix,
				)
			}
		}
		is OperatorParameter.BooleanParameter -> {
			PropertyCheckboxRow(
				checked = parameter.value,
				onCheckedChange = { checked -> onChange(parameter.copy(value = checked)) },
				label = label,
			)
		}
		is OperatorParameter.ChoiceParameter -> {
			// Choice labels resolve here, in composable scope, because the dropdown's label lambda is not one.
			val labelByKey =
				parameter.choices.associate { choice ->
					choice.key to (choice.labelKey?.let { labelKey -> operatorParameterLabel(labelKey) } ?: choice.key)
				}
			PropertyFieldRow(label) {
				SelectField(
					selected = parameter.value,
					options = parameter.choices.map { choice -> choice.key },
					label = { key -> labelByKey[key] ?: key },
					onSelect = { key -> onChange(parameter.copy(value = key)) },
					modifier = Modifier.fillMaxWidth(),
				)
			}
		}
	}
}