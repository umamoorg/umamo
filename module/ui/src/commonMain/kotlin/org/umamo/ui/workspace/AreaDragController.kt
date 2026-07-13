package org.umamo.ui.workspace

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** The drag distance a corner must travel inside its own area before a split is armed and its axis locked. */
internal val SPLIT_ARM_DISTANCE = 24.dp

/** Which corner of a leaf area a drag was started from. */
enum class AreaCorner {
	TopLeft,
	TopRight,
	BottomLeft,
	BottomRight,
}

/**
 * The live state of an in-flight corner drag.  All coordinates are content-local pixels (relative to
 * the shell content Box, the one shared space the leaf rects and the overlay also use).  Null on the
 * controller means no drag is active.
 *
 * A drag resolves to one outcome at any moment: a valid JOIN (over a different aligned-sibling leaf), a
 * valid DOCK (over a different non-aligned leaf - the source relocates to a workspace edge), a valid SPLIT
 * (back inside the source area, dragged past the arm distance, axis locked), or none (a no-op on release).
 *
 * 進行中のコーナードラッグの状態。座標はシェル内容ボックス基準のローカルピクセル。結合・ドック・分割・無効。
 *
 * @property String sourceId The leaf the drag started from (the survivor on a join, the docked/split leaf).
 * @property AreaCorner startCorner The corner grabbed.
 * @property Offset startPointer Where the drag began, content-local (for the split arm distance and axis).
 * @property Offset pointer The current pointer position, content-local.
 * @property String? targetId The leaf currently under the pointer, or null if over none.
 * @property Boolean validJoin True when releasing now would perform a valid aligned-sibling join.
 * @property Boolean validDock True when releasing now would dock the source to a workspace edge (non-aligned).
 * @property DockEdge? dockEdge The workspace edge the source would dock to (from the cursor's zone in the target).
 * @property Float dockRatio The docked strip's fraction of the perpendicular axis (keeps the source's size).
 * @property SplitOrientation? splitOrientation The locked split axis once armed (persists for the drag), else null.
 * @property Float splitRatio The divider fraction (original side) when splitting, following the cursor.
 * @property Boolean validSplit True when releasing now would split the source area.
 */
data class AreaDragState(
	val sourceId: String,
	val startCorner: AreaCorner,
	val startPointer: Offset,
	val pointer: Offset,
	val targetId: String?,
	val validJoin: Boolean,
	val validDock: Boolean,
	val dockEdge: DockEdge?,
	val dockRatio: Float,
	val splitOrientation: SplitOrientation?,
	val splitRatio: Float,
	val validSplit: Boolean,
)

/** The docked strip's fraction of the axis when the source's own size cannot be measured. */
private const val DEFAULT_DOCK_FRACTION = 0.4f

/**
 * Shared, cross-area state for the corner-drag area gesture, held once per shell and exposed through
 * [LocalAreaDragController].  It owns three things the gesture needs but no single leaf has: every
 * leaf's on-screen rectangle (content-local), the current tree root (to test sibling-ness), and the
 * in-flight [dragState] the overlay renders.  It is a plain holder (not a composable); its state is
 * Compose snapshot state so reads in the overlay recompose on drag moves.
 *
 * The gesture itself lives in the corner hotspot (Compose can't hand an in-flight gesture to the shell
 * overlay): the corner calls [beginDrag] / [updateDrag] / [endDrag], and the shell applies the returned
 * command.  [cancelDrag] backs the Escape key.  Dragging into a different aligned sibling joins; dragging
 * back inside the source area (past [splitThresholdPx]) splits it.
 *
 * コーナードラッグによるエリア操作の共有状態。各葉の矩形・現在のツリー・進行中ドラッグを保持する。
 */
@Stable
class AreaDragController {
	// Content-local rect per leaf id; written by each AreaLeaf's onGloballyPositioned, read by the overlay.
	private val bounds = mutableStateMapOf<String, Rect>()

	// The shell content Box's coordinates (the shared space) and the current tree root, both pushed by the
	// shell.  Plain fields: read at gesture time, not observed for recomposition.
	var contentCoords: LayoutCoordinates? = null
	var currentRoot: AreaNode? = null

	// The arm distance in px (the shell sets a density-correct value); below it a split is not yet armed.
	var splitThresholdPx: Float = 0f

	var dragState by mutableStateOf<AreaDragState?>(null)
		private set

	/** Whether a corner drag is currently in flight (used to gate the Escape-to-cancel key). */
	val isDragging: Boolean
		get() = dragState != null

	/**
	 * Records (or clears) a leaf's content-local rectangle.  Called from each leaf's layout callback.
	 *
	 * @param String areaId The leaf id.
	 * @param Rect rect The leaf's bounds in content-local space.
	 */
	fun putBounds(areaId: String, rect: Rect) {
		bounds[areaId] = rect
	}

	/**
	 * Drops a leaf's rectangle when the leaf leaves composition (e.g. it was consumed by a join).
	 *
	 * @param String areaId The leaf id to forget.
	 */
	fun removeBounds(areaId: String) {
		bounds.remove(areaId)
	}

	/**
	 * Returns a leaf's content-local rectangle, or null if it has not been measured.  A snapshot read,
	 * so the overlay recomposes when the rect changes.
	 *
	 * @param String areaId The leaf id.
	 * @return Rect? The leaf's bounds, or null.
	 */
	fun boundsOf(areaId: String): Rect? = bounds[areaId]

	/**
	 * Begins a corner drag from [sourceId]'s [corner] at the content-local [pointer].
	 *
	 * @param String sourceId The leaf the drag starts from.
	 * @param AreaCorner corner The grabbed corner.
	 * @param Offset pointer The starting pointer position, content-local.
	 */
	fun beginDrag(sourceId: String, corner: AreaCorner, pointer: Offset) {
		// startPointer == pointer and no locked axis yet, so a split cannot arm until the pointer travels.
		dragState = resolve(sourceId, corner, pointer, pointer, lockedOrientation = null)
	}

	/**
	 * Updates the in-flight drag to a new pointer position, recomputing the hovered target, join validity,
	 * and split preview.  Carries the locked split axis forward so it does not flip mid-drag.  A no-op if
	 * no drag is active (e.g. it was cancelled by Escape).
	 *
	 * @param Offset pointer The current pointer position, content-local.
	 */
	fun updateDrag(pointer: Offset) {
		val active = dragState ?: return
		dragState = resolve(active.sourceId, active.startCorner, active.startPointer, pointer, active.splitOrientation)
	}

	/**
	 * Ends the drag, always clearing the state.  Returns the command to apply: a join when released over a
	 * valid aligned-sibling target, a split when released inside the armed source area, else null.
	 *
	 * @return AreaCommand? The structural edit to apply, or null.
	 */
	fun endDrag(): AreaCommand? {
		val active = dragState
		dragState = null
		if (active == null) {
			return null
		}
		val target = active.targetId
		if (active.validJoin && target != null) {
			return AreaCommand.JoinAreas(survivorId = active.sourceId, consumedId = target)
		}
		val edge = active.dockEdge
		if (active.validDock && edge != null) {
			return AreaCommand.DockArea(active.sourceId, edge, active.dockRatio)
		}
		val orientation = active.splitOrientation
		if (active.validSplit && orientation != null) {
			return AreaCommand.SplitArea(active.sourceId, orientation, active.splitRatio)
		}
		return null
	}

	/** Cancels any in-flight drag without applying a command (backs the Escape key). */
	fun cancelDrag() {
		dragState = null
	}

	/**
	 * Builds a drag state for a pointer position: hit-tests the leaf rectangles, then decides whether the
	 * drag is a valid join (over a different sibling) or a split of the source area (back inside it, armed).
	 *
	 * @param String sourceId The drag's source leaf.
	 * @param AreaCorner corner The grabbed corner.
	 * @param Offset startPointer Where the drag began, content-local.
	 * @param Offset pointer The current pointer position, content-local.
	 * @param SplitOrientation? lockedOrientation The split axis already locked this drag, or null.
	 * @return AreaDragState The recomputed state.
	 */
	private fun resolve(
		sourceId: String,
		corner: AreaCorner,
		startPointer: Offset,
		pointer: Offset,
		lockedOrientation: SplitOrientation?,
	): AreaDragState {
		val targetId = bounds.entries.firstOrNull { entry -> entry.value.contains(pointer) }?.key
		val overSource = targetId == sourceId

		// Split: back inside the source area.  Lock the axis once the pointer has travelled the arm distance
		// (from the dominant drag direction), then keep it; the divider follows the cursor along that axis.
		var splitOrientation = lockedOrientation
		var validSplit = false
		var splitRatio = 0.5f
		val sourceRect = bounds[sourceId]
		if (overSource && sourceRect != null) {
			if (splitOrientation == null) {
				val delta = pointer - startPointer
				if (delta.getDistance() >= splitThresholdPx) {
					splitOrientation =
						if (abs(delta.x) >= abs(delta.y)) {
							SplitOrientation.Horizontal
						} else {
							SplitOrientation.Vertical
						}
				}
			}
			val orientation = splitOrientation
			if (orientation != null) {
				splitRatio = ratioWithin(sourceRect, pointer, orientation)
				validSplit = true
			}
		}

		// Over a different leaf: an aligned sibling joins (collapse); any other leaf docks the source to a
		// workspace edge (the edge is the cursor's zone within the target; the strip keeps the source's size).
		val root = currentRoot
		val onDifferentLeaf = !overSource && targetId != null && root != null
		val aligned = onDifferentLeaf && areDirectSiblings(root, sourceId, targetId)
		val validJoin = aligned
		var validDock = false
		var dockEdge: DockEdge? = null
		var dockRatio = DEFAULT_DOCK_FRACTION
		if (onDifferentLeaf && !aligned) {
			val targetRect = bounds[targetId]
			if (targetRect != null) {
				dockEdge = edgeZoneOf(targetRect, pointer)
				dockRatio = dockStripFractionOf(sourceId, dockEdge)
				validDock = true
			}
		}

		return AreaDragState(
			sourceId = sourceId,
			startCorner = corner,
			startPointer = startPointer,
			pointer = pointer,
			targetId = targetId,
			validJoin = validJoin,
			validDock = validDock,
			dockEdge = dockEdge,
			dockRatio = dockRatio,
			splitOrientation = splitOrientation,
			splitRatio = splitRatio,
			validSplit = validSplit,
		)
	}

	/**
	 * The workspace edge a dock targets, from where [pointer] sits within the target [rect]: the rect is
	 * cut into four triangles by its diagonals, and the pointer's triangle names the nearest edge.
	 *
	 * @param Rect rect The target leaf's rectangle, content-local.
	 * @param Offset pointer The pointer position, content-local.
	 * @return DockEdge The nearest edge of the target.
	 */
	private fun edgeZoneOf(rect: Rect, pointer: Offset): DockEdge {
		val fractionX = if (rect.width > 0f) (pointer.x - rect.left) / rect.width else 0.5f
		val fractionY = if (rect.height > 0f) (pointer.y - rect.top) / rect.height else 0.5f
		val offsetX = fractionX - 0.5f
		val offsetY = fractionY - 0.5f
		return if (abs(offsetX) >= abs(offsetY)) {
			if (offsetX < 0f) DockEdge.Left else DockEdge.Right
		} else {
			if (offsetY < 0f) DockEdge.Top else DockEdge.Bottom
		}
	}

	/**
	 * The docked strip's fraction of the perpendicular axis, taken from the source leaf's current size so
	 * the docked area keeps roughly its dimensions.  Falls back to [DEFAULT_DOCK_FRACTION] if unmeasured.
	 *
	 * @param String sourceId The leaf being docked.
	 * @param DockEdge edge The edge it docks to (selects which axis the strip spans).
	 * @return Float The clamped strip fraction.
	 */
	private fun dockStripFractionOf(sourceId: String, edge: DockEdge): Float {
		val sourceRect = bounds[sourceId]
		val content = contentCoords?.size
		if (sourceRect == null || content == null) {
			return DEFAULT_DOCK_FRACTION
		}
		val fraction =
			when (edge) {
				DockEdge.Top, DockEdge.Bottom ->
					if (content.height > 0) sourceRect.height / content.height.toFloat() else DEFAULT_DOCK_FRACTION
				DockEdge.Left, DockEdge.Right ->
					if (content.width > 0) sourceRect.width / content.width.toFloat() else DEFAULT_DOCK_FRACTION
			}
		return fraction.coerceIn(MIN_RATIO, 1f - MIN_RATIO)
	}

	/**
	 * The divider fraction (original side) for a split at [pointer] within [rect] along [orientation],
	 * clamped so neither side falls below [MIN_RATIO].
	 *
	 * @param Rect rect The source area rectangle, content-local.
	 * @param Offset pointer The pointer position, content-local.
	 * @param SplitOrientation orientation The split axis.
	 * @return Float The clamped divider fraction.
	 */
	private fun ratioWithin(rect: Rect, pointer: Offset, orientation: SplitOrientation): Float {
		val fraction =
			when (orientation) {
				SplitOrientation.Horizontal ->
					if (rect.width > 0f) (pointer.x - rect.left) / rect.width else 0.5f
				SplitOrientation.Vertical ->
					if (rect.height > 0f) (pointer.y - rect.top) / rect.height else 0.5f
			}
		return fraction.coerceIn(MIN_RATIO, 1f - MIN_RATIO)
	}
}

/**
 * The active [AreaDragController] for the shell, or null where no shell provides one (e.g. an isolated
 * preview).  staticCompositionLocalOf - like [LocalViewportHost] / [LocalSpaceRegistry] - so the
 * recursive area tree need not thread the controller through its parameter list.
 */
val LocalAreaDragController = staticCompositionLocalOf<AreaDragController?> { null }
