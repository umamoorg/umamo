package org.umamo.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.theme.UmamoIcon
import org.umamo.ui.theme.drawIcon
import kotlin.math.roundToInt

/** The alpha applied to the accent tint when a bounded field draws its left-to-right magnitude fill. */
private const val NUMBER_FIELD_FILL_ALPHA = 0.30f

/**
 * The field's fixed height, shared with the other inline form controls via [FIELD_CONTROL_HEIGHT] so a
 * NumberField and a SelectField line up.  Pinned (rather than content-driven) so the display and type-in
 * faces do not jump when swapping, and so the edge chevrons' fillMaxHeight has a bounded constraint to fill
 * - a scroll container hands children an unbounded max height, under which fillMaxHeight collapses to the
 * glyph.  With no vertical padding on the inner content, the box's fixed height plus center alignment
 * position the single text line, so the glyph is never clipped by a competing content height.
 */
private val NUMBER_FIELD_HEIGHT = FIELD_CONTROL_HEIGHT

/**
 * A compact numeric field for a scalar [value], clamped to [range].  Blender-style: the field shows the
 * value while idle; a quick click enters type-in mode; a horizontal drag past the touch slop scrubs the
 * value (previewed live, committed once on release); hovering reveals decrement / increment chevrons that
 * step by [step].  When [range] is a usable finite span a left-to-right magnitude fill tracks where the
 * value sits in it.  Commits (type-in confirm, chevron step, drag release) each fire [onValueChange] once
 * with the value rounded to [decimals] places and clamped to [range]; a whole scrub is one commit, so a
 * caller can treat it as one undo step.
 *
 * 数値入力欄。クリックで入力、ドラッグでスクラブ、ホバーで増減シェブロン。範囲が有限なら塗りで大きさを示す。
 *
 * @param Float value The current value, shown when not being edited.
 * @param Function onValueChange Called with the committed value (rounded to [decimals] and clamped to [range]).
 * @param ClosedFloatingPointRange range The min..max the committed value is clamped to (an infinite endpoint = unbounded, no fill).
 * @param Modifier modifier Layout modifier (the caller supplies the width).
 * @param Int decimals The fractional places shown; the stored value is rounded to the same (Float-capped).
 * @param Float step The increment for a chevron press and the scrub sensitivity.
 * @param String? unitSuffix An optional trailing unit shown in the idle field (e.g. "px", "%"); never in the edit text.
 * @param Boolean showFill Whether to draw the magnitude fill when the range is bounded (off for wide sanity clamps).
 * @param Boolean plain When true, renders a bare type-in box only (no scrub, chevrons, or fill) - the simple field.
 * @param StackPosition stackPosition This field's position in a vertical stack (corner rounding + seam).
 */
@Composable
fun NumberField(
	value: Float,
	onValueChange: (Float) -> Unit,
	range: ClosedFloatingPointRange<Float>,
	modifier: Modifier = Modifier,
	decimals: Int = 2,
	step: Float = 1f,
	unitSuffix: String? = null,
	showFill: Boolean = true,
	plain: Boolean = false,
	stackPosition: StackPosition = StackPosition.Single,
) {
	val bounded = showFill && range.start.isFinite() && range.endInclusive.isFinite() && range.endInclusive > range.start
	NumberFieldCore(
		value = value,
		range = range,
		step = step,
		bounded = bounded,
		format = { toFormat -> formatDecimals(toFormat, decimals) },
		parse = { typed -> typed.trim().toFloatOrNull() },
		onCommit = { raw -> onValueChange(roundToDecimals(raw, decimals).coerceIn(range.start, range.endInclusive)) },
		modifier = modifier,
		unitSuffix = unitSuffix,
		plain = plain,
		stackPosition = stackPosition,
	)
}

/**
 * Integer variant of [NumberField]: shows and commits a whole number (a typed decimal or non-number is
 * rejected).  Same Blender-style interaction - click to type, drag to scrub, hover chevrons - stepping by
 * [step].  The magnitude fill draws only for a genuinely bounded range: a half-open sanity clamp such as
 * `0..Int.MAX_VALUE` reads as unbounded and shows no fill.
 *
 * 整数版の数値入力欄。クリックで入力、ドラッグでスクラブ、ホバーで増減。両端が有限のときだけ塗りを描く。
 *
 * @param Int value The current value, shown when not being edited.
 * @param Function onValueChange Called with the committed value (clamped to [range]).
 * @param IntRange range The min..max the committed value is clamped to.
 * @param Modifier modifier Layout modifier (the caller supplies the width).
 * @param Int step The increment for a chevron press and the scrub sensitivity.
 * @param String? unitSuffix An optional trailing unit shown in the idle field; never in the edit text.
 * @param Boolean showFill Whether to draw the magnitude fill when the range is bounded.
 * @param Boolean plain When true, renders a bare type-in box only (the simple field).
 * @param StackPosition stackPosition This field's position in a vertical stack (corner rounding + seam).
 */
@Composable
fun NumberField(
	value: Int,
	onValueChange: (Int) -> Unit,
	range: IntRange,
	modifier: Modifier = Modifier,
	step: Int = 1,
	unitSuffix: String? = null,
	showFill: Boolean = true,
	plain: Boolean = false,
	stackPosition: StackPosition = StackPosition.Single,
) {
	val floatRange = range.first.toFloat()..range.last.toFloat()
	// A whole-domain endpoint means "unbounded", so a plain int clamp such as 0..Int.MAX_VALUE draws no fill.
	val bounded = showFill && range.first > Int.MIN_VALUE && range.last < Int.MAX_VALUE
	NumberFieldCore(
		value = value.toFloat(),
		range = floatRange,
		step = step.toFloat(),
		bounded = bounded,
		format = { toFormat -> toFormat.roundToInt().toString() },
		parse = { typed -> typed.trim().toIntOrNull()?.toFloat() },
		onCommit = { raw -> onValueChange(raw.roundToInt().coerceIn(range.first, range.last)) },
		modifier = modifier,
		unitSuffix = unitSuffix,
		plain = plain,
		stackPosition = stackPosition,
	)
}

/**
 * The shared engine behind both [NumberField] overloads, working in Float and delegating type-specific
 * formatting / parsing / rounding to the overload via [format], [parse], and [onCommit].  Holds the two
 * interaction states: a display face (tap to edit, drag to scrub, hover chevrons, magnitude fill) and the
 * type-in editor swapped in while editing.  A scrub previews locally (nothing commits per frame) and
 * commits once on release; [plain] skips straight to a bare type-in box.
 *
 * @param Float value The current value (the source of truth while idle).
 * @param ClosedFloatingPointRange range The clamp range.
 * @param Float step The chevron / scrub increment.
 * @param Boolean bounded Whether to draw the magnitude fill (a usable finite range).
 * @param Function format Formats a value for display and for seeding the editor.
 * @param Function parse Parses typed text to a raw value, or null when unparseable.
 * @param Function onCommit Rounds / clamps a raw value and fires the caller's callback (one committed edit).
 * @param Modifier modifier Layout modifier (the width).
 * @param String? unitSuffix The idle trailing unit, or null.
 * @param Boolean plain Whether to render the bare type-in box only.
 * @param StackPosition stackPosition The field's position in a vertical stack.
 */
@Composable
private fun NumberFieldCore(
	value: Float,
	range: ClosedFloatingPointRange<Float>,
	step: Float,
	bounded: Boolean,
	format: (Float) -> String,
	parse: (String) -> Float?,
	onCommit: (Float) -> Unit,
	modifier: Modifier,
	unitSuffix: String?,
	plain: Boolean,
	stackPosition: StackPosition,
) {
	val shapes = LocalUmamoShapes.current
	val shape = stackedShape(shapes.small, stackPosition, StackAxis.Vertical)
	// Parses typed text, clamps to the range, and commits; an unparseable entry does nothing.
	val commitTyped: (String) -> Unit = { typed ->
		parse(typed)?.let { parsed -> onCommit(parsed.coerceIn(range.start, range.endInclusive)) }
	}

	if (plain) {
		// The simple field: a bare type-in box, matching the pre-upgrade behavior (no scrub / chevrons / fill).
		NumberEntryField(display = format(value), shape = shape, modifier = modifier, commit = commitTyped)
		return
	}

	var editing by remember { mutableStateOf(false) }
	// The live scrub value while a drag is in flight (null when idle); previews without committing per frame.
	var dragValue by remember { mutableStateOf<Float?>(null) }

	if (editing) {
		NumberEntryField(
			display = format(value),
			shape = shape,
			modifier = modifier,
			autoFocus = true,
			onFocusLost = { editing = false },
			commit = commitTyped,
		)
	} else {
		val shown = dragValue ?: value
		NumberFieldDisplay(
			text = format(shown),
			unitSuffix = unitSuffix,
			fillFraction = if (bounded) numberFieldFillFraction(shown, range.start, range.endInclusive) else null,
			shape = shape,
			modifier = modifier,
			onTapToEdit = { editing = true },
			onScrubStart = { dragValue = value },
			onScrub = { totalDeltaPx -> dragValue = scrubValue(value, totalDeltaPx, step, range) },
			onScrubEnd = {
				dragValue?.let { finalValue -> onCommit(finalValue) }
				dragValue = null
			},
			onStep = { direction -> onCommit((value + direction * step).coerceIn(range.start, range.endInclusive)) },
		)
	}
}

/**
 * The idle display face of a [NumberField]: a bordered box showing [text] (plus an optional [unitSuffix])
 * over a left-to-right accent magnitude fill, with decrement / increment chevrons that fade in on hover.
 * A quick tap enters type-in mode ([onTapToEdit]); a horizontal drag past the touch slop scrubs
 * ([onScrubStart] / [onScrub] with the total delta from the gesture start / [onScrubEnd]).  The two
 * pointer handlers are separate inputs like [Slider], and callbacks are read through
 * [rememberUpdatedState] so a reused row slot always drives the current field.
 *
 * @param String text The formatted value to show.
 * @param String? unitSuffix An optional trailing unit, or null.
 * @param Float? fillFraction The magnitude fill in 0..1, or null for no fill.
 * @param Shape shape The field's (stack-aware) corner shape.
 * @param Modifier modifier Layout modifier (the width).
 * @param Function onTapToEdit Enters type-in mode (a click that did not become a drag).
 * @param Function onScrubStart Begins a scrub gesture.
 * @param Function onScrub Reports the total horizontal drag delta from the gesture start, in pixels.
 * @param Function onScrubEnd Ends the scrub (commit the previewed value).
 * @param Function onStep Steps the value by a chevron press (-1 or +1).
 */
@Composable
private fun NumberFieldDisplay(
	text: String,
	unitSuffix: String?,
	fillFraction: Float?,
	shape: Shape,
	modifier: Modifier,
	onTapToEdit: () -> Unit,
	onScrubStart: () -> Unit,
	onScrub: (Float) -> Unit,
	onScrubEnd: () -> Unit,
	onStep: (Int) -> Unit,
) {
	val colors = LocalUmamoColors.current
	val typography = LocalUmamoTypography.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	val currentTap by rememberUpdatedState(onTapToEdit)
	val currentScrubStart by rememberUpdatedState(onScrubStart)
	val currentScrub by rememberUpdatedState(onScrub)
	val currentScrubEnd by rememberUpdatedState(onScrubEnd)
	val fillColor = colors.accent.copy(alpha = NUMBER_FIELD_FILL_ALPHA)
	Box(
		modifier =
			modifier
				.height(NUMBER_FIELD_HEIGHT)
				.clip(shape)
				.background(if (hovered) colors.controlBackgroundHover else colors.controlBackground)
				.border(1.dp, Color.Transparent, shape)
				.hoverable(interaction)
				.pointerInput(Unit) {
					detectTapGestures { currentTap() }
				}
				// Horizontal-only: the scrub claims left/right drags but leaves vertical drags to the enclosing
				// scroll (a scroll gesture begun on a field must still scroll the panel, not be eaten as a scrub).
				.pointerInput(Unit) {
					var total = 0f
					detectHorizontalDragGestures(
						onDragStart = {
							total = 0f
							currentScrubStart()
						},
						onDragEnd = { currentScrubEnd() },
						onDragCancel = { currentScrubEnd() },
					) { change, dragAmount ->
						change.consume()
						total += dragAmount
						currentScrub(total)
					}
				},
		contentAlignment = Alignment.Center,
	) {
		if (fillFraction != null) {
			// The magnitude fill sits behind the value (drawn first) and spans the fraction of the width.
			Canvas(modifier = Modifier.matchParentSize()) {
				drawRect(color = fillColor, size = Size(size.width * fillFraction, size.height))
			}
		}
		Row(
			// No vertical padding: the fixed-height box centers the single text line, so it is never clipped
			// by an inner content height that would exceed the box (the cause of a padded row overflowing).
			modifier = Modifier.padding(horizontal = 4.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(text = text, style = typography.bodySmall, color = colors.text)
			if (unitSuffix != null) {
				Spacer(modifier = Modifier.width(2.dp))
				Text(text = unitSuffix, style = typography.bodySmall, color = colors.text)
			}
		}
		if (hovered) {
			StepChevron(
				icon = LocalUmamoIcons.chevronLeft,
				onClick = { onStep(-1) },
				modifier = Modifier.align(Alignment.CenterStart),
			)
			StepChevron(
				icon = LocalUmamoIcons.chevronRight,
				onClick = { onStep(1) },
				modifier = Modifier.align(Alignment.CenterEnd),
			)
		}
	}
}

/**
 * A decrement / increment chevron shown on the idle field's edge while hovered: a full-height edge button
 * with its own control-fill background (brightening on hover) so the glyph stays legible over the value
 * text and the magnitude fill behind it.  Its square outer corners are clipped to the field's rounded
 * shape by the parent.  A pointer convenience over the field's type-in, so it carries no accessibility
 * label of its own.
 *
 * @param UmamoIcon icon The chevron glyph (left = decrement, right = increment).
 * @param Function onClick Steps the value one increment.
 * @param Modifier modifier Layout modifier (the caller aligns it to an edge).
 */
@Composable
private fun StepChevron(icon: UmamoIcon, onClick: () -> Unit, modifier: Modifier) {
	val colors = LocalUmamoColors.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	Box(
		modifier =
			modifier
				.fillMaxHeight()
				.width(16.dp)
				.background(if (hovered) colors.buttonHover else colors.controlBackground)
				.clickable(interactionSource = interaction, indication = null, onClick = onClick),
		contentAlignment = Alignment.Center,
	) {
		Canvas(modifier = Modifier.size(12.dp)) {
			drawIcon(icon, colors.text)
		}
	}
}

/**
 * The single-line type-in editor: a [BasicTextField] showing [display] while idle and letting the user
 * type a replacement, committing the raw text to [commit] on Enter or focus loss and discarding on Escape.
 * When [autoFocus] is set it grabs focus on first composition (the field just swapped into edit mode) and
 * calls [onFocusLost] when focus leaves, so the host can swap the display face back in.  It lends its
 * cancel hook to [LocalInlineEditController] while focused so the shell routes Escape here and yields
 * shortcut keys (Space, digits, minus) to the field.
 *
 * @param String display The formatted external value, shown while idle.
 * @param Shape shape The field's (stack-aware) corner shape.
 * @param Modifier modifier Layout modifier (the width).
 * @param Boolean autoFocus When true, requests focus on first composition (rich edit mode).
 * @param Function onFocusLost Called after focus leaves (rich edit mode swaps back to the display face).
 * @param Function commit Parses / clamps / fires the caller callback with the raw typed text.
 */
@Composable
private fun NumberEntryField(
	display: String,
	shape: Shape,
	modifier: Modifier,
	autoFocus: Boolean = false,
	onFocusLost: () -> Unit = {},
	commit: (String) -> Unit,
) {
	val colors = LocalUmamoColors.current
	val controller = LocalInlineEditController.current
	val focusManager = LocalFocusManager.current
	val focusRequester = remember { FocusRequester() }
	var focused by remember { mutableStateOf(false) }
	var text by remember { mutableStateOf(display) }
	// True between an Escape (which clears focus to discard) and the resulting focus-loss, so that path
	// discards instead of committing.  Plain commit paths (Enter, click-away) leave it false.
	var discarding by remember { mutableStateOf(false) }

	if (autoFocus) {
		LaunchedEffect(Unit) {
			focusRequester.requestFocus()
		}
	}
	// While not editing, mirror the external value (a slider / pad drag or a reset updates the number).
	LaunchedEffect(display, focused) {
		if (!focused) {
			text = display
		}
	}

	// The visual box carries the fixed height, fill, and border so it matches the display face exactly (no
	// jump on click); the inner text field wraps its own line height and the box centers it, with horizontal
	// padding only - the same "let the fixed box own the height" approach the display face uses.
	Box(
		modifier =
			modifier
				.height(NUMBER_FIELD_HEIGHT)
				.clip(shape)
				.background(colors.controlBackground)
				.border(1.dp, colors.controlBorder, shape),
		contentAlignment = Alignment.Center,
	) {
		BasicTextField(
			value = text,
			onValueChange = { newText -> text = newText },
			textStyle = LocalUmamoTypography.current.bodySmall.copy(color = colors.text, textAlign = TextAlign.End),
			singleLine = true,
			cursorBrush = SolidColor(colors.text),
			keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
			// Soft-keyboard Done: clear focus so the single commit happens on the focus-loss path below.
			keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
			modifier =
				Modifier
					.fillMaxWidth()
					.padding(horizontal = 4.dp)
					.focusRequester(focusRequester)
					.onFocusChanged { focusState ->
						// hasFocus (not isFocused): BasicTextField focuses an internal child, so this node only ever
						// sees its subtree's focus.
						if (focusState.hasFocus) {
							if (!focused) {
								focused = true
								controller.cancel = {
									discarding = true
									focusManager.clearFocus()
								}
							}
						} else if (focused) {
							focused = false
							controller.cancel = null
							if (discarding) {
								discarding = false
							} else {
								commit(text)
							}
							onFocusLost()
						}
					}
					.onKeyEvent { event ->
						// Hardware Enter confirms by clearing focus, so the commit runs once on the focus-loss path.
						if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
							focusManager.clearFocus()
							true
						} else {
							false
						}
					},
		)
	}
}
