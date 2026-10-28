package org.umamo.ui.workspace.operationstrip

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
import org.umamo.ui.kit.container.SectionHeader
import org.umamo.ui.kit.field.AspectLockFieldPair
import org.umamo.ui.kit.field.NumberField
import org.umamo.ui.kit.field.PropertyCheckboxRow
import org.umamo.ui.kit.field.PropertyFieldRow
import org.umamo.ui.kit.field.SelectField
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.workspace.LocalOperationStripInset
import org.umamo.ui.workspace.SpaceKind
import org.umamo.ui.workspace.changeLabel
import org.umamo.ui.workspace.hostsOperationStrip

/*
 * The operation settings strip - Blender's "Adjust Last Operation" panel.  A collapsed header in the
 * bottom-left of the area the last operation ran in; expanded, one row per parameter, and editing a
 * row re-runs the operation over its own history step.  The rows are rendered from the parameter
 * KINDS, never from the operation, so a new adjustable operation adds a parameter list and label
 * strings, not a pane.  Two real-valued parameters the first names as its ratio partner (a Scale's
 * factors) render as one pair with an aspect lock, the same control the Properties panel's Size uses.
 *
 * The strip lives in that one area and nowhere else - Blender's redo panel is a region of the editor
 * the operator ran in.  A record naming an area outside the active workspace's tree (the rigger
 * switched workspaces), an area that no longer hosts a work surface, or no area at all (the workspace
 * has no 2D viewport or UV editor) shows nowhere; it shows again when its area is back on screen.
 *
 * The labels for the strip's rows are in OperationStripLabels.kt.  Which spaces host the strip
 * (SpaceKind.hostsOperationStrip) and the inset the strip claims (LocalOperationStripInset) are at the
 * workspace root, because command routing and the viewport read them.
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
 * Where a panel-driven operation shows its strip: the same answer the command routing gives a command
 * fired from a panel (the hovered work surface, else the last one the pointer touched, else the
 * workspace's first), or null when the workspace has none.  Called as the operation commits, never read
 * during composition - it answers from the pointer's dispatch-time state.  Defaults to no area, so a
 * panel hosted without the shell registers a record that shows nowhere.
 */
val LocalOperationStripArea = staticCompositionLocalOf<() -> String?> { { null } }

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
 * [areaId] and [kind] hosts the strip.  Mounted by every area leaf, so the gate lives in one place
 * rather than in each space - and it is the ONLY place the strip is drawn, so a record whose area is
 * not composed (another workspace is active, or the area closed) or names no area shows nowhere.
 *
 * @param String?   areaId  The hosting area's id.
 * @param SpaceKind kind    The space the area currently hosts.
 * @param Function  content The space body.
 */
@Composable
internal fun OperationStripHost(areaId: String?, kind: SpaceKind, content: @Composable () -> Unit) {
	val session = LocalEditorSession.current
	// Only a record naming THIS area shows here, and only while the area is a work surface; one naming
	// another area, or none, is not this area's to show.
	val record =
		session?.adjustableOperation?.collectAsState()?.value?.takeIf { candidate -> candidate.areaId == areaId && kind.hostsOperationStrip }
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
				for (row in operationStripRows(record.parameters)) {
					key(row.key) {
						when (row) {
							is OperationStripRow.Single -> {
								OperationParameterRow(row.parameter) { updated ->
									session.adjustLastOperation(record.parameters.withParameter(row.parameter.key, updated), adjustedKey = row.parameter.key)
								}
							}

							is OperationStripRow.RatioPair -> {
								// A pair adjusts as one setting, named by its first row: a locked edit sets both.
								OperationParameterPair(row.first, row.second) { first, second ->
									session.adjustLastOperation(
										record.parameters.withParameter(first.key, first).withParameter(second.key, second),
										adjustedKey = first.key,
									)
								}
							}
						}
					}
				}
			}
		}
	}
}

/**
 * One row of the strip: a lone parameter, or two real-valued parameters drawn as one ratio-lockable pair.
 */
internal sealed interface OperationStripRow {
	/** The key the row composes under: its first parameter's. */
	val key: String

	/**
	 * A lone parameter, rendered by its kind.
	 *
	 * @property OperatorParameter parameter The parameter.
	 */
	data class Single(val parameter: OperatorParameter) : OperationStripRow {
		override val key: String get() = parameter.key
	}

	/**
	 * Two parameters drawn as one [AspectLockFieldPair]: [first] names [second] as its ratio partner.
	 *
	 * @property OperatorParameter.FloatParameter first  The first parameter.
	 * @property OperatorParameter.FloatParameter second The parameter right after it.
	 */
	data class RatioPair(val first: OperatorParameter.FloatParameter, val second: OperatorParameter.FloatParameter) : OperationStripRow {
		override val key: String get() = first.key
	}
}

/**
 * The strip's rows for [parameters], in order: a real-valued parameter whose ratio partner is the real-valued
 * parameter right after it pairs with it, and every other parameter stands alone - a partner key naming
 * anything else is ignored.
 *
 * @param List parameters The record's parameters, in display order.
 * @return List The rows.
 */
internal fun operationStripRows(parameters: List<OperatorParameter>): List<OperationStripRow> {
	val rows = ArrayList<OperationStripRow>()
	var parameterIndex = 0

	while (parameterIndex < parameters.size) {
		val parameter = parameters[parameterIndex]
		val partner = parameters.getOrNull(parameterIndex + 1)

		if (parameter is OperatorParameter.FloatParameter && partner is OperatorParameter.FloatParameter && parameter.ratioPartnerKey == partner.key) {
			rows.add(OperationStripRow.RatioPair(parameter, partner))
			parameterIndex += 2
		} else {
			rows.add(OperationStripRow.Single(parameter))
			parameterIndex += 1
		}
	}

	return rows
}

/**
 * One row of the strip, rendered by the parameter's kind through the Properties panel's own row
 * primitives - a right-aligned label over a control filling the right half for the numeric and
 * choice kinds, a lone checkbox in the field column for a flag - so a strip row and a panel row are
 * the same row, description tooltip included.  The number fields commit once per edit (a scrub's
 * release, a typed value), which is the one run per adjustment the design promises.
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
	val description = operatorParameterDescription(parameter.labelKey)

	when (parameter) {
		is OperatorParameter.IntParameter -> {
			val unitSuffix = parameterUnitSuffix(parameter.unit)
			PropertyFieldRow(label, description = description) {
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
			PropertyFieldRow(label, description = description) {
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
				description = description,
			)
		}

		is OperatorParameter.ChoiceParameter -> {
			// Choice labels resolve here, in composable scope, because the dropdown's label lambda is not one.
			val labelByKey =
				parameter.choices.associate { choice ->
					choice.key to (choice.labelKey?.let { labelKey -> operatorParameterLabel(labelKey) } ?: choice.key)
				}
			PropertyFieldRow(label, description = description) {
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

/**
 * A ratio-lockable pair of the strip, drawn through the kit's [AspectLockFieldPair] - the control the Properties
 * panel's Size uses - so a strip Scale and a panel Size are the same pair, lock included.  Both fields take the
 * first parameter's range, step, and unit, as a pair measures one quantity along two axes, and commit once per
 * edit as [OperationParameterRow]'s number fields do.
 *
 * @param OperatorParameter.FloatParameter first    The first parameter, naming [second] as its ratio partner.
 * @param OperatorParameter.FloatParameter second   The parameter right after it.
 * @param Function                         onChange Receives both parameters with their new values.
 */
@Composable
private fun OperationParameterPair(
	first: OperatorParameter.FloatParameter,
	second: OperatorParameter.FloatParameter,
	onChange: (OperatorParameter.FloatParameter, OperatorParameter.FloatParameter) -> Unit,
) {
	AspectLockFieldPair(
		firstLabel = operatorParameterLabel(first.labelKey),
		firstDescription = operatorParameterDescription(first.labelKey),
		firstValue = first.value,
		secondLabel = operatorParameterLabel(second.labelKey),
		secondDescription = operatorParameterDescription(second.labelKey),
		secondValue = second.value,
		range = first.min..first.max,
		decimals = 2,
		step = first.step,
		unitSuffix = parameterUnitSuffix(first.unit),
		onCommit = { firstValue, secondValue -> onChange(first.copy(value = firstValue), second.copy(value = secondValue)) },
	)
}