package org.umamo.edit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The tool settings a session carries between gestures and a saved document carries between sessions: the
 * 2D and UV cursors, the transform pivot mode, and proportional editing with the configuration it
 * re-enables with.  Apart from the transient latches ([ToolLatches]) because these are
 * what [seed] lays in from a saved [SessionViewState] and what the session's viewState() gathers back; the
 * public face is [SessionToolSettings], which [EditorSession] delegates to this one instance.
 *
 * @param Function notify Emits a transient user notice (the proportional toggles confirm through it).
 */
internal class ToolSettings(private val notify: (String, NoticePlacement) -> Unit) : SessionToolSettings {
	private val mutableCursor2d = MutableStateFlow<Cursor2d?>(null)

	/** The 2D cursor's world position, or null before any placement (see [SessionToolSettings.cursor2d]). */
	override val cursor2d: StateFlow<Cursor2d?> = mutableCursor2d.asStateFlow()

	private val mutableUvCursor = MutableStateFlow<UvCursor?>(null)

	/** The UV editor's cursor in atlas coordinates, or null before any placement (see [SessionToolSettings.uvCursor]). */
	override val uvCursor: StateFlow<UvCursor?> = mutableUvCursor.asStateFlow()

	private val mutablePivotMode = MutableStateFlow(TransformPivotMode.MedianPoint)

	/** What a modal Scale / Rotate turns the selection about (see [SessionToolSettings.pivotMode]). */
	override val pivotMode: StateFlow<TransformPivotMode> = mutablePivotMode.asStateFlow()

	private val mutableProportionalEdit = MutableStateFlow<ProportionalEditState?>(null)

	/** Proportional editing, non-null while enabled (see [SessionToolSettings.proportionalEdit]). */
	override val proportionalEdit: StateFlow<ProportionalEditState?> = mutableProportionalEdit.asStateFlow()

	// The configuration proportional editing applies with, kept while it is off: the falloff, radius, and
	// connected flag survive an off/on toggle (the circle-select radius pattern), so O comes back the way it
	// was left.  Equal to the live state whenever proportional editing is on.
	private val mutableProportionalSettings = MutableStateFlow(DEFAULT_PROPORTIONAL_EDIT_STATE)

	/** The proportional configuration, live or remembered (see [SessionToolSettings.proportionalSettings]). */
	override val proportionalSettings: StateFlow<ProportionalEditState> = mutableProportionalSettings.asStateFlow()

	/**
	 * Places (or moves) the 2D cursor.
	 *
	 * @param Float worldX The cursor's new world-space x.
	 * @param Float worldZ The cursor's new world-space z (up).
	 */
	override fun setCursor2d(worldX: Float, worldZ: Float) {
		mutableCursor2d.value = Cursor2d(worldX, worldZ)
	}

	/**
	 * Places (or moves) the UV editor's cursor.
	 *
	 * @param Float u The cursor's new normalized atlas u coordinate.
	 * @param Float v The cursor's new normalized atlas v coordinate.
	 */
	override fun setUvCursor(u: Float, v: Float) {
		mutableUvCursor.value = UvCursor(u, v)
	}

	/**
	 * Selects the transform pivot mode.
	 *
	 * @param TransformPivotMode mode The pivot mode the next transforms anchor on.
	 */
	override fun setPivotMode(mode: TransformPivotMode) {
		mutablePivotMode.value = mode
	}

	/**
	 * Toggles proportional editing on or off (Blender's O), restoring the last falloff and radius on
	 * re-enable and confirming either way with a near-cursor notice (an idle toggle has no other
	 * visible effect - the influence circle only shows during a modal transform).
	 */
	override fun toggleProportionalEdit() {
		if (mutableProportionalEdit.value != null) {
			mutableProportionalEdit.value = null
			notify("notice.proportional.off", NoticePlacement.NearCursor)
		} else {
			mutableProportionalEdit.value = mutableProportionalSettings.value
			notify("notice.proportional.on", NoticePlacement.NearCursor)
		}
	}

	/**
	 * Toggles Connected Only for proportional editing (influence measured along mesh edges instead of
	 * straight-line, so the halo never leaps to unconnected geometry).  Proportional editing itself stays
	 * as it is: while it is off the flag is what the next toggle brings back (Blender's rule - changing a
	 * setting never switches the tool on).  Confirms either way with a near-cursor notice.
	 */
	override fun toggleProportionalConnected() {
		val updated = mutableProportionalSettings.value.let { settings -> settings.copy(connectedOnly = !settings.connectedOnly) }
		applyProportionalSettings(updated)
		notify(
			if (updated.connectedOnly) "notice.proportional.connected.on" else "notice.proportional.connected.off",
			NoticePlacement.NearCursor,
		)
	}

	/**
	 * Selects the proportional falloff curve.  Proportional editing itself stays as it is: while it is off
	 * the curve is what the next toggle brings back, and the header chip's curve glyph shows it.
	 *
	 * @param ProportionalFalloff falloff The falloff curve the influence weights follow.
	 */
	override fun setProportionalFalloff(falloff: ProportionalFalloff) {
		applyProportionalSettings(mutableProportionalSettings.value.copy(falloff = falloff))
	}

	/**
	 * Sets the proportional influence radius, clamped to the allowed range.  Proportional editing itself
	 * stays as it is: while it is off the radius is what the next toggle brings back.
	 *
	 * @param Float radiusWorld The influence radius in world units (canvas px).
	 */
	override fun setProportionalRadius(radiusWorld: Float) {
		applyProportionalSettings(mutableProportionalSettings.value.copy(radiusWorld = clampProportionalRadius(radiusWorld)))
	}

	/**
	 * Stores a changed proportional configuration, and makes it the live state only while proportional
	 * editing is on - the one rule every setting write follows, so no setter can switch the tool on.
	 *
	 * @param ProportionalEditState settings The configuration to store.
	 */
	private fun applyProportionalSettings(settings: ProportionalEditState) {
		mutableProportionalSettings.value = settings
		if (mutableProportionalEdit.value != null) {
			mutableProportionalEdit.value = settings
		}
	}

	/**
	 * Sets proportional editing outright - on with [state] (its radius clamped), or off with null -
	 * silently, and remembering the configuration a later toggle restores.  The operation settings
	 * strip's write-back: the proportional rows of an adjusted transform become the state the next
	 * gesture starts from, and the strip's rows are their own confirmation, so no notice fires.
	 *
	 * @param ProportionalEditState? state The state to set, or null to turn proportional editing off.
	 */
	override fun setProportionalEdit(state: ProportionalEditState?) {
		if (state == null) {
			mutableProportionalEdit.value = null
			return
		}
		val clamped = state.copy(radiusWorld = clampProportionalRadius(state.radiusWorld))
		mutableProportionalSettings.value = clamped
		mutableProportionalEdit.value = clamped
	}

	/**
	 * Lays a saved session's tool state in, silently: the cursors, the pivot mode, proportional editing with the
	 * configuration it would re-enable with.  Called once, as the session is built, so nothing here is a gesture
	 * and nothing here posts a notice.
	 *
	 * @param SessionViewState viewState The saved state, already fitted to the model.
	 */
	fun seed(viewState: SessionViewState) {
		mutableCursor2d.value = viewState.cursor2d
		mutableUvCursor.value = viewState.uvCursor
		mutablePivotMode.value = viewState.pivotMode
		viewState.proportionalSettings?.let { settings ->
			mutableProportionalSettings.value = settings.copy(radiusWorld = clampProportionalRadius(settings.radiusWorld))
		}
		mutableProportionalEdit.value = mutableProportionalSettings.value.takeIf { viewState.proportionalEnabled }
	}
}