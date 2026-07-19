package org.umamo.runtime.ingest

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.moc3.Moc3
import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.PuppetModel
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// The MultiplyScreenColors sample's authored colors: multiply #677CD1, screen #95C068 (editor hex,
// each channel serialized as hex / 255 in f32 on both formats - verified channel-exact in the
// extraction, docs/plan/offscreen-support.md).
private val AUTHORED_MULTIPLY = ColorRgb(103f / 255f, 124f / 255f, 209f / 255f)
private val AUTHORED_SCREEN = ColorRgb(149f / 255f, 192f / 255f, 104f / 255f)
private const val COLOR_TOLERANCE = 1e-6f

/**
 * Corpus-gated offscreen ingest anchors over the authored extraction family (see
 * docs/plan/offscreen-support.md § corpus evidence): part offscreen state, clip lists, invert,
 * static and keyformed colors, and the drawable-level extended blend + culling - on BOTH import
 * paths. Skips gracefully without the corpus.
 */
class CompositeImportTest {
	private fun probeFiles(): Map<String, File> =
		System.getProperty("cmo3.probe")
			?.split(',')
			?.map(::File)
			?.filter { it.isFile }
			?.associateBy { it.name }
			?: emptyMap()

	private fun mocFiles(): Map<String, File> =
		System.getProperty("moc3.samples")
			?.let(::File)
			?.takeIf { it.isDirectory }
			?.walkTopDown()
			?.filter { it.isFile && it.extension == "moc3" }
			?.associateBy { it.name }
			?: emptyMap()

	private fun importCmo3(file: File): PuppetModel {
		val root = Cmo3.read(file).root as? CModelSource ?: error("${file.name}: root is not a CModelSource")
		return Cmo3Import.fromModelSource(root)
	}

	private fun importMoc3(file: File): PuppetModel = Moc3Import.fromMocDocument(Moc3.decode(file.readBytes()), null)

	private fun assertColorClose(expected: ColorRgb, actual: ColorRgb, label: String) {
		assertTrue(
			abs(expected.red - actual.red) < COLOR_TOLERANCE &&
				abs(expected.green - actual.green) < COLOR_TOLERANCE &&
				abs(expected.blue - actual.blue) < COLOR_TOLERANCE,
			"$label: expected $expected, got $actual",
		)
	}

	@Test
	fun cmo3BaseFamilyPinsCompositionEnums() {
		val file = probeFiles()["ModelWithOffscreen.cmo3"] ?: return println("ModelWithOffscreen.cmo3 not present; skipping")
		val puppet = importCmo3(file)
		// Authored: Face = Normal/Over, Eye = Multiply (Before 5.3)/Disjoint, both offscreen.
		val isolatedParts = puppet.parts.filter { it.composite != null }
		assertEquals(2, isolatedParts.size, "isolated part count")
		val face = assertNotNull(puppet.parts.find { it.name == "Face" }?.composite, "Face offscreen")
		assertEquals(BlendMode.Normal, face.blendMode, "Face color blend")
		assertEquals(AlphaBlendMode.Over, face.alphaBlendMode, "Face alpha blend")
		val eye = assertNotNull(puppet.parts.find { it.name == "Eye" }?.composite, "Eye offscreen")
		assertEquals(BlendMode.Multiply, eye.blendMode, "Eye color blend (bare legacy token)")
		assertEquals(AlphaBlendMode.Disjoint, eye.alphaBlendMode, "Eye alpha blend")
	}

	@Test
	fun cmo3ClippingReverseMaskPinsClipAndLatentFields() {
		val file =
			probeFiles()["ModelWithOffscreenClippingIDReverseMask.cmo3"]
				?: return println("ModelWithOffscreenClippingIDReverseMask.cmo3 not present; skipping")
		val puppet = importCmo3(file)
		val drawableIds = puppet.drawables.mapTo(HashSet()) { it.id }
		// The authored clip: an offscreen part with Reverse mask on and one drawable as Clipping ID.
		val clipped = assertNotNull(puppet.parts.mapNotNull { it.composite }.find { it.maskedBy.isNotEmpty() }, "clipped offscreen part")
		assertTrue(clipped.invertMask, "reverse mask on")
		assertEquals(1, clipped.maskedBy.size, "one clip source")
		assertTrue(clipped.maskedBy.all { it in drawableIds }, "clip sources resolve to drawables")
		// The fields-survive-uncheck evidence: latent composition values on a NON-offscreen part must
		// not materialize an Isolated group mode.
		assertTrue(puppet.parts.any { it.composite == null }, "non-offscreen parts stay non-isolated")
	}

	@Test
	fun cmo3PartClippingPinsDrawableExtendedBlendAndCulling() {
		val file =
			probeFiles()["ModelWithOffscreenPartClipping.cmo3"]
				?: return println("ModelWithOffscreenPartClipping.cmo3 not present; skipping")
		val puppet = importCmo3(file)
		// The authored drawable: color blend Screen, alpha blend Out, Culling on.
		val screenDrawable = assertNotNull(puppet.drawables.find { it.blendMode == BlendMode.Screen }, "Screen drawable")
		assertEquals(AlphaBlendMode.Out, screenDrawable.alphaBlendMode, "Out alpha blend")
		assertTrue(puppet.drawables.any { it.culling }, "a drawable has culling on")
		// The part-as-clip-source expansion: the offscreen part's clip list resolves to the OTHER
		// part's constituent drawables (never a part id).
		val drawableIds = puppet.drawables.mapTo(HashSet()) { it.id }
		val clipped = assertNotNull(puppet.parts.mapNotNull { it.composite }.find { it.maskedBy.isNotEmpty() }, "clipped offscreen part")
		assertTrue(clipped.maskedBy.all { it in drawableIds }, "part clip expanded to drawables")
	}

	@Test
	fun cmo3MultiplyScreenColorsPinsAuthoredColors() {
		val file = probeFiles()["MultiplyScreenColors.cmo3"] ?: return println("MultiplyScreenColors.cmo3 not present; skipping")
		val puppet = importCmo3(file)
		val composite = assertNotNull(puppet.parts.mapNotNull { it.composite }.firstOrNull(), "an offscreen part")
		assertColorClose(AUTHORED_MULTIPLY, composite.multiplyColor, "multiply color")
		assertColorClose(AUTHORED_SCREEN, composite.screenColor, "screen color")
	}

	@Test
	fun moc3PartClippingPinsDrawableExtendedBlendAndCulling() {
		val file =
			mocFiles()["ModelWithOffscreenPartClipping.moc3"]
				?: return println("ModelWithOffscreenPartClipping.moc3 not present; skipping")
		val puppet = importMoc3(file)
		// MOC3 v6 §5.6 s153: the authored drawable stored packed 522 = Screen or (Out shl 8).
		val screenDrawable = assertNotNull(puppet.drawables.find { it.blendMode == BlendMode.Screen }, "Screen drawable")
		assertEquals(AlphaBlendMode.Out, screenDrawable.alphaBlendMode, "Out alpha blend")
		// Culling = the inverse of drawable constant-flags bit 2 (double-sided).
		assertTrue(puppet.drawables.any { it.culling }, "a drawable has culling on")
		assertTrue(puppet.drawables.any { !it.culling }, "default drawables stay double-sided")
		val drawableIds = puppet.drawables.mapTo(HashSet()) { it.id }
		val clipped = assertNotNull(puppet.parts.mapNotNull { it.composite }.find { it.maskedBy.isNotEmpty() }, "clipped offscreen part")
		assertTrue(clipped.maskedBy.all { it in drawableIds }, "offscreen mask indices resolve to drawables")
	}

	@Test
	fun moc3MultiplyScreenColorsPinsAuthoredColors() {
		val file = mocFiles()["MultiplyScreenColors.moc3"] ?: return println("MultiplyScreenColors.moc3 not present; skipping")
		val puppet = importMoc3(file)
		val composite = assertNotNull(puppet.parts.mapNotNull { it.composite }.firstOrNull(), "an offscreen part")
		assertColorClose(AUTHORED_MULTIPLY, composite.multiplyColor, "multiply color")
		assertColorClose(AUTHORED_SCREEN, composite.screenColor, "screen color")
	}

	@Test
	fun moc3ModelAPinsOrganicOffscreenCount() {
		val file = mocFiles()["modelA.moc3"] ?: return println("modelA.moc3 not present; skipping")
		val puppet = importMoc3(file)
		// Model A's 24 offscreen parts (== the moc's CountInfo[35]); the organic cross-check that the
		// owner-part join holds beyond the authored extraction family.
		assertEquals(24, puppet.parts.count { it.composite != null }, "isolated part count")
		// The keyformed-opacity evidence: at least one offscreen part carries a grid whose opacity
		// varies across cells (the ParamHologram crossfade).
		val hasKeyformedOpacity =
			puppet.parts.any { part ->
				part.composite != null && (part.formGrid?.cells?.map { it.form.opacity }?.distinct()?.size ?: 0) > 1
			}
		assertTrue(hasKeyformedOpacity, "an offscreen part has keyformed opacity")
	}
}
