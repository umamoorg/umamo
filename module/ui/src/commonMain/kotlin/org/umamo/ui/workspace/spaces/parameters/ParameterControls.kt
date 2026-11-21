package org.umamo.ui.workspace.spaces.parameters

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.runtime.eval.wrapOrClamp
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterKind
import org.umamo.ui.kit.StackPosition
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.button.DisclosureChevron
import org.umamo.ui.kit.button.IconButton
import org.umamo.ui.kit.button.IconButtonAppearance
import org.umamo.ui.kit.field.Checkbox
import org.umamo.ui.kit.field.Dial
import org.umamo.ui.kit.field.FieldStack
import org.umamo.ui.kit.field.NumberField
import org.umamo.ui.kit.field.Pad2D
import org.umamo.ui.kit.field.Slider
import org.umamo.ui.kit.field.SliderKeyMark
import org.umamo.ui.kit.field.SliderKeyShape
import org.umamo.ui.kit.field.numberFieldStepForSpan
import org.umamo.ui.kit.singleOrDoubleClick
import org.umamo.ui.kit.textentry.InlineRenameField
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.theme.UmamoColors
import org.umamo.ui.theme.UmamoIcon
import kotlin.math.abs

/** A value within this of a parameter's default counts as "at default" - no reset glyph, no drift. */
private const val RESET_EPSILON = 1e-4f

/**
 * A generous clamp for the range-editor numeric fields.  The three fields (min / default / max) are
 * interdependent, so each is clamped only to this wide sanity bound and the session normalizes the
 * triple (min <= max, default within range); a tight per-field clamp would block typing a default beyond
 * the not-yet-committed max.
 */
private val RANGE_FIELD_LIMIT = -1_000_000f..1_000_000f

/** The diameter of the dial a repeating parameter shows in place of its slider. */
internal val PARAMETER_DIAL_DIAMETER: Dp = 120.dp

/** The space above and below a dial, between it and the value row over it. */
internal val PARAMETER_DIAL_SPACING: Dp = 6.dp

/** The drag that scrubs a parameter's value field from min to max, the same hand motion whatever the range. */
internal val PARAMETER_FIELD_SCRUB_TRAVEL: Dp = 200.dp

/** The fractional places a parameter's value field shows and commits. */
private const val PARAMETER_FIELD_DECIMALS = 2

/**
 * One name's inline rename as the row showing it sees it: whether the field is open, and what opening,
 * committing, and abandoning it do.
 *
 * Immutable so Compose compares it by value.  The three callbacks are lambdas the compiler keeps across
 * recompositions while what they capture is unchanged, so a slot built again for the same name equals
 * the one before it and the control taking it skips.
 *
 * @param Boolean  renaming Whether this name is being edited in place.
 * @param Function onStart  Called on a double click of the name to open the field.  The double click's
 *   first press has already run the name's single click by then, so this undoes it.
 * @param Function onCommit Called with the new name when the field commits.
 * @param Function onCancel Called when the field is abandoned.
 */
@Immutable
internal data class ParameterRenameSlot(
	val renaming: Boolean,
	val onStart: () -> Unit,
	val onCommit: (String) -> Unit,
	val onCancel: () -> Unit,
)

/**
 * The link edit a row offers on its trailing glyph: link with the slider below, or split a pad in two.
 * A row that offers neither passes no action at all, so a glyph never shows without its click or its
 * name.
 *
 * Immutable, and the annotation is what lets a row skip: an icon holds a list of layers, which the
 * compiler cannot prove stable, and a holder it cannot prove stable is compared by instance - never
 * equal to the one built the recomposition before.
 *
 * @param UmamoIcon icon               The glyph.
 * @param String    contentDescription The glyph's localized accessible label.
 * @param Function  onClick            Called when the glyph is clicked.
 */
@Immutable
internal data class ParameterLinkAction(
	val icon: UmamoIcon,
	val contentDescription: String,
	val onClick: () -> Unit,
)

/**
 * The rounded elevation island wrapping one parameter (slider or pad) and, while open, its range
 * editor.  One visual step raised from the panel fill (headerBackground sits exactly one elevation
 * step above panelBackground in both schemes), rounded with the kit's medium shape.  The group-depth
 * indent lives on the caller's row (beside the drag grip), so the island only carries [modifier].
 *
 * @param Modifier modifier The layout modifier (the caller supplies the row weight).
 * @param Boolean selected Whether this island's parameter(s) are the current keyform-authoring target.
 * @param Function? onSelect Called when the island's own surface is clicked, or null to disable selection.
 * @param Function content The island's rows.
 */
@Composable
internal fun ParameterIsland(
	modifier: Modifier = Modifier,
	selected: Boolean = false,
	onSelect: (() -> Unit)? = null,
	content: @Composable ColumnScope.() -> Unit,
) {
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	Column(
		modifier =
			modifier
				.clip(shapes.medium)
				.let { base ->
					// The slider, pad, and name each consume their own pointer input first, so this only fires on
					// the island's own surface.  Scrubbing therefore does NOT retarget: a scrub is a pose gesture,
					// and making it push a selection undo step would bury the history under drag noise.
					//
					// NOT FOCUSABLE, and that is load-bearing: a clickable takes focus, and deleting the row that
					// holds focus leaves Compose's focus null, which silently kills every keyboard shortcut until
					// the next click.  The range-editor chevron below guards itself the same way.
					if (onSelect != null) {
						base
							.focusProperties { canFocus = false }
							.clickable(indication = null, interactionSource = null, onClick = onSelect)
					} else {
						base
					}
				}
				.background(colors.headerBackground, shape = shapes.medium)
				.border(
					width = if (selected) 2.dp else 1.dp,
					color = if (selected) colors.accent else colors.panelBorder,
					shape = shapes.medium,
				)
				.padding(horizontal = 6.dp, vertical = 6.dp),
		content = content,
	)
}

/**
 * One labelled slider for a parameter, clamped to its range, with a numeric entry field and (when the
 * value is off its default) a reset glyph.  It shows a dial in place of the slider when [control] says so.
 * A drag of the slider or a scrub of the field previews live and commits one step on release; a typed
 * value or the reset commits one step.
 *
 * @param Parameter parameter        The parameter to scrub.
 * @param SingleControl control      The control the row shows, the slider or the dial.
 * @param ParameterKeyMarks? keyMarks The parameter's grid / blend key marks to draw on the slider, or null.
 * @param Float     value            The current value.
 * @param ParameterLabels labels     The panel's localized chrome.
 * @param Boolean   rangeOpen        Whether this island's range editor is open (highlights the name).
 * @param Function  onToggleRange    Called when the name / chevron is clicked (toggles the range editor).
 * @param ParameterRenameSlot rename The inline rename of this parameter's name.
 * @param Function  onPreview        Called per drag frame with the new value (transient, no undo step).
 * @param Function  onCancelPreview  Called when the field's scrub is cancelled, to drop its previews.
 * @param Function  onCommitGesture  Called on slider release / tap to commit the gesture as one step.
 * @param Function  onCommitValue    Called by the numeric field / reset with a discrete value to commit.
 * @param ParameterLinkAction? link  The link edit on the trailing glyph, or null when the row offers none.
 */
@Composable
internal fun ParameterSlider(
	parameter: Parameter,
	control: SingleControl,
	keyMarks: ParameterKeyMarks?,
	value: Float,
	labels: ParameterLabels,
	rangeOpen: Boolean,
	onToggleRange: () -> Unit,
	rename: ParameterRenameSlot,
	onPreview: (Float) -> Unit,
	onCancelPreview: () -> Unit,
	onCommitGesture: () -> Unit,
	onCommitValue: (Float) -> Unit,
	link: ParameterLinkAction?,
) {
	ParameterValueRow(
		parameter = parameter,
		value = value,
		labels = labels,
		showRangeToggle = true,
		showLeadingSlot = true,
		rangeOpen = rangeOpen,
		onToggleRange = onToggleRange,
		rename = rename,
		onPreview = onPreview,
		onScrubCancel = onCancelPreview,
		onCommitValue = onCommitValue,
		link = link,
	)
	val sliderKeyMarks = remember(keyMarks) { sliderKeyMarksOf(keyMarks) }
	val accentColor = controlAccentOf(parameter.repeat, LocalUmamoColors.current)

	when (control) {
		SingleControl.Dial -> {
			// The dial reports laps past the seam, and the parameter's own rule wraps them, so the pose stays in range.
			// Centered by its modifier, not in a box, so a drag frame runs no extra lambda.
			Dial(
				value = value,
				onValueChange = { raw -> onPreview(parameter.wrapOrClamp(raw)) },
				onValueChangeFinished = onCommitGesture,
				valueRange = parameter.min..parameter.max,
				keyMarks = sliderKeyMarks,
				thumbShape = thumbShapeOf(parameter),
				wraps = true,
				accentColor = accentColor,
				modifier =
					Modifier
						.padding(vertical = PARAMETER_DIAL_SPACING)
						.fillMaxWidth()
						.wrapContentWidth()
						.size(PARAMETER_DIAL_DIAMETER),
			)
		}

		SingleControl.Slider -> {
			Slider(
				value = value,
				onValueChange = onPreview,
				onValueChangeFinished = onCommitGesture,
				valueRange = parameter.min..parameter.max,
				keyMarks = sliderKeyMarks,
				thumbShape = thumbShapeOf(parameter),
				accentColor = accentColor,
				modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
			)
		}
	}
}

/**
 * The marks a parameter's slider or dial draws at its keys: a circle at each grid key and a square at each
 * blend-shape key.
 *
 * @param ParameterKeyMarks? keyMarks The parameter's grid / blend key values, or null with none.
 * @return List<SliderKeyMark> The marks, grid keys first.
 */
private fun sliderKeyMarksOf(keyMarks: ParameterKeyMarks?): List<SliderKeyMark> =
	buildList {
		keyMarks?.gridKeys?.forEach { keyValue -> add(SliderKeyMark(keyValue, SliderKeyShape.Circle)) }
		keyMarks?.blendKeys?.forEach { keyValue -> add(SliderKeyMark(keyValue, SliderKeyShape.Square)) }
	}

/**
 * The thumb a parameter's slider or dial takes, from the parameter's own kind, so a blend-shape parameter reads as
 * a square knob even before it has any keys.
 *
 * @param Parameter parameter The parameter the control drives.
 * @return SliderKeyShape Square for a blend-shape parameter, Circle otherwise.
 */
private fun thumbShapeOf(parameter: Parameter): SliderKeyShape =
	if (parameter.kind == ParameterKind.BLEND_SHAPE) {
		SliderKeyShape.Square
	} else {
		SliderKeyShape.Circle
	}

/**
 * The accent a parameter's control draws its fill in: the repeat accent when its value wraps, so it reads apart from a
 * control that clamps, and the plain accent otherwise.
 *
 * @param Boolean repeats Whether the value the control drives wraps; for a pad, whether either axis does.
 * @param UmamoColors colors The palette.
 * @return Color The accent.
 */
private fun controlAccentOf(repeats: Boolean, colors: UmamoColors): Color = if (repeats) colors.repeatAccent else colors.accent

/**
 * One 2D pad for a LINKED parameter pair: a labelled numeric/reset row per axis above a [Pad2D] that
 * scrubs both at once.  [horizontal] is the X axis, [vertical] the Y axis.  The pad drag previews both live
 * and commits one step (covering both parameters) on release; an axis field's scrub previews and commits
 * its own axis alone.  The range chevron sits on the horizontal (upper) axis row; clicking either axis
 * name toggles the island's shared range editor.
 *
 * When [control] says so, the pair shows a dial per axis in place of the pad, the upper axis on the left.  Each
 * drives its own axis and commits it alone, and a member that does not repeat stops at its seam.
 *
 * @param Parameter horizontal       The X-axis parameter.
 * @param Parameter vertical         The Y-axis parameter.
 * @param PairControl control        The control the row shows, the pad or a dial per axis.
 * @param ParameterKeyMarks? horizontalKeyMarks The X axis's key marks, which the pad or the axis's dial draws, or null with none.
 * @param ParameterKeyMarks? verticalKeyMarks The Y axis's key marks, which the pad or the axis's dial draws, or null with none.
 * @param Float     xValue           The current X value.
 * @param Float     yValue           The current Y value.
 * @param ParameterLabels labels     The panel's localized chrome.
 * @param Boolean   rangeOpen        Whether this island's range editor is open.
 * @param Function  onToggleRange    Called when an axis name / the chevron is clicked.
 * @param ParameterRenameSlot horizontalRename The inline rename of the X axis's name.
 * @param ParameterRenameSlot verticalRename   The inline rename of the Y axis's name.
 * @param Function  onPreview        Called per drag frame with (id, value) for each axis (transient).
 * @param Function  onCancelPreview  Called with the axis whose field's scrub was cancelled, to drop its previews.
 * @param Function  onCommitGesture  Called on pad release to commit both axes as one step.
 * @param Function  onCommitValue    Called by an axis field / reset with (id, value) to commit one step.
 * @param ParameterLinkAction? link  The unlink edit, shown on the horizontal (upper) axis row - the
 *                                   link "points at the parameter below" so it sits on the upper member -
 *                                   or null when the pad offers none.
 */
@Composable
internal fun ParameterPad2D(
	horizontal: Parameter,
	vertical: Parameter,
	control: PairControl,
	horizontalKeyMarks: ParameterKeyMarks?,
	verticalKeyMarks: ParameterKeyMarks?,
	xValue: Float,
	yValue: Float,
	labels: ParameterLabels,
	rangeOpen: Boolean,
	onToggleRange: () -> Unit,
	horizontalRename: ParameterRenameSlot,
	verticalRename: ParameterRenameSlot,
	onPreview: (ParameterId, Float) -> Unit,
	onCancelPreview: (Parameter) -> Unit,
	onCommitGesture: (Set<ParameterId>) -> Unit,
	onCommitValue: (ParameterId, Float) -> Unit,
	link: ParameterLinkAction?,
) {
	val colors = LocalUmamoColors.current
	// Both axis names toggle the SAME island range editor, so the disclosure chevron is shared and
	// vertically centerd across the two axis rows rather than pinned to the first.  It is pulled out of the
	// per-row leading slot (both rows pass showLeadingSlot = false, supplying the indent from here) and
	// kept keyboard-focus-safe like the slider's inline toggle.
	// IntrinsicSize.Min gives the row the height of its tallest child (the two-axis Column), so the shared
	// chevron can fillMaxHeight to span both names top-to-bottom - a tall, wide hit target rather than a
	// 12.dp dot pinned to the first row.
	Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
		Box(
			modifier =
				Modifier
					.fillMaxHeight()
					.width(24.dp)
					.focusProperties { canFocus = false }
					.clickable(onClick = onToggleRange),
			contentAlignment = Alignment.Center,
		) {
			// Named for what it discloses rather than the generic expand / collapse, and deliberately without a
			// tooltip: the axis names it opens sit immediately to its right.
			DisclosureChevron(expanded = rangeOpen, tint = colors.text, contentDescription = labels.rangeToggle)
		}
		// The two axis rows butt into one stacked field group (First / Last, hairline seam between) so a linked
		// pair reads as a single joined control, matching the Canvas dimension stack.
		FieldStack(
			modifier = Modifier.weight(1f),
			rows =
				listOf(
					{ position ->
						ParameterValueRow(
							parameter = horizontal,
							value = xValue,
							labels = labels,
							showRangeToggle = false,
							showLeadingSlot = false,
							rangeOpen = rangeOpen,
							onToggleRange = onToggleRange,
							rename = horizontalRename,
							onPreview = { newX -> onPreview(horizontal.id, newX) },
							onScrubCancel = { onCancelPreview(horizontal) },
							onCommitValue = { newX -> onCommitValue(horizontal.id, newX) },
							link = link,
							stackPosition = position,
						)
					},
					{ position ->
						ParameterValueRow(
							parameter = vertical,
							value = yValue,
							labels = labels,
							showRangeToggle = false,
							showLeadingSlot = false,
							rangeOpen = rangeOpen,
							onToggleRange = onToggleRange,
							rename = verticalRename,
							onPreview = { newY -> onPreview(vertical.id, newY) },
							onScrubCancel = { onCancelPreview(vertical) },
							onCommitValue = { newY -> onCommitValue(vertical.id, newY) },
							// The glyph sits on the upper row alone.
							link = null,
							stackPosition = position,
						)
					},
				),
		)
	}

	// The pad has one handle for both axes, so it takes the repeat accent when either of them wraps.
	val padAccentColor = controlAccentOf(horizontal.repeat || vertical.repeat, colors)

	when (control) {
		PairControl.Dials -> {
			Row(modifier = Modifier.fillMaxWidth().padding(vertical = PARAMETER_DIAL_SPACING)) {
				ParameterPairDial(horizontal, xValue, horizontalKeyMarks, onPreview, onCommitGesture, Modifier.weight(1f))
				ParameterPairDial(vertical, yValue, verticalKeyMarks, onPreview, onCommitGesture, Modifier.weight(1f))
			}
		}

		PairControl.Pad -> {
			val xKeyMarks = remember(horizontalKeyMarks) { sliderKeyMarksOf(horizontalKeyMarks) }
			val yKeyMarks = remember(verticalKeyMarks) { sliderKeyMarksOf(verticalKeyMarks) }
			Pad2D(
				xValue = xValue,
				yValue = yValue,
				onChange = { newX, newY ->
					onPreview(horizontal.id, newX)
					onPreview(vertical.id, newY)
				},
				onChangeFinished = { onCommitGesture(setOf(horizontal.id, vertical.id)) },
				xRange = horizontal.min..horizontal.max,
				yRange = vertical.min..vertical.max,
				xKeyMarks = xKeyMarks,
				yKeyMarks = yKeyMarks,
				accentColor = padAccentColor,
				modifier = Modifier.padding(10.dp).fillMaxWidth(),
			)
		}
	}
}

/**
 * One axis's dial in a pair that shows dials in place of its pad, captioned with the axis's name so the two read
 * apart.  The name is user data, never localized.
 *
 * The dial reports laps past its seam and the axis's own rule resolves them: a repeating axis wraps, and one that
 * does not stops at the seam until the pointer comes back across it.
 *
 * @param Parameter parameter The axis the dial drives.
 * @param Float value The axis's current value.
 * @param ParameterKeyMarks? keyMarks The axis's key marks, or null with none.
 * @param Function onPreview Called per drag frame with (id, value) for this axis (transient).
 * @param Function onCommitGesture Called on release with this axis alone, to commit it as one step.
 * @param Modifier modifier The layout modifier (the caller supplies the weight).
 */
@Composable
private fun ParameterPairDial(
	parameter: Parameter,
	value: Float,
	keyMarks: ParameterKeyMarks?,
	onPreview: (ParameterId, Float) -> Unit,
	onCommitGesture: (Set<ParameterId>) -> Unit,
	modifier: Modifier = Modifier,
) {
	val dialKeyMarks = remember(keyMarks) { sliderKeyMarksOf(keyMarks) }
	Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
		Dial(
			value = value,
			onValueChange = { raw -> onPreview(parameter.id, parameter.wrapOrClamp(raw)) },
			onValueChangeFinished = { onCommitGesture(setOf(parameter.id)) },
			valueRange = parameter.min..parameter.max,
			keyMarks = dialKeyMarks,
			thumbShape = thumbShapeOf(parameter),
			wraps = parameter.repeat,
			accentColor = controlAccentOf(parameter.repeat, LocalUmamoColors.current),
			modifier = Modifier.size(PARAMETER_DIAL_DIAMETER),
		)
		Text(
			text = parameter.name,
			style = LocalUmamoTypography.current.labelSmall,
			color = LocalUmamoColors.current.textMuted,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
		)
	}
}

/**
 * The shared label row above a slider or pad axis: a leading range chevron (on the island's toggle
 * row), the clickable parameter name (name and chevron both toggle the island's range editor, the name
 * highlighted while it is open), an optional reset glyph (shown only when [value] is off its default),
 * and a numeric entry field held to the parameter's range, which a repeating parameter wraps into and
 * any other clamps to.  A scrub of the field crosses the range over [PARAMETER_FIELD_SCRUB_TRAVEL] whatever its
 * width, previews live, and commits one step on release; its chevrons step a tenth of the range.
 *
 * @param Parameter parameter       The parameter the row shows: its name, default, and range.
 * @param Float    value            The current value.
 * @param ParameterLabels labels    The panel's localized chrome.
 * @param Boolean  showRangeToggle  Whether this row's leading slot draws the island's chevron (versus a
 *                                  matching spacer); only consulted when [showLeadingSlot] is true.
 * @param Boolean  showLeadingSlot  Whether this row renders its own leading chevron / spacer slot.  A
 *                                  linked pad shares one external chevron centerd across both axis rows,
 *                                  so its rows pass false and let that chevron supply the indent.
 * @param Boolean  rangeOpen        Whether the island's range editor is open.
 * @param Function onToggleRange    Called when the name / chevron is clicked.
 * @param ParameterRenameSlot rename The inline rename of this row's name.
 * @param Function onPreview        Called per frame of a field scrub with the in-flight value (transient).
 * @param Function onScrubCancel    Called when a field scrub is cancelled, to drop what it previewed.
 * @param Function onCommitValue    Called with the new value: a typed value, a reset, a chevron step, or a
 *                                  scrub's release, each committed as one step.
 * @param ParameterLinkAction? link The link edit on the trailing glyph, or null for an empty slot.
 * @param StackPosition stackPosition This field's position in a vertical stack: a linked pair's two axis
 *                                  rows butt into one stacked group (First / Last), while a standalone
 *                                  slider row stays Single (a self-contained, fully-rounded field).
 */
@Composable
private fun ParameterValueRow(
	parameter: Parameter,
	value: Float,
	labels: ParameterLabels,
	showRangeToggle: Boolean,
	showLeadingSlot: Boolean,
	rangeOpen: Boolean,
	onToggleRange: () -> Unit,
	rename: ParameterRenameSlot,
	onPreview: (Float) -> Unit,
	onScrubCancel: () -> Unit,
	onCommitValue: (Float) -> Unit,
	link: ParameterLinkAction?,
	stackPosition: StackPosition = StackPosition.Single,
) {
	val colors = LocalUmamoColors.current
	Row(
		modifier = Modifier.fillMaxWidth(),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Row(
			// A single click toggles the range editor; a double click begins inline rename. singleOrDoubleClick
			// uses raw pointerInput (never requests focus, so keyboard dispatch stays on the shell root) and
			// fires the single click immediately - no double-tap wait.  While renaming, the field below consumes
			// its own presses, so this gesture does not fight it.
			//
			// The press is claimed.  The name sits inside the island, whose own surface targets the row, and a
			// press left unconsumed would reach it: opening a range editor would retarget the keyform sheet and
			// record a step.
			modifier =
				Modifier
					.weight(1f)
					.singleOrDoubleClick(onSingle = { onToggleRange() }, onDouble = rename.onStart, consumePress = true),
			verticalAlignment = Alignment.CenterVertically,
		) {
			if (showLeadingSlot) {
				if (showRangeToggle) {
					// Named for what it discloses, tooltip-free: the parameter name sits immediately to its right.
					DisclosureChevron(expanded = rangeOpen, tint = colors.text, contentDescription = labels.rangeToggle)
				} else {
					Spacer(modifier = Modifier.width(12.dp))
				}
			}

			if (rename.renaming) {
				InlineRenameField(
					initialName = parameter.name,
					textStyle = LocalUmamoTypography.current.labelSmall.copy(color = colors.text),
					cursorColor = colors.text,
					onCommit = rename.onCommit,
					onCancel = rename.onCancel,
					modifier = Modifier.weight(1f).padding(start = 4.dp),
				)
			} else {
				Text(
					text = parameter.name,
					style = LocalUmamoTypography.current.labelSmall,
					color = if (rangeOpen) colors.accent else colors.text,
					modifier = Modifier.padding(start = 4.dp),
				)
			}
		}

		if (abs(value - parameter.default) > RESET_EPSILON) {
			IconButton(
				icon = LocalUmamoIcons.reset,
				onClick = { onCommitValue(parameter.default) },
				contentDescription = labels.reset,
				size = DpSize(20.dp, 20.dp),
				appearance = IconButtonAppearance.Filled(LocalUmamoShapes.current.small),
			)
			Spacer(modifier = Modifier.width(4.dp))
		}

		NumberField(
			value = value,
			onValueChange = onCommitValue,
			range = parameter.min..parameter.max,
			modifier = Modifier.width(64.dp),
			decimals = PARAMETER_FIELD_DECIMALS,
			step = numberFieldStepForSpan(parameter.max - parameter.min, PARAMETER_FIELD_DECIMALS),
			stackPosition = stackPosition,
			// The parameter's own rule, which the eval reads it through: a repeating value wraps, any other clamps.
			bringIntoRange = { raw -> parameter.wrapOrClamp(raw) },
			// A scrub drives the rig as it goes, as the slider does; its release still commits once, through onValueChange.
			onPreview = onPreview,
			onScrubCancel = onScrubCancel,
			// One travel crosses any range, so a 0..1 axis and a -30..30 one take the same hand motion.
			scrubTravel = PARAMETER_FIELD_SCRUB_TRAVEL,
		)
		// A fixed-width trailing slot whether or not this row offers a link edit, so the number
		// fields stay column-aligned across all rows.
		Spacer(modifier = Modifier.width(4.dp))
		Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
			if (link != null) {
				IconButton(
					icon = link.icon,
					onClick = link.onClick,
					contentDescription = link.contentDescription,
					size = DpSize(20.dp, 20.dp),
					appearance = IconButtonAppearance.Filled(LocalUmamoShapes.current.small),
					suppressFocus = true,
				)
			}
		}
	}
}

/**
 * The axis-name caption above a pad axis's range fields (user data, never localized).
 *
 * @param String name The axis parameter's display name.
 */
@Composable
internal fun RangeAxisLabel(name: String) {
	Text(
		text = name,
		style = LocalUmamoTypography.current.labelSmall,
		color = LocalUmamoColors.current.textMuted,
		modifier = Modifier.padding(start = 8.dp, top = 4.dp),
	)
}

/**
 * One parameter's range editor, inside its island while the editor is open: the min / default / max fields, then
 * the Repeat checkbox.  A pad shows one per axis.
 *
 * @param Parameter parameter The parameter the editor edits.
 * @param ParameterLabels labels The panel's localized chrome.
 * @param Function onSetRange Called with (min, default, max) to commit a range edit.
 * @param Function onSetRepeat Called with the new flag when the checkbox is clicked.
 */
@Composable
internal fun ParameterRangeEditor(
	parameter: Parameter,
	labels: ParameterLabels,
	onSetRange: (Float, Float, Float) -> Unit,
	onSetRepeat: (Boolean) -> Unit,
) {
	RangeFieldsRow(parameter, onSetRange)
	// The checkbox claims its own press, so a click on it never reaches the island and retargets the keyform sheet.
	Box(modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 2.dp)) {
		Checkbox(checked = parameter.repeat, onCheckedChange = onSetRepeat, label = labels.repeat)
	}
}

/**
 * The min / default / max fields for one parameter's range, rendered inside its island while the range
 * editor is open.  Editing any field commits one undo step through the session (which normalizes
 * min <= max, clamps the default into the range, and brings the live pose back into it); the model then
 * refreshes the fields.  Any number of islands can hold an open range editor at once.
 *
 * @param Parameter parameter  The parameter whose range is edited.
 * @param Function  onSetRange Called with (min, default, max) to commit a range edit.
 */
@Composable
private fun RangeFieldsRow(parameter: Parameter, onSetRange: (Float, Float, Float) -> Unit) {
	Row(
		modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
		verticalAlignment = Alignment.Bottom,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		RangeField(
			label = stringResource(Res.string.parameter_range_min),
			value = parameter.min,
			modifier = Modifier.weight(1f),
			onCommit = { newMin -> onSetRange(newMin, parameter.default, parameter.max) },
		)
		RangeField(
			label = stringResource(Res.string.parameter_range_default),
			value = parameter.default,
			modifier = Modifier.weight(1f),
			onCommit = { newDefault -> onSetRange(parameter.min, newDefault, parameter.max) },
		)
		RangeField(
			label = stringResource(Res.string.parameter_range_max),
			value = parameter.max,
			modifier = Modifier.weight(1f),
			onCommit = { newMax -> onSetRange(parameter.min, parameter.default, newMax) },
		)
	}
}

/**
 * One labelled numeric field of the range editor (min / default / max).  The field clamps only to a wide
 * sanity bound; the session normalizes the resulting triple.
 *
 * @param String   label    The field's caption.
 * @param Float    value    The current value.
 * @param Modifier modifier The layout modifier (the caller supplies the row weight).
 * @param Function onCommit Called with the typed value when the field commits.
 */
@Composable
private fun RangeField(
	label: String,
	value: Float,
	modifier: Modifier = Modifier,
	onCommit: (Float) -> Unit,
) {
	Column(modifier = modifier) {
		Text(
			text = label,
			style = LocalUmamoTypography.current.labelSmall,
			color = LocalUmamoColors.current.textMuted,
			modifier = Modifier.padding(start = 2.dp, bottom = 2.dp),
		)
		NumberField(
			value = value,
			onValueChange = onCommit,
			range = RANGE_FIELD_LIMIT,
			modifier = Modifier.fillMaxWidth(),
			// The limit is a wide sanity clamp, not a display range, so the magnitude fill would be meaningless.
			showFill = false,
		)
	}
}