package org.umamo.interop.cmo3

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.caff.CaffArchive
import org.umamo.format.cmo3.caff.CaffCodec
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.interop.cmo3.export.Cmo3Conversion
import org.umamo.interop.cmo3.export.Cmo3Export
import org.umamo.interop.cmo3.import.Cmo3Import
import org.umamo.interop.diffPuppetModels
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RotationPivotForm
import org.umamo.runtime.model.RuntimeTarget
import org.umamo.runtime.model.WarpLatticeForm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A deformer's eye crosses CMO3 both ways: a fresh conversion writes each deformer's flag into its synthesized
 * source, and a toggle on a retained graph rewrites that one `isVisible` element and nothing else, so the
 * decompressed main.xml differs from the file as read in exactly one line.
 */
class Cmo3DeformerVisibilityTest {
	private val hiddenWarpId = DeformerId("WarpHidden")
	private val shownWarpId = DeformerId("WarpShown")
	private val hiddenRotationId = DeformerId("RotationHidden")

	private val visibleOff = """<b xs.n="isVisible">false</b>"""
	private val visibleOn = """<b xs.n="isVisible">true</b>"""

	private fun <TForm> restGrid(form: TForm): KeyformGrid<TForm> = KeyformGrid(emptyList(), listOf(KeyformCell(IntArray(0), form)))

	private fun warp(id: DeformerId, isVisible: Boolean): Deformer.Warp =
		Deformer.Warp(
			id = id,
			name = id.raw,
			parent = null,
			partId = null,
			rows = 1,
			columns = 1,
			isQuadTransform = false,
			geometryGrid = restGrid(WarpLatticeForm(floatArrayOf(0f, 0f, 64f, 0f, 0f, 64f, 64f, 64f))),
			isVisible = isVisible,
		)

	private fun puppet(): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers =
				listOf(
					warp(hiddenWarpId, isVisible = false),
					warp(shownWarpId, isVisible = true),
					Deformer.Rotation(
						id = hiddenRotationId,
						name = hiddenRotationId.raw,
						parent = null,
						partId = null,
						baseAngle = 0f,
						geometryGrid = restGrid(RotationPivotForm(32f, 32f, 0f, 1f)),
						isVisible = false,
					),
				),
			drawables = emptyList(),
			rootChildren = emptyList(),
			rootPartId = null,
			parameterTree = emptyList(),
			canvasWidth = 64f,
			canvasHeight = 64f,
			worldOriginX = 32f,
			worldOriginZ = -32f,
			runtimeTarget = RuntimeTarget.Cubism53,
		)

	private fun convertedBytes(): ByteArray {
		val converted = Cmo3Conversion.freshCmo3(puppet(), emptyList(), emptyMap(), "visibility", nowMillis = 0L, obfuscateKey = 0)

		return Cmo3.write(converted.model)
	}

	private fun mainXmlLines(cmo3Bytes: ByteArray): List<String> =
		CaffCodec
			.read(cmo3Bytes)
			.firstByTag(CaffArchive.TAG_MAIN_XML)!!
			.content
			.decodeToString()
			.lines()

	private fun importOf(cmo3Bytes: ByteArray): PuppetModel = Cmo3Import.fromModelSource(assertIs<CModelSource>(Cmo3.read(cmo3Bytes).root))

	private fun visibilityById(model: PuppetModel): Map<DeformerId, Boolean> = model.deformers.associate { deformer -> deformer.id to deformer.isVisible }

	private fun withVisibility(puppet: PuppetModel, deformerId: DeformerId, visible: Boolean): PuppetModel =
		puppet.copy(
			deformers =
				puppet.deformers.map { deformer ->
					if (deformer.id != deformerId) {
						deformer
					} else {
						when (deformer) {
							is Deformer.Warp -> deformer.copy(isVisible = visible)
							is Deformer.Rotation -> deformer.copy(isVisible = visible)
						}
					}
				},
		)

	/**
	 * Applies one deformer's eye toggle to a fresh parse of [sourceBytes], asserting the export is lossless and
	 * changes only that deformer's `isVisible` line.
	 *
	 * @param ByteArray  sourceBytes The CMO3 as read.
	 * @param DeformerId deformerId  The deformer to toggle.
	 * @param Boolean    visible     The flag it is given.
	 */
	private fun assertTogglesOnlyItsFlag(sourceBytes: ByteArray, deformerId: DeformerId, visible: Boolean) {
		val cmo3 = Cmo3.read(sourceBytes)
		val edited = withVisibility(Cmo3Import.fromModelSource(assertIs<CModelSource>(cmo3.root)), deformerId, visible)
		val report = Cmo3Export.apply(edited, cmo3)
		assertTrue(report.isEmpty, "an eye toggle lowers fully: ${report.notices}")
		val written = Cmo3.write(cmo3)

		val residual = diffPuppetModels(importOf(written), edited)
		assertTrue(residual.isEmpty, "the toggle survives re-import: $residual")

		val before = mainXmlLines(sourceBytes)
		val after = mainXmlLines(written)
		assertEquals(before.size, after.size, "the re-emitted main.xml keeps its line count")
		val changed = before.indices.filter { lineIndex -> before[lineIndex] != after[lineIndex] }.map { lineIndex -> before[lineIndex].trim() to after[lineIndex].trim() }
		val expected = if (visible) visibleOff to visibleOn else visibleOn to visibleOff
		assertEquals(listOf(expected), changed, "only the toggled deformer's isVisible changes")
	}

	@Test
	fun aFreshConversionWritesEachDeformersEye() {
		val written = convertedBytes()

		assertEquals(mapOf(hiddenWarpId to false, shownWarpId to true, hiddenRotationId to false), visibilityById(importOf(written)))
	}

	@Test
	fun showingAHiddenWarpRewritesOnlyThatFlag() {
		assertTogglesOnlyItsFlag(convertedBytes(), hiddenWarpId, visible = true)
	}

	@Test
	fun hidingAShownWarpRewritesOnlyThatFlag() {
		assertTogglesOnlyItsFlag(convertedBytes(), shownWarpId, visible = false)
	}

	@Test
	fun showingAHiddenRotationRewritesOnlyThatFlag() {
		assertTogglesOnlyItsFlag(convertedBytes(), hiddenRotationId, visible = true)
	}
}