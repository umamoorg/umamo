package org.umamo.edit

import org.umamo.runtime.model.ArtSource
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.ArtSourceLayer
import org.umamo.runtime.model.ArtworkAdditions
import org.umamo.runtime.model.ArtworkReload
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.ReplacedTile
import org.umamo.runtime.model.SourceLayerRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The two artwork edits: rebinding a tile to a source layer, and appending an artwork file's
 * additions.  Both refuse rather than half-apply - a binding to an unlisted file, or an addition
 * whose ids collide - so the Sources space and the add flow can trust a returned model.
 */
class ArtworkEditsTest {
	private val sourceA = ArtSource(ArtSourceId("art-0"), "a.psd", "/a.psd", "psd", listOf(ArtSourceLayer("lyid:1", "L1", "", 0, 0, 4, 4, true)))
	private val refA1 = SourceLayerRef(ArtSourceId("art-0"), "lyid:1", stableKey = true)

	private fun quad(): DrawableMesh = DrawableMesh(floatArrayOf(0f, 0f, 4f, 0f, 4f, 4f, 0f, 4f), floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), intArrayOf(0, 1, 2, 0, 2, 3))

	private fun drawable(id: String, tileId: String): Drawable =
		Drawable(DrawableId(id), id, null, BlendMode.Normal, emptyList(), quad(), null, atlasTileId = AtlasTileId(tileId))

	private fun model(): PuppetModel {
		val drawable = drawable("ArtMesh1", "art-0/lyid:1")
		return PuppetModel(
			parameters = emptyList(),
			parts = listOf(Part(PartId("Part1"), "Folder", listOf(OrgChild.Drawable(drawable.id)))),
			deformers = emptyList(),
			drawables = listOf(drawable),
			rootChildren = listOf(OrgChild.Part(PartId("Part1"))),
			rootPartId = null,
			atlas = PuppetAtlas(tiles = listOf(AtlasTile(AtlasTileId("art-0/lyid:1"), "L1", 4, 4, source = refA1))),
			sources = listOf(sourceA),
		)
	}

	@Test
	fun aTileRebindsToAListedSourceAndUnbinds() {
		val base = model()
		val other = SourceLayerRef(ArtSourceId("art-0"), "lyid:2", stableKey = true)

		val rebound = base.withTileSource(AtlasTileId("art-0/lyid:1"), other)
		assertEquals(other, rebound.atlas.tiles.single().source)
		assertSame(base.drawables, rebound.drawables, "a rebind moves nothing else")

		val unbound = rebound.withTileSource(AtlasTileId("art-0/lyid:1"), null)
		assertNull(unbound.atlas.tiles.single().source)
	}

	@Test
	fun aRebindToAnUnlistedSourceOrAnUnknownTileIsRefused() {
		val base = model()
		assertSame(base, base.withTileSource(AtlasTileId("art-0/lyid:1"), SourceLayerRef(ArtSourceId("art-9"), "lyid:1", true)), "an unlisted file")
		assertSame(base, base.withTileSource(AtlasTileId("nope"), refA1), "an unknown tile")
		assertSame(base, base.withTileSource(AtlasTileId("art-0/lyid:1"), refA1), "the binding it already has")
	}

	@Test
	fun artworkAdditionsAppendAfterTheExistingRigWithADerivedRenderRoot() {
		val base = model()
		val added = drawable("ArtMesh2", "art-1/uuid-7")
		val additions =
			ArtworkAdditions(
				source = ArtSource(ArtSourceId("art-1"), "b.clip", null, "clip", listOf(ArtSourceLayer("uuid-7", "Wing", "", 2, 2, 4, 4, true))),
				tiles = listOf(AtlasTile(AtlasTileId("art-1/uuid-7"), "Wing", 4, 4, source = SourceLayerRef(ArtSourceId("art-1"), "uuid-7", true))),
				drawables = listOf(added),
				parts = listOf(Part(PartId("Part2"), "Wings", listOf(OrgChild.Drawable(added.id)))),
				rootChildren = listOf(OrgChild.Part(PartId("Part2"))),
			)

		val grown = base.withArtworkAdded(additions)
		assertEquals(listOf("art-0", "art-1"), grown.sources.map { source -> source.id.raw })
		assertEquals(listOf("ArtMesh1", "ArtMesh2"), grown.drawables.map { drawable -> drawable.id.raw })
		assertEquals(listOf("Part1", "Part2"), grown.parts.map { part -> part.id.raw })
		assertEquals(listOf(OrgChild.Part(PartId("Part1")), OrgChild.Part(PartId("Part2"))), grown.rootChildren, "the file's order follows the existing root children")
		assertEquals(2, grown.atlas.tiles.size)
		assertTrue(grown.renderRoot.children.isNotEmpty(), "the render root is re-derived over the grown tree")
		assertEquals(base.atlas.pages, grown.atlas.pages, "the pages are untouched - the pack is a separate step")
	}

	@Test
	fun artworkAdditionsThatCollideAreRefused() {
		val base = model()
		val collidingDrawable =
			ArtworkAdditions(
				source = ArtSource(ArtSourceId("art-1"), "b.clip", null, "clip"),
				tiles = emptyList(),
				drawables = listOf(drawable("ArtMesh1", "art-1/x")),
				parts = emptyList(),
				rootChildren = emptyList(),
			)
		assertSame(base, base.withArtworkAdded(collidingDrawable), "a drawable id the model already has")
		val collidingSource =
			ArtworkAdditions(source = sourceA, tiles = emptyList(), drawables = emptyList(), parts = emptyList(), rootChildren = emptyList())
		assertSame(base, base.withArtworkAdded(collidingSource), "a source id the model already lists")
	}

	@Test
	fun aReloadSwapsTheTileCarriesTheDrawablesAndAppendsTheAdditions() {
		val base = model()
		val newMesh = DrawableMesh(floatArrayOf(0f, 0f, 6f, 0f, 6f, 6f, 0f, 6f), floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), intArrayOf(0, 1, 2, 0, 2, 3))
		val replacement = AtlasTile(AtlasTileId("art-0/lyid:1~1"), "L1", 6, 6, source = refA1, pinned = true, replaces = AtlasTileId("art-0/lyid:1"))
		val added = drawable("ArtMesh2", "art-0/lyid:2")
		val refreshed = sourceA.copy(layers = listOf(ArtSourceLayer("lyid:1", "L1", "", 0, 0, 6, 6, true), ArtSourceLayer("lyid:2", "L2", "", 9, 9, 4, 4, true)))
		val reload =
			ArtworkReload(
				source = refreshed,
				replacedTiles = listOf(ReplacedTile(AtlasTileId("art-0/lyid:1"), replacement)),
				drawableMeshes = mapOf(DrawableId("ArtMesh1") to newMesh),
				additions =
					ArtworkAdditions(
						source = refreshed,
						tiles = listOf(AtlasTile(AtlasTileId("art-0/lyid:2"), "L2", 4, 4, source = SourceLayerRef(ArtSourceId("art-0"), "lyid:2", true))),
						drawables = listOf(added),
						parts = emptyList(),
						rootChildren = listOf(OrgChild.Drawable(added.id)),
					),
				outgrown = emptyList(),
			)

		val reloaded = base.withArtworkReloaded(reload)
		assertEquals(listOf("art-0/lyid:1~1", "art-0/lyid:2"), reloaded.atlas.tiles.map { tile -> tile.id.raw }, "the old tile is gone, the replacement and the addition appended")
		assertSame(replacement, reloaded.atlas.tiles.first())
		val carried = reloaded.drawables.first { drawable -> drawable.id.raw == "ArtMesh1" }
		assertEquals(AtlasTileId("art-0/lyid:1~1"), carried.atlasTileId, "the drawable moved onto the replacement")
		assertSame(newMesh, carried.mesh, "with the mesh the plan decided")
		assertEquals(listOf("ArtMesh1", "ArtMesh2"), reloaded.drawables.map { drawable -> drawable.id.raw })
		assertEquals(listOf(OrgChild.Part(PartId("Part1")), OrgChild.Drawable(added.id)), reloaded.rootChildren)
		assertEquals(refreshed, reloaded.sources.single(), "the file's record takes the new inventory")
		assertTrue(reloaded.renderRoot != null, "the render root is re-derived")
	}

	@Test
	fun aReloadThatCollidesOrNamesTheUnknownIsRefused() {
		val base = model()
		val unknownOld =
			ArtworkReload(sourceA, listOf(ReplacedTile(AtlasTileId("nope"), AtlasTile(AtlasTileId("nope~1"), "x", 1, 1))), emptyMap(), null, emptyList())
		assertSame(base, base.withArtworkReloaded(unknownOld), "an unknown superseded tile")
		val collidingNew =
			ArtworkReload(sourceA, listOf(ReplacedTile(AtlasTileId("art-0/lyid:1"), AtlasTile(AtlasTileId("art-0/lyid:1"), "x", 1, 1))), emptyMap(), null, emptyList())
		assertSame(base, base.withArtworkReloaded(collidingNew), "a replacement reusing an existing id")
		val unlisted = ArtworkReload(ArtSource(ArtSourceId("art-9"), "z", null, "psd"), emptyList(), emptyMap(), null, emptyList())
		assertSame(base, base.withArtworkReloaded(unlisted), "a file the model does not list")
	}
}