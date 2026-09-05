package org.umamo.interop.cmo3

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CArtMeshForm
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CDrawableSourceSet
import org.umamo.format.cmo3.model.gen.KeyformGridSource
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * An UNKEYED drawable - no geometry grid, no channel track, the state every drawable born from an
 * artwork import is in - exports to a CMO3 the official editor can open.
 *
 * The editor requires a keyform grid with its default form on every source: setKeyformGridSource
 * rejects null and getDefaultKeyForm throws "no KeyForms" on an empty pool, and a model with either
 * floods the log and crashes on open.  So the written art mesh must carry an axis-less grid with one
 * cell and one form holding the base mesh, and the file must re-import to the same drawable.  Pinned
 * here on a synthetic puppet, so the rule cannot rot behind corpus models whose meshes are all keyed.
 */
class Cmo3UnkeyedDrawableExportTest {
	private val base = floatArrayOf(1f, 1f, 7f, 1f, 7f, 7f, 1f, 7f)

	private fun unkeyedPuppet(): PuppetModel {
		val drawable =
			Drawable(
				id = DrawableId("ArtMesh1"),
				name = "Eye",
				parentDeformerId = null,
				blendMode = BlendMode.Normal,
				maskedBy = emptyList(),
				mesh = DrawableMesh(base.copyOf(), floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), intArrayOf(0, 1, 2, 0, 2, 3)),
				geometryGrid = null,
			)
		return PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = listOf(drawable),
			rootChildren = listOf(OrgChild.Drawable(drawable.id)),
			rootPartId = null,
			canvasWidth = 8f,
			canvasHeight = 8f,
		)
	}

	@Test
	fun anUnkeyedDrawableWritesADefaultCellAndReimports() {
		val page = PngCodec.write(RasterImage(8, 8, ByteArray(8 * 8 * 4) { index -> if (index % 4 == 3) 0xFF.toByte() else 0x40 }))
		val result =
			Cmo3Conversion.freshCmo3(
				puppet = unkeyedPuppet(),
				pages = listOf(Cmo3Conversion.AtlasPage(page, 8, 8)),
				pageIndexByDrawableId = mapOf("ArtMesh1" to 0),
				modelName = "unkeyed",
				nowMillis = 0L,
				obfuscateKey = 0,
			)

		val root = result.model.root as CModelSource
		val artMesh =
			Cmo3Import.elementsOf((root.drawableSourceSet as? CDrawableSourceSet)?._sources).filterIsInstance<CArtMeshSource>().single()
		val gridSource = assertNotNull(artMesh.keyformGridSource as? KeyformGridSource, "the mesh keeps a keyform grid")
		assertTrue(Cmo3Import.elementsOf(gridSource.keyformBindings).isEmpty(), "an unkeyed mesh binds no parameter")
		assertEquals(1, Cmo3Import.elementsOf(gridSource.keyformsOnGrid).size, "one default cell")
		val form = Cmo3Import.elementsOf(artMesh.keyforms).filterIsInstance<CArtMeshForm>().single()
		assertTrue(base.contentEquals(form.positions as? FloatArray), "the default form holds the base mesh: ${(form.positions as? FloatArray)?.toList()}")

		val reimported = Cmo3Import.fromModelSource(Cmo3.read(Cmo3.write(result.model)).root as CModelSource)
		val reread = reimported.drawables.single { drawable -> drawable.id == DrawableId("ArtMesh1") }
		assertEquals("Eye", reread.name)
		assertTrue(base.contentEquals(assertNotNull(reread.mesh).positions), "the base comes back as written")
	}
}