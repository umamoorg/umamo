package org.umamo.ui.workspace.spaces.sources

import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.SourceLayerRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the visible rows and what a row stands for: which rows a fold lists, what a row previews on
 * hover, and which drawables its click selects.  Hand-built rows, no Compose.
 */
class SourcesRowsTest {
	private val artA = ArtSourceId("art-0")

	private fun node(kind: SourcesNodeKind, status: SourcesStatus): SourcesNode = SourcesNode("row", "Row", SourcesDetail.None, kind, status, emptyList())

	private fun node(id: String, kind: SourcesNodeKind, children: List<SourcesNode> = emptyList()): SourcesNode =
		SourcesNode(id, id, SourcesDetail.None, kind, SourcesStatus.None, children)

	/**
	 * A tile row over the drawables [drawables].
	 *
	 * @param String tileId    The tile.
	 * @param String drawables The ids of the drawables sampling it.
	 * @return SourcesNode The row.
	 */
	private fun tileRow(tileId: String, vararg drawables: String): SourcesNode =
		node("tile:$tileId", SourcesNodeKind.Tile(AtlasTileId(tileId))).copy(drawableIds = drawableIds(*drawables))

	private fun drawableIds(vararg ids: String): List<DrawableId> = ids.map { id -> DrawableId(id) }

	/**
	 * A tree of two files and the unbound group: the first file holds two layers, the first of them a
	 * tile with two drawables.
	 *
	 * @return List<SourcesNode> The top-level rows.
	 */
	private fun tree(): List<SourcesNode> {
		val tile =
			node(
				"tile:first",
				SourcesNodeKind.Tile(AtlasTileId("first")),
				listOf(node("drawable:a", SourcesNodeKind.Drawable(DrawableId("a"))), node("drawable:b", SourcesNodeKind.Drawable(DrawableId("b")))),
			)

		return listOf(
			node(
				"source:art-0",
				SourcesNodeKind.Source(artA),
				listOf(
					node("layer:art-0/lyid:1", SourcesNodeKind.Layer(SourceLayerRef(artA, "lyid:1", true)), listOf(tile)),
					node("layer:art-0/lyid:2", SourcesNodeKind.Layer(SourceLayerRef(artA, "lyid:2", true))),
				),
			),
			node("source:art-1", SourcesNodeKind.Source(ArtSourceId("art-1")), listOf(node("layer:art-1/uuid-9", SourcesNodeKind.Layer(SourceLayerRef(ArtSourceId("art-1"), "uuid-9", true))))),
			node(SOURCES_UNBOUND_GROUP_ID, SourcesNodeKind.UnboundGroup, listOf(node("tile:bare", SourcesNodeKind.Tile(AtlasTileId("bare"))))),
		)
	}

	/** The flatten lists a row's children only while the row is open, each one deeper than its parent. */
	@Test
	fun flattenFollowsTheOpenRows() {
		val closed = flattenSources(tree()) { id -> id.startsWith("source:") }
		assertEquals(
			listOf("source:art-0", "layer:art-0/lyid:1", "layer:art-0/lyid:2", "source:art-1", "layer:art-1/uuid-9", SOURCES_UNBOUND_GROUP_ID),
			closed.map { row -> row.node.id },
		)
		assertEquals(listOf(0, 1, 1, 0, 1, 0), closed.map { row -> row.depth })
		val open = flattenSources(tree()) { true }
		assertTrue(open.any { row -> row.node.id == "drawable:b" && row.depth == 3 }, "an open tile lists its drawables three deep")
		assertEquals(10, open.size, "every row of the tree")
	}

	/** A row with nothing under it is asked nothing: the fold of a leaf decides no row. */
	@Test
	fun flattenAsksOnlyTheRowsThatHaveChildren() {
		val asked = ArrayList<String>()

		flattenSources(tree()) { id ->
			asked.add(id)
			true
		}

		assertEquals(listOf("source:art-0", "layer:art-0/lyid:1", "tile:first", "source:art-1", SOURCES_UNBOUND_GROUP_ID), asked)
	}

	/** A tile row and a drawable row preview themselves. */
	@Test
	fun tileAndDrawableRowsPreviewThemselves() {
		val tileId = AtlasTileId("tile")
		assertEquals(SourcesPreviewSubject.Tile(tileId), sourcesPreviewSubject(node(SourcesNodeKind.Tile(tileId), SourcesStatus.Bound)))
		val drawableId = DrawableId("mesh")
		assertEquals(SourcesPreviewSubject.Drawable(drawableId), sourcesPreviewSubject(node(SourcesNodeKind.Drawable(drawableId), SourcesStatus.Bound)))
	}

	/**
	 * A layer row previews the first tile bound to it, and a layer under review still does: its binding is
	 * what the review is about, so its old art is exactly what the rigger needs to see.
	 */
	@Test
	fun aLayerRowPreviewsItsFirstBoundTile() {
		val layerKind = SourcesNodeKind.Layer(SourceLayerRef(artA, "lyid:1", true))
		val tileIds = listOf(AtlasTileId("first"), AtlasTileId("second"))
		val bound = node(layerKind, SourcesStatus.Bound).copy(tileIds = tileIds)
		assertEquals(SourcesPreviewSubject.Tile(AtlasTileId("first")), sourcesPreviewSubject(bound))
		val underReview = node(layerKind, SourcesStatus.NeedsReview).copy(tileIds = tileIds)
		assertEquals(SourcesPreviewSubject.Tile(AtlasTileId("first")), sourcesPreviewSubject(underReview))
	}

	/** A search that lists only a layer's second tile leaves the layer previewing its first: the row stands for the binding. */
	@Test
	fun aLayerRowPreviewsItsFirstBoundTileWhateverIsListed() {
		val layerKind = SourcesNodeKind.Layer(SourceLayerRef(artA, "lyid:1", true))
		val listed = listOf(tileRow("second", "c"))
		val layer = node(layerKind, SourcesStatus.Bound).copy(children = listed, tileIds = listOf(AtlasTileId("first"), AtlasTileId("second")))

		assertEquals(SourcesPreviewSubject.Tile(AtlasTileId("first")), sourcesPreviewSubject(layer))
	}

	/** An unbound layer's pixels live in its file, not the document, and a file or the unbound group is no single piece of art. */
	@Test
	fun rowsWithNoArtOfTheirOwnPreviewNothing() {
		assertNull(sourcesPreviewSubject(node(SourcesNodeKind.Layer(SourceLayerRef(artA, "lyid:1", true)), SourcesStatus.Unbound)))
		assertNull(sourcesPreviewSubject(node(SourcesNodeKind.Source(artA), SourcesStatus.Present)))
		assertNull(sourcesPreviewSubject(node(SourcesNodeKind.UnboundGroup, SourcesStatus.Unknown)))
	}

	/** A drawable row's click selects that drawable. */
	@Test
	fun aDrawableRowSelectsItself() {
		val row = node("drawable:c", SourcesNodeKind.Drawable(DrawableId("c")))

		assertEquals(listOf<SelectionTarget>(SelectionTarget.Drawable(DrawableId("c"))), sourcesSelectionTargets(row))
	}

	/** A tile row's click selects every drawable sampling the tile, and a tile nothing samples selects nothing. */
	@Test
	fun aTileRowSelectsEveryDrawableOverIt() {
		val sampled = tileRow("first", "a", "b")
		val bare = tileRow("bare")

		assertEquals(listOf<SelectionTarget>(SelectionTarget.Drawable(DrawableId("a")), SelectionTarget.Drawable(DrawableId("b"))), sourcesSelectionTargets(sampled))
		assertTrue(sourcesSelectionTargets(bare).isEmpty())
	}

	/** A layer row's click selects every drawable over every tile bound to the layer, tile by tile. */
	@Test
	fun aLayerRowSelectsEveryDrawableOverItsTiles() {
		val tiles = listOf(tileRow("first", "a", "b"), tileRow("second", "c"))
		val layer = node("layer:art-0/lyid:1", SourcesNodeKind.Layer(SourceLayerRef(artA, "lyid:1", true)), tiles).copy(drawableIds = drawableIds("a", "b", "c"))

		assertEquals(listOf("a", "b", "c"), sourcesSelectionTargets(layer).map { target -> (target as SelectionTarget.Drawable).id.raw })
	}

	/** A search that lists only one of a layer's tiles leaves the layer's click selecting the drawables over both. */
	@Test
	fun aLayerRowSelectsTheSameWhateverIsListed() {
		val listed = listOf(tileRow("second", "c"))
		val layer = node("layer:art-0/lyid:1", SourcesNodeKind.Layer(SourceLayerRef(artA, "lyid:1", true)), listed).copy(drawableIds = drawableIds("a", "b", "c"))

		assertEquals(listOf("a", "b", "c"), sourcesSelectionTargets(layer).map { target -> (target as SelectionTarget.Drawable).id.raw })
	}

	/** A file, the unbound group, and a layer no tile binds select nothing, which is what lets their click fold instead. */
	@Test
	fun rowsWithNothingToSelectSelectNothing() {
		val file = node("source:art-0", SourcesNodeKind.Source(artA), tree()[0].children)
		val group = node(SOURCES_UNBOUND_GROUP_ID, SourcesNodeKind.UnboundGroup, listOf(tileRow("first", "a", "b")))
		val unbound = node("layer:art-0/lyid:2", SourcesNodeKind.Layer(SourceLayerRef(artA, "lyid:2", true)))

		assertTrue(sourcesSelectionTargets(file).isEmpty())
		assertTrue(sourcesSelectionTargets(group).isEmpty())
		assertTrue(sourcesSelectionTargets(unbound).isEmpty())
	}
}