package org.umamo.render

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.moc3.Moc3
import org.umamo.runtime.ingest.Cmo3Import
import org.umamo.runtime.ingest.Moc3Import
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.PartForm
import org.umamo.runtime.model.PuppetModel
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Offscreen channels are stored as plain f32 on both sides (no quantization like keyform
// coordinates), so the parity bar is tight.
private const val SCALAR_TOLERANCE = 0.001f

/**
 * Imports the offscreen corpus pairs from their `.cmo3` source and baked `.moc3` and asserts the
 * offscreen surface agrees across the two paths: part offscreen state (composition enums, clip
 * lists, invert, static channels), the keyformed opacity/color cells, and the drawable-level
 * extended blend + culling. The offscreen counterpart of [Moc3Cmo3ParityTest]. Skips gracefully
 * without the corpus.
 */
class OffscreenImportParityTest {
	private val pairNames = listOf("ModelWithOffscreenPartClipping", "MultiplyScreenColors")

	private fun cmo3ByBasename(): Map<String, File> =
		System.getProperty("cmo3.probe")
			?.split(',')
			?.map(::File)
			?.filter { it.isFile }
			?.associateBy { it.name.removeSuffix(".cmo3") }
			?: emptyMap()

	private fun moc3ByBasename(): Map<String, File> =
		System.getProperty("moc3.samples")
			?.let(::File)
			?.takeIf { it.isDirectory }
			?.walkTopDown()
			?.filter { it.isFile && it.extension == "moc3" }
			?.associateBy { it.name.removeSuffix(".moc3") }
			?: emptyMap()

	private fun assertColorClose(expected: ColorRgb, actual: ColorRgb, label: String) {
		assertTrue(
			abs(expected.red - actual.red) < SCALAR_TOLERANCE &&
				abs(expected.green - actual.green) < SCALAR_TOLERANCE &&
				abs(expected.blue - actual.blue) < SCALAR_TOLERANCE,
			"$label: expected $expected, got $actual",
		)
	}

	private fun assertGridChannelsMatch(cmo3Grid: KeyformGrid<PartForm>, moc3Grid: KeyformGrid<PartForm>, label: String) {
		assertEquals(cmo3Grid.axes.size, moc3Grid.axes.size, "$label: axis count")
		assertEquals(cmo3Grid.cells.size, moc3Grid.cells.size, "$label: cell count")
		val moc3ByCoordinate = moc3Grid.cells.associateBy { it.coordinate.toList() }
		for (cmo3Cell in cmo3Grid.cells) {
			val coordinate = cmo3Cell.coordinate.toList()
			val moc3Cell = assertNotNull(moc3ByCoordinate[coordinate], "$label: cell $coordinate present in moc3")
			assertTrue(
				abs(cmo3Cell.form.opacity - moc3Cell.form.opacity) < SCALAR_TOLERANCE,
				"$label: opacity at $coordinate (${cmo3Cell.form.opacity} vs ${moc3Cell.form.opacity})",
			)
			assertColorClose(cmo3Cell.form.multiplyColor, moc3Cell.form.multiplyColor, "$label: multiply at $coordinate")
			assertColorClose(cmo3Cell.form.screenColor, moc3Cell.form.screenColor, "$label: screen at $coordinate")
		}
	}

	private fun assertOffscreenParity(cmo3Puppet: PuppetModel, moc3Puppet: PuppetModel, pairName: String) {
		val cmo3Parts = cmo3Puppet.parts.associateBy { it.id }
		var comparedOffscreens = 0
		for (moc3Part in moc3Puppet.parts) {
			// The bake drops sketch parts; iterate the moc side, which must be a subset.
			val cmo3Part = assertNotNull(cmo3Parts[moc3Part.id], "$pairName: moc part ${moc3Part.id.raw} in cmo3")
			val moc3Offscreen = moc3Part.offscreen
			val cmo3Offscreen = cmo3Part.offscreen
			assertEquals(cmo3Offscreen != null, moc3Offscreen != null, "$pairName: offscreen presence of ${moc3Part.id.raw}")
			if (cmo3Offscreen == null || moc3Offscreen == null) {
				continue
			}
			comparedOffscreens++
			val partLabel = "$pairName: ${moc3Part.id.raw}"
			assertEquals(cmo3Offscreen.blendMode, moc3Offscreen.blendMode, "$partLabel color blend")
			assertEquals(cmo3Offscreen.alphaBlendMode, moc3Offscreen.alphaBlendMode, "$partLabel alpha blend")
			assertEquals(cmo3Offscreen.invertMask, moc3Offscreen.invertMask, "$partLabel invert mask")
			assertEquals(cmo3Offscreen.maskedBy.toSet(), moc3Offscreen.maskedBy.toSet(), "$partLabel clip list")
			assertTrue(
				abs(cmo3Offscreen.opacity - moc3Offscreen.opacity) < SCALAR_TOLERANCE,
				"$partLabel static opacity (${cmo3Offscreen.opacity} vs ${moc3Offscreen.opacity})",
			)
			assertColorClose(cmo3Offscreen.multiplyColor, moc3Offscreen.multiplyColor, "$partLabel static multiply")
			assertColorClose(cmo3Offscreen.screenColor, moc3Offscreen.screenColor, "$partLabel static screen")
			// Keyformed channels: comparable only when both sides carry a parameter-driven grid (a
			// static moc part stores no grid, while the cmo3 may keep a degenerate zero-axis one).
			val cmo3Grid = cmo3Part.drawOrderGrid
			val moc3Grid = moc3Part.drawOrderGrid
			if (cmo3Grid != null && moc3Grid != null && cmo3Grid.axes.isNotEmpty()) {
				assertGridChannelsMatch(cmo3Grid, moc3Grid, partLabel)
			}
		}
		assertTrue(comparedOffscreens > 0, "$pairName: compared at least one offscreen part")

		val cmo3Drawables = cmo3Puppet.drawables.associateBy { it.id }
		for (moc3Drawable in moc3Puppet.drawables) {
			val cmo3Drawable = assertNotNull(cmo3Drawables[moc3Drawable.id], "$pairName: moc drawable ${moc3Drawable.id.raw} in cmo3")
			val drawableLabel = "$pairName: ${moc3Drawable.id.raw}"
			assertEquals(cmo3Drawable.blendMode, moc3Drawable.blendMode, "$drawableLabel color blend")
			assertEquals(cmo3Drawable.alphaBlendMode, moc3Drawable.alphaBlendMode, "$drawableLabel alpha blend")
			assertEquals(cmo3Drawable.culling, moc3Drawable.culling, "$drawableLabel culling")
		}
	}

	@Test
	fun offscreenSurfaceAgreesAcrossImports() {
		val cmo3Files = cmo3ByBasename()
		val moc3Files = moc3ByBasename()
		var comparedPairs = 0
		for (pairName in pairNames) {
			val cmo3File = cmo3Files[pairName]
			val moc3File = moc3Files[pairName]
			if (cmo3File == null || moc3File == null) {
				println("$pairName cmo3/moc3 pair not present; skipping")
				continue
			}
			val cmo3Root = Cmo3.read(cmo3File).root as? CModelSource ?: error("$pairName: root is not a CModelSource")
			val cmo3Puppet = Cmo3Import.fromModelSource(cmo3Root)
			val moc3Puppet = Moc3Import.fromMocDocument(Moc3.decode(moc3File.readBytes()), null)
			assertOffscreenParity(cmo3Puppet, moc3Puppet, pairName)
			comparedPairs++
			println("[Umamo][offscreen-parity] $pairName: parts=${moc3Puppet.parts.size} drawables=${moc3Puppet.drawables.size} OK")
		}
		if (comparedPairs == 0) {
			println("no offscreen cmo3/moc3 pairs present; skipping")
		}
	}
}
