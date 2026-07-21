package org.umamo.render

import org.umamo.format.cmo3.Cmo3Model
import org.umamo.format.cmo3.model.custom.CImageResource
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CDrawableSourceSet
import org.umamo.format.cmo3.model.gen.GTexture2D
import org.umamo.format.cmo3.model.identity.Id
import org.umamo.format.png.PngCodec

/**
 * Extracts the atlas texture(s) a CMO3 model's art meshes sample. Walks each `CArtMeshSource`'s
 * `GTexture2D.srcImageResource`, decodes each distinct page's embedded PNG once, and keys it by the
 * drawable id (matching `DrawableId` from `Cmo3Import`).
 *
 * Lives in jvmAndroidMain (not commonMain) because [Cmo3Model] belongs to :format's JDOM-backed
 * codec, which is JVM-only API shared by the desktop-JVM and Android targets.
 *
 * CMO3: 各アートメッシュの GTexture2D.srcImageResource（共有アトラス）を一度だけデコードする。
 *
 * @param Cmo3Model model The loaded CMO3.
 * @return PuppetTextures The decoded atlas pages + per-drawable index.
 */
fun extractPuppetTextures(model: Cmo3Model): PuppetTextures {
	val root = model.root as? CModelSource ?: return PuppetTextures(emptyList(), emptyMap(), false)
	val sources = (root.drawableSourceSet as? CDrawableSourceSet)?._sources
	val artMeshes = elementsOfCollection(sources).filterIsInstance<CArtMeshSource>()

	val indexByResource = LinkedHashMap<CImageResource, Int>()
	val atlases = ArrayList<DecodedImage>()
	val atlasIndexByDrawableId = HashMap<String, Int>()
	var premultiplied = false

	for (mesh in artMeshes) {
		val drawableId = (mesh.id as? Id)?.idstr?.takeIf { it.isNotEmpty() } ?: continue
		val texture = mesh.texture as? GTexture2D ?: continue
		// CMO3: GTexture2D.isPremultiplied is an editor texture-upload-convention flag (serialized true on
		// nearly every model), NOT a claim the embedded PNG is premultiplied - PngCodec decodes straight
		// alpha and the shader premultiplies.  Aggregated here but deliberately unconsumed downstream; do
		// not drive rendering off it (that would over-darken the corpus).  The version-dependent
		// premultiplied-vs-straight COMPOSITING axis rides BlendMode.isLegacy, not this flag.  See
		// PuppetTextures.premultipliedAlpha and docs/format/CMO3.md, "Premultiplied vs straight alpha".
		premultiplied = premultiplied || texture.isPremultiplied
		val resource = texture.srcImageResource as? CImageResource ?: continue
		val index =
			indexByResource.getOrPut(resource) {
				val decoded = decodeAtlasPng(model.extractLayerPng(resource)) ?: return@getOrPut -1
				atlases.add(decoded)
				atlases.size - 1
			}
		if (index >= 0) {
			atlasIndexByDrawableId[drawableId] = index
		}
	}
	return PuppetTextures(atlases, atlasIndexByDrawableId, premultiplied)
}

/**
 * Decodes an embedded atlas PNG to a [DecodedImage] via the shared :format PNG codec, or null when
 * the bytes are absent or undecodable (the drawable is then skipped, as before).
 *
 * Replaces the retired `decodePngToRgba` expect/actual: one pure-Kotlin decoder now serves desktop
 * and Android identically, instead of javax.imageio (desktop) and BitmapFactory (Android).  The
 * codec yields the same straight-alpha, top-first, tightly-packed RGBA byte stream GL upload expects.
 *
 * @param ByteArray? png The embedded PNG bytes, or null when the resource has none.
 * @return DecodedImage? The decoded atlas page, or null when absent/undecodable.
 */
private fun decodeAtlasPng(png: ByteArray?): DecodedImage? {
	if (png == null) {
		return null
	}
	return try {
		val image = PngCodec.read(png)
		DecodedImage(image.rgba, image.width, image.height)
	} catch (_: Exception) {
		null
	}
}

/**
 * Flattens a CMO3 collection field (CArrayList/CHashMap, held as `Any?`) to a plain list.
 *
 * @param Any? collection The raw collection field.
 * @return List<Any?> The elements (empty when null/unrecognised).
 */
private fun elementsOfCollection(collection: Any?): List<Any?> =
	when (collection) {
		is Map<*, *> -> collection.values.toList()
		is Iterable<*> -> collection.toList()
		is Array<*> -> collection.toList()
		else -> emptyList()
	}
