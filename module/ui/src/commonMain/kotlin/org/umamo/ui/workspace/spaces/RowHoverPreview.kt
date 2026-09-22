package org.umamo.ui.workspace.spaces

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import kotlinx.coroutines.delay
import org.umamo.ui.workspace.rowdrag.RowCoordinatesHolder

/*
 * The hover art preview a list space pops beside a rested-on row, shared by the Outliner and the Sources
 * space so both keep one rest delay and one ownership rule.  A space holds one state, each row reports
 * its own hover into it, and the space draws the one preview the state shows beside its row
 * (RowThumbnailPreview).  What a row previews is the space's business: the state carries only the row's
 * key, which the space resolves back to its art.
 */

/** Pause the pointer must rest on a row before its art preview pops, so a sweep down the list does not flicker. */
private const val ROW_HOVER_PREVIEW_DELAY_MILLIS = 10L

/**
 * A pending art preview: which row is hovered, the name shown under its art, and the row's window bounds
 * the preview anchors beside.
 *
 * @property Key    key       The hovered row's identity, which the space resolves back to its art.
 * @property String name      The row's display name (document data, not localized).
 * @property Rect   rowBounds The hovered row's bounds in window pixels.
 */
internal data class RowHoverPreview<Key>(val key: Key, val name: String, val rowBounds: Rect)

/**
 * One list space's hover preview: the row under the pointer the moment it is hovered, and the row whose
 * preview shows, which lags it by the rest delay.
 *
 * Only the row that owns the current preview may clear it.  Moving onto the next row reports that row
 * first, so the old row's exit, arriving after, must not undo it.
 */
@Stable
internal class RowHoverPreviewState<Key> {
	/** The row under the pointer, or null when none reports one. */
	var hovered: RowHoverPreview<Key>? by mutableStateOf(null)
		private set

	/** The row whose preview shows: [hovered] once the pointer has rested on it, or null. */
	var shown: RowHoverPreview<Key>? by mutableStateOf(null)
		internal set

	/**
	 * Reports a row the pointer is over, replacing whichever row was.
	 *
	 * @param RowHoverPreview preview The hovered row.
	 */
	fun report(preview: RowHoverPreview<Key>) {
		hovered = preview
	}

	/**
	 * Withdraws the row [key] reported, when it still owns the preview.
	 *
	 * @param Key key The row leaving.
	 */
	fun clear(key: Key) {
		if (hovered?.key == key) {
			hovered = null
		}
	}
}

/**
 * The space's hover preview state, with the rest delay running: [RowHoverPreviewState.shown] follows
 * [RowHoverPreviewState.hovered] only once the hovered row has held for the delay.  The delay re-arms on
 * every change of row, so only a rested pointer surfaces a preview.
 *
 * @return RowHoverPreviewState The state.
 */
@Composable
internal fun <Key> rememberRowHoverPreviewState(): RowHoverPreviewState<Key> {
	val state = remember { RowHoverPreviewState<Key>() }
	LaunchedEffect(state.hovered) {
		val pending = state.hovered
		if (pending == null) {
			state.shown = null
		} else {
			delay(ROW_HOVER_PREVIEW_DELAY_MILLIS)
			state.shown = pending
		}
	}
	return state
}

/**
 * Reports one row's hover into [state]: the row's preview while it is hovered, withdrawn when the pointer
 * leaves, when the row stops having art to preview, or when the row leaves the list (scrolled away or
 * filtered out), so a row that is gone never leaves its preview standing.
 *
 * Keyed on the row's identity, so a row slot that comes to hold another item reports the new one.
 *
 * @param RowHoverPreviewState state        The space's hover preview state.
 * @param Key                  key          The row's identity.
 * @param String               name         The row's display name, shown under its art.
 * @param Boolean              hovered      Whether the pointer is over the row.
 * @param Boolean              enabled      Whether the row has art to preview.
 * @param RowCoordinatesHolder boundsHolder The row's coordinates, read for its window bounds.
 */
@Composable
internal fun <Key> ReportRowHover(
	state: RowHoverPreviewState<Key>,
	key: Key,
	name: String,
	hovered: Boolean,
	enabled: Boolean,
	boundsHolder: RowCoordinatesHolder,
) {
	LaunchedEffect(hovered, enabled, key) {
		if (hovered && enabled) {
			val bounds = boundsHolder.coordinates?.boundsInWindow() ?: return@LaunchedEffect
			state.report(RowHoverPreview(key, name, bounds))
		} else {
			state.clear(key)
		}
	}
	DisposableEffect(state, key) {
		onDispose { state.clear(key) }
	}
}