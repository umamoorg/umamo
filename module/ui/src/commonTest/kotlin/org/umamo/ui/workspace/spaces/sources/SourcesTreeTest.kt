package org.umamo.ui.workspace.spaces.sources

import org.umamo.reimport.LayerMatch
import org.umamo.reimport.MatchSignals
import org.umamo.runtime.model.ArtSource
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.ArtSourceLayer
import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.SourceLayerRef
import org.umamo.runtime.model.layerKeyLooksStable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit-tests the Sources tree: files with their presence, inventory layers with their binding status,
 * tiles under the layers they are bound to (and strays under keys the inventory lacks), drawables
 * under tiles, the unbound-art group, and the four filters.  Hand-built rig, no Compose.
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
	fun layerPositionsReadFromTheFileOrTheWorldAxes() {
		// A 64 x 64 canvas with the origin at its center, world (32, -32); file A placed 5 right and 3 down.
		val base = model()
		val puppet =
			base.copy(
				canvasWidth = 64f,
				canvasHeight = 64f,
				worldOriginX = 32f,
				worldOriginZ = -32f,
				sources = base.sources.map { source -> if (source.id == artA) source.copy(offsetX = 5, offsetZ = -3) else source },
			)

		// The Eye layer sits at document (10, 20), top-left, y down.
		val fileFrame = buildSourcesTree(puppet, ::presence, "Unbound art")[0].children[0].detail
		assertEquals(SourcesDetail.Layer(4, 4, 5, 17), fileFrame, "the default takes the placement back out, so the numbers match the art file")

		val worldAxes = buildSourcesTree(puppet, ::presence, "Unbound art", layerPositionsFromWorldAxes = true)[0].children[0].detail
		assertEquals(SourcesDetail.LayerOnAxes(4, 4, -22f, 12f), worldAxes, "the same corner, 22 left of and 12 above the axes")
	}

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
		assertEquals(listOf(DrawableId("a"), DrawableId("b")), eye.children[0].drawableIds, "a tile row carries the drawables over it")
		assertEquals(SourceLayerRef(artA, "lyid:1", true), eye.children[0].binding, "and its binding as the tile carries it")
		assertEquals(listOf(AtlasTileId("tA1")), eye.tileIds, "a layer row carries the tiles bound to it")
		assertEquals(listOf(DrawableId("a"), DrawableId("b")), eye.drawableIds, "and the drawables over them")
		assertNull(eye.binding, "the binding is the tile's to carry")
		assertTrue(fileA.children[1].tileIds.isEmpty() && fileA.children[1].drawableIds.isEmpty(), "a layer no tile binds carries none")
		assertTrue(fileA.tileIds.isEmpty() && fileA.drawableIds.isEmpty(), "and neither does a file")
		assertEquals(SourcesStatus.Unbound, fileA.children[1].status, "a layer no tile binds")
		val stray = fileA.children[2]
		assertEquals("Stray", stray.label, "a name key shows as the name")
		assertEquals(SourcesStatus.NeedsReview, stray.status, "a binding to a layer the file no longer lists waits on a decision")
		assertEquals(SourcesStatus.Unplaced, stray.children.single().status)
		assertEquals(SourcesStatus.Unknown, tree[1].status, "no path, no verdict")
		assertEquals(SourcesDetail.Source("clip", 1, hasPath = false), tree[1].detail)
		val unbound = tree[2]
		assertEquals(SourcesNodeKind.UnboundGroup, unbound.kind)
		assertEquals(listOf("tile:tLoose"), unbound.children.map { node -> node.id })
		assertNull(unbound.children[0].binding, "a tile bound to no layer")
		assertEquals(listOf(DrawableId("d")), unbound.children[0].drawableIds)
	}

	/**
	 * A tile bound to a file the document does not list - a sources entry too new to read, or one a foreign
	 * writer dropped - is never invisible: it joins the unbound group reading as needing review, with the
	 * binding it carries, and the review filter finds it (UMA §3.6).
	 */
	@Test
	fun aTileBoundToAnUnlistedFileIsShownForReview() {
		val base = model()
		val orphan = AtlasTile(AtlasTileId("tOrphan"), "Orphan", 4, 4, placement = AtlasPlacement(0, 1f, 1f, 1f, 1f, 0f), source = SourceLayerRef(ArtSourceId("art-9"), "lyid:7", true))
		val puppet = base.copy(atlas = base.atlas.copy(tiles = base.atlas.tiles + orphan))
		val tree = buildSourcesTree(puppet, ::presence, "Unbound art")
		val group = tree.last()
		assertEquals(SourcesNodeKind.UnboundGroup, group.kind)
		assertEquals(listOf("tile:tLoose", "tile:tOrphan"), group.children.map { node -> node.id }, "the unlisted binding joins the group")
		val row = group.children[1]
		assertEquals(SourcesStatus.SourceNotListed, row.status, "it waits on a decision")
		assertEquals(SourcesDetail.UnlistedBinding(ArtSourceId("art-9"), "lyid:7"), row.detail, "and shows the binding it carries")
		assertEquals(SourceLayerRef(ArtSourceId("art-9"), "lyid:7", true), row.binding, "which the relink chip unbinds or moves")
		val review = filterSourcesTree(tree, "", setOf(SourcesFilter.NeedsReview))
		assertTrue(review.any { node -> node.children.any { child -> child.id == "tile:tOrphan" } }, "the review filter finds it")
		val unlisted = puppet.copy(sources = emptyList())
		val onlyGroup = buildSourcesTree(unlisted, ::presence, "Unbound art")
		assertEquals(listOf(SOURCES_UNBOUND_GROUP_ID), onlyGroup.map { node -> node.id }, "with no file listed, every tile is in the group")
		assertEquals(4, onlyGroup.single().children.size, "all four tiles, none lost")
	}

	/** A search that lists only some of a tile's drawables leaves the tile's own list whole. */
	@Test
	fun aSearchLeavesATilesDrawablesWhole() {
		val tree = buildSourcesTree(model(), ::presence, "Unbound art")

		val tile = filterSourcesTree(tree, "b", SourcesFilter.entries.toSet())[0].children[0].children[0]

		assertEquals(listOf("drawable:b"), tile.children.map { node -> node.id }, "the search lists the one match")
		assertEquals(listOf(DrawableId("a"), DrawableId("b")), tile.drawableIds)
	}

	/** A search that lists only one of a layer's tiles leaves the layer's own lists whole. */
	@Test
	fun aSearchLeavesALayersTilesWhole() {
		val base = model()
		val puppet =
			base.copy(
				drawables = base.drawables + drawable("e", "tA2"),
				atlas = base.atlas.copy(tiles = base.atlas.tiles + AtlasTile(AtlasTileId("tA2"), "Lash", 4, 4, source = SourceLayerRef(artA, "lyid:1", true))),
			)
		val tree = buildSourcesTree(puppet, ::presence, "Unbound art")

		val layer = filterSourcesTree(tree, "lash", SourcesFilter.entries.toSet())[0].children[0]

		assertEquals(listOf("tile:tA2"), layer.children.map { node -> node.id }, "the search lists the one tile")
		assertEquals(listOf(AtlasTileId("tA1"), AtlasTileId("tA2")), layer.tileIds)
		assertEquals(listOf(DrawableId("a"), DrawableId("b"), DrawableId("e")), layer.drawableIds)
	}

	/**
	 * Each kind of row is its own toggle: the table shows the rows of every enabled kind with their
	 * descendants and the ancestors that give them context, kinds combine by union, every kind on
	 * hides nothing, and none on shows nothing.
	 */
	@Test
	fun theFiltersKeepWhatTheyNameAndTheirAncestorsAndCombine() {
		val tree = buildSourcesTree(model(), ::presence, "Unbound art")
		val everything = SourcesFilter.entries.toSet()

		val unbound = filterSourcesTree(tree, "", setOf(SourcesFilter.Unbound))
		assertEquals(listOf("source:art-0", "source:art-1", SOURCES_UNBOUND_GROUP_ID), unbound.map { node -> node.id })
		assertEquals(listOf("layer:art-0/lyid:2"), unbound[0].children.map { node -> node.id }, "only the unbound layer survives under the file")
		assertEquals(listOf("layer:art-1/uuid-9"), unbound[1].children.map { node -> node.id })
		assertEquals(listOf("tile:tLoose"), unbound[2].children.map { node -> node.id }, "the unbound group keeps its tiles")

		val bound = filterSourcesTree(tree, "", setOf(SourcesFilter.Bound))
		assertEquals(listOf("source:art-0"), bound.map { node -> node.id }, "only the file with a bound layer")
		assertEquals(listOf("layer:art-0/lyid:1"), bound[0].children.map { node -> node.id })
		assertEquals(listOf("tile:tA1"), bound[0].children[0].children.map { node -> node.id }, "with its tile and drawables beneath")

		val missing = filterSourcesTree(tree, "", setOf(SourcesFilter.Missing))
		assertEquals(listOf("source:art-0"), missing.map { node -> node.id })
		assertEquals(3, missing[0].children.size, "a missing file keeps its whole subtree")

		val review = filterSourcesTree(tree, "", setOf(SourcesFilter.NeedsReview))
		assertEquals(listOf("source:art-0"), review.map { node -> node.id })
		assertEquals(listOf("layer:art-0/name:Stray"), review[0].children.map { node -> node.id }, "only the stray binding needs review")

		val both = filterSourcesTree(tree, "", setOf(SourcesFilter.Unbound, SourcesFilter.NeedsReview))
		assertEquals(listOf("layer:art-0/lyid:2", "layer:art-0/name:Stray"), both[0].children.map { node -> node.id }, "two kinds combine by union")
		assertEquals(3, both.size, "the unbound group still shows")

		assertEquals(tree, filterSourcesTree(tree, "", everything), "every kind on hides nothing")
		assertTrue(filterSourcesTree(tree, "", emptySet()).isEmpty(), "no kind on shows nothing")

		val searched = filterSourcesTree(tree, "wing", everything)
		assertEquals(listOf("source:art-1"), searched.map { node -> node.id }, "a search keeps the matching row's ancestors")
		assertEquals(listOf("layer:art-1/uuid-9"), searched[0].children.map { node -> node.id })
		assertTrue(filterSourcesTree(tree, "wing", setOf(SourcesFilter.Bound)).isEmpty(), "a search narrows within the enabled kinds")
	}

	/**
	 * A layer the file lost but a tile still binds keeps its row - named and sized as the inventory last
	 * saw it, reading as needing review - and carries the proposal the matcher made for it, unless the
	 * proposal names a layer the file no longer has or one some tile already binds.  A binding the
	 * inventory never listed still falls back to its raw key.
	 */
	@Test
	fun lostRowsReadTheirNameAndCarryAValidSuggestion() {
		val base = model()
		val puppet =
			base.copy(
				atlas =
					base.atlas.copy(
						tiles =
							base.atlas.tiles + AtlasTile(AtlasTileId("tA9"), "Old brow", 4, 4, source = SourceLayerRef(artA, "lyid:9", true)),
					),
				sources =
					base.sources.map { source ->
						if (source.id != artA) {
							source
						} else {
							source.copy(
								layers =
									source.layers +
										ArtSourceLayer("lyid:5", "Brow", "Head", 12, 22, 4, 4, true) +
										ArtSourceLayer("lyid:9", "Brow (old)", "Head", 12, 22, 4, 4, true, present = false),
							)
						}
					},
			)

		fun match(key: String, score: Float): LayerMatch = LayerMatch(key, score, MatchSignals(1f, 1f, 1f, 1f, null, hashEqual = false))
		val tree = buildSourcesTree(puppet, ::presence, "Unbound art") { sourceId, key -> if (sourceId == artA && key == "lyid:9") listOf(match("lyid:5", 0.92f)) else emptyList() }
		val fileA = tree[0]
		assertEquals(listOf("layer:art-0/lyid:1", "layer:art-0/lyid:2", "layer:art-0/lyid:5", "layer:art-0/lyid:9", "layer:art-0/name:Stray"), fileA.children.map { node -> node.id })
		val lost = fileA.children[3]
		assertEquals("Brow (old)", lost.label, "the lost row keeps the name the inventory last saw")
		assertEquals(SourcesDetail.Layer(4, 4, 12, 22), lost.detail)
		assertEquals(SourcesStatus.NeedsReview, lost.status)
		assertEquals(listOf("tile:tA9"), lost.children.map { node -> node.id })
		assertEquals(LayerSuggestion("lyid:5", "Brow", 0.92f), lost.suggestion)
		assertNull(fileA.children[2].suggestion, "a present row proposes nothing")
		val stray = fileA.children[4]
		assertEquals("Stray", stray.label, "a binding the inventory never listed still shows by its key")
		assertEquals(SourcesStatus.NeedsReview, stray.status)

		val boundElsewhere = buildSourcesTree(puppet, ::presence, "Unbound art") { _, _ -> listOf(match("lyid:1", 0.9f)) }
		assertNull(boundElsewhere[0].children[3].suggestion, "a proposal naming a layer some tile binds is dropped")
		val goneCandidate = buildSourcesTree(puppet, ::presence, "Unbound art") { _, _ -> listOf(match("lyid:77", 0.9f)) }
		assertNull(goneCandidate[0].children[3].suggestion, "a proposal naming a layer the file lacks is dropped")
		// A stale published proposal ahead of a live one: the row takes the live one rather than nothing.
		val staleFirst = buildSourcesTree(puppet, ::presence, "Unbound art") { _, _ -> listOf(match("lyid:1", 0.95f), match("lyid:5", 0.6f)) }
		assertEquals(LayerSuggestion("lyid:5", "Brow", 0.6f), staleFirst[0].children[3].suggestion, "a dropped proposal does not hide the next one")
		val review = filterSourcesTree(tree, "", setOf(SourcesFilter.NeedsReview))
		assertEquals(listOf("layer:art-0/lyid:9", "layer:art-0/name:Stray"), review[0].children.map { node -> node.id })

		// Unbind the lost row's one tile: the row reviews nothing now, so it leaves the table at once
		// rather than waiting for the refresh that prunes it from the inventory.
		val unbound = puppet.copy(atlas = puppet.atlas.copy(tiles = puppet.atlas.tiles.map { tile -> if (tile.id == AtlasTileId("tA9")) tile.copy(source = null) else tile }))
		val afterUnbind = buildSourcesTree(unbound, ::presence, "Unbound art")
		assertEquals(listOf("layer:art-0/lyid:1", "layer:art-0/lyid:2", "layer:art-0/lyid:5", "layer:art-0/name:Stray"), afterUnbind[0].children.map { node -> node.id })
		assertTrue(filterSourcesTree(afterUnbind, "", setOf(SourcesFilter.NeedsReview))[0].children.none { node -> node.id == "layer:art-0/lyid:9" })
	}

	/** A bound layer erased to nothing reads as its own review state and joins the review filter; an unbound one is simply unbound. */
	@Test
	fun anErasedBoundLayerReadsAsEmptiedAndJoinsTheReviewFilter() {
		val base = model()
		val puppet =
			base.copy(
				sources =
					base.sources.map { source ->
						if (source.id != artA) source else source.copy(layers = source.layers.map { layer -> layer.copy(empty = true) })
					},
			)
		val tree = buildSourcesTree(puppet, ::presence, "Unbound art")
		val fileA = tree[0]
		assertEquals(SourcesStatus.Emptied, fileA.children[0].status, "bound and erased")
		assertEquals(SourcesStatus.Unbound, fileA.children[1].status, "unbound and erased is just unbound")
		val review = filterSourcesTree(tree, "", setOf(SourcesFilter.NeedsReview))
		assertEquals(listOf("layer:art-0/lyid:1", "layer:art-0/name:Stray"), review[0].children.map { node -> node.id }, "the emptied row reviews beside the stray")
	}

	/**
	 * A lost row says how it was lost - the file no longer has the layer, or a replacement file lacks its
	 * key - and an unbound layer the rigger ignored reads as ignored, under the Unbound filter and not the
	 * review one; a bound layer reads bound whatever its mark says.
	 */
	@Test
	fun replacedAndIgnoredRowsReadTheirOwnStatus() {
		val base = model()
		val puppet =
			base.copy(
				atlas = base.atlas.copy(tiles = base.atlas.tiles + AtlasTile(AtlasTileId("tA9"), "Old brow", 4, 4, source = SourceLayerRef(artA, "lyid:9", true))),
				sources =
					base.sources.map { source ->
						if (source.id != artA) {
							source
						} else {
							source.copy(
								layers =
									listOf(
										source.layers[0].copy(ignored = true),
										source.layers[1].copy(ignored = true),
										ArtSourceLayer("lyid:9", "Brow (old)", "Head", 12, 22, 4, 4, true, present = false, replaced = true),
									),
							)
						}
					},
			)
		val tree = buildSourcesTree(puppet, ::presence, "Unbound art")
		val fileA = tree[0]
		assertEquals(listOf("layer:art-0/lyid:1", "layer:art-0/lyid:2", "layer:art-0/lyid:9", "layer:art-0/name:Stray"), fileA.children.map { node -> node.id })
		assertEquals(SourcesStatus.Bound, fileA.children[0].status, "a bound row reads bound whatever its mark says")
		assertEquals(SourcesStatus.Ignored, fileA.children[1].status)
		assertEquals(SourcesStatus.SourceReplaced, fileA.children[2].status)
		assertTrue(fileA.children[2].status.isReview)
		assertTrue(!fileA.children[1].status.isReview, "ignored is settled, not under review")
		assertEquals(listOf("layer:art-0/lyid:2"), filterSourcesTree(tree, "", setOf(SourcesFilter.Unbound))[0].children.map { node -> node.id }, "the ignored row shows under Unbound")
		assertEquals(listOf("layer:art-0/lyid:9", "layer:art-0/name:Stray"), filterSourcesTree(tree, "", setOf(SourcesFilter.NeedsReview))[0].children.map { node -> node.id }, "the replaced row reviews beside the stray")
	}

	/**
	 * A layer row hands out the binding its tiles carry: a key its reader marked weak stays weak on the row,
	 * though its shape reads stable, so the row's status and the ref a drag carries agree.
	 */
	@Test
	fun aLayerRowsRefTakesItsTilesWord() {
		val base = model()
		val puppet =
			base.copy(
				atlas = base.atlas.copy(tiles = base.atlas.tiles + AtlasTile(AtlasTileId("tHat"), "Hat", 4, 4, source = SourceLayerRef(artB, "hat.png", false))),
				sources =
					base.sources.map { source ->
						if (source.id == artB) source.copy(layers = source.layers + ArtSourceLayer("hat.png", "Hat", "", 0, 0, 4, 4, true)) else source
					},
			)

		val fileB = buildSourcesTree(puppet, ::presence, "Unbound art")[1]
		val hat = fileB.children.first { node -> node.id == "layer:art-1/hat.png" }
		val wing = fileB.children.first { node -> node.id == "layer:art-1/uuid-9" }

		assertEquals(SourcesStatus.BoundByName, hat.status)
		assertEquals(SourcesNodeKind.Layer(SourceLayerRef(artB, "hat.png", stableKey = false)), hat.kind, "the reader's word, not the shape's")
		assertEquals(SourcesNodeKind.Layer(SourceLayerRef(artB, "uuid-9", stableKey = true)), wing.kind, "a layer no tile binds goes by its key's shape")
	}

	@Test
	fun keyShapesSayWhatTheyAre() {
		assertTrue(layerKeyLooksStable("lyid:12"))
		assertTrue(layerKeyLooksStable("3f2a-uuid"))
		assertTrue(!layerKeyLooksStable("name:Eye"))
		assertTrue(!layerKeyLooksStable("Eye#4"))
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
		assertEquals(listOf(AtlasTileId("t1")), file.children[0].tileIds)
		assertTrue(file.children.drop(1).all { node -> node.tileIds.isEmpty() && node.drawableIds.isEmpty() }, "and stand for no art, so a click selects nothing and a hover previews nothing")

		fun ids(nodes: List<SourcesNode>): List<String> = nodes.flatMap { node -> listOf(node.id) + ids(node.children) }
		val allIds = ids(listOf(file))
		assertEquals(allIds.size, allIds.toSet().size, "no row id repeats anywhere in the tree")
	}

	/**
	 * A proposal naming a layer some tile binds stands when only a fresh, untouched drawable sits over
	 * that tile (a reload minted it for the re-created layer before the match could take it), and names
	 * the tile accepting it retires; the same layer under rig work is passed over.
	 */
	@Test
	fun aProposalNamingALayerUnderAFreshDrawableStandsAndSaysWhatItRetires() {
		val base = model()
		val freshQuad = DrawableMesh(floatArrayOf(12f, 22f, 16f, 22f, 16f, 26f, 12f, 26f), floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), intArrayOf(0, 1, 2, 0, 2, 3))
		val fresh = drawable("e", "tA5").copy(mesh = freshQuad)
		val puppet =
			base.copy(
				drawables = base.drawables + fresh,
				atlas =
					base.atlas.copy(
						tiles =
							base.atlas.tiles +
								AtlasTile(AtlasTileId("tA5"), "Brow", 4, 4, source = SourceLayerRef(artA, "lyid:5", true)) +
								AtlasTile(AtlasTileId("tA9"), "Old brow", 4, 4, source = SourceLayerRef(artA, "lyid:9", true)),
					),
				sources =
					base.sources.map { source ->
						if (source.id != artA) {
							source
						} else {
							source.copy(
								layers =
									source.layers +
										ArtSourceLayer("lyid:5", "Brow", "Head", 12, 22, 4, 4, true) +
										ArtSourceLayer("lyid:9", "Brow (old)", "Head", 12, 22, 4, 4, true, present = false),
							)
						}
					},
			)

		fun match(key: String, score: Float): LayerMatch = LayerMatch(key, score, MatchSignals(1f, 1f, 1f, 1f, null, hashEqual = false))
		val proposals: (ArtSourceId, String) -> List<LayerMatch> = { sourceId, key -> if (sourceId == artA && key == "lyid:9") listOf(match("lyid:5", 0.6f)) else emptyList() }
		val lost = buildSourcesTree(puppet, ::presence, "Unbound art", suggestionsFor = proposals)[0].children.first { node -> node.id == "layer:art-0/lyid:9" }
		assertEquals(LayerSuggestion("lyid:5", "Brow", 0.6f, retires = listOf(AtlasTileId("tA5"))), lost.suggestion)
		val rigged = puppet.copy(drawables = puppet.drawables.map { drawable -> if (drawable.id.raw == "e") drawable.copy(parentDeformerId = DeformerId("warp")) else drawable })
		val riggedLost = buildSourcesTree(rigged, ::presence, "Unbound art", suggestionsFor = proposals)[0].children.first { node -> node.id == "layer:art-0/lyid:9" }
		assertNull(riggedLost.suggestion, "under rig work the layer is passed over")
	}
}