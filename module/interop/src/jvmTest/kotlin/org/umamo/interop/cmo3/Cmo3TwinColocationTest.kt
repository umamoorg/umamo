package org.umamo.interop.cmo3

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelImage
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CModelImageGroup
import org.umamo.format.cmo3.model.gen.CTextureAtlas
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RuntimeTarget
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the twin co-location prepass: baked twins over one atlas slot reach the image chain with ONE canvas
 * mesh - the shape every official file has - so a MOC3-origin conversion keeps its own pages and shares one
 * material per slot rather than copying the patch onto a page of its own.
 */
class Cmo3TwinColocationTest {
	private val pageSize = 32
	private val uvs = floatArrayOf(4f / 32f, 4f / 32f, 28f / 32f, 4f / 32f, 4f / 32f, 28f / 32f, 28f / 32f, 28f / 32f)
	private val indices = intArrayOf(0, 1, 2, 1, 2, 3)
	private val straightPositions = FloatArray(uvs.size) { index -> uvs[index] * pageSize }

	/** [straightPositions] mirrored about x = 50, the way a reflecting deformer shows a twin. */
	private val mirroredPositions =
		FloatArray(uvs.size) { index ->
			if (index % 2 == 0) {
				100f - uvs[index] * pageSize
			} else {
				uvs[index] * pageSize
			}
		}

	/**
	 * A 32 x 32 page with one opaque block inside the slot the quad samples.
	 *
	 * @return Cmo3Conversion.AtlasPage The page.
	 */
	private fun page(): Cmo3Conversion.AtlasPage {
		val rgba = ByteArray(pageSize * pageSize * 4)

		for (rowIndex in 12 until 19) {
			for (columnIndex in 10 until 15) {
				rgba.fill(0xFF.toByte(), (rowIndex * pageSize + columnIndex) * 4, (rowIndex * pageSize + columnIndex) * 4 + 4)
			}
		}

		return Cmo3Conversion.AtlasPage(PngCodec.write(RasterImage(pageSize, pageSize, rgba)), pageSize, pageSize)
	}

	/**
	 * A drawable over the shared slot whose canvas mesh and keyform base are separate arrays, as a deformer
	 * child's are.
	 *
	 * @param String     id        The drawable id.
	 * @param FloatArray positions The canvas mesh; the keyform base is a copy of it.
	 * @return Drawable The drawable.
	 */
	private fun drawable(id: String, positions: FloatArray): Drawable =
		rootDrawable(id, positions).copy(mesh = DrawableMesh(positions = positions, localPositions = positions.copyOf(), uvs = uvs.copyOf(), indices = indices.copyOf()))

	/**
	 * A drawable over the shared slot with no deformer: one array is both its canvas mesh and its keyform base.
	 *
	 * @param String     id        The drawable id.
	 * @param FloatArray positions The canvas mesh and keyform base.
	 * @return Drawable The drawable.
	 */
	private fun rootDrawable(id: String, positions: FloatArray): Drawable =
		Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh.withLocalEqualToCanvas(positions = positions, uvs = uvs.copyOf(), indices = indices.copyOf()),
			geometryGrid = null,
		)

	/**
	 * A puppet holding [drawables] at the root.
	 *
	 * @param List drawables The drawables, in drawable order.
	 * @return PuppetModel The puppet.
	 */
	private fun puppet(drawables: List<Drawable>): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = drawables,
			rootChildren = drawables.map { drawable -> OrgChild.Drawable(drawable.id) },
			rootPartId = null,
			canvasWidth = 200f,
			canvasHeight = 200f,
			worldOriginX = 100f,
			worldOriginZ = -100f,
			runtimeTarget = RuntimeTarget.Cubism53,
		)

	/**
	 * The drawable [id] of [model].
	 *
	 * @param PuppetModel model The model.
	 * @param String      id    The drawable id.
	 * @return Drawable The drawable.
	 */
	private fun drawableOf(model: PuppetModel, id: String): Drawable = model.drawables.single { drawable -> drawable.id.raw == id }

	@Test
	fun aMirroredTwinTakesItsPartnersCanvasMesh() {
		val twinA = drawable("TwinA", straightPositions)
		val twinB = drawable("TwinB", mirroredPositions)
		val result = Cmo3TwinColocation.colocate(puppet(listOf(twinA, twinB)), listOf(page()), mapOf("TwinA" to 0, "TwinB" to 0))

		assertEquals(listOf("TwinB"), result.colocatedDrawableIds)
		assertSame(twinA, drawableOf(result.puppet, "TwinA"), "the representative passes through untouched")
		val colocated = drawableOf(result.puppet, "TwinB").mesh!!
		assertContentEquals(straightPositions, colocated.positions, "the twin's canvas mesh is its partner's")
		assertSame(twinB.mesh!!.localPositions, colocated.localPositions, "the keyform base is the twin's own")
		assertSame(twinB.mesh!!.uvs, colocated.uvs, "the uvs stay on the shared slot")
		assertSame(twinB.mesh!!.indices, colocated.indices)
	}

	@Test
	fun theUprightPlacementIsTheRepresentativeEvenWhenTheMirrorComesFirst() {
		val result =
			Cmo3TwinColocation.colocate(
				puppet(listOf(drawable("Mirror", mirroredPositions), drawable("Upright", straightPositions))),
				listOf(page()),
				mapOf("Mirror" to 0, "Upright" to 0),
			)

		assertEquals(listOf("Mirror"), result.colocatedDrawableIds, "the shared material holds the art upright")
		assertContentEquals(straightPositions, drawableOf(result.puppet, "Mirror").mesh!!.positions)
	}

	@Test
	fun aShiftedTwinFollowsTheFirstPlacement() {
		val shiftedPositions = FloatArray(uvs.size) { index -> straightPositions[index] + if (index % 2 == 0) 50f else 0f }
		val result =
			Cmo3TwinColocation.colocate(
				puppet(listOf(drawable("First", straightPositions), drawable("Shifted", shiftedPositions))),
				listOf(page()),
				mapOf("First" to 0, "Shifted" to 0),
			)

		assertEquals(listOf("Shifted"), result.colocatedDrawableIds, "with both upright, drawable order decides")
		assertContentEquals(straightPositions, drawableOf(result.puppet, "Shifted").mesh!!.positions)
	}

	@Test
	fun aRootTwinKeepsItsOwnKeyformBase() {
		val twinB = rootDrawable("TwinB", mirroredPositions)
		val result =
			Cmo3TwinColocation.colocate(
				puppet(listOf(rootDrawable("TwinA", straightPositions), twinB)),
				listOf(page()),
				mapOf("TwinA" to 0, "TwinB" to 0),
			)
		val colocated = drawableOf(result.puppet, "TwinB").mesh!!

		assertContentEquals(straightPositions, colocated.positions)
		assertSame(mirroredPositions, colocated.localPositions, "the shared array stays the keyform base, so the twin still shows mirrored")
	}

	@Test
	fun coLocatedDuplicatesComeBackUntouched() {
		val model = puppet(listOf(drawable("VariantA", straightPositions), drawable("VariantB", straightPositions.copyOf())))
		val result = Cmo3TwinColocation.colocate(model, listOf(page()), mapOf("VariantA" to 0, "VariantB" to 0))

		assertTrue(result.colocatedDrawableIds.isEmpty())
		assertSame(model, result.puppet, "the puppet passes through untouched")
	}

	@Test
	fun identicalUvsOnDifferentPagesAreSeparateSlots() {
		val model = puppet(listOf(drawable("OnPageZero", straightPositions), drawable("OnPageOne", mirroredPositions)))
		val result = Cmo3TwinColocation.colocate(model, listOf(page(), page()), mapOf("OnPageZero" to 0, "OnPageOne" to 1))

		assertTrue(result.colocatedDrawableIds.isEmpty(), "different pages sample different pixels")
		assertSame(model, result.puppet)
	}

	@Test
	fun drawablesWithoutAPageOrAMeshAreLeftAlone() {
		val meshless = drawable("Meshless", mirroredPositions).copy(mesh = null)
		val model = puppet(listOf(drawable("Paged", straightPositions), drawable("Unpaged", mirroredPositions), meshless))
		val result = Cmo3TwinColocation.colocate(model, listOf(page()), mapOf("Paged" to 0, "Meshless" to 0))

		assertTrue(result.colocatedDrawableIds.isEmpty())
		assertSame(model, result.puppet)
	}

	@Test
	fun aConvertedMirrorPairSharesOnePageAndOneMaterial() {
		val model = puppet(listOf(rootDrawable("TwinA", straightPositions), rootDrawable("TwinB", mirroredPositions)))
		val result =
			Cmo3Conversion.freshCmo3(
				puppet = model,
				pages = listOf(page()),
				pageIndexByDrawableId = mapOf("TwinA" to 0, "TwinB" to 0),
				modelName = "Twin Test",
				nowMillis = 1_700_000_000_000L,
				obfuscateKey = 0x5EEDBEEF,
			)
		val source = assertIs<CModelSource>(Cmo3.read(Cmo3.write(result.model)).root)
		// CMO3: CModelSource field textureManager -> CTextureManager fields _textureAtlases and
		// _modelImageGroups -> CModelImageGroup field _modelImages.
		val textureManager = assertIs<CTextureManager>(source.textureManager)
		val atlases = Cmo3Import.elementsOf(textureManager._textureAtlases).filterIsInstance<CTextureAtlas>()
		val modelImages =
			Cmo3Import
				.elementsOf(textureManager._modelImageGroups)
				.filterIsInstance<CModelImageGroup>()
				.flatMap { group -> Cmo3Import.elementsOf(group._modelImages).filterIsInstance<CModelImage>() }

		assertEquals(1, atlases.size, "no page is synthesized for the twin")
		assertEquals(1, modelImages.size, "the twins share one material")

		val reimported = Cmo3Import.fromModelSource(source)
		val reimportedA = drawableOf(reimported, "TwinA").mesh!!
		val reimportedB = drawableOf(reimported, "TwinB").mesh!!
		assertContentEquals(reimportedA.positions, reimportedB.positions, "the twins share one canvas mesh")
		assertContentEquals(straightPositions, reimportedB.positions)
		assertContentEquals(mirroredPositions, reimportedB.localPositions, "the twin still shows mirrored")
		assertContentEquals(reimportedA.uvs, reimportedB.uvs, "both sample the bake's own slot")
		assertContentEquals(straightPositions, drawableOf(result.puppet, "TwinB").mesh!!.positions, "the encoded puppet carries the co-located canvas mesh")
	}

	@Test
	fun decodedPixelsMustBeThePagesOwnSize() {
		val page = page()
		assertFailsWith<IllegalArgumentException> {
			Cmo3Conversion.AtlasPage(page.pngBytes, pageSize, pageSize, decoded = RasterImage(pageSize / 2, pageSize, ByteArray(pageSize / 2 * pageSize * 4)))
		}
		assertFailsWith<IllegalArgumentException> {
			Cmo3Conversion.AtlasPage(page.pngBytes, pageSize, pageSize, decoded = RasterImage(pageSize, pageSize, ByteArray(pageSize)))
		}
	}
}