package org.umamo.format.moc3.json

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/**
 * `*.pose3.json` - the part groups a runtime switches between, one visible part per group at a time.
 *
 * The fade time is kept as [JsonPrimitive] so the editor's exact numeric token round-trips.  Decoding
 * tolerates unknown keys and this model drops them on a re-encode, so a file carried through an export is
 * re-emitted from its original text rather than from this.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md § 6.7 pose3.json</a>
 */
@Serializable
public data class Pose3Json(
	@SerialName("Type") val type: String,
	@SerialName("FadeInTime") val fadeInTime: JsonPrimitive? = null,
	@SerialName("Groups") val groups: List<List<PoseGroupEntry>>,
)

/**
 * One part of a pose group.
 *
 * @property String id   The part id.
 * @property List?  link The part ids whose opacity follows this part's, or absent when none does.
 */
@Serializable
public data class PoseGroupEntry(
	@SerialName("Id") val id: String,
	@SerialName("Link") val link: List<String>? = null,
)