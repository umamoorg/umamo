package org.umamo.interop.art

import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceGroup
import org.umamo.format.art.SourceLayer
import org.umamo.format.art.SourceLayerKind
import org.umamo.runtime.model.ArtSource
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.OrgInsertion
import org.umamo.runtime.model.OrgSlot
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.PartGroupMode
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.SourceLayerRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins what the bridge makes of a layered document: which layers become drawables and which are
 * skipped with a note, the birth quad's geometry in both frames, the clipping and blend mappings, the
 * folder-to-part tree with its order and its composites, the tiles and their source bindings, the
 * source inventory, and the seeded parameters.  Synthetic art throughout - the corpus twin is
 * SourceArtImportCorpusTest.
 */
class SourceArtImportTest {
	private class FixtureLayer(
		override val id: LayerId,
		override val idIsStable: Boolean,
		override val name: String,
		override val groupPath: String,
		override val order: Int,
		override val bounds: LayerBounds,
		override val raster: LayerRaster,
		override val kind: SourceLayerKind = SourceLayerKind.Raster,
		override val visible: Boolean = true,
		override val opacity: Float = 1f,
		override val clipped: Boolean = false,
		override val blend: LayerBlend = LayerBlend.Normal,
	) : SourceLayer

	private class FixtureGroup(
		override val path: String,
		override val name: String,
		override val visible: Boolean = true,
		override val opacity: Float = 1f,
		override val clipped: Boolean = false,
		override val blend: LayerBlend = LayerBlend.Normal,
		override val passThrough: Boolean = true,
	) : SourceGroup

	private class FixtureArt(
		override val widthPx: Int,
		override val heightPx: Int,
		override val layers: List<SourceLayer>,
		override val groups: List<SourceGroup> = emptyList(),
	) : SourceArt

	/** A raster opaque inside [opaque] (raster-local) and transparent elsewhere; fully transparent when null. */
	private fun rasterOf(width: Int, height: Int, opaque: LayerBounds? = LayerBounds(0, 0, width, height)): LayerRaster {
		val rgba = ByteArray(width * height * 4)
		if (opaque != null) {
			for (row in opaque.top until opaque.top + opaque.height) {
				for (column in opaque.left until opaque.left + opaque.width) {
					rgba[(row * width + column) * 4 + 3] = 0xFF.toByte()
				}
			}
		}
		return LayerRaster(width, height, rgba)
	}

	private fun layer(
		id: String,
		name: String,
		order: Int,
		left: Int,
		top: Int,
		raster: LayerRaster,
		groupPath: String = "",
		stable: Boolean = true,
		kind: SourceLayerKind = SourceLayerKind.Raster,
		visible: Boolean = true,
		opacity: Float = 1f,
		clipped: Boolean = false,
		blend: LayerBlend = LayerBlend.Normal,
	): FixtureLayer =
		FixtureLayer(
			id = LayerId(id),
			idIsStable = stable,
			name = name,
			groupPath = groupPath,
			order = order,
			bounds = LayerBounds(left, top, raster.width, raster.height),
			raster = raster,
			kind = kind,
			visible = visible,
			opacity = opacity,
			clipped = clipped,
			blend = blend,
		)

	private val eyeRaster = rasterOf(10, 8, opaque = LayerBounds(2, 1, 4, 3))

	/** The fixture, listed bottom-to-top the way the readers emit it; [SourceLayer.order] is what counts. */
	private fun fixture(): SourceArt =
		FixtureArt(
			widthPx = 200,
			heightPx = 100,
			layers =
				listOf(
					layer("lyid:7", "Lonely", order = 7, left = 5, top = 5, raster = rasterOf(3, 3), groupPath = "Solo", clipped = true),
					layer("lyid:6", "Hidden", order = 6, left = 20, top = 20, raster = rasterOf(5, 5), groupPath = "Body/Arm", visible = false, opacity = 0.5f),
					layer("lyid:5", "Odd", order = 5, left = 10, top = 0, raster = rasterOf(6, 6), blend = LayerBlend.Difference),
					layer("Glow#4", "Glow", order = 4, left = 0, top = 0, raster = rasterOf(6, 6), stable = false, blend = LayerBlend.AddGlow),
					layer("lyid:3", "Empty", order = 3, left = 0, top = 0, raster = rasterOf(4, 4, opaque = null), groupPath = "Head"),
					layer("lyid:2", "Face", order = 2, left = 90, top = 40, raster = rasterOf(20, 20), groupPath = "Head"),
					layer("lyid:1", "Eye", order = 1, left = 100, top = 50, raster = eyeRaster, groupPath = "Head", clipped = true),
					layer("lyid:0", "Title", order = 0, left = 0, top = 0, raster = rasterOf(1, 1, opaque = null), groupPath = "Head", kind = SourceLayerKind.Text),
				),
			groups =
				listOf(
					FixtureGroup("Head", "Head", visible = false),
					FixtureGroup("Body", "Body", opacity = 0.5f, passThrough = false),
					FixtureGroup("Body/Arm", "Arm"),
				),
		)

	private val descriptor = ArtSourceDescriptor(name = "fixture.psd", path = "/art/fixture.psd", format = "psd")

	@Test
	fun rasterLayersWithArtBecomeDrawablesTopMostFirst() {
		val result = SourceArtImport.fromSourceArt(fixture(), descriptor)
		val puppet = result.puppet

		assertEquals(listOf("Eye", "Face", "Glow", "Odd", "Hidden", "Lonely"), puppet.drawables.map { drawable -> drawable.name })
		assertEquals((1..6).map { index -> DrawableId("ArtMesh$index") }, puppet.drawables.map { drawable -> drawable.id }, "ids follow the panel order")
		assertEquals(
			setOf(
				SourceArtImportNotice.NonRasterLayer("Title", SourceLayerKind.Text),
				SourceArtImportNotice.EmptyLayer("Empty"),
				SourceArtImportNotice.BlendUnsupported("Odd", LayerBlend.Difference),
				SourceArtImportNotice.ClipBaseMissing("Lonely"),
			),
			result.notices.toSet(),
			"every skipped or altered layer is named, and nothing else is",
		)
	}

	/** The quad spans the opaque bounds plus the margin, in canvas pixels and in the art's own frame. */
	@Test
	fun theBirthQuadIsTheOpaqueBoundsPlusTheMarginInBothFrames() {
		val puppet = SourceArtImport.fromSourceArt(fixture(), descriptor).puppet
		val mesh = assertNotNull(puppet.drawables.first { drawable -> drawable.name == "Eye" }.mesh)

		// Opaque (2, 1) 4x3 in a 10x8 raster at canvas (100, 50), margin 2: the quad is (0, -1)..(8, 6)
		// raster-local, so canvas (100, 49)..(108, 56) and art-frame (0, -1/8)..(0.8, 0.75).
		assertTrue(mesh.positions.contentEquals(floatArrayOf(100f, 49f, 108f, 49f, 108f, 56f, 100f, 56f)), "positions: ${mesh.positions.toList()}")
		assertTrue(mesh.uvs.contentEquals(floatArrayOf(0f, -0.125f, 0.8f, -0.125f, 0.8f, 0.75f, 0f, 0.75f)), "uvs: ${mesh.uvs.toList()}")
		assertTrue(mesh.indices.contentEquals(intArrayOf(0, 1, 2, 0, 2, 3)), "two triangles over the four corners")
	}

	@Test
	fun aClippingLayerMasksByTheFirstUnclippedLayerBelowItInItsFolder() {
		val puppet = SourceArtImport.fromSourceArt(fixture(), descriptor).puppet
		val byName = puppet.drawables.associateBy { drawable -> drawable.name }

		assertEquals(listOf(byName.getValue("Face").id), byName.getValue("Eye").maskedBy, "Eye clips to Face, the layer below it in Head")
		assertTrue(byName.getValue("Lonely").maskedBy.isEmpty(), "a clipping layer with no base imports unclipped")
	}

	@Test
	fun blendVisibilityAndOpacityCarryOntoTheDrawable() {
		val puppet = SourceArtImport.fromSourceArt(fixture(), descriptor).puppet
		val byName = puppet.drawables.associateBy { drawable -> drawable.name }

		assertEquals(BlendMode.AdditiveGlow, byName.getValue("Glow").blendMode)
		assertEquals(BlendMode.Normal, byName.getValue("Odd").blendMode, "an unsupported blend falls back to Normal")
		assertTrue(!byName.getValue("Hidden").isVisible, "a hidden layer imports hidden, not dropped")
		assertEquals(0.5f, byName.getValue("Hidden").opacity)
		assertTrue(byName.getValue("Eye").isVisible)
	}

	/** Folders become parts nested by path, ordered where their top-most layer sits, composited when they must be. */
	@Test
	fun foldersBecomePartsInPanelOrderWithTheirComposites() {
		val puppet = SourceArtImport.fromSourceArt(fixture(), descriptor).puppet
		val byName = puppet.drawables.associateBy { drawable -> drawable.name }
		val partById = puppet.parts.associateBy { part -> part.id }

		assertEquals(listOf("Head", "Body", "Arm", "Solo"), puppet.parts.map { part -> part.name }, "parts are minted in pre-order")
		assertEquals((1..4).map { index -> PartId("Part$index") }, puppet.parts.map { part -> part.id })
		assertEquals(
			listOf(
				OrgChild.Part(PartId("Part1")),
				OrgChild.Drawable(byName.getValue("Glow").id),
				OrgChild.Drawable(byName.getValue("Odd").id),
				OrgChild.Part(PartId("Part2")),
				OrgChild.Part(PartId("Part4")),
			),
			puppet.rootChildren,
			"the root interleaves folders and layers in the file's own order",
		)

		val head = partById.getValue(PartId("Part1"))
		assertEquals(listOf(OrgChild.Drawable(byName.getValue("Eye").id), OrgChild.Drawable(byName.getValue("Face").id)), head.children)
		assertTrue(!head.isVisible, "a hidden folder imports as a hidden part")
		assertEquals(PartGroupMode.PassThrough, head.groupMode, "a plain folder is pass-through")

		val body = partById.getValue(PartId("Part2"))
		assertEquals(listOf(OrgChild.Part(PartId("Part3"))), body.children)
		assertEquals(PartGroupMode.Isolated, body.groupMode, "a translucent folder must composite as one layer")
		assertEquals(0.5f, body.composite.opacity)
		assertEquals(BlendMode.Normal, body.composite.blendMode)

		assertEquals(listOf(OrgChild.Drawable(byName.getValue("Hidden").id)), partById.getValue(PartId("Part3")).children)
		assertEquals("Solo", partById.getValue(PartId("Part4")).name, "a folder the reader did not describe is synthesized from the layer's path")
		assertTrue(puppet.renderRoot.children.isNotEmpty(), "the render root is derived from the tree")
	}

	@Test
	fun everyDrawableGetsAnUnplacedTileBoundToItsSourceLayer() {
		val result = SourceArtImport.fromSourceArt(fixture(), descriptor)
		val puppet = result.puppet
		val atlas = puppet.atlas

		assertTrue(atlas.pages.isEmpty(), "the bridge packs nothing")
		assertTrue(atlas.storedUvsAddressPages, "but the coordinates are declared page-frame, so the pack at open re-derives them through the identity")
		assertEquals(6, atlas.tiles.size)
		assertTrue(atlas.tiles.all { tile -> tile.placement == null }, "every tile is unplaced")

		val eye = puppet.drawables.first { drawable -> drawable.name == "Eye" }
		val eyeTile = assertNotNull(atlas.tileById[assertNotNull(eye.atlasTileId)])
		assertEquals(AtlasTileId("art-0/lyid:1"), eyeTile.id)
		assertEquals(SourceLayerRef(ArtSourceId("art-0"), "lyid:1", stableKey = true), eyeTile.source)
		assertEquals(10, eyeTile.width)
		assertEquals(8, eyeTile.height)
		assertSame(eyeRaster, result.rasterByTile[eyeTile.id], "the tile's pixels are the layer's own raster")

		val glowTile = assertNotNull(atlas.tileById[assertNotNull(puppet.drawables.first { drawable -> drawable.name == "Glow" }.atlasTileId)])
		assertEquals(false, assertNotNull(glowTile.source).stableKey, "a name-and-order key is recorded as unstable")
	}

	/** The inventory marks a raster layer with no alpha anywhere as empty; every other row is not. */
	@Test
	fun theInventoryMarksAnErasedLayerEmpty() {
		val rows = SourceArtImport.inventoryOf(fixture()).associateBy { row -> row.key }
		assertTrue(rows.getValue("lyid:3").empty, "the Empty layer has no alpha")
		assertTrue(!rows.getValue("lyid:2").empty, "Face has art")
		assertTrue(!rows.getValue("lyid:0").empty, "a text layer is not a raster erased to nothing")
	}

	@Test
	fun theSourceListRecordsTheFileAndEveryLayerSkippedOnesIncluded() {
		val puppet = SourceArtImport.fromSourceArt(fixture(), descriptor).puppet
		val source = puppet.sources.single()

		assertEquals(ArtSourceId("art-0"), source.id)
		assertEquals("fixture.psd", source.name)
		assertEquals("/art/fixture.psd", source.path)
		assertEquals("psd", source.format)
		assertEquals(listOf("Title", "Eye", "Face", "Empty", "Glow", "Odd", "Hidden", "Lonely"), source.layers.map { entry -> entry.name }, "top-most first, nothing left out")
		val eyeEntry = source.layers.first { entry -> entry.name == "Eye" }
		assertEquals("lyid:1", eyeEntry.key)
		assertEquals("Head", eyeEntry.groupPath)
		assertEquals(listOf(100, 50, 10, 8), listOf(eyeEntry.left, eyeEntry.top, eyeEntry.width, eyeEntry.height))
	}

	@Test
	fun theCanvasAndOriginAndDisplayModeMatchAFreshImport() {
		val puppet = SourceArtImport.fromSourceArt(fixture(), descriptor).puppet

		assertEquals(200f, puppet.canvasWidth)
		assertEquals(100f, puppet.canvasHeight)
		assertEquals(100f, puppet.worldOriginX)
		assertEquals(-50f, puppet.worldOriginY, "the origin is the canvas center, y negated into world space")
		assertTrue(puppet.rendersFromSourceLayers, "a fresh import shows the layers as drawn")
		assertNull(puppet.pixelsPerUnit)
		assertTrue(puppet.deformers.isEmpty())
	}

	@Test
	fun theSeedParametersLandVerbatimOrNothingIsSeeded() {
		val seed =
			listOf(
				Parameter(id = ParameterId("ParamAngleX"), name = "Angle X", min = -30f, max = 30f, default = 0f),
				Parameter(id = ParameterId("ParamEyeLOpen"), name = "Eye L Open", min = 0f, max = 1f, default = 1f),
			)
		val seeded = SourceArtImport.fromSourceArt(fixture(), descriptor, SourceArtImportOptions(parameters = seed)).puppet
		val bare = SourceArtImport.fromSourceArt(fixture(), descriptor, SourceArtImportOptions()).puppet

		assertEquals(seed, seeded.parameters, "the bridge seeds exactly what it is handed, in order")
		assertEquals(
			seed.map { parameter -> ParameterNode.Param(parameter.id) },
			seeded.parameterTree,
			"the tree is materialized flat, so an export places every parameter in the group hierarchy",
		)
		assertTrue(bare.parameters.isEmpty(), "the bridge's own default seeds nothing; the template is the caller's policy")
		assertTrue(bare.parameterTree.isEmpty())
	}

	/**
	 * Added to an open document, a file's ids continue the document's own sequences and its root
	 * order lands after the existing children - so the additions can be appended without a rename.
	 * The same additions over a blank model are exactly what a fresh import assembles.
	 */
	@Test
	fun additionsContinueTheExistingIdSequences() {
		val fresh = SourceArtImport.fromSourceArt(fixture(), descriptor).puppet
		val existing =
			fresh.copy(
				drawables = fresh.drawables.take(1).map { drawable -> drawable.copy(id = DrawableId("ArtMesh3")) },
				parts = fresh.parts.take(1).map { part -> part.copy(id = PartId("Part2"), children = emptyList()) },
				rootChildren = listOf(OrgChild.Part(PartId("Part2")), OrgChild.Drawable(DrawableId("ArtMesh3"))),
				atlas = fresh.atlas.copy(tiles = fresh.atlas.tiles.take(1)),
			)

		val added = SourceArtImport.additionsFor(fixture(), ArtSourceDescriptor("second.psd", null, "psd"), SourceArtImportOptions(), existing).additions
		assertEquals(ArtSourceId("art-1"), added.source.id, "the source continues past art-0")
		assertEquals((4..9).map { index -> DrawableId("ArtMesh$index") }, added.drawables.map { drawable -> drawable.id }, "drawables continue past ArtMesh3")
		assertEquals((3..6).map { index -> PartId("Part$index") }, added.parts.map { part -> part.id }, "parts continue past Part2")
		assertTrue(added.tiles.all { tile -> tile.id.raw.startsWith("art-1/") }, "tiles are keyed under the new source")
		assertTrue(added.tiles.all { tile -> tile.source?.sourceId == ArtSourceId("art-1") })
		assertEquals(5, added.rootChildren.size, "the file's own top-level order, to append after the existing children")

		val overBlank = SourceArtImport.additionsFor(fixture(), descriptor, SourceArtImportOptions(), fresh.copy(drawables = emptyList(), parts = emptyList(), rootChildren = emptyList(), atlas = PuppetAtlas.Empty, sources = emptyList())).additions
		assertEquals(fresh.drawables.map { drawable -> drawable.id }, overBlank.drawables.map { drawable -> drawable.id }, "over a blank model the additions are the fresh import's")
	}

	/**
	 * A reload's additions (under a listed source) mint only the folders the added layers live in; a
	 * folder the document already keeps as a part - found through the source's bindings, since a part
	 * carries no folder of its own - takes the layers as children instead of a second part of the same
	 * name; a new sub-folder inside such a part is minted as a new part under it; and every new child is
	 * placed among the existing children where the file puts it: after its nearest shown sibling above,
	 * else before its nearest below, so a layer added at the top of the file lands first.
	 */
	@Test
	fun additionsUnderAListedSourceReuseTheDocumentsPartsAndPlaceByTheFilesOrder() {
		val existing = SourceArtImport.fromSourceArt(fixture(), descriptor).puppet
		val existingByName = existing.drawables.associateBy { drawable -> drawable.name }
		val headPart = existing.parts.first { part -> part.name == "Head" }
		val bodyPart = existing.parts.first { part -> part.name == "Body" }
		val armPart = existing.parts.first { part -> part.name == "Arm" }
		val cap = layer("lyid:11", "Cap", order = -1, left = 0, top = 0, raster = rasterOf(2, 2))
		val brow = layer("lyid:8", "Brow", order = 8, left = 90, top = 30, raster = rasterOf(4, 4), groupPath = "Head")
		val lash = layer("lyid:9", "Lash", order = 9, left = 95, top = 45, raster = rasterOf(3, 3), groupPath = "Head/Lashes")
		val tail = layer("lyid:10", "Tail", order = 10, left = 0, top = 80, raster = rasterOf(6, 6), groupPath = "Body/Tail")
		val wholeFile =
			FixtureArt(
				widthPx = 200,
				heightPx = 100,
				layers = fixture().layers + cap + brow + lash + tail,
				groups = fixture().groups + FixtureGroup("Head/Lashes", "Lashes") + FixtureGroup("Body/Tail", "Tail"),
			)
		val gained = setOf("lyid:11", "lyid:8", "lyid:9", "lyid:10")

		val added = SourceArtImport.additionsFor(wholeFile, descriptor, SourceArtImportOptions(), existing, underSource = ArtSourceId("art-0"), layerKeys = gained).additions
		val byName = added.drawables.associateBy { drawable -> drawable.name }
		assertEquals(listOf("Cap", "Brow", "Lash", "Tail"), added.drawables.map { drawable -> drawable.name }, "only the named layers are minted, top-most first")
		assertEquals(listOf("Lashes", "Tail"), added.parts.map { part -> part.name }, "only the folders the document has no part for are minted; Head, Body, Arm, and Solo are not")
		assertEquals(listOf(PartId("Part5"), PartId("Part6")), added.parts.map { part -> part.id }, "past the document's four parts")
		assertTrue(added.rootChildren.isEmpty(), "a listed file appends nothing after the root; its new children are placed")
		val lashes = added.parts.first { part -> part.name == "Lashes" }
		val tailPart = added.parts.first { part -> part.name == "Tail" }
		assertEquals(listOf(OrgChild.Drawable(byName.getValue("Lash").id)), lashes.children)
		assertEquals(
			listOf(
				OrgInsertion(null, OrgChild.Drawable(byName.getValue("Cap").id), OrgSlot.Before(OrgChild.Part(headPart.id))),
				OrgInsertion(headPart.id, OrgChild.Drawable(byName.getValue("Brow").id), OrgSlot.After(OrgChild.Drawable(existingByName.getValue("Face").id))),
				OrgInsertion(headPart.id, OrgChild.Part(lashes.id), OrgSlot.After(OrgChild.Drawable(byName.getValue("Brow").id))),
				OrgInsertion(bodyPart.id, OrgChild.Part(tailPart.id), OrgSlot.After(OrgChild.Part(armPart.id))),
			),
			added.insertions,
			"the top layer goes before the root's first folder; Brow after Face (the empty layer above it shows nothing); the new Lashes folder after Brow; the new Tail folder after Arm",
		)

		val fresh = SourceArtImport.additionsFor(wholeFile, ArtSourceDescriptor("other.psd", null, "psd"), SourceArtImportOptions(), existing).additions
		assertEquals(listOf("Head", "Lashes", "Body", "Arm", "Tail", "Solo"), fresh.parts.map { part -> part.name }, "a NEW file still mints every folder it has, in pre-order")
		assertTrue(fresh.insertions.isEmpty(), "and appends its whole tree after the root")
		assertEquals(OrgChild.Drawable(fresh.drawables.first { drawable -> drawable.name == "Cap" }.id), fresh.rootChildren.first())
	}

	/** Two layers sharing a weak key still get distinct tiles, disambiguated by draw order. */
	@Test
	fun aDuplicateLayerKeyIsDisambiguatedByOrder() {
		val art =
			FixtureArt(
				widthPx = 10,
				heightPx = 10,
				layers =
					listOf(
						layer("Same#0", "Same", order = 0, left = 0, top = 0, raster = rasterOf(2, 2), stable = false),
						layer("Same#0", "Same", order = 1, left = 4, top = 4, raster = rasterOf(2, 2), stable = false),
					),
			)
		val puppet = SourceArtImport.fromSourceArt(art, descriptor).puppet

		assertEquals(listOf(AtlasTileId("art-0/Same#0"), AtlasTileId("art-0/Same#0#1")), puppet.atlas.tiles.map { tile -> tile.id })
		assertEquals(2, puppet.drawables.map { drawable -> drawable.atlasTileId }.toSet().size, "each drawable samples its own tile")
	}

	/**
	 * Placing a file shifts every layer's bounds and nothing else: the rasters are the same instances
	 * (the decode memo and the texture cache key on them), the file's canvas and folders pass through,
	 * and a zero offset hands the art back untouched.
	 */
	@Test
	fun placingAFileShiftsItsBoundsAndKeepsItsRasters() {
		val art = fixture()
		assertSame(art, art.placedBy(CanvasOffset.Zero), "a zero offset places nothing")

		val placed = art.placedBy(CanvasOffset(30, -10))
		assertEquals(art.widthPx to art.heightPx, placed.widthPx to placed.heightPx, "the file's own canvas passes through")
		assertSame(art.groups, placed.groups)
		for ((original, moved) in art.layers.zip(placed.layers)) {
			assertSame(original.raster, moved.raster, "'${original.name}' keeps its raster instance")
			assertEquals(LayerBounds(original.bounds.left + 30, original.bounds.top - 10, original.bounds.width, original.bounds.height), moved.bounds)
			assertEquals(original.id, moved.id)
			assertEquals(original.groupPath, moved.groupPath)
			assertEquals(original.clipped, moved.clipped)
		}
		val source = ArtSource(ArtSourceId("art-3"), "x.psd", null, "psd", offsetX = 4, offsetY = 6)
		assertEquals(LayerBounds(104, 56, 10, 8), art.placedFor(source).layers.first { layer -> layer.name == "Eye" }.bounds, "a record's offset places the same way")
	}

	/**
	 * The anchor splits the room between the document's canvas and the file's by its fraction, rounded
	 * down, and the nudge is added on top; a file larger than the canvas overhangs it (a negative offset);
	 * a rig's first artwork and a document with no canvas are never placed.
	 */
	@Test
	fun theOffsetFollowsTheAnchorAndTheNudge() {
		val host = SourceArtImport.fromSourceArt(fixture(), descriptor).puppet.copy(canvasWidth = 300f, canvasHeight = 250f)
		val small = FixtureArt(widthPx = 101, heightPx = 50, layers = listOf(layer("lyid:0", "Dot", order = 0, left = 0, top = 0, raster = rasterOf(2, 2))))
		val large = FixtureArt(widthPx = 400, heightPx = 250, layers = small.layers)

		fun offsetOf(art: SourceArt, anchor: ArtworkAnchor, nudgeX: Int = 0, nudgeY: Int = 0): CanvasOffset =
			SourceArtImport.offsetFor(host, art, SourceArtImportOptions(anchor = anchor, nudgeX = nudgeX, nudgeY = nudgeY))

		assertEquals(CanvasOffset(99, 100), offsetOf(small, ArtworkAnchor.Center), "199 x 200 of room, halved and rounded down")
		assertEquals(CanvasOffset(0, 0), offsetOf(small, ArtworkAnchor.TopLeft))
		assertEquals(CanvasOffset(199, 200), offsetOf(small, ArtworkAnchor.BottomRight))
		assertEquals(CanvasOffset(99, 0), offsetOf(small, ArtworkAnchor.Top))
		assertEquals(CanvasOffset(0, 100), offsetOf(small, ArtworkAnchor.Left))
		assertEquals(CanvasOffset(199, 100), offsetOf(small, ArtworkAnchor.Right))
		assertEquals(CanvasOffset(102, 96), offsetOf(small, ArtworkAnchor.Center, nudgeX = 3, nudgeY = -4), "the nudge is added to the anchor's placement")
		assertEquals(CanvasOffset(-50, 0), offsetOf(large, ArtworkAnchor.Center), "a wider file overhangs both sides by the same amount")
		assertEquals(CanvasOffset(-100, 0), offsetOf(large, ArtworkAnchor.BottomRight))

		val blank = host.copy(drawables = emptyList(), atlas = PuppetAtlas.Empty)
		assertEquals(CanvasOffset.Zero, SourceArtImport.offsetFor(blank, small, SourceArtImportOptions(nudgeX = 7)), "a first artwork sets the canvas and is never placed")
		assertTrue(SourceArtImport.isFirstArtwork(blank))
		assertTrue(!SourceArtImport.isFirstArtwork(host))
		assertEquals(CanvasOffset.Zero, SourceArtImport.offsetFor(host.copy(canvasWidth = 0f), small, SourceArtImportOptions()), "no canvas, nothing to place within")
	}

	/**
	 * A placed file's birth quads sit at the placed positions, its inventory rows are recorded in the
	 * document frame, and its record keeps the offset; a listed file's additions keep the offset its
	 * record already carries, whatever the caller passes.
	 */
	@Test
	fun additionsRecordTheOffsetTheFileWasPlacedBy() {
		val existing = SourceArtImport.fromSourceArt(fixture(), descriptor).puppet
		val offset = CanvasOffset(50, 20)
		val added = SourceArtImport.additionsFor(fixture().placedBy(offset), ArtSourceDescriptor("second.psd", null, "psd"), SourceArtImportOptions(), existing, offset = offset).additions

		assertEquals(50 to 20, added.source.offsetX to added.source.offsetY, "the new record keeps the offset")
		val eye = assertNotNull(added.drawables.first { drawable -> drawable.name == "Eye" }.mesh)
		assertTrue(eye.positions.contentEquals(floatArrayOf(150f, 69f, 158f, 69f, 158f, 76f, 150f, 76f)), "the quad is the fixture's, shifted: ${eye.positions.toList()}")
		assertEquals(150 to 70, added.source.layers.first { row -> row.name == "Eye" }.let { row -> row.left to row.top }, "the inventory row is in the document frame")

		val listed = existing.copy(sources = existing.sources.map { source -> source.copy(offsetX = 8, offsetY = 9) })
		val underListed = SourceArtImport.additionsFor(fixture(), descriptor, SourceArtImportOptions(), listed, underSource = ArtSourceId("art-0"), layerKeys = setOf("lyid:1"), offset = offset).additions
		assertEquals(8 to 9, underListed.source.offsetX to underListed.source.offsetY, "a listed file's record keeps its own offset")
	}
}