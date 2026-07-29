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
 * The keyform sheet's per-area view state: the label column's width and which groups are open.
 *
 * On the AreaScope rather than the session because these are how one area is LOOKING at the rig, not what
 * the rig is - two sheets side by side may reasonably be folded differently, and neither belongs in undo.
 */
internal class KeyformSheetViewState {
	/** The label column's width, dragged on the separator. */
	var labelColumnWidth: Dp by mutableStateOf(TRACK_LABEL_COLUMN_DEFAULT_WIDTH)

	/** The group rows whose tracks are shown. */
	var expandedKeys: Set<String> by mutableStateOf(emptySet())

	/**
	 * The selected keys, which is what Delete acts on.
	 *
	 * On the view state rather than remembered against the projection: the projection is rebuilt on every
	 * model change, so keying the selection to it discarded the selection on the user's own edit - and a
	 * click both selects AND scrubs, so even selecting could not survive its own gesture.  Refs that no
	 * longer resolve are pruned at use, which is cheaper and less surprising than clearing wholesale.
	 */
	var selectedKeys: Set<TrackKeyRef> by mutableStateOf(emptySet())

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
	 * The in-flight group drag, as a signed fraction of each dragged key's parameter range, or 0 for none.
	 *
	 * Held on the SHEET rather than in the lane that owns the gesture because the selection it previews
	 * spans rows and sections, and a lane knows only its own marks.  Already clamped to the group's most
	 * constrained member (`limitedDragFraction`), so what is drawn is what the release will commit - a
	 * preview at the raw fraction would show the marks running past a wall they are about to stop at.
	 */
	var dragPreviewFraction: Float by mutableStateOf(0f)

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
