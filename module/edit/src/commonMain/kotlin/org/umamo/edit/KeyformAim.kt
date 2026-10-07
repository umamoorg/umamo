package org.umamo.edit

import org.umamo.runtime.model.KeyformTrackRef
import org.umamo.runtime.model.ParameterId

/**
 * Where a keyform edit is aimed: at a place the user named, or at the pose the rig is standing in.
 *
 * The distinction is not cosmetic.  Pointing at a spot on a track is a statement about WHICH spot, so a key
 * lands there rather than at the playhead; and pointing at a track but not at a key means "that key, of
 * which there is none", which must remove nothing rather than falling back to the pose and destroying a key
 * the user never pointed at.
 */
sealed interface KeyformAim {
	/**
	 * No place was named, so the edit acts at the pose the editor shows ([EditorSession.shownPose]) - a
	 * property row, or a bare keypress.
	 */
	data object Pose : KeyformAim

	/**
	 * A named place on the track's axis.
	 *
	 * @property Float position The parameter value pointed at.
	 * @property Int? keyIndex The ordinal of the key sitting there, or null when the pointer is between keys.
	 */
	data class Position(val position: Float, val keyIndex: Int?) : KeyformAim
}

/**
 * What a deferred keyform edit will do once the user picks the axis it writes on.
 */
enum class KeyformAction {
	/** Insert a key ([captureKeyOnTrack]). */
	Capture,

	/** Remove a key ([removeKeyOnTrack]). */
	Remove,
}

/**
 * A keyform edit held back because more than one parameter is targeted and the user never named one.
 *
 * The linked-pad case: a 2D pad targets both its axes but reports only the HORIZONTAL one as active, so an
 * unaimed edit would silently always write there and the vertical section would keep reading as unkeyed.
 * Guessing is the one thing that must not happen - the wrong axis is authored work in the wrong place - so
 * the edit parks here and the shell asks.
 *
 * Carries the whole edit rather than just the question, so answering it is a plain replay through the same
 * entry point with the axis filled in; the UI decides nothing but which name was clicked.
 *
 * @property KeyformTrackRef track The track the edit was aimed at.
 * @property KeyformAim aim Where on that track it lands.
 * @property KeyformAction action Which edit is waiting.
 * @property List candidates The targeted parameters to choose between, in model order.
 * @property String? rowKey The keyform-sheet row the aim came from, carried so the replay can reconcile the
 *   key selection exactly as an unparked edit does; null when the aim came from somewhere with no sheet row.
 */
data class ParameterChoiceRequest(
	val track: KeyformTrackRef,
	val aim: KeyformAim,
	val action: KeyformAction,
	val candidates: List<ParameterId>,
	val rowKey: String? = null,
)