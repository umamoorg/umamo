package org.umamo.ui.workspace.spaces

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp
import org.umamo.runtime.model.ParameterId
import org.umamo.ui.tracks.TRACK_LABEL_COLUMN_DEFAULT_WIDTH
import org.umamo.ui.tracks.TrackWindow

/** The key this space's view state is stored under on its hosting area. */
internal const val KEYFORM_SHEET_VIEW_STATE_KEY = "keyformsheet"

/**
 * The keyform sheet's per-area view state: the label column's width, which groups are open, the zoom window.
 *
 * On the AreaScope rather than the session because these are how one area is LOOKING at the rig, not what
 * the rig is - two sheets side by side may reasonably be folded differently, and none of it belongs in undo.
 *
 * The SELECTED KEYS used to live here on that reasoning and no longer do: undo restores session state, so a
 * selection that undo is meant to bring back has to be session state, and that outranks letting two sheets
 * disagree about what is selected.  It is EditorSession.keySelection now, alongside the object, mesh, and
 * parameter selections, which all made the same trade.
 */
internal class KeyformSheetViewState {
	/** The label column's width, dragged on the separator. */
	var labelColumnWidth: Dp by mutableStateOf(TRACK_LABEL_COLUMN_DEFAULT_WIDTH)

	/** The group rows whose tracks are shown. */
	var expandedKeys: Set<String> by mutableStateOf(emptySet())

	/**
	 * Whether [expandedKeys] has been seeded yet.
	 *
	 * A fresh sheet opens with every group expanded (an all-collapsed sheet looks identical to one with
	 * nothing keyed), but "collapse everything" has to stay reachable - so the seed happens ONCE rather
	 * than whenever the set is empty.
	 */
	var seeded: Boolean = false

	/**
	 * The parameter sections folded away.
	 *
	 * COLLAPSED rather than expanded, so a section that appears later (targeting a second parameter) opens
	 * rather than arriving invisible.  Sections matter for a linked pad, where one parameter's tracks can
	 * bury the other's.
	 */
	var collapsedParameters: Set<ParameterId> by mutableStateOf(emptySet())

	/**
	 * The visible slice of every section's domain.
	 *
	 * ONE window for the whole area, normalized, so zooming works like a timeline's: every track and both
	 * of a linked pad's sections move together.  Per-track zoom has no precedent in any editor with tracks
	 * and would make comparing two rows - the reason the sheet exists - impossible.
	 */
	var window: TrackWindow by mutableStateOf(TrackWindow.Full)

	/** Whether a box-select marquee is awaiting its drag. */
	var boxSelectArmed: Boolean by mutableStateOf(false)

	/**
	 * The in-flight group drag, as a signed fraction of each dragged key's parameter range, or NULL when
	 * no group drag is in flight.
	 *
	 * Null rather than 0f for "none", because 0f is a real state: a group whose most constrained member is
	 * already at its parameter's end clamps the whole drag to a standstill, and the mark under the hand
	 * has to stop with the rest rather than run on to the pointer and snap back on release.
	 *
	 * Held on the SHEET rather than in the lane that owns the gesture because the selection it previews
	 * spans rows and sections, and a lane knows only its own marks.  Already clamped to the group's most
	 * constrained member (`limitedDragFraction`), so what is drawn is what the release will commit.
	 */
	var dragPreviewFraction: Float? by mutableStateOf(null)

	/**
	 * Where each lane sits, in WINDOW coordinates, so a marquee drawn over the whole scrolling sheet can
	 * say which rows it crossed without either side knowing the other's scroll offset.
	 *
	 * A plain map rather than snapshot state: it is written during layout and only ever read when a
	 * marquee is released, so observing it would recompose the sheet on every scroll for nothing.
	 */
	val laneBounds: MutableMap<String, Rect> = mutableMapOf()

	/** Whether GEOMETRY tracks are listed. */
	var showGeometry: Boolean by mutableStateOf(true)

	/** Whether CHANNEL tracks (opacity, draw order, the colours, the flips) are listed. */
	var showChannels: Boolean by mutableStateOf(true)

	/** Whether BLEND-SHAPE tracks are listed. */
	var showBlendShapes: Boolean by mutableStateOf(true)

	/** Whether every track kind is shown - what the header's funnel glyph reports. */
	val isUnfiltered: Boolean get() = showGeometry && showChannels && showBlendShapes
}
