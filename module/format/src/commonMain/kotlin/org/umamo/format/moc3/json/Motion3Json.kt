package org.umamo.format.moc3.json

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/**
 * `*.motion3.json` - one motion: its timing, the curves over parameters and part opacities, and any timed
 * user data.
 *
 * Times, rates, and the segment stream are kept as [JsonPrimitive] so the editor's exact numeric tokens
 * round-trip; a segment stream interleaves segment-type markers with point coordinates, and the motion math
 * is out of scope here.  Decoding tolerates unknown keys and this model drops them on a re-encode, so a file
 * carried through an export is re-emitted from its original text rather than from this.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md § 6.6 motion3.json</a>
 */
@Serializable
public data class Motion3Json(
	@SerialName("Version") val version: Int,
	@SerialName("Meta") val meta: MotionMeta,
	@SerialName("Curves") val curves: List<MotionCurve>,
	@SerialName("UserData") val userData: List<MotionUserData>? = null,
)

/** The timing, counts, and fade defaults of a [Motion3Json]. */
@Serializable
public data class MotionMeta(
	@SerialName("Duration") val duration: JsonPrimitive,
	@SerialName("Fps") val fps: JsonPrimitive,
	@SerialName("Loop") val loop: Boolean,
	@SerialName("AreBeziersRestricted") val areBeziersRestricted: Boolean? = null,
	@SerialName("FadeInTime") val fadeInTime: JsonPrimitive? = null,
	@SerialName("FadeOutTime") val fadeOutTime: JsonPrimitive? = null,
	@SerialName("CurveCount") val curveCount: Int,
	@SerialName("TotalSegmentCount") val totalSegmentCount: Int,
	@SerialName("TotalPointCount") val totalPointCount: Int,
	@SerialName("UserDataCount") val userDataCount: Int? = null,
	@SerialName("TotalUserDataSize") val totalUserDataSize: Int? = null,
)

/**
 * One curve of a motion.
 *
 * @property String        target      What the curve drives: "Parameter" (a parameter id), "PartOpacity" (a
 *   part id), or "Model" (a model-level channel such as "Opacity", which names no object).
 * @property String        id          The parameter or part id, or the model channel's name.
 * @property List          segments    The segment stream, as the file's tokens.
 * @property JsonPrimitive? fadeInTime  This curve's own fade-in, overriding the motion's.
 * @property JsonPrimitive? fadeOutTime This curve's own fade-out, overriding the motion's.
 */
@Serializable
public data class MotionCurve(
	@SerialName("Target") val target: String,
	@SerialName("Id") val id: String,
	@SerialName("Segments") val segments: List<JsonPrimitive>,
	@SerialName("FadeInTime") val fadeInTime: JsonPrimitive? = null,
	@SerialName("FadeOutTime") val fadeOutTime: JsonPrimitive? = null,
)

/**
 * One timed user-data event of a motion.
 *
 * @property JsonPrimitive time  When it fires, in seconds, as the file's token.
 * @property String        value The event's text.
 */
@Serializable
public data class MotionUserData(
	@SerialName("Time") val time: JsonPrimitive,
	@SerialName("Value") val value: String,
)