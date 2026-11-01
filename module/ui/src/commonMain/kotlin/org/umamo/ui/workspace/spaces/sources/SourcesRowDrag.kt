package org.umamo.ui.workspace.spaces.sources

import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.SourceLayerRef
import org.umamo.ui.workspace.rowdrag.RowDragController

/** What a dragged row carries: the binding a layer row stands for, or the tile a tile row stands for. */
internal sealed interface SourcesDragPayload {
	data class Layer(val ref: SourceLayerRef) : SourcesDragPayload

	data class Tile(val tileId: AtlasTileId) : SourcesDragPayload
}

/**
 * What a drag from a row carries, or null for a row that does not lift: a file, a drawable, and the
 * unbound group stand for nothing a drop could bind.  A layer under review drags nowhere either, since its
 * binding is what is under review, not a layer to offer, and an ignored layer stays out of the rig until
 * its row says otherwise.
 *
 * @param SourcesNode node The row.
 * @return SourcesDragPayload? What the row carries, or null.
 */
internal fun sourcesDragPayload(node: SourcesNode): SourcesDragPayload? =
	when (val kind = node.kind) {
		is SourcesNodeKind.Layer -> if (node.status.isReview || node.status == SourcesStatus.Ignored) null else SourcesDragPayload.Layer(kind.ref)
		is SourcesNodeKind.Tile -> SourcesDragPayload.Tile(kind.tileId)
		else -> null
	}

/**
 * The rebind a drop means: a layer onto a tile, or a tile onto a layer; anything else is no drop.  A
 * layer row under review (lost, erased, or lost to a replacement) is no target either - it stands for
 * a review, not for art a tile could take, and a binding to it would read as needing review the moment
 * it landed - and neither is an ignored row, which the rigger keeps out of the rig.
 *
 * @param SourcesDragPayload payload The dragged row.
 * @param SourcesNode        target  The row it was dropped on.
 * @return Pair? The tile to rebind and its new binding, or null.
 */
internal fun relinkFor(payload: SourcesDragPayload, target: SourcesNode): Pair<AtlasTileId, SourceLayerRef>? {
	val kind = target.kind

	return when {
		payload is SourcesDragPayload.Layer && kind is SourcesNodeKind.Tile -> kind.tileId to payload.ref
		payload is SourcesDragPayload.Tile && kind is SourcesNodeKind.Layer && !target.status.isReview && target.status != SourcesStatus.Ignored -> payload.tileId to kind.ref
		else -> null
	}
}

/**
 * Applies the drop at the end of a drag through [relinkFor], the rule the drop ring drew by, so what the
 * rigger saw is what happens, then opens the layer row the tile lands under so the rigger sees it land.
 *
 * The row opened is named by the BINDING the tile takes, not by the row the pointer was on: a file that
 * lists one key twice has a second row for it, and a tile bound to the key is listed under the first.
 *
 * @param RowDragController controller The drag state: read for the dragged row and the target, then ended.
 * @param Map               nodeById   The visible rows by node id, to resolve the target.
 * @param Function?         onRelink   Rebinds the tile to the layer, retiring nothing; null (no session to
 *   land an edit on) asks for nothing.
 * @param Function          expand     Opens the row with the given node id.
 */
internal fun performSourcesDrop(
	controller: RowDragController<SourcesDragPayload>,
	nodeById: Map<String, SourcesNode>,
	onRelink: ((List<AtlasTileId>, SourceLayerRef?, List<AtlasTileId>) -> Unit)?,
	expand: (String) -> Unit,
) {
	val payload = controller.draggedPayload
	val target = controller.dropTargetKey?.let { key -> nodeById[key] }
	val rebind = if (payload != null && target != null) relinkFor(payload, target) else null

	if (onRelink != null && rebind != null) {
		val (tileId, ref) = rebind
		onRelink(listOf(tileId), ref, emptyList())
		expand(sourcesLayerRowId(ref.sourceId, ref.layerKey))
	}

	controller.end()
}