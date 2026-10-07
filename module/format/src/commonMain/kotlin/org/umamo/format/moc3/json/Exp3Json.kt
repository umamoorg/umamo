package org.umamo.format.moc3.json

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/**
 * `*.exp3.json` - one expression: the parameters it sets and how each blends over the pose.
 *
 * The fade times are kept as [JsonPrimitive] so the editor's exact numeric tokens round-trip.  Decoding
 * tolerates unknown keys and this model drops them on a re-encode, so a file carried through an export is
 * re-emitted from its original text rather than from this.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md § 6.5 exp3.json</a>
 */
@Serializable
public data class Exp3Json(
	@SerialName("Type") val type: String,
	@SerialName("FadeInTime") val fadeInTime: JsonPrimitive? = null,
	@SerialName("FadeOutTime") val fadeOutTime: JsonPrimitive? = null,
	@SerialName("Parameters") val parameters: List<ExpressionParameter>,
)

/**
 * One parameter an expression sets.
 *
 * @property String        id    The parameter id.
 * @property JsonPrimitive value The value set, as the file's token.
 * @property String?       blend How the value joins the pose: "Add", "Multiply", or "Overwrite"; "Add" when absent.
 */
@Serializable
public data class ExpressionParameter(
	@SerialName("Id") val id: String,
	@SerialName("Value") val value: JsonPrimitive,
	@SerialName("Blend") val blend: String? = null,
)