package org.umamo.ui.viewport.gizmo

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.EditorSession
import org.umamo.edit.Selection
import org.umamo.edit.SelectionOps
import org.umamo.edit.SelectionTarget
import org.umamo.edit.structure.selectableOf
import org.umamo.render.ViewportCamera
import org.umamo.render.pick.PickCandidate
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel

/**
 * Resolves a primary click against the object selection: a hit with a membership modifier toggles
 * (Blender-style, so a second modified click deselects), a plain hit replaces, a modified click on
 * empty canvas keeps the selection, and a plain one clears it.
 *
 * @param Selection current The committed object selection.
 * @param SelectionTarget.Drawable? target The selectable drawable under the cursor, or null on empty canvas.
 * @param Boolean toggleMembership True when a membership modifier (Shift / Ctrl / Meta) is held.
 * @return Selection The selection the click produces.
 */
internal fun resolveObjectClickSelection(
	current: Selection,
	target: SelectionTarget.Drawable?,
	toggleMembership: Boolean,
): Selection =
	when {
		target != null && toggleMembership -> SelectionOps.toggle(current, target)
		target != null -> SelectionOps.replace(target)
		toggleMembership -> current
		else -> SelectionOps.clear()
	}

/** The outcome of an Alt click over a candidate stack: open the overlap picker, select directly, or nothing. */
internal sealed class AltPickResolution {
	/** Two or more candidates are stacked under the cursor: the overlap picker disambiguates. */
	class ShowOverlap(val candidates: List<PickCandidate>) : AltPickResolution()

	/** Exactly one candidate: select it directly, no picker needed. */
	class SelectSingle(val id: DrawableId) : AltPickResolution()

	/** Empty canvas: the selection stays as-is (Alt is the disambiguate gesture, not a clear). */
	object None : AltPickResolution()
}

/**
 * Resolves an Alt click over the (already selectable-filtered) candidate stack under the cursor.
 *
 * @param List<PickCandidate> candidates The selectable candidates under the cursor, front-to-back.
 * @return AltPickResolution What the click does.
 */
internal fun resolveAltOverlapPick(candidates: List<PickCandidate>): AltPickResolution =
	when {
		candidates.size > 1 -> AltPickResolution.ShowOverlap(candidates)
		candidates.size == 1 -> AltPickResolution.SelectSingle(candidates.first().id)
		else -> AltPickResolution.None
	}

/**
 * Resolves a finished box drag against the object selection: additive (Shift) keeps the current targets
 * and adds the enclosed drawables, the last one becoming active (or the current active surviving when the
 * box enclosed nothing); plain replaces the selection with the enclosed set.  One rule for every surface
 * that boxes whole drawables - the viewport by centroid, the UV editor by any island vertex.
 *
 * @param Selection current The committed object selection.
 * @param List<SelectionTarget.Drawable> enclosed The enclosed, selectable drawables in enclosure order.
 * @param Boolean additive True when Shift extends the selection.
 * @return Selection The selection the box produces.
 */
internal fun resolveObjectBoxSelection(
	current: Selection,
	enclosed: List<SelectionTarget.Drawable>,
	additive: Boolean,
): Selection =
	if (additive) {
		Selection(current.targets + enclosed, enclosed.lastOrNull() ?: current.active)
	} else {
		Selection(enclosed.toSet(), enclosed.lastOrNull())
	}

/**
 * The selectable drawables among [ids], as selection targets in the same order: a region selection
 * passes over what cannot be selected, the way a click passes through it.
 *
 * @param Iterable<DrawableId> ids The drawables a region enclosed.
 * @param PuppetModel model The model the selectable flags live in.
 * @return List<SelectionTarget.Drawable> The selectable ones, in the order given.
 */
internal fun selectableDrawableTargets(ids: Iterable<DrawableId>, model: PuppetModel): List<SelectionTarget.Drawable> =
	ids.map { drawableId -> SelectionTarget.Drawable(drawableId) }.filter { target -> model.selectableOf(target) }

/**
 * The marquee (box + circle) machinery over whole drawables, for every surface that selects objects: the
 * stroke is seeded from and committed to the session's object selection, the wheel resizes the session's
 * brush, and a right-click inside the circle tool leaves it.  What differs per surface passes in: how a
 * stamp paints, how a finished box applies, and the viewport's snapshot and GPU tint.  The stamp has no
 * default on purpose - an identity default would quietly turn a new surface's circle tool into a no-op -
 * and the preview never defaults to the session's tint, which only the viewport publishes.
 *
 * @param EditorSession session The session owning the object selection and the armed tool.
 * @param Function stampStroke Applies one brush stamp (working, erasing, center, radiusPx, camera, size).
 * @param Function applyBox Applies a finished box drag (start, end, additive, camera, size).
 * @param Function onStrokeBegin Runs before the first stamp of a stroke; defaults to nothing.
 * @param Function previewStroke Publishes the live stroke, and null when it ends; defaults to nothing.
 * @return MarqueeSelectController<Selection> The marquee.
 */
internal fun objectMarquee(
	session: EditorSession,
	stampStroke: (Selection, Boolean, Offset, Float, ViewportCamera, IntSize) -> Selection,
	applyBox: (Offset, Offset, Boolean, ViewportCamera, IntSize) -> Unit,
	onStrokeBegin: () -> Unit = {},
	previewStroke: (Selection?) -> Unit = {},
): MarqueeSelectController<Selection> =
	MarqueeSelectController(
		seedStroke = { session.selection.value },
		stampStroke = stampStroke,
		commitStroke = { stroke -> session.setSelection(stroke) },
		applyBox = applyBox,
		setCircleRadius = { radiusPx -> session.setCircleRadius(radiusPx) },
		clearTool = { session.clearSelectTool() },
		onStrokeBegin = onStrokeBegin,
		previewStroke = previewStroke,
		setGestureActive = { active -> session.setViewportGestureActive(active) },
	)

/**
 * The object-selecting surfaces' pointer flow, shared by the 2D viewport's Object mode and the UV editor's:
 * the box select (un-armed and armed, one flow - see BoxSelectFlow) with the object domain's click, which
 * picks on the release of a sub-threshold click (plain replaces, Shift / Ctrl toggles membership, an Alt
 * click resolves the overlap stack, an unmodified click on empty canvas clears).
 *
 * The domain seams pass in as constructor callbacks, the [MarqueeSelectController] pattern: the 2D
 * viewport binds the render service's raster pickers and the world 2D cursor; a UV object mode binds
 * its own display-space island hit tests and the UV cursor over the SAME session selection.  The
 * shared parts - the selection store and the selectable filter - read the session directly, like
 * [MeshPickController] does for the mesh-element domain.
 *
 * @param EditorSession session The session owning the model, the object selection, and the gesture-active flag.
 * @param MarqueeSelectController<Selection> marquee The box machinery this flow rubber-bands through.
 * @param Function pickTopmost The front-most selectable-unfiltered drawable at an area-local point, or null.
 * @param Function pickStack The full candidate stack at an area-local point, front-to-back, unfiltered.
 * @param Function onOverlapRequest Opens the overlap picker for an Alt click with 2+ candidates.
 * @param Function placeCursor Places the space's cursor at a Shift+RightClick, given the unprojected point.
 * @param Function onBoxBegin Runs at the press that starts the rubber-band (the viewport refreshes its
 *   selection anchors here, the [MarqueeSelectController] onStrokeBegin precedent); defaults to nothing.
 */
internal class ObjectPickController(
	private val session: EditorSession,
	marquee: MarqueeSelectController<Selection>,
	private val pickTopmost: (Offset) -> DrawableId?,
	private val pickStack: (Offset) -> List<PickCandidate>,
	private val onOverlapRequest: (Offset, List<PickCandidate>) -> Unit,
	placeCursor: (Float, Float) -> Unit,
	onBoxBegin: () -> Unit = {},
) {
	// The box gesture, with the object click as its sub-threshold release.
	private val boxFlow =
		BoxSelectFlow(
			session = session,
			marquee = marquee,
			placeCursor = placeCursor,
			onClick = { event, change ->
				val modifiers = event.keyboardModifiers
				applyClickPick(
					position = change.position,
					toggleMembership = modifiers.isCtrlPressed || modifiers.isMetaPressed || modifiers.isShiftPressed,
					alt = modifiers.isAltPressed,
				)
			},
			onBoxBegin = onBoxBegin,
		)

	/**
	 * Handles one pointer event while no transform or circle tool owns the area: the box select, armed or
	 * not, and the click pick.
	 *
	 * @param PointerEvent event The full pointer event (buttons and modifiers).
	 * @param PointerInputChange change The event's first change (position and consumption).
	 * @param Boolean armed True while Box select is armed in this area.
	 * @param ViewportCamera camera The area camera.
	 * @param IntSize size The area size in pixels.
	 */
	fun handleEvent(event: PointerEvent, change: PointerInputChange, armed: Boolean, camera: ViewportCamera, size: IntSize) {
		boxFlow.handleEvent(event, change, armed, camera, size)
	}

	/**
	 * Abandons an in-flight box, dropping the rubber-band without touching the selection; a no-op when none
	 * is in flight, so callers invoke it unconditionally (Escape, a tool switch, another area taking over,
	 * the overlay leaving composition).
	 */
	fun cancel() {
		boxFlow.cancel()
	}

	/**
	 * Applies the sub-threshold click: the Alt path resolves the candidate stack (2+ opens the overlap
	 * picker, exactly one selects directly, empty leaves the selection), the plain path picks the
	 * front-most drawable through the decision table.  Unselectable drawables are excluded from both,
	 * so a click passes through them.
	 *
	 * @param Offset position The click position in area-local pixels.
	 * @param Boolean toggleMembership True when a membership modifier (Shift / Ctrl / Meta) is held.
	 * @param Boolean alt True when Alt is held (the disambiguate gesture).
	 */
	private fun applyClickPick(position: Offset, toggleMembership: Boolean, alt: Boolean) {
		val model = session.model.value
		if (alt) {
			val candidates =
				pickStack(position).filter { candidate -> model.selectableOf(SelectionTarget.Drawable(candidate.id)) }
			when (val resolution = resolveAltOverlapPick(candidates)) {
				is AltPickResolution.ShowOverlap -> onOverlapRequest(position, resolution.candidates)
				is AltPickResolution.SelectSingle -> session.setSelection(SelectionOps.replace(SelectionTarget.Drawable(resolution.id)))
				AltPickResolution.None -> {}
			}
			return
		}
		val hit = pickTopmost(position)?.takeIf { drawableId -> model.selectableOf(SelectionTarget.Drawable(drawableId)) }
		session.setSelection(
			resolveObjectClickSelection(
				current = session.selection.value,
				target = hit?.let { drawableId -> SelectionTarget.Drawable(drawableId) },
				toggleMembership = toggleMembership,
			),
		)
	}
}