package org.umamo.interop.art

import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceGroup
import org.umamo.format.art.SourceLayer
import org.umamo.format.art.SourceLayerKind
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.PartGroupMode
import org.umamo.runtime.model.PartId
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
 * source inventory, and the template.  Synthetic art throughout - the corpus twin is
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
	fun theTemplateSeedsTheParametersOrNothing() {
		val humanoid = SourceArtImport.fromSourceArt(fixture(), descriptor, SourceArtImportOptions(parameterTemplate = ParameterTemplate.Humanoid)).puppet
		val none = SourceArtImport.fromSourceArt(fixture(), descriptor, SourceArtImportOptions(parameterTemplate = ParameterTemplate.None)).puppet

		assertEquals(HumanoidParameters.list, humanoid.parameters)
		assertEquals(
			HumanoidParameters.list.map { parameter -> ParameterNode.Param(parameter.id) },
			humanoid.parameterTree,
			"the tree is materialized flat, so an export places every parameter in the group hierarchy",
		)
		assertTrue(none.parameterTree.isEmpty())
		assertEquals("ParamAngleX", humanoid.parameters.first().id.raw, "the standard ids are verbatim")
		assertEquals(HumanoidParameters.list.size, HumanoidParameters.list.map { parameter -> parameter.id }.toSet().size, "no id repeats")
		assertTrue(none.parameters.isEmpty())
		assertEquals(ParameterTemplate.Humanoid, ParameterTemplate.fromKey("humanoid"))
		assertEquals(ParameterTemplate.Default, ParameterTemplate.fromKey("not-a-template"), "a stale setting falls back to the default")
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
}