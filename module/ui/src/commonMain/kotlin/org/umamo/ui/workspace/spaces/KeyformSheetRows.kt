package org.umamo.ui.workspace.spaces

import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.tracks.TrackKeyMark
import org.umamo.ui.tracks.TrackKeyShape
import org.umamo.ui.tracks.TrackRow

/*
 * The PuppetModel -> track rows projection: everything the keyform sheet knows about the document.
 *
 * This is the ONLY place the sheet touches the model.  org.umamo.ui.tracks stays domain-agnostic so the
 * eventual time-based animation dope sheet can reuse the same widgets, which means the mapping from
 * "keyform grids on a parameter" to "rows of marks" has to live on this side of the boundary.
 *
 * Compose-free so the projection is unit-testable without a composition.
 *
 * PuppetModel からトラック行への射影。シート側が唯一モデルに触れる場所。
 */

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
 */
class KeyformTrackLabels(
	val channelName: (FormChannel) -> String,
	val geometry: String,
	val blendShape: String,
)

/**
 * The per-(item, channel) tracks keyed on [parameterId], in outliner order.
 *
 * One row per channel rather than one per item, because that is what the split made possible: an item can
 * key opacity on a parameter its geometry never touches, and collapsing those into one row would hide
 * exactly the thing the sheet exists to show.  An item contributes a GEOMETRY row when its geometry grid
 * keys on the parameter, one row per channel track that does, and one row per blend-shape binding driven
 * by it (drawn with square marks, matching the parameter slider's own key marks).
 *
 * @param PuppetModel puppet The rig.
 * @param ParameterId parameterId The parameter whose tracks to list.
 * @param KeyformTrackLabels labels The localized chrome labels.
 * @return List<TrackRow> The rows, empty when nothing keys on the parameter.
 */
fun keyformSheetRows(puppet: PuppetModel, parameterId: ParameterId, labels: KeyformTrackLabels): List<TrackRow> {
	val rows = ArrayList<TrackRow>()
	for (part in puppet.parts) {
		rows.addAll(part.trackRows(parameterId, labels))
	}
	for (deformer in puppet.deformers) {
		rows.addAll(deformer.trackRows(parameterId, labels))
	}
	for (drawable in puppet.drawables) {
		rows.addAll(drawable.trackRows(parameterId, labels))
	}
	for ((glueIndex, glue) in puppet.glues.withIndex()) {
		rows.addAll(
			glue.channelGrids.channelRows(
				ownerKey = "glue$glueIndex",
				ownerLabel = "${glue.meshA.raw} ↔ ${glue.meshB.raw}",
				parameterId = parameterId,
				labels = labels,
			),
		)
	}
	return rows
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
	return axis.keys.map { keyValue -> TrackKeyMark(keyValue, shape) }
}

/**
 * One row per channel track of this owner that keys on [parameterId].
 *
 * @param String ownerKey A stable per-owner key prefix, so row identity survives list changes.
 * @param String ownerLabel The owner's display name.
 * @param ParameterId parameterId The parameter to filter by.
 * @param KeyformTrackLabels labels The localized chrome labels.
 * @return List<TrackRow> The channel rows.
 */
private fun ChannelGrids.channelRows(
	ownerKey: String,
	ownerLabel: String,
	parameterId: ParameterId,
	labels: KeyformTrackLabels,
): List<TrackRow> =
	gridsByChannel.entries.mapNotNull { (channel, track) ->
		val marks = marksOf(track, parameterId, TrackKeyShape.Circle) ?: return@mapNotNull null
		TrackRow(
			key = "$ownerKey/${channel.name}",
			label = ownerLabel,
			detail = labels.channelName(channel),
			depth = 1,
			marks = marks,
		)
	}

/** This drawable's geometry, channel, and blend-shape rows for [parameterId]. */
private fun Drawable.trackRows(parameterId: ParameterId, labels: KeyformTrackLabels): List<TrackRow> {
	val rows = ArrayList<TrackRow>()
	marksOf(geometryGrid, parameterId, TrackKeyShape.Circle)?.let { marks ->
		rows.add(TrackRow(key = "drawable:${id.raw}/geometry", label = name, detail = labels.geometry, marks = marks))
	}
	rows.addAll(channelGrids.channelRows("drawable:${id.raw}", name, parameterId, labels))
	for ((bindingIndex, binding) in blendShapes.withIndex()) {
		if (binding.parameterId != parameterId) {
			continue
		}
		rows.add(
			TrackRow(
				key = "drawable:${id.raw}/blend$bindingIndex",
				label = name,
				detail = labels.blendShape,
				depth = 1,
				marks = binding.keys.map { keyValue -> TrackKeyMark(keyValue, TrackKeyShape.Square) },
			),
		)
	}
	return rows
}

/** This deformer's geometry and channel rows for [parameterId]. */
private fun Deformer.trackRows(parameterId: ParameterId, labels: KeyformTrackLabels): List<TrackRow> {
	val rows = ArrayList<TrackRow>()
	val geometry =
		when (this) {
			is Deformer.Warp -> geometryGrid
			is Deformer.Rotation -> geometryGrid
		}
	marksOf(geometry, parameterId, TrackKeyShape.Circle)?.let { marks ->
		rows.add(TrackRow(key = "deformer:${id.raw}/geometry", label = name, detail = labels.geometry, marks = marks))
	}
	rows.addAll(channelGrids.channelRows("deformer:${id.raw}", name, parameterId, labels))
	val blendKeys =
		when (this) {
			is Deformer.Warp -> blendShapes
			is Deformer.Rotation -> blendShapes
		}
	for ((bindingIndex, binding) in blendKeys.withIndex()) {
		if (binding.parameterId != parameterId) {
			continue
		}
		rows.add(
			TrackRow(
				key = "deformer:${id.raw}/blend$bindingIndex",
				label = name,
				detail = labels.blendShape,
				depth = 1,
				marks = binding.keys.map { keyValue -> TrackKeyMark(keyValue, TrackKeyShape.Square) },
			),
		)
	}
	return rows
}

/** This part's channel rows for [parameterId] (a part carries no geometry). */
private fun Part.trackRows(parameterId: ParameterId, labels: KeyformTrackLabels): List<TrackRow> =
	channelGrids.channelRows("part:${id.raw}", name, parameterId, labels)

/**
 * The axis domain for [parameter] - its authored range, which is what the sheet rules against.
 *
 * @param Parameter parameter The parameter.
 * @return Pair<Float, Float> The domain start and end.
 */
fun parameterDomain(parameter: Parameter): Pair<Float, Float> = parameter.min to parameter.max
