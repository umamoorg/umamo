package org.umamo.runtime.ingest

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CDrawableSourceSet
import org.umamo.format.cmo3.model.gen.CTextureInputExtension
import org.umamo.format.cmo3.model.gen.CTextureInput_TextureAtlasRegion
import org.umamo.format.cmo3.model.gen.GTexture2D
import org.umamo.format.cmo3.model.identity.Id
import org.umamo.format.cmo3.model.type.CAffine
import org.umamo.runtime.model.DrawableId
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * Locks in the UV frame convention CMO3 ingest must honor (see docs/format/CMO3.md §4):
 *
 *  - An UNPACKED drawable (no CTextureInput_TextureAtlasRegion) stores its UVs in the model-image LOGICAL
 *    frame, scaled by GTexture2D.transformImageResource01toLogical01; ingest inverts that affine so the UVs
 *    span the sampled per-layer image's full [0,1] frame.  Skipping it clips the outer margin and enlarges
 *    the art - the MultiplyScreenColors "white border cut off" bug.
 *  - A PACKED drawable (carries an atlas region) already stores its UVs in the source-image frame, so ingest
 *    must leave them VERBATIM.  Applying the inverse there over-expands the UVs and shrinks the art - the
 *    modelA "shrunken pupil" regression this guards against (modelA has an atlas but is saved in
 *    combined-layer mode, so its packed drawables carry a non-identity affine yet need no remap).
 *
 * Corpus-gated by name; self-skips when a sample is absent.
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

	private fun elements(collection: Any?): List<Any?> =
		when (collection) {
			is Map<*, *> -> collection.values.toList()
			is Iterable<*> -> collection.toList()
			is Array<*> -> collection.toList()
			else -> emptyList()
		}

	private fun artMeshes(root: CModelSource): List<CArtMeshSource> =
		elements((root.drawableSourceSet as? CDrawableSourceSet)?._sources).filterIsInstance<CArtMeshSource>()

	private fun hasAtlasRegion(source: CArtMeshSource): Boolean {
		val extension = elements(source._extensions).filterIsInstance<CTextureInputExtension>().firstOrNull() ?: return false
		return elements(extension._textureInputs).any { it is CTextureInput_TextureAtlasRegion }
	}

	private fun isIdentity(source: CArtMeshSource): Boolean {
		val affine = (source.texture as? GTexture2D)?.transformImageResource01toLogical01 as? CAffine ?: return true
		return affine.m00 == 1f && affine.m01 == 0f && affine.m02 == 0f && affine.m10 == 0f && affine.m11 == 1f && affine.m12 == 0f
	}

	private fun maxUvComponent(uvs: FloatArray): Float = uvs.maxOrNull() ?: Float.NaN

	@Test
	fun unpackedPerLayerUvsSpanFullImageAfterInverseTransform() {
		val file =
			corpusFile("MultiplyScreenColors.cmo3") ?: run {
				println("MultiplyScreenColors.cmo3 not present; skipping unpacked-UV test")
				return
			}
		val root = Cmo3.read(file).root as? CModelSource ?: error("root is not a CModelSource")
		val puppet = Cmo3Import.fromModelSource(root)

		// Inset_Pink_Square is unpacked (no atlas); its logical UVs top out at ~0.965 (the affine scale) and
		// must reach the image edge (~1.0) after inversion so the whole PNG - white border included - maps on.
		val insetPink = puppet.drawables.first { it.id == DrawableId("Inset_Pink_Square") }
		val insetUvMax = maxUvComponent(insetPink.mesh!!.uvs)
		assertTrue(insetUvMax > 0.99f, "unpacked UVs must reach the image edge; got max $insetUvMax")
		assertTrue(insetUvMax < 1.05f, "unpacked UVs must not wildly overshoot; got max $insetUvMax")
	}

	@Test
	fun packedUvsAreLeftVerbatim() {
		val file =
			corpusFile("modelA.cmo3") ?: run {
				println("modelA.cmo3 not present; skipping packed-UV test")
				return
			}
		val root = Cmo3.read(file).root as? CModelSource ?: error("root is not a CModelSource")
		val puppet = Cmo3Import.fromModelSource(root)

		val sourcesById = artMeshes(root).associateBy { (it.id as? Id)?.idstr }
		var packedNonIdentityChecked = 0
		for (drawable in puppet.drawables) {
			val source = sourcesById[drawable.id.raw] ?: continue
			if (!hasAtlasRegion(source)) {
				continue
			}
			// A packed drawable's UVs must survive ingest byte-for-byte (no inverse-transform remap).
			val sourceUvs = source.uvs as? FloatArray ?: continue
			assertContentEquals(sourceUvs, drawable.mesh!!.uvs, "packed drawable ${drawable.id.raw} UVs must be verbatim")
			// Count the ones where the affine is non-identity AND the raw UVs reach past the image edge -
			// exactly where the earlier over-eager remap shrank the art, so the guard is non-vacuous.
			if (!isIdentity(source) && maxUvComponent(sourceUvs) > 1.0f) {
				packedNonIdentityChecked++
			}
		}
		assertTrue(
			packedNonIdentityChecked > 0,
			"expected packed drawables with a non-identity affine and edge-spanning UVs (the regression surface); found none",
		)
	}
}
