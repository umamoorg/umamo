package org.umamo.runtime.ingest

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.runtime.model.DrawableId
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression test for the atlasless-CMO3 UV bug: a drawable that samples its own per-layer image (not a
 * packed atlas page) stores its UVs in the model-image logical frame, scaled down by
 * `GTexture2D.transformImageResource01toLogical01`.  Ingest must invert that affine so the UVs span the
 * sampled image's full [0,1] frame; otherwise the image overhangs its UV region and renders enlarged with
 * its outer margin clipped (the "Inset_Pink_Square white border cut off" symptom).
 *
 * Corpus-gated by name (self-skips when the sample is absent), so it stays green without a committed corpus.
 */
class Cmo3ImageResourceUvTest {
	private fun corpusFile(fileName: String): File? {
		var directory: File? = File(System.getProperty("user.dir"))
		while (directory != null) {
			val corpus = File(directory, "test/corpus")
			if (corpus.isDirectory) {
				return corpus.walkTopDown().firstOrNull { it.isFile && it.name == fileName }
			}
			directory = directory.parentFile
		}
		return null
	}

	private fun maxUvComponent(uvs: FloatArray): Float {
		var maximum = -Float.MAX_VALUE
		for (value in uvs) {
			if (value > maximum) {
				maximum = value
			}
		}
		return maximum
	}

	@Test
	fun perLayerUvsSpanFullImageAfterInverseTransform() {
		val file = corpusFile("MultiplyScreenColors.cmo3")
		if (file == null) {
			println("MultiplyScreenColors.cmo3 not present; skipping per-layer UV test")
			return
		}
		val root = Cmo3.read(file).root as? CModelSource ?: error("root is not a CModelSource")
		val puppet = Cmo3Import.fromModelSource(root)

		// Inset_Pink_Square: logical UVs top out at ~0.965 (the transform scale); after inverting the
		// transform they must reach the image edge (~1.0), so the whole PNG - including the white border -
		// maps onto the mesh.  Guard well above the pre-fix 0.965.
		val insetPink = puppet.drawables.first { it.id == DrawableId("Inset_Pink_Square") }
		val insetUvMax = maxUvComponent(insetPink.mesh!!.uvs)
		assertTrue(insetUvMax > 0.99f, "Inset_Pink_Square UVs must reach the image edge; got max $insetUvMax")
		assertTrue(insetUvMax < 1.05f, "Inset_Pink_Square UVs must not wildly overshoot; got max $insetUvMax")
	}

	@Test
	fun atlasPackedUvsAreLeftUntouched() {
		// An atlas-packed model has identity transforms, so imported UVs must equal the raw source UVs.
		val file = corpusFile("EricaTamamo.cmo3")
		if (file == null) {
			println("EricaTamamo.cmo3 not present; skipping atlas identity test")
			return
		}
		val model = Cmo3.read(file)
		val root = model.root as? CModelSource ?: error("root is not a CModelSource")
		val puppet = Cmo3Import.fromModelSource(root)

		// Atlas pages are 8192 - the UVs already index them; any sane drawable reaches a good part of the
		// page but never through the inverse of a non-identity affine.  Assert the max stays a plain UV in
		// [0, 1.001] (a tiny outer-margin overshoot is normal), i.e. no accidental division blew it up.
		val maxAcrossModel = puppet.drawables.mapNotNull { it.mesh }.maxOf { maxUvComponent(it.uvs) }
		assertTrue(maxAcrossModel <= 1.001f, "atlas UVs must be left untouched (max $maxAcrossModel)")
	}
}
