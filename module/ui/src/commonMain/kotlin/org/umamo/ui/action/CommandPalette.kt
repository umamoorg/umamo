package org.umamo.ui.action

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.kit.Surface
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.TextField
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.search_hint
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography

/**
 * The searchable command overlay: a scrim over the shell with a search field and a filtered list of the
 * registered [Command]s, each invokable by click or by keyboard.  A discoverability surface that falls
 * almost free out of the registry - the caller passes the already-filtered, user-facing commands
 * (title != null) in registration order.
 *
 * It is keyboard-first (Blender-like): the search field autofocuses on open, typing narrows the list
 * (ranked by match quality; a blank query keeps registration order), Up/Down move a highlighted
 * selection, Enter invokes it, and Escape (or a scrim click) dismisses.  The
 * shell yields every non-Escape key to this overlay while it is open, so the field and the navigation keys
 * receive input; the bound accelerator for each command is shown on the right via the active [Keymap].
 *
 * 検索可能なコマンドパレット。検索欄は自動でフォーカスされ、入力で絞り込み、上下で選択、Enter で実行、Esc で閉じる。
 *
 * @param List commands The commands to list (already filtered to titled, user-facing ones; registry order).
 * @param Function onDismiss Called to close the palette without invoking.
 * @param Function onInvoke Called with the chosen command.
 */
@Composable
fun CommandPalette(commands: List<Command>, onDismiss: () -> Unit, onInvoke: (Command) -> Unit) {
	val colors = LocalUmamoColors.current
	val typography = LocalUmamoTypography.current
	val keymap = LocalKeymap.current

	var query by remember { mutableStateOf("") }
	// The highlighted row, driven by Up/Down and Enter.  Clamped at read (filtering can shrink the list
	// out from under it), and reset to the top whenever the query changes the result set (the effect below).
	var selectedIndex by remember { mutableStateOf(0) }
	val searchFocus = remember { FocusRequester() }
	val listState = rememberLazyListState()

	// Labels need stringResource (a composable), so resolve them here; filtering is then plain Kotlin.  The
	// list is small (~a dozen commands) so resolving every label each recomposition is cheap.
	val labeled =
		commands.map { command ->
			command to (command.title?.let { title -> stringResource(title) } ?: command.id)
		}
	val filtered =
		if (query.isBlank()) {
			labeled
		} else {
			// Ranked match over the visible label or the dotted id ("view.zoom" works directly): a label
			// prefix beats a word start beats a substring beats an id-only hit - see CommandSearch.
			rankCommandMatches(labeled, query, labelOf = { entry -> entry.second }, idOf = { entry -> entry.first.id })
		}
	val safeIndex = selectedIndex.coerceIn(0, maxOf(0, filtered.size - 1))

	// Reset the highlight to the top result whenever the query (and thus the filtered list) changes.
	LaunchedEffect(query) {
		selectedIndex = 0
	}
	// Autofocus the search field on open.  The palette is composed fresh each time it opens, so Unit keys
	// the effect to that single open.
	LaunchedEffect(Unit) {
		searchFocus.requestFocus()
	}
	// Keep the highlighted row scrolled into view as Up/Down move it.
	LaunchedEffect(safeIndex) {
		if (filtered.isNotEmpty()) {
			listState.scrollToItem(safeIndex)
		}
	}

	Box(
		// Dismiss on an outside pointer tap only - a tap gesture, not a clickable.  A keyboard-activatable
		// clickable scrim would let Space (or Enter) typed in the search field bubble up to it and fire
		// onDismiss, closing the palette mid-search; keyboard dismissal stays Escape (handled by the card's
		// onPreviewKeyEvent and the shell).
		modifier =
			Modifier
				.fillMaxSize()
				.pointerInput(Unit) { detectTapGestures { onDismiss() } },
		contentAlignment = Alignment.TopCenter,
	) {
		Surface(
			modifier =
				Modifier.padding(top = 80.dp).width(480.dp).clickable(enabled = false, onClick = {})
					// onPreviewKeyEvent on this ancestor sees keys before the focused search field, so the
					// navigation keys drive the list while ordinary typing falls through to the field.
					.onPreviewKeyEvent { event ->
						if (event.type != KeyEventType.KeyDown) {
							false
						} else {
							when (event.key) {
								Key.DirectionDown -> {
									if (filtered.isNotEmpty()) {
										selectedIndex = (safeIndex + 1) % filtered.size
									}
									true
								}

								Key.DirectionUp -> {
									if (filtered.isNotEmpty()) {
										selectedIndex = (safeIndex - 1 + filtered.size) % filtered.size
									}
									true
								}

								Key.Enter, Key.NumPadEnter -> {
									filtered.getOrNull(safeIndex)?.let { (command, _) -> onInvoke(command) }
									true
								}
								// Belt-and-suspenders: the shell already consumes Escape to close the palette, but
								// handle it here too so the overlay stands alone if reused.
								Key.Escape -> {
									onDismiss()
									true
								}

								else -> false
							}
						}
					},
			color = colors.menuBackground,
			shape = LocalUmamoShapes.current.medium,
			border = BorderStroke(1.dp, colors.panelBorder),
			shadowElevation = 8.dp,
		) {
			Column(modifier = Modifier.fillMaxWidth()) {
				TextField(
					value = query,
					onValueChange = { newQuery -> query = newQuery },
					modifier = Modifier.fillMaxWidth().focusRequester(searchFocus).padding(8.dp),
					placeholder = stringResource(Res.string.search_hint),
				)
				LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
					itemsIndexed(filtered) { index, entry ->
						val command = entry.first
						val label = entry.second
						val isSelected = index == safeIndex
						Row(
							modifier =
								Modifier.fillMaxWidth()
									.padding(horizontal = 4.dp, vertical = 4.dp)
									.background(
										when {
											isSelected -> colors.selection
											else -> Color.Transparent
										},
										shape = LocalUmamoShapes.current.small,
									)
									.clickable { onInvoke(command) }
									.padding(horizontal = 4.dp, vertical = 4.dp),
							verticalAlignment = Alignment.CenterVertically,
						) {
							Text(
								text = label,
								style = typography.bodyMedium,
								color = if (isSelected) colors.selectionText else colors.text,
								modifier = Modifier.weight(1f),
							)
							// The command's bound accelerator (canonical chord), dimmed; omitted when unbound.
							keymap.chordFor(command.id)?.let { chord ->
								Text(
									text = formatAccelerator(chord),
									style = typography.bodySmall,
									color = if (isSelected) colors.selectionText else colors.textMuted,
								)
							}
						}
					}
				}
			}
		}
	}
}
