package org.umamo.ui.workspace.spaces

import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyableTarget
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.KeyformOwner
import org.umamo.runtime.model.KeyformTrackRef
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.tracks.TrackKeyMark
import org.umamo.ui.tracks.TrackKeyShape
import org.umamo.ui.tracks.TrackRow
import org.umamo.ui.tracks.TrackRowTone

/*
 * The PuppetModel -> track rows projection: everything the keyform sheet knows about the document.
 *
 * This is the ONLY place the sheet touches the model.  org.umamo.ui.tracks stays domain-agnostic so the
 * eventual time-based animation dope sheet can reuse the same widgets, which means the mapping from
 * "keyform grids on a parameter" to "rows of marks" has to live on this side of the boundary.
 *
 * Compose-free so the projection is unit-testable without a composition.
 */

/**
 * The kinds of thing that can own keyform tracks, which is what decides a group row's icon and its type
 * subtitle.
 *
 * Coarser than the model's own type hierarchy on purpose: the sheet only needs enough to pick an icon,
 * and every one of these already has art in the icon set.
 */
enum class KeyformOwnerKind {
	ArtMesh,
	WarpDeformer,
	RotationDeformer,
	Part,
	Glue,
}

/**
 * The labels the projection needs, resolved by the caller from string resources.
 *
 * Injected rather than looked up here so the projection stays Compose-free AND localized: these are Umamo
 * chrome, and the guardrail is that no UI string is hardcoded.  Item names are NOT here - those are the
 * user's own document data and are never translated.
 *
 * @property Function channelName A channel's short display label.
 * @property String geometry The label for a geometry track.
 * @property String blendShape The label for a blend-shape track.
 * @property Function ownerKindName An owner kind's display label, shown as a group row's subtitle.
 */
class KeyformTrackLabels(
	val channelName: (FormChannel) -> String,
	val geometry: String,
	val blendShape: String,
	val ownerKindName: (KeyformOwnerKind) -> String,
)

/**
 * One selected key: the parameter section, the row, and WHICH key on that row.
 *
 * By ordinal rather than by parameter value.  Two keys a hair apart are legal and useful, and resolving a
 * value back to a key then picks whichever happens to be nearer - which is how dragging one of a pair of
 * near-coincident marks moved the other.  The parameter is part of the identity because a linked pair
 * renders two sections at once and one item's row key is the same string in both.
 *
 * @property ParameterId parameterId The parameter whose section the key sits in.
 * @property String rowKey The owning row's stable key.
 * @property Int keyIndex The key's ordinal on that row's track.
 */
data class TrackKeyRef(val parameterId: ParameterId, val rowKey: String, val keyIndex: Int)

/**
 * The sheet's projection of a rig: rows to draw, and what each row edits.
 *
 * The targets and owner kinds ride alongside rather than inside [TrackRow] because a row is a
 * domain-agnostic widget input - org.umamo.ui.tracks must never learn what a keyform channel is - so the
 * mapping back to the model stays on this side of the boundary, keyed by the row's own identity.
 *
 * @property List rows The group rows, each carrying its tracks as children.
 * @property Map tracksByRowKey What each track row edits.  A GROUP row has no entry (it names the owner,
 *   not a track) and neither does a blend-shape row (a blend binding is not a keyform grid, so the sheet's
 *   grid ops cannot touch it) - so a null lookup is the sheet's "this row is read-only" signal.
 * @property Map ownerKindByRowKey The owner kind behind each GROUP row, for its icon.
 * @property Set groupRowKeys Every group row's key, so a caller can seed "expand all".
 */
class KeyformSheetProjection(
	val rows: List<TrackRow>,
	val tracksByRowKey: Map<String, KeyformTrackRef>,
	val ownerKindByRowKey: Map<String, KeyformOwnerKind>,
	val groupRowKeys: Set<String>,
)

/**
 * The per-(item, channel) tracks keyed on [parameterId], in outliner order, grouped under their owner.
 *
 * One row per channel rather than one per item, because that is what the split made possible: an item can
 * key opacity on a parameter its geometry never touches, and collapsing those into one row would hide
 * exactly the thing the sheet exists to show.  An item contributes a GEOMETRY track when its geometry grid
 * keys on the parameter, one track per channel grid that does, and one per blend-shape binding driven by
 * it (drawn with square marks, matching the parameter slider's own key marks).
 *
 * Those tracks hang under a per-owner group row so a rig with hundreds of deformers can be folded down to
 * the handful being worked on.  A collapsed group still shows its subtree's key positions - see
 * `summarizedMarks` - so folding hides the detail, never the fact that keys are there.
 *
 * @param PuppetModel puppet The rig.
 * @param ParameterId parameterId The parameter whose tracks to list.
 * @param KeyformTrackLabels labels The localized chrome labels.
 * @return KeyformSheetProjection The rows and their model bindings; empty when nothing keys on it.
 */
fun keyformSheetRows(puppet: PuppetModel, parameterId: ParameterId, labels: KeyformTrackLabels): KeyformSheetProjection {
	val builder = ProjectionBuilder(labels)
	for (part in puppet.parts) {
		builder.addOwner(
			ownerKey = "part:${part.id.raw}",
			ownerName = part.name,
			ownerKind = KeyformOwnerKind.Part,
			owner = KeyformOwner.Part(part.id),
			geometryMarks = null,
			channelGrids = part.channelGrids,
			blendShapeMarks = emptyList(),
			parameterId = parameterId,
		)
	}
	for (deformer in puppet.deformers) {
		val geometry =
			when (deformer) {
				is Deformer.Warp -> deformer.geometryGrid
				is Deformer.Rotation -> deformer.geometryGrid
			}
		val blendShapes =
			when (deformer) {
				is Deformer.Warp -> deformer.blendShapes
				is Deformer.Rotation -> deformer.blendShapes
			}
		builder.addOwner(
			ownerKey = "deformer:${deformer.id.raw}",
			ownerName = deformer.name,
			ownerKind =
				when (deformer) {
					is Deformer.Warp -> KeyformOwnerKind.WarpDeformer
					is Deformer.Rotation -> KeyformOwnerKind.RotationDeformer
				},
			owner = KeyformOwner.Deformer(deformer.id),
			geometryMarks = marksOf(geometry, parameterId, TrackKeyShape.Circle),
			channelGrids = deformer.channelGrids,
			blendShapeMarks = blendShapeMarksOf(blendShapes.map { it.parameterId to it.keys }, parameterId),
			parameterId = parameterId,
		)
	}
	for (drawable in puppet.drawables) {
		builder.addOwner(
			ownerKey = "drawable:${drawable.id.raw}",
			ownerName = drawable.name,
			ownerKind = KeyformOwnerKind.ArtMesh,
			owner = KeyformOwner.Drawable(drawable.id),
			geometryMarks = marksOf(drawable.geometryGrid, parameterId, TrackKeyShape.Circle),
			channelGrids = drawable.channelGrids,
			blendShapeMarks = blendShapeMarksOf(drawable.blendShapes.map { it.parameterId to it.keys }, parameterId),
			parameterId = parameterId,
		)
	}
	val drawableNameById = puppet.drawables.associate { drawable -> drawable.id to drawable.name }
	for (glue in puppet.glues) {
		// Keyed by the MESH PAIR, the same stable identity KeyformOwner.Glue encodes - a list ordinal
		// renumbers every later glue when one is removed, silently migrating selection and collapse state
		// (and so a Delete) onto a different glue's rows.  Labeled with display names, not raw ids.
		builder.addOwner(
			ownerKey = "glue:${glue.meshA.raw}:${glue.meshB.raw}",
			ownerName = "${drawableNameById[glue.meshA] ?: glue.meshA.raw} ↔ ${drawableNameById[glue.meshB] ?: glue.meshB.raw}",
			ownerKind = KeyformOwnerKind.Glue,
			owner = KeyformOwner.Glue(glue.meshA, glue.meshB),
			geometryMarks = null,
			channelGrids = glue.channelGrids,
			blendShapeMarks = emptyList(),
			parameterId = parameterId,
		)
	}
	return builder.build()
}

/**
 * Accumulates one owner at a time into the group rows, targets, and kind map that make up a projection.
 *
 * A builder rather than three separate passes because the row key is the join between all three maps, and
 * deriving it twice in two places is exactly how they drifted apart before.
 *
 * @property KeyformTrackLabels labels The localized chrome labels.
 */
private class ProjectionBuilder(private val labels: KeyformTrackLabels) {
	private val rows = ArrayList<TrackRow>()
	private val tracks = HashMap<String, KeyformTrackRef>()
	private val ownerKinds = HashMap<String, KeyformOwnerKind>()

	/**
	 * Adds one owner's group row and its tracks, or nothing at all when it keys nothing on the parameter.
	 *
	 * @param String ownerKey The owner's stable row-key prefix.
	 * @param String ownerName The owner's display name (user data - never translated).
	 * @param KeyformOwnerKind ownerKind What the owner is, for its icon and subtitle.
	 * @param KeyformOwner owner The model reference the tracks edit.
	 * @param List<TrackKeyMark>? geometryMarks Its geometry track's marks, or null when it has none.
	 * @param ChannelGrids channelGrids Its channel tracks.
	 * @param List blendShapeMarks Its blend-shape tracks' marks, one list per binding.
	 * @param ParameterId parameterId The parameter being listed.
	 */
	fun addOwner(
		ownerKey: String,
		ownerName: String,
		ownerKind: KeyformOwnerKind,
		owner: KeyformOwner,
		geometryMarks: List<TrackKeyMark>?,
		channelGrids: ChannelGrids,
		blendShapeMarks: List<List<TrackKeyMark>>,
		parameterId: ParameterId,
	) {
		val children = ArrayList<TrackRow>()
		if (geometryMarks != null) {
			val rowKey = "$ownerKey/geometry"
			children.add(
				TrackRow(
					key = rowKey,
					label = labels.geometry,
					tone = TrackRowTone.Primary,
					marks = geometryMarks,
				),
			)
			// A geometry row is editable: its key POSITIONS are ordinary grid algebra even though authoring
			// the forms themselves is deferred.  Leaving it unaddressable made the sheet silently swallow
			// every gesture on what is, after compaction, nearly every row a corpus rig shows.
			tracks[rowKey] = KeyformTrackRef.Geometry(owner)
		}
		for ((channel, track) in channelGrids.gridsByChannel) {
			val marks = marksOf(track, parameterId, TrackKeyShape.Circle) ?: continue
			val rowKey = "$ownerKey/${channel.name}"
			children.add(
				TrackRow(
					key = rowKey,
					label = labels.channelName(channel),
					tone = TrackRowTone.Secondary,
					marks = marks,
				),
			)
			tracks[rowKey] = KeyformTrackRef.Channel(KeyableTarget(owner, channel))
		}
		// A blend-shape binding is not a keyform grid, so it gets a row but no track ref: the sheet's ops
		// would have nothing to apply, and offering a drag that reverts is worse than offering none.
		for ((bindingIndex, marks) in blendShapeMarks.withIndex()) {
			children.add(
				TrackRow(
					key = "$ownerKey/blend$bindingIndex",
					label = labels.blendShape,
					tone = TrackRowTone.Alternate,
					marks = marks,
				),
			)
		}
		if (children.isEmpty()) {
			return
		}
		rows.add(
			TrackRow(
				key = ownerKey,
				label = ownerName,
				detail = labels.ownerKindName(ownerKind),
				tone = TrackRowTone.Group,
				children = children,
			),
		)
		ownerKinds[ownerKey] = ownerKind
	}

	/** The accumulated projection. */
	fun build(): KeyformSheetProjection =
		KeyformSheetProjection(
			rows = rows,
			tracksByRowKey = tracks,
			ownerKindByRowKey = ownerKinds,
			groupRowKeys = rows.map { row -> row.key }.toSet(),
		)
}

/**
 * The marks for one grid's axis on [parameterId], or null when the grid does not key on it.
 *
 * @param KeyformGrid? grid The grid to read.
 * @param ParameterId parameterId The parameter to look for.
 * @param TrackKeyShape shape The mark shape to emit.
 * @return List<TrackKeyMark>? The marks, or null when the grid has no such axis.
 */
private fun marksOf(grid: KeyformGrid<*>?, parameterId: ParameterId, shape: TrackKeyShape): List<TrackKeyMark>? {
	val axis = grid?.axes?.firstOrNull { axis -> axis.parameterId == parameterId } ?: return null
	return axis.keys.mapIndexed { keyIndex, keyValue -> TrackKeyMark(keyIndex, keyValue, shape) }
}

/**
 * The marks for each blend-shape binding driven by [parameterId], drawn as squares.
 *
 * Squares rather than circles because a blend-shape key is not a keyform-grid key and cannot be moved or
 * removed by the sheet's ops - the shape is the affordance saying so, and matches the parameter slider.
 *
 * @param List bindings Each binding's driving parameter and its key positions.
 * @param ParameterId parameterId The parameter being listed.
 * @return List The marks per matching binding, in binding order.
 */
private fun blendShapeMarksOf(
	bindings: List<Pair<ParameterId, FloatArray>>,
	parameterId: ParameterId,
): List<List<TrackKeyMark>> =
	bindings
		.filter { (bindingParameter, _) -> bindingParameter == parameterId }
		.map { (_, keys) ->
			keys.mapIndexed { keyIndex, keyValue ->
				// NOT editable: a blend row has no track ref, so none of the sheet's ops can apply - an
				// editable mark would accept a drag that snaps back and a selection Delete cannot resolve.
				TrackKeyMark(keyIndex, keyValue, TrackKeyShape.Square, editable = false)
			}
		}

/**
 * The axis domain for [parameter] - its authored range, which is what the sheet rules against.
 *
 * @param Parameter parameter The parameter.
 * @return Pair<Float, Float> The domain start and end.
 */
fun parameterDomain(parameter: Parameter): Pair<Float, Float> = parameter.min to parameter.max
