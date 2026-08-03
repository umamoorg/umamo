package org.umamo.render

import org.umamo.format.png.PngCodec

/** A decoded RGBA image (top row first), ready for GL upload. */
class DecodedImage(val rgba: ByteArray, val width: Int, val height: Int)

/**
 * The texture data backing a puppet preview: the distinct atlas page(s) the art meshes sample, plus a
 * per-drawable index into them.  Art meshes typically share one atlas page in `TEXTURE_ATLAS` mode, but
 * this structure generalises to multiple pages.
 *
 * [premultipliedAlpha] is the OR-fold of the CMO3 `GTexture2D.isPremultiplied` bits and is currently
 * UNCONSUMED - and is deliberately kept that way.  It is an editor texture-upload-convention flag
 * (serialized true on nearly every model), NOT a claim that the decoded bytes are premultiplied: every
 * ingested page is straight-alpha (PngCodec yields straight alpha; the fragment shader premultiplies
 * in-shader with source factor GL_ONE), so wiring this to "unpremultiply the bytes" would over-darken
 * the whole corpus.  The version-dependent premultiplied-vs-straight COMPOSITING distinction (Cubism
 * 5.2 and earlier plus the "(Before 5.3)" modes composite premultiplied; 5.3+ Color blend composites
 * straight) is a separate axis, carried by `BlendMode.isLegacy` and handled in
 * `BlendMath.compositeReference`, not by this field.  See docs/format/CMO3.md, "Premultiplied vs
 * straight alpha".
 *
 * @property List<DecodedImage> atlases               The distinct decoded atlas pages.
 * @property Map<String,Int>    atlasIndexByDrawableId Drawable id (`ArtMesh…`) → index into [atlases].
 * @property Boolean            premultipliedAlpha     Aggregated `GTexture2D.isPremultiplied`; unconsumed
 *                                                     (see the note above) - do not drive rendering off it.
 */
class PuppetTextures(
	val atlases: List<DecodedImage>,
	val atlasIndexByDrawableId: Map<String, Int>,
	val premultipliedAlpha: Boolean,
)

/**
 * How [buildPuppetTextures] treats an atlas page whose bytes will not decode.
 *
 * The two ingest paths want opposite answers and each is right for its format, so the policy is the
 * caller's to state rather than something the shared builder decides for them.
 */
enum class UndecodablePagePolicy {
	/**
	 * Drop the page and unmap every drawable that sampled it; the build still succeeds.  What a CMO3
	 * wants: its pixels are embedded in the model file, so a page that will not decode is one corrupt
	 * resource inside an otherwise editable rig, and refusing to open the document over it would cost
	 * the rigger everything else in the file.  Those drawables render in fallback color.
	 */
	Skip,

	/**
	 * Fail the whole build so the loader can report a broken import.  What a MOC3 wants: its pages are
	 * sibling files named by model3.json, so one that will not decode means the family on disk is
	 * incomplete or mismatched - an import error to surface, not a puppet to render half of.
	 */
	Fail,
}

/**
 * Builds the [PuppetTextures] for a puppet from its atlas page bytes and a per-drawable index into
 * them - the format-agnostic core both ingest paths end at.
 *
 * Each format's adapter ([cmo3PuppetTextures], [moc3PuppetTextures]) does its own walk to produce
 * these two arguments and then hands them here, so the decode, the page-index validation, and the
 * PuppetTextures shape are written once.  Nothing about this function knows a format; it is
 * deliberately reachable with hand-built arguments so tests need no corpus file.
 *
 * @param List<ByteArray>       pageBytes              The PNG bytes per atlas page, in index order.
 * @param Map<String, Int>      atlasIndexByDrawableId Drawable id (`ArtMesh…`) → index into [pageBytes].
 * @param Boolean               premultipliedAlpha     Passed through to [PuppetTextures.premultipliedAlpha];
 *                                                     see its docblock - aggregated by the CMO3 adapter
 *                                                     and unconsumed, false everywhere else.
 * @param UndecodablePagePolicy undecodablePage        What an undecodable page costs.
 * @return PuppetTextures? The decoded pages + index, or null when [undecodablePage] is
 *                         [UndecodablePagePolicy.Fail] and a page failed to decode or a drawable's
 *                         index fell outside [pageBytes].  Never null under
 *                         [UndecodablePagePolicy.Skip].
 */
fun buildPuppetTextures(
	pageBytes: List<ByteArray>,
	atlasIndexByDrawableId: Map<String, Int>,
	premultipliedAlpha: Boolean = false,
	undecodablePage: UndecodablePagePolicy = UndecodablePagePolicy.Fail,
): PuppetTextures? {
	val failOnBadPage = undecodablePage == UndecodablePagePolicy.Fail
	// An index outside the page list must never reach the renderer: PuppetRenderer resolves pages by
	// direct list indexing, so broken atlas wiring is caught here rather than crashing the render
	// thread at first frame.  Under Skip the same guard applies per drawable instead of per build -
	// an unresolvable page number simply leaves that drawable out of the rebuilt map below.
	if (failOnBadPage && atlasIndexByDrawableId.values.any { pageIndex -> pageIndex !in pageBytes.indices }) {
		return null
	}

	// Skip can drop pages, which shifts every later page down one slot, so decoding records where each
	// surviving page landed and the drawable map is rebuilt against that numbering.  Under Fail nothing
	// is ever dropped and the remap is an identity copy.
	val atlases = ArrayList<DecodedImage>(pageBytes.size)
	val keptIndexBySourceIndex = HashMap<Int, Int>(pageBytes.size)
	for ((sourceIndex, bytes) in pageBytes.withIndex()) {
		val image =
			try {
				PngCodec.read(bytes)
			} catch (_: Exception) {
				if (failOnBadPage) {
					return null
				}
				continue
			}
		keptIndexBySourceIndex[sourceIndex] = atlases.size
		atlases.add(DecodedImage(image.rgba, image.width, image.height))
	}

	val keptIndexByDrawableId = LinkedHashMap<String, Int>(atlasIndexByDrawableId.size)
	for ((drawableId, sourceIndex) in atlasIndexByDrawableId) {
		keptIndexBySourceIndex[sourceIndex]?.let { keptIndex -> keptIndexByDrawableId[drawableId] = keptIndex }
	}
	return PuppetTextures(atlases, keptIndexByDrawableId, premultipliedAlpha)
}
