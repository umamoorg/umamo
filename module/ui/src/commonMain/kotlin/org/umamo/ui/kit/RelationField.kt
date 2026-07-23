package org.umamo.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import org.umamo.ui.kit.button.IconButton
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.theme.UmamoIcon
import org.umamo.ui.theme.drawIcon

/** Edge length of the leading type glyph and the trailing affordances inside a relation field. */
private val RELATION_GLYPH_SIZE = 14.dp

/** How tall the candidate dropdown may grow before it scrolls. */
private val RELATION_DROPDOWN_MAX_HEIGHT = 260.dp

/** The narrowest the dropdown may be, so it stays readable before the anchor has been measured. */
private val RELATION_DROPDOWN_MIN_WIDTH = 140.dp

/**
 * A Blender-style single relation picker: one box that shows the bound entity and, once focused, doubles
 * as the search field for rebinding it.  A leading glyph carries the bound entity's type, a trailing "x"
 * clears the binding, and a trailing eyedropper (when [onPick] is supplied) hands the pick to the viewport
 * or the outliner instead of the list.
 *
 * `[type glyph] [current relation, or the typed query] [x] [eyedropper]`
 *
 * Focusing the field swaps its text for an empty query and opens the candidate list; blurring restores the
 * bound entity's name.  The dropdown is deliberately NON-focusable and its rows decline focus, so clicking
 * a row cannot blur the field out from under the click (the classic combo-box race); it closes on commit,
 * Escape, or the field losing focus.  Up / Down / Enter are intercepted ahead of the field so they navigate
 * the list while ordinary typing still reaches it.
 *
 * Like [NumberField] and [HexColorField] it lends its cancel hook to [LocalInlineEditController] while
 * focused, so the shell's root key handler yields keystrokes to the field instead of firing shortcuts.
 *
 * Blender 風の単一リレーション選択欄。フォーカス時は検索欄、非フォーカス時は現在の関連付けを表示する。
 *
 * @param T The candidate entity type.
 * @param Object? selected The currently bound entity, or null when unbound.
 * @param List candidates Every entity that may be bound, in menu order.
 * @param Function label The display name of an entity (also what the search matches).
 * @param Function icon The type glyph of an entity.
 * @param Function onSelect Binds an entity, or null to clear the binding.
 * @param Modifier modifier The layout modifier (the caller supplies the width).
 * @param Function? onPick Arms an eyedropper pick, or null to omit the eyedropper.
 * @param Boolean picking Whether a pick armed by THIS field is in flight (lights the eyedropper).
 * @param String placeholder The muted text shown when nothing is bound.
 */
@Composable
fun <T> RelationField(
	selected: T?,
	candidates: List<T>,
	label: (T) -> String,
	icon: (T) -> UmamoIcon,
	onSelect: (T?) -> Unit,
	modifier: Modifier = Modifier,
	onPick: (() -> Unit)? = null,
	picking: Boolean = false,
	placeholder: String = "",
) {
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	val typography = LocalUmamoTypography.current
	val controller = LocalInlineEditController.current
	val focusManager = LocalFocusManager.current
	val density = LocalDensity.current
	val listState = rememberLazyListState()

	var focused by remember { mutableStateOf(false) }
	var query by remember { mutableStateOf("") }
	var highlighted by remember { mutableStateOf(0) }
	var anchorWidthPx by remember { mutableStateOf(0) }

	val selectedLabel = selected?.let(label) ?: ""
	// The field is a search box while focused and a read-out of the binding otherwise.
	val fieldText = if (focused) query else selectedLabel
	val filtered =
		if (query.isBlank()) {
			candidates
		} else {
			candidates.filter { candidate -> label(candidate).contains(query.trim(), ignoreCase = true) }
		}
	// Filtering can shrink the list under the highlight, so clamp at read rather than on every edit.
	val safeIndex = highlighted.coerceIn(0, maxOf(0, filtered.size - 1))
	val open = focused

	LaunchedEffect(query) {
		highlighted = 0
	}
	LaunchedEffect(safeIndex, open, filtered.size) {
		if (open && filtered.isNotEmpty()) {
			listState.scrollToItem(safeIndex)
		}
	}
	// Drop the lent cancel hook if the field disposes while focused (a section switch mid-edit).
	DisposableEffect(Unit) {
		onDispose {
			if (focused) {
				controller.cancel = null
			}
		}
	}

	val commit: (T?) -> Unit = { value ->
		onSelect(value)
		query = ""
		// ONLY when this field actually holds focus.  The clear "x" is shown while unfocused, and clearing
		// focus from there would null the shell root's focus owner - killing every keyboard shortcut until
		// Tab traversal reclaims it (the same hazard IconButton.suppressFocus exists to avoid).  Blurring a
		// focused field is safe: dropping the lent inline-edit hook triggers the shell's focus-reclaim.
		if (focused) {
			focusManager.clearFocus()
		}
	}

	Box(
		modifier =
			modifier
				.onSizeChanged { size -> anchorWidthPx = size.width }
				// Navigation keys are claimed ahead of the focused field; ordinary typing falls through to it.
				.onPreviewKeyEvent { event ->
					if (!open || event.type != KeyEventType.KeyDown) {
						return@onPreviewKeyEvent false
					}
					when (event.key) {
						Key.DirectionDown -> {
							if (filtered.isNotEmpty()) {
								highlighted = (safeIndex + 1) % filtered.size
							}
							true
						}

						Key.DirectionUp -> {
							if (filtered.isNotEmpty()) {
								highlighted = (safeIndex - 1 + filtered.size) % filtered.size
							}
							true
						}

						Key.Enter, Key.NumPadEnter -> {
							filtered.getOrNull(safeIndex)?.let { candidate -> commit(candidate) }
							true
						}

						Key.Escape -> {
							query = ""
							focusManager.clearFocus()
							true
						}

						else -> false
					}
				},
	) {
		Row(
			modifier =
				Modifier
					.fillMaxWidth()
					.height(FIELD_CONTROL_HEIGHT)
					.clip(shapes.small)
					.background(colors.tabBackground)
					.border(1.dp, colors.controlBorder, shapes.small)
					.padding(horizontal = 4.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			val leadingGlyph = selected?.let(icon)
			if (leadingGlyph != null) {
				Canvas(modifier = Modifier.size(RELATION_GLYPH_SIZE)) {
					drawIcon(leadingGlyph, colors.textMuted)
				}
				Spacer(modifier = Modifier.width(4.dp))
			}
			BasicTextField(
				value = fieldText,
				onValueChange = { newText -> query = newText },
				textStyle = typography.bodySmall.copy(color = colors.text),
				singleLine = true,
				cursorBrush = SolidColor(colors.text),
				modifier =
					Modifier
						.weight(1f)
						.onFocusChanged { focusState ->
							// hasFocus (not isFocused): BasicTextField focuses an internal child.
							if (focusState.hasFocus) {
								if (!focused) {
									focused = true
									query = ""
									controller.cancel = { focusManager.clearFocus() }
								}
							} else if (focused) {
								focused = false
								query = ""
								controller.cancel = null
							}
						},
				decorationBox = { inner ->
					Box(contentAlignment = Alignment.CenterStart) {
						if (fieldText.isEmpty()) {
							Text(
								text = placeholder,
								style = typography.bodySmall,
								color = colors.textMuted,
								maxLines = 1,
							)
						}
						inner()
					}
				},
			)
			if (selected != null && !focused) {
				IconButton(
					icon = LocalUmamoIcons.close,
					onClick = { commit(null) },
					contentDescription = placeholder,
					size = DpSize(16.dp, 16.dp),
					glyphSize = 12.dp,
				)
			}
			if (onPick != null) {
				IconButton(
					icon = LocalUmamoIcons.eyedropper,
					onClick = onPick,
					contentDescription = placeholder,
					size = DpSize(16.dp, 16.dp),
					glyphSize = 12.dp,
					active = picking,
				)
			}
		}
		if (open) {
			val anchorWidth = with(density) { anchorWidthPx.toDp() }
			Popup(
				popupPositionProvider = BelowAnchorPositionProvider,
				onDismissRequest = { focusManager.clearFocus() },
				// NOT focusable: a focusable popup would blur the search field the moment it opened.
				properties = PopupProperties(focusable = false),
			) {
				Surface(color = colors.menuBackground, shape = shapes.medium) {
					Box {
						// Pinned to the field's own width: a LazyColumn fills whatever max width it is handed,
						// and inside a popup that is the whole window, so an unbounded one spans the screen.
						LazyColumn(
							state = listState,
							modifier =
								Modifier
									.width(anchorWidth.coerceAtLeast(RELATION_DROPDOWN_MIN_WIDTH))
									.heightIn(max = RELATION_DROPDOWN_MAX_HEIGHT)
									.padding(vertical = 2.dp),
						) {
							itemsIndexed(filtered) { index, candidate ->
								RelationCandidateRow(
									label = label(candidate),
									icon = icon(candidate),
									highlighted = index == safeIndex,
									onClick = { commit(candidate) },
								)
							}
						}
						// matchParentSize keeps the scrollbar a pure overlay - it fills this box rather than the
						// popup's window-sized constraints, so it cannot inflate the dropdown to full height.
						Box(modifier = Modifier.matchParentSize()) {
							VerticalScrollbarOverlay(listState)
						}
					}
				}
			}
		}
	}
}

/**
 * One candidate row in a [RelationField]'s dropdown: the entity's type glyph and name, lit when it is the
 * keyboard highlight.  The row declines focus so clicking it cannot blur the search field out from under
 * the click, which would tear the popup down before the click resolved.
 *
 * @param String label The entity's display name.
 * @param UmamoIcon icon The entity's type glyph.
 * @param Boolean highlighted Whether this row is the current keyboard highlight.
 * @param Function onClick Binds this candidate.
 */
@Composable
private fun RelationCandidateRow(label: String, icon: UmamoIcon, highlighted: Boolean, onClick: () -> Unit) {
	val colors = LocalUmamoColors.current
	Row(
		modifier =
			Modifier
				.fillMaxWidth()
				.padding(horizontal = 4.dp, vertical = 1.dp)
				.focusProperties { canFocus = false }
				.clickable(onClick = onClick)
				.background(
					color = if (highlighted) colors.rowHover else Color.Transparent,
					shape = LocalUmamoShapes.current.small,
				)
				.padding(horizontal = 4.dp, vertical = 3.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Canvas(modifier = Modifier.size(RELATION_GLYPH_SIZE)) {
			drawIcon(icon, colors.textMuted)
		}
		Spacer(modifier = Modifier.width(6.dp))
		Text(
			text = label,
			style = LocalUmamoTypography.current.bodySmall,
			color = colors.text,
			maxLines = 1,
		)
	}
}
