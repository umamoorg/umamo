package org.umamo.interop.cmo3.export

import org.umamo.format.cmo3.Cmo3Model
import org.umamo.format.cmo3.model.custom.CModelImage
import org.umamo.format.cmo3.model.gen.CModelImageGroup
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.cmo3.model.gen.ModelImageFilterSet
import org.umamo.interop.cmo3.elementsOf

/**
 * The archive side of writing into a retained graph: the path sequences a minted image or icon is
 * named from, the filter definitions a minted model image shares, and the PNG entries collected
 * since the last embed.
 */
internal class Cmo3RetainedArchive(
	private val target: Cmo3Model,
	private val textureManager: CTextureManager,
) {
	/**
	 * The names object every mint threads, built on first use: the archive's own path sequences, and
	 * the filter definitions of a model image the graph already holds (fresh ones when it holds none,
	 * or one another writer shaped differently).
	 */
	val names: Cmo3FreshChainNames by lazy { namesFor() }

	/** The PNG entries the shared builders collected since the last [embedPending]. */
	val pendingEntries = ArrayList<Cmo3FreshFile.PngEntry>()

	/**
	 * Embeds every collected entry in the archive.  Called after each builder call, so the local path
	 * sequences and the archive never disagree about what is taken.
	 */
	fun embedPending() {
		for (entry in pendingEntries) {
			target.addPng(entry.path, entry.pngBytes)
		}

		pendingEntries.clear()
	}

	/**
	 * The names object a minted web threads: the archive's own path sequences, and the filter
	 * definitions of a model image the graph already holds (fresh ones when it holds none, or one
	 * another writer shaped differently).
	 *
	 * @return Cmo3FreshChainNames The names.
	 */
	private fun namesFor(): Cmo3FreshChainNames {
		val retainedFilterSet =
			elementsOf(textureManager._modelImageGroups)
				.filterIsInstance<CModelImageGroup>()
				.flatMap { group -> elementsOf(group._modelImages).filterIsInstance<CModelImage>() }
				.firstNotNullOfOrNull { modelImage -> modelImage.inputFilter as? ModelImageFilterSet }
		val filters =
			retainedFilterSet?.let { filterSet -> Cmo3ModelImageWeb.FilterCommons.fromFilterSet(filterSet) }
				?: Cmo3ModelImageWeb.FilterCommons.fresh()
		val bufferPaths = Cmo3FreshPathSequence.continuingFrom("imageFileBuf", target.nextImageFileBufPath())
		val iconPaths = Cmo3FreshPathSequence.continuingFrom("image", target.nextImagePath())

		return Cmo3FreshChainNames(filters, bufferPaths::next, iconPaths::next)
	}
}