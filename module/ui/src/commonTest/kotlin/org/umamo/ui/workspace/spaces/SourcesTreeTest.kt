package org.umamo.ui.workspace.spaces

import org.umamo.runtime.model.ArtSource
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.ArtSourceLayer
import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.SourceLayerRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit-tests the Sources tree: files with their presence, inventory layers with their binding status,
 * tiles under the layers they are bound to (and strays under keys the inventory lacks), drawables
 * under tiles, the unbound-art group, the three filters, and the flatten.  Hand-built rig, no Compose.
 */
class SourcesTreeTest {
	private val artA = ArtSourceId("art-0")
	private val artB = ArtSourceId("art-1")

	private fun drawable(id: String, tileId: String): Drawable =
		Drawable(DrawableId(id), id, null, BlendMode.Normal, emptyList(), null, null, atlasTileId = AtlasTileId(tileId))

	private fun model(): PuppetModel {
		val drawables = listOf(drawable("a", "tA1"), drawable("b", "tA1"), drawable("c", "tA3"), drawable("d", "tLoose"))
		return PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = drawables,
			rootChildren = drawables.map { drawable -> OrgChild.Drawable(drawable.id) },
			rootPartId = null,
			atlas =
				PuppetAtlas(
					pages = listOf(AtlasPage(64, 64)),
					tiles =
						listOf(
							AtlasTile(AtlasTileId("tA1"), "Eye", 4, 4, placement = AtlasPlacement(0, 1f, 1f, 1f, 1f, 0f), source = SourceLayerRef(artA, "lyid:1", true)),
							AtlasTile(AtlasTileId("tA3"), "Hair", 4, 4, source = SourceLayerRef(artA, "name:Stray", false)),
							AtlasTile(AtlasTileId("tLoose"), "Loose", 4, 4),
						),
				),
			sources =
				listOf(
					ArtSource(
						artA,
						"a.psd",
						"/art/a.psd",
						"psd",
						listOf(
							ArtSourceLayer("lyid:1", "Eye", "Head", 10, 20, 4, 4, true),
							ArtSourceLayer("lyid:2", "Unused", "Head", 0, 0, 2, 2, false),
						),
					),
					ArtSource(artB, "b.clip", null, "clip", listOf(ArtSourceLayer("uuid-9", "Wing", "", 0, 0, 8, 8, true))),
				),
		)
	}

	private fun presence(source: ArtSource): SourcePresence = if (source.id == artA) SourcePresence.Missing else SourcePresence.Unknown

	@Test
	fun theTreeReadsFileLayerTileDrawable() {
		val tree = buildSourcesTree(model(), ::presence, "Unbound art")

		assertEquals(listOf("source:art-0", "source:art-1", SOURCES_UNBOUND_GROUP_ID), tree.map { node -> node.id })
		val fileA = tree[0]
		assertEquals(SourcesStatus.Missing, fileA.status)
		assertEquals(SourcesDetail.Source("psd", 2, hasPath = true), fileA.detail)
		assertEquals(listOf("layer:art-0/lyid:1", "layer:art-0/lyid:2", "layer:art-0/name:Stray"), fileA.children.map { node -> node.id }, "inventory rows, then the stray binding")
		val eye = fileA.children[0]
		assertEquals(SourcesStatus.Bound, eye.status)
		assertEquals(SourcesDetail.Layer(4, 4, 10, 20), eye.detail)
		assertEquals(listOf("tile:tA1"), eye.children.map { node -> node.id })
		assertEquals(SourcesDetail.TilePage(1), eye.children[0].detail)
		assertEquals(listOf("drawable:a", "drawable:b"), eye.children[0].children.map { node -> node.id })
		assertEquals(SourcesStatus.Unbound, fileA.children[1].status, "a layer no tile binds")
		val stray = fileA.children[2]
		assertEquals("Stray", stray.label, "a name key shows as the name")
		assertEquals(SourcesStatus.BoundByName, stray.status)
		assertEquals(SourcesStatus.Unplaced, stray.children.single().status)
		assertEquals(SourcesStatus.Unknown, tree[1].status, "no path, no verdict")
		assertEquals(SourcesDetail.Source("clip", 1, hasPath = false), tree[1].detail)
		val unbound = tree[2]
		assertEquals(SourcesNodeKind.UnboundGroup, unbound.kind)
		assertEquals(listOf("tile:tLoose"), unbound.children.map { node -> node.id })
	}

	@Test
	fun theFiltersKeepWhatTheyNameAndTheirAncestors() {
		val tree = buildSourcesTree(model(), ::presence, "Unbound art")

		val unbound = filterSourcesTree(tree, "", SourcesFilter.Unbound)
		assertEquals(listOf("source:art-0", "source:art-1", SOURCES_UNBOUND_GROUP_ID), unbound.map { node -> node.id })
		assertEquals(listOf("layer:art-0/lyid:2"), unbound[0].children.map { node -> node.id }, "only the unbound layer survives under the file")
		assertEquals(listOf("layer:art-1/uuid-9"), unbound[1].children.map { node -> node.id })
		assertEquals(listOf("tile:tLoose"), unbound[2].children.map { node -> node.id }, "the unbound group keeps its tiles")

		val missing = filterSourcesTree(tree, "", SourcesFilter.Missing)
		assertEquals(listOf("source:art-0"), missing.map { node -> node.id })
		assertEquals(3, missing[0].children.size, "a missing file keeps its whole subtree")

		val searched = filterSourcesTree(tree, "wing", SourcesFilter.All)
		assertEquals(listOf("source:art-1"), searched.map { node -> node.id }, "a search keeps the matching row's ancestors")
		assertEquals(listOf("layer:art-1/uuid-9"), searched[0].children.map { node -> node.id })
	}

	@Test
	fun flattenFollowsTheOpenRows() {
		val tree = buildSourcesTree(model(), ::presence, "Unbound art")
		val closed = flattenSources(tree) { id -> id.startsWith("source:") }
		assertEquals(
			listOf("source:art-0", "layer:art-0/lyid:1", "layer:art-0/lyid:2", "layer:art-0/name:Stray", "source:art-1", "layer:art-1/uuid-9", SOURCES_UNBOUND_GROUP_ID),
			closed.map { row -> row.node.id },
		)
		assertEquals(listOf(0, 1, 1, 1, 0, 1, 0), closed.map { row -> row.depth })
		val open = flattenSources(tree) { true }
		assertTrue(open.any { row -> row.node.id == "drawable:b" && row.depth == 3 }, "an open tile lists its drawables three deep")
	}

	@Test
	fun keyShapesSayWhatTheyAre() {
		assertTrue(layerKeyLooksStable("lyid:12"))
		assertTrue(layerKeyLooksStable("3f2a-uuid"))
		assertTrue(!layerKeyLooksStable("name:Eye"))
		assertTrue(!layerKeyLooksStable("Eye#4"))
	}

	@Test
	fun aDropRebindsOnlyAcrossTheTwoKinds() {
		val ref = SourceLayerRef(artA, "lyid:1", true)
		assertEquals(AtlasTileId("t") to ref, relinkFor(SourcesDragPayload.Layer(ref), SourcesNodeKind.Tile(AtlasTileId("t"))))
		assertEquals(AtlasTileId("t") to ref, relinkFor(SourcesDragPayload.Tile(AtlasTileId("t")), SourcesNodeKind.Layer(ref)))
		assertEquals(null, relinkFor(SourcesDragPayload.Tile(AtlasTileId("t")), SourcesNodeKind.Tile(AtlasTileId("u"))))
		assertEquals(null, relinkFor(SourcesDragPayload.Layer(ref), SourcesNodeKind.Source(artA)))
	}

	/**
	 * A repeated inventory key (a foreign or broken file) still yields unique row ids - the list keys
	 * on them - and the bound tile lists once, under the first row.
	 */
	@Test
	fun repeatedInventoryKeysKeepRowIdsUniqueAndListTilesOnce() {
		val drawables = listOf(drawable("a", "t1"))
		val puppet =
			PuppetModel(
				parameters = emptyList(),
				parts = emptyList(),
				deformers = emptyList(),
				drawables = drawables,
				rootChildren = drawables.map { drawable -> OrgChild.Drawable(drawable.id) },
				rootPartId = null,
				atlas = PuppetAtlas(pages = emptyList(), tiles = listOf(AtlasTile(AtlasTileId("t1"), "One", 4, 4, source = SourceLayerRef(artA, "name:1", false)))),
				sources =
					listOf(
						ArtSource(
							artA,
							"a.psd",
							null,
							"psd",
							listOf(
								ArtSourceLayer("name:1", "1", "", 0, 0, 4, 4, true),
								ArtSourceLayer("name:1", "1", "Folder", 0, 0, 4, 4, true),
								ArtSourceLayer("name:1", "1", "Folder/Deeper", 0, 0, 4, 4, true),
							),
						),
					),
			)
		val file = buildSourcesTree(puppet, { SourcePresence.Unknown }, "Unbound").single()
		assertEquals(listOf("layer:art-0/name:1", "layer:art-0/name:1~2", "layer:art-0/name:1~3"), file.children.map { node -> node.id })
		assertEquals(listOf("tile:t1"), file.children[0].children.map { node -> node.id }, "the first row owns the binding")
		assertTrue(file.children.drop(1).all { node -> node.children.isEmpty() && node.status == SourcesStatus.Unbound }, "later rows list nothing")

		fun ids(nodes: List<SourcesNode>): List<String> = nodes.flatMap { node -> listOf(node.id) + ids(node.children) }
		val allIds = ids(listOf(file))
		assertEquals(allIds.size, allIds.toSet().size, "no row id repeats anywhere in the tree")
	}
}