package org.umamo.ui.workspace.spaces.sources

import androidx.compose.ui.geometry.Rect
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.SourceLayerRef
import org.umamo.ui.workspace.rowdrag.RowDragController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the drag rules: which rows lift and what they carry, what a drop rebinds, and what the release
 * asks for.  None needs a composition: the drag state is driven by hand.
 */
class SourcesDropTest {
	private val artA = ArtSourceId("art-0")
	private val ref = SourceLayerRef(artA, "lyid:1", true)

	/** The row the binding [ref] is listed under. */
	private val layerRowId = sourcesLayerRowId(artA, "lyid:1")

	/** The second row of a file that lists the key of [ref] twice. */
	private val repeatedRowId = "$layerRowId~2"

	private fun node(kind: SourcesNodeKind, status: SourcesStatus): SourcesNode = SourcesNode("row", "Row", SourcesDetail.None, kind, status, emptyList())

	private fun node(id: String, kind: SourcesNodeKind, status: SourcesStatus): SourcesNode = SourcesNode(id, id, SourcesDetail.None, kind, status, emptyList())

	/** The rows a drop opened, by node id. */
	private val opened = ArrayList<String>()

	/** One relink a drop asked for. */
	private class Relink(val tileIds: List<AtlasTileId>, val ref: SourceLayerRef?, val retire: List<AtlasTileId>)

	/**
	 * A drag of [payload] from the row [draggedKey], resting over the row [targetKey]: two rows of 22
	 * pixels, the dragged one above the target.
	 *
	 * @param String             draggedKey The dragged row's id.
	 * @param SourcesDragPayload payload    What the drag carries.
	 * @param String             targetKey  The id of the row under the pointer.
	 * @return RowDragController The drag state.
	 */
	private fun dragOver(draggedKey: String, payload: SourcesDragPayload, targetKey: String): RowDragController<SourcesDragPayload> {
		val controller = RowDragController<SourcesDragPayload>()
		controller.reportBounds(draggedKey, Rect(0f, 0f, 100f, 22f))
		controller.reportBounds(targetKey, Rect(0f, 22f, 100f, 44f))
		controller.start(draggedKey, payload, 50f, 11f)
		controller.drag(50f, 33f)

		return controller
	}

	/** A dragged row rebinds only between a layer and a tile, and a row the file no longer offers is no target. */
	@Test
	fun aDropRebindsOnlyAcrossTheTwoKindsAndNeverOntoALostRow() {
		val ref = SourceLayerRef(artA, "lyid:1", true)
		val tile = SourcesDragPayload.Tile(AtlasTileId("t"))

		assertEquals(AtlasTileId("t") to ref, relinkFor(SourcesDragPayload.Layer(ref), node(SourcesNodeKind.Tile(AtlasTileId("t")), SourcesStatus.None)))
		assertEquals(AtlasTileId("t") to ref, relinkFor(tile, node(SourcesNodeKind.Layer(ref), SourcesStatus.Bound)))
		assertEquals(null, relinkFor(tile, node(SourcesNodeKind.Tile(AtlasTileId("u")), SourcesStatus.None)))
		assertEquals(null, relinkFor(SourcesDragPayload.Layer(ref), node(SourcesNodeKind.Source(artA), SourcesStatus.None)))
		assertEquals(null, relinkFor(tile, node(SourcesNodeKind.Layer(ref), SourcesStatus.NeedsReview)), "a lost row is no target")
		assertEquals(null, relinkFor(tile, node(SourcesNodeKind.Layer(ref), SourcesStatus.Emptied)), "nor an erased one")
		assertEquals(null, relinkFor(tile, node(SourcesNodeKind.Layer(ref), SourcesStatus.SourceReplaced)), "nor one a replacement lost")
		assertEquals(null, relinkFor(tile, node(SourcesNodeKind.Layer(ref), SourcesStatus.Ignored)), "nor an ignored one")
	}

	/** A bound or an unbound layer row carries its binding, and a tile row its tile. */
	@Test
	fun aLayerAndATileRowLift() {
		assertEquals(SourcesDragPayload.Layer(ref), sourcesDragPayload(node(SourcesNodeKind.Layer(ref), SourcesStatus.Bound)))
		assertEquals(SourcesDragPayload.Layer(ref), sourcesDragPayload(node(SourcesNodeKind.Layer(ref), SourcesStatus.BoundByName)))
		assertEquals(SourcesDragPayload.Layer(ref), sourcesDragPayload(node(SourcesNodeKind.Layer(ref), SourcesStatus.Unbound)))
		assertEquals(SourcesDragPayload.Tile(AtlasTileId("t")), sourcesDragPayload(node(SourcesNodeKind.Tile(AtlasTileId("t")), SourcesStatus.Unplaced)))
	}

	/** A row under review offers no layer, an ignored row stays out of the rig, and the other kinds stand for nothing a drop could bind. */
	@Test
	fun aRowThatOffersNoLayerDoesNotLift() {
		assertNull(sourcesDragPayload(node(SourcesNodeKind.Layer(ref), SourcesStatus.NeedsReview)))
		assertNull(sourcesDragPayload(node(SourcesNodeKind.Layer(ref), SourcesStatus.Emptied)))
		assertNull(sourcesDragPayload(node(SourcesNodeKind.Layer(ref), SourcesStatus.SourceReplaced)))
		assertNull(sourcesDragPayload(node(SourcesNodeKind.Layer(ref), SourcesStatus.Ignored)))
		assertNull(sourcesDragPayload(node(SourcesNodeKind.Source(artA), SourcesStatus.Present)))
		assertNull(sourcesDragPayload(node(SourcesNodeKind.Drawable(DrawableId("a")), SourcesStatus.None)))
		assertNull(sourcesDragPayload(node(SourcesNodeKind.UnboundGroup, SourcesStatus.None)))
	}

	/** A release over a row the drag may bind to asks for one relink of the one tile, retiring nothing, and ends the drag. */
	@Test
	fun aDropAsksForTheRelinkAndEndsTheDrag() {
		val layer = node("layer", SourcesNodeKind.Layer(ref), SourcesStatus.Unbound)
		val controller = dragOver("tile", SourcesDragPayload.Tile(AtlasTileId("t")), "layer")
		val asked = ArrayList<Relink>()

		performSourcesDrop(controller, mapOf("layer" to layer), onRelink = { tileIds, target, retire -> asked.add(Relink(tileIds, target, retire)) }, expand = { nodeId -> opened.add(nodeId) })

		assertEquals(1, asked.size)
		assertEquals(listOf(AtlasTileId("t")), asked[0].tileIds)
		assertEquals(ref, asked[0].ref)
		assertTrue(asked[0].retire.isEmpty())
		assertFalse(controller.isDragging)
	}

	/** A tile dropped on a layer lands under that layer's row, so the drop opens it. */
	@Test
	fun aTileDropOpensTheLayerItLandsIn() {
		val layer = node(layerRowId, SourcesNodeKind.Layer(ref), SourcesStatus.Unbound)
		val controller = dragOver("tile", SourcesDragPayload.Tile(AtlasTileId("t")), layerRowId)

		performSourcesDrop(controller, mapOf(layerRowId to layer), onRelink = { _, _, _ -> }, expand = { nodeId -> opened.add(nodeId) })

		assertEquals(listOf(layerRowId), opened)
	}

	/** A layer dropped on a tile takes the tile under its own row, so the drop opens the dragged row. */
	@Test
	fun aLayerDropOpensTheLayerThatWasDragged() {
		val tile = node("tile", SourcesNodeKind.Tile(AtlasTileId("t")), SourcesStatus.None)
		val controller = dragOver(layerRowId, SourcesDragPayload.Layer(ref), "tile")

		performSourcesDrop(controller, mapOf("tile" to tile), onRelink = { _, _, _ -> }, expand = { nodeId -> opened.add(nodeId) })

		assertEquals(listOf(layerRowId), opened)
	}

	/**
	 * A file that lists one key twice has a second row for it, which takes a drop like any unbound layer.
	 * The tile is bound to the key, so it lands under the key's first row, and that is the row opened.
	 */
	@Test
	fun aTileDropOnARepeatedRowOpensTheRowTheArtLandsUnder() {
		val repeated = node(repeatedRowId, SourcesNodeKind.Layer(ref), SourcesStatus.Unbound)
		val controller = dragOver("tile", SourcesDragPayload.Tile(AtlasTileId("t")), repeatedRowId)

		performSourcesDrop(controller, mapOf(repeatedRowId to repeated), onRelink = { _, _, _ -> }, expand = { nodeId -> opened.add(nodeId) })

		assertEquals(listOf(layerRowId), opened)
	}

	/** A repeated row dragged onto a tile carries the same key, so the row opened is the key's first row too. */
	@Test
	fun aRepeatedRowDroppedOnATileOpensTheRowTheArtLandsUnder() {
		val tile = node("tile", SourcesNodeKind.Tile(AtlasTileId("t")), SourcesStatus.None)
		val controller = dragOver(repeatedRowId, SourcesDragPayload.Layer(ref), "tile")

		performSourcesDrop(controller, mapOf("tile" to tile), onRelink = { _, _, _ -> }, expand = { nodeId -> opened.add(nodeId) })

		assertEquals(listOf(layerRowId), opened)
	}

	/** A layer's row is named by its file and its key, which is all a binding to it carries. */
	@Test
	fun aLayerRowIsNamedByItsFileAndKey() {
		assertEquals("layer:art-0/lyid:1", sourcesLayerRowId(artA, "lyid:1"))
		assertEquals("layer:art-0/name:Eye", sourcesLayerRowId(artA, "name:Eye"))
	}

	/** A release over a row the drag may not bind to asks for nothing, and still ends the drag. */
	@Test
	fun aDropOnARowThatTakesNoneAsksForNothing() {
		val lost = node("layer", SourcesNodeKind.Layer(ref), SourcesStatus.NeedsReview)
		val controller = dragOver("tile", SourcesDragPayload.Tile(AtlasTileId("t")), "layer")
		val asked = ArrayList<Relink>()

		performSourcesDrop(controller, mapOf("layer" to lost), onRelink = { tileIds, target, retire -> asked.add(Relink(tileIds, target, retire)) }, expand = { nodeId -> opened.add(nodeId) })

		assertTrue(asked.isEmpty())
		assertTrue(opened.isEmpty(), "a drop that binds nothing opens nothing")
		assertFalse(controller.isDragging)
	}

	/** A release over a row the table no longer lists asks for nothing. */
	@Test
	fun aDropOnARowThatLeftTheTableAsksForNothing() {
		val controller = dragOver("tile", SourcesDragPayload.Tile(AtlasTileId("t")), "layer")
		val asked = ArrayList<Relink>()

		performSourcesDrop(controller, emptyMap(), onRelink = { tileIds, target, retire -> asked.add(Relink(tileIds, target, retire)) }, expand = { nodeId -> opened.add(nodeId) })

		assertTrue(asked.isEmpty())
		assertTrue(opened.isEmpty())
		assertFalse(controller.isDragging)
	}

	/** With nothing to land an edit on, a release ends the drag and asks for nothing. */
	@Test
	fun aDropWithNothingToLandOnEndsTheDrag() {
		val layer = node("layer", SourcesNodeKind.Layer(ref), SourcesStatus.Unbound)
		val controller = dragOver("tile", SourcesDragPayload.Tile(AtlasTileId("t")), "layer")

		performSourcesDrop(controller, mapOf("layer" to layer), onRelink = null, expand = { nodeId -> opened.add(nodeId) })

		assertTrue(opened.isEmpty())
		assertFalse(controller.isDragging)
	}
}