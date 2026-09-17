package org.umamo.format.uma.puppet

import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * The puppet entry's geometry half (docs/format/UMA.md §4.10-§4.14): meshes, keyform grids, channel tracks,
 * blend shapes, and glue.  Per-vertex and per-control-point arrays are @Contextual so they travel as accessors
 * into the entry's buffer (§4.9); everything else is inline JSON.
 *
 * Classes holding arrays are plain classes rather than data classes, since a generated equality would compare
 * the arrays by reference and read as structural when it is not.
 */

/**
 * UMA §4.10: a drawable's rest-pose mesh.
 *
 * @property FloatArray positions x, y per vertex of the rest pose, before any deformer applies (an accessor).
 * @property FloatArray uvs       u, v per vertex (an accessor).
 * @property IntArray   indices   Three per triangle (an accessor).
 */
@Serializable
public class UmaMesh(
	@Contextual val positions: FloatArray,
	@Contextual val uvs: FloatArray,
	@Contextual val indices: IntArray,
)

/**
 * UMA §4.11: one axis of a keyform grid.
 *
 * @property String      parameter The parameter the axis keys on.
 * @property List<Float> keys      The parameter values keyed, inline.
 */
@Serializable
public data class UmaAxis(
	val parameter: String,
	val keys: List<Float>,
)

/**
 * UMA §4.11: a drawable's geometry keyform grid.
 *
 * @property List<UmaAxis>     axes  The axes.
 * @property List<UmaMeshCell> cells The keyed cells, in the model's order.
 */
@Serializable
public data class UmaMeshGrid(
	val axes: List<UmaAxis>,
	val cells: List<UmaMeshCell>,
)

/**
 * UMA §4.11: one cell of a drawable's geometry grid.
 *
 * @property List<Int>  coordinate     The key index per axis.
 * @property FloatArray positionDeltas x, y per vertex, relative to the rest mesh (an accessor).
 */
@Serializable
public class UmaMeshCell(
	val coordinate: List<Int>,
	@Contextual val positionDeltas: FloatArray,
)

/**
 * UMA §4.11: a deformer's geometry keyform grid.
 *
 * @property List<UmaAxis>         axes  The axes.
 * @property List<UmaDeformerCell> cells The keyed cells, in the model's order.
 */
@Serializable
public data class UmaDeformerGrid(
	val axes: List<UmaAxis>,
	val cells: List<UmaDeformerCell>,
)

/**
 * UMA §4.11: one cell of a deformer's geometry grid - a warp's lattice or a rotation's pivot, by the deformer's
 * kind.
 *
 * @property List<Int>   coordinate    The key index per axis.
 * @property FloatArray? controlPoints A warp's absolute lattice points, x, y per point (an accessor).
 * @property Float?      originX       A rotation's pivot x.
 * @property Float?      originY       A rotation's pivot y.
 * @property Float?      angle         A rotation's angle.
 * @property Float?      scale         A rotation's scale.
 */
@Serializable
public class UmaDeformerCell(
	val coordinate: List<Int>,
	@Contextual val controlPoints: FloatArray? = null,
	val originX: Float? = null,
	val originY: Float? = null,
	val angle: Float? = null,
	val scale: Float? = null,
)

/** UMA §4.12: a keyed non-geometry channel. */
@Serializable
public enum class UmaFormChannel {
	@SerialName("drawOrder")
	DrawOrder,

	@SerialName("opacity")
	Opacity,

	@SerialName("multiplyColor")
	MultiplyColor,

	@SerialName("screenColor")
	ScreenColor,

	@SerialName("flipX")
	FlipX,

	@SerialName("flipY")
	FlipY,

	@SerialName("glueIntensity")
	GlueIntensity,
}

/**
 * UMA §4.12: one channel's keyform track.
 *
 * @property List<UmaAxis>        axes  The axes.
 * @property List<UmaChannelCell> cells The keyed cells, in the model's order.
 */
@Serializable
public data class UmaChannelGrid(
	val axes: List<UmaAxis>,
	val cells: List<UmaChannelCell>,
)

/**
 * UMA §4.12: one cell of a channel track.
 *
 * @property List<Int>   coordinate The key index per axis.
 * @property JsonElement value      A number, a color `[r, g, b]`, or a boolean, by the channel's kind.
 */
@Serializable
public data class UmaChannelCell(
	val coordinate: List<Int>,
	val value: JsonElement,
)

/**
 * UMA §4.13: one point of a blend-weight limit curve.
 *
 * @property Float value  The constraint parameter's value.
 * @property Float weight The weight cap there.
 */
@Serializable
public data class UmaBlendLimitPoint(
	val value: Float,
	val weight: Float,
)

/**
 * UMA §4.13: a blend-weight limit curve over another parameter.
 *
 * @property String                   parameter The constraint parameter.
 * @property List<UmaBlendLimitPoint> points    The curve, ascending by value.
 */
@Serializable
public data class UmaBlendLimit(
	val parameter: String,
	val points: List<UmaBlendLimitPoint>,
)

/**
 * UMA §4.13: a drawable's blend-shape form.
 *
 * @property FloatArray   positionDeltas x, y per vertex, relative to the rest mesh (an accessor).
 * @property Float?       drawOrder      Absent at 500.
 * @property Float?       opacity        Absent at 1.
 * @property List<Float>? multiplyColor  Absent at white.
 * @property List<Float>? screenColor    Absent at black.
 */
@Serializable
public class UmaMeshForm(
	@Contextual val positionDeltas: FloatArray,
	val drawOrder: Float? = null,
	val opacity: Float? = null,
	val multiplyColor: List<Float>? = null,
	val screenColor: List<Float>? = null,
)

/**
 * UMA §4.13: a deformer's blend-shape form - a warp's lattice or a rotation's pivot, by the deformer's kind.
 *
 * @property FloatArray?  controlPoints A warp's absolute lattice points (an accessor).
 * @property Float?       originX       A rotation's pivot x.
 * @property Float?       originY       A rotation's pivot y.
 * @property Float?       angle         A rotation's angle.
 * @property Float?       scale         A rotation's scale.
 * @property Boolean?     flipX         A rotation's horizontal reflection, absent when false.
 * @property Boolean?     flipY         A rotation's vertical reflection, absent when false.
 * @property Float?       opacity       Absent at 1.
 * @property List<Float>? multiplyColor Absent at white.
 * @property List<Float>? screenColor   Absent at black.
 */
@Serializable
public class UmaDeformerForm(
	@Contextual val controlPoints: FloatArray? = null,
	val originX: Float? = null,
	val originY: Float? = null,
	val angle: Float? = null,
	val scale: Float? = null,
	val flipX: Boolean? = null,
	val flipY: Boolean? = null,
	val opacity: Float? = null,
	val multiplyColor: List<Float>? = null,
	val screenColor: List<Float>? = null,
)

/**
 * UMA §4.13: a part's blend-shape form.
 *
 * @property Float        drawOrder     The part's draw order at this key.
 * @property Float?       opacity       Absent at 1.
 * @property List<Float>? multiplyColor Absent at white.
 * @property List<Float>? screenColor   Absent at black.
 */
@Serializable
public data class UmaPartForm(
	val drawOrder: Float,
	val opacity: Float? = null,
	val multiplyColor: List<Float>? = null,
	val screenColor: List<Float>? = null,
)

/**
 * UMA §4.13: a drawable's blend-shape binding.
 *
 * @property String               parameter    The driving parameter.
 * @property List<Float>          keys         The keyed values, ascending, the neutral included.
 * @property Int                  neutralIndex The neutral key's index.
 * @property List<UmaMeshForm?>   forms        One form per key; null where there is none.
 * @property List<UmaBlendLimit>? limits       Weight limits, absent when none.
 */
@Serializable
public data class UmaMeshBlendShape(
	val parameter: String,
	val keys: List<Float>,
	val neutralIndex: Int,
	val forms: List<UmaMeshForm?>,
	val limits: List<UmaBlendLimit>? = null,
)

/**
 * UMA §4.13: a deformer's blend-shape binding.
 *
 * @property String                 parameter    The driving parameter.
 * @property List<Float>            keys         The keyed values, ascending, the neutral included.
 * @property Int                    neutralIndex The neutral key's index.
 * @property List<UmaDeformerForm?> forms        One form per key; null where there is none.
 * @property List<UmaBlendLimit>?   limits       Weight limits, absent when none.
 */
@Serializable
public data class UmaDeformerBlendShape(
	val parameter: String,
	val keys: List<Float>,
	val neutralIndex: Int,
	val forms: List<UmaDeformerForm?>,
	val limits: List<UmaBlendLimit>? = null,
)

/**
 * UMA §4.13: a part's blend-shape binding.
 *
 * @property String               parameter    The driving parameter.
 * @property List<Float>          keys         The keyed values, ascending, the neutral included.
 * @property Int                  neutralIndex The neutral key's index.
 * @property List<UmaPartForm?>   forms        One form per key; null where there is none.
 * @property List<UmaBlendLimit>? limits       Weight limits, absent when none.
 */
@Serializable
public data class UmaPartBlendShape(
	val parameter: String,
	val keys: List<Float>,
	val neutralIndex: Int,
	val forms: List<UmaPartForm?>,
	val limits: List<UmaBlendLimit>? = null,
)

/**
 * UMA §4.14: a glue's welded vertex pairs, as parallel arrays.
 *
 * @property IntArray   indicesA Vertex indices into mesh A (an accessor).
 * @property IntArray   indicesB Vertex indices into mesh B (an accessor).
 * @property FloatArray weightsA Mesh A's pull toward B per pair (an accessor).
 * @property FloatArray weightsB Mesh B's pull toward A per pair (an accessor).
 */
@Serializable
public class UmaGluePairs(
	@Contextual val indicesA: IntArray,
	@Contextual val indicesB: IntArray,
	@Contextual val weightsA: FloatArray,
	@Contextual val weightsB: FloatArray,
)

/**
 * UMA §4.14: a glue affecter, identified by its ordered mesh pair.
 *
 * @property String                                 meshA     The first welded drawable.
 * @property String                                 meshB     The second welded drawable.
 * @property UmaGluePairs                           pairs     The welded vertex pairs.
 * @property Map<UmaFormChannel, UmaChannelGrid>?   channels  The keyed weld strength, absent when static.
 * @property Float?                                 intensity The static weld strength, absent at 1.
 * @property String?                                id        The authored name, absent when none.
 */
@Serializable
public data class UmaGlue(
	val meshA: String,
	val meshB: String,
	val pairs: UmaGluePairs,
	val channels: Map<UmaFormChannel, UmaChannelGrid>? = null,
	val intensity: Float? = null,
	val id: String? = null,
)