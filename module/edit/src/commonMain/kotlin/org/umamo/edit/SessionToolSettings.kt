package org.umamo.edit

import kotlinx.coroutines.flow.StateFlow

/**
 * The tool settings a session carries between gestures and a saved document carries between sessions:
 * the 2D and UV cursors, the transform pivot mode, the viewport grid, and proportional editing.  Unlike
 * [SessionToolLatches] these survive mode switches and are laid in from [SessionViewState] at open; like
 * them they are never snapshotted and never enter the change bus.  [EditorSession] exposes this surface by
 * delegation.
 */
interface SessionToolSettings {
	/**
	 * The 2D cursor's world position, or null before any placement.  Transient session state like the
	 * tool latches (deliberately NOT part of EditorSnapshot - see [Cursor2d]); placed by Shift+RightClick
	 * in the viewport, moved by the snap commands, and drawn by the HUD overlay only once placed.  An
	 * operation that uses the cursor as a point reads [EditorSession.cursor2dOrWorldOrigin] instead.
	 */
	val cursor2d: StateFlow<Cursor2d?>

	/**
	 * Places (or moves) the 2D cursor.
	 *
	 * @param Float worldX The cursor's new world-space x.
	 * @param Float worldZ The cursor's new world-space z (up).
	 */
	fun setCursor2d(worldX: Float, worldZ: Float)

	/**
	 * The UV editor's cursor in normalized atlas coordinates, or null before any placement.  The
	 * texture-space sibling of [cursor2d] (see [UvCursor]): placed by Shift+RightClick in the UV editor,
	 * drawn by its overlay, and read as the UV transform pivot in [TransformPivotMode.Cursor].
	 */
	val uvCursor: StateFlow<UvCursor?>

	/**
	 * Places (or moves) the UV editor's cursor.
	 *
	 * @param Float u The cursor's new normalized atlas u coordinate.
	 * @param Float v The cursor's new normalized atlas v coordinate.
	 */
	fun setUvCursor(u: Float, v: Float)

	/**
	 * The viewport grid geometry (major spacing + subdivisions) driving both the drawn backdrop grid and
	 * the grid snap increment.  Session state, deliberately NOT snapshotted - like the 2D cursor.  Seeded from
	 * the global-default settings, or from the document's own value when it saved one
	 * ([EditorSession.gridFollowsApplication]).  Read by the snap commands ([GridConfig.snapStep]) and pushed to the renderer
	 * by the viewport binding.
	 */
	val gridConfig: StateFlow<GridConfig>

	/**
	 * Sets the viewport grid geometry.  Called by the viewport binding when the global-default settings
	 * change, while the grid follows them ([EditorSession.gridFollowsApplication]).
	 *
	 * @param GridConfig config The new grid scale and subdivisions.
	 */
	fun setGridConfig(config: GridConfig)

	/**
	 * What a modal Scale / Rotate turns the selection about (the Period pie / the header dropdown).
	 * Transient editor state - it survives mode switches but is never snapshotted; the default is
	 * Blender's Median Point.
	 */
	val pivotMode: StateFlow<TransformPivotMode>

	/**
	 * Selects the transform pivot mode.
	 *
	 * @param TransformPivotMode mode The pivot mode the next transforms anchor on.
	 */
	fun setPivotMode(mode: TransformPivotMode)

	/**
	 * Proportional editing (Blender's O): non-null while enabled, carrying the falloff curve and the
	 * influence radius.  Transient editor state like [pivotMode] - it survives mode switches but is
	 * never snapshotted; the Edit overlay reads it when a modal operator latches (and on mid-gesture
	 * radius scrolls) to weight the unselected vertices near the selection.
	 */
	val proportionalEdit: StateFlow<ProportionalEditState?>

	/**
	 * Toggles proportional editing on or off (Blender's O), restoring the last falloff and radius on
	 * re-enable and confirming either way with a near-cursor notice (an idle toggle has no other
	 * visible effect - the influence circle only shows during a modal transform).
	 */
	fun toggleProportionalEdit()

	/**
	 * Toggles Connected Only for proportional editing (influence measured along mesh edges instead of
	 * straight-line, so the halo never leaps to unconnected geometry), enabling proportional editing
	 * if it was off - and then connected mode turns ON regardless of the remembered flag, since the
	 * command expresses the intent to use it.  Confirms either way with a near-cursor notice.
	 */
	fun toggleProportionalConnected()

	/**
	 * Selects the proportional falloff curve, enabling proportional editing if it was off - picking a
	 * falloff from the palette or header expresses the intent to use it, and silently updating a
	 * disabled state would look like the command did nothing.
	 *
	 * @param ProportionalFalloff falloff The falloff curve the influence weights follow.
	 */
	fun setProportionalFalloff(falloff: ProportionalFalloff)

	/**
	 * Sets the proportional influence radius, clamped to the allowed range.  A no-op while proportional
	 * editing is off (the radius only changes from the mid-gesture scroll, which requires it on).
	 *
	 * @param Float radiusWorld The influence radius in world units (canvas px).
	 */
	fun setProportionalRadius(radiusWorld: Float)

	/**
	 * Sets proportional editing outright - on with [state], or off with null - without a notice: the
	 * operation settings strip writes an adjusted transform's proportional rows back through here, so
	 * the next gesture starts from what the rigger last dialled in.
	 *
	 * @param ProportionalEditState? state The state to set, or null to turn proportional editing off.
	 */
	fun setProportionalEdit(state: ProportionalEditState?)
}