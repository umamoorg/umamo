package org.umamo.interop.cmo3.export

import org.umamo.format.cmo3.model.gen.CBlend_Normal
import org.umamo.format.cmo3.type.CHashMap

/*
 * The per-document state of a fresh image chain: the archive-path sequences and the shared singletons
 * every layer, model image, and page of one export threads through.
 */

/**
 * The per-document state an image chain threads through every page, crop, and real layer it
 * writes: the archive-entry path minters (the editor's imageFileBuf / image_N de-dupe sequences,
 * which must stay unique across the whole file), the filter web's shared definitions, and the
 * blend and option instances every layer shares.
 *
 * A fresh graph takes the defaults: counters from the skeleton's own first indices and fresh
 * filter definitions.  A retained graph hands in the archive's own minters and the definitions
 * recovered from a model image it already holds, so a layer minted into it continues the file's
 * sequences and shares its singletons rather than growing a second set.
 *
 * @param Cmo3ModelImageWeb.FilterCommons filters               The filter web's per-document singletons.
 * @param Function      mintImageFileBufPath  The next unique archive path for an image buffer.
 * @param Function      mintIconPath          The next unique archive path for an icon.
 */
internal class Cmo3FreshChainNames(
	val filters: Cmo3ModelImageWeb.FilterCommons = Cmo3ModelImageWeb.FilterCommons.fresh(),
	// CMO3: the imageFileBuf de-dupe naming convention (imageFileBuf, imageFileBuf_0, ...); pages
	// claim the first indices, then crops and real layers continue the sequence.
	private val mintImageFileBufPath: () -> String = Cmo3FreshPathSequence("imageFileBuf", firstSuffix = -1)::next,
	// The skeleton's three model icons take image.png / image_0.png / image_1.png, so icon entries
	// continue the editor's image_N naming from suffix 2.
	private val mintIconPath: () -> String = Cmo3FreshPathSequence("image", firstSuffix = 2)::next,
) {
	/** The one blend node every synthesized layer and group references. */
	val sharedBlend: CBlend_Normal = CBlend_Normal()

	/** The one option map every synthesized layer and group references. */
	val sharedOptions: CHashMap<String, Any?> = CHashMap()

	/**
	 * The next unique archive path for an image buffer.
	 *
	 * @return String The path.
	 */
	fun nextImageFileBufPath(): String = mintImageFileBufPath()

	/**
	 * The next unique archive path for an icon.
	 *
	 * @return String The path.
	 */
	fun nextIconPath(): String = mintIconPath()
}

/**
 * One of the editor's archive de-dupe sequences: `<stem>.png` for suffix -1, then `<stem>_<n>.png`.
 * A fresh archive starts at the skeleton's first free suffix; a retained archive continues from
 * the first path its own minter reports ([continuingFrom]) and counts locally from there, so a
 * batch of icons minted before their entries are embedded still takes distinct paths.
 *
 * @param String stem        The sequence's stem.
 * @param Int    firstSuffix The suffix the first minted path takes.
 */
internal class Cmo3FreshPathSequence(private val stem: String, firstSuffix: Int) {
	private var nextSuffix = firstSuffix

	/**
	 * Mints the next path.
	 *
	 * @return String The path.
	 */
	fun next(): String {
		val suffix = nextSuffix
		nextSuffix += 1

		return if (suffix < 0) "$stem.png" else "${stem}_$suffix.png"
	}

	companion object {
		/**
		 * The sequence whose first path is [firstPath], as a retained archive's minter reports it.
		 *
		 * @param String stem      The sequence's stem.
		 * @param String firstPath The next unused path in the archive, in the sequence's own form.
		 * @return Cmo3FreshPathSequence The sequence.
		 */
		fun continuingFrom(stem: String, firstPath: String): Cmo3FreshPathSequence {
			val firstSuffix =
				if (firstPath == "$stem.png") {
					-1
				} else {
					checkNotNull(firstPath.removePrefix("${stem}_").removeSuffix(".png").toIntOrNull()) { "'$firstPath' is not in the $stem sequence" }
				}

			return Cmo3FreshPathSequence(stem, firstSuffix)
		}
	}
}