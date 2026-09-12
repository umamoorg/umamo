package org.umamo.reimport

import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceGroup
import org.umamo.format.art.SourceLayer

/**
 * A view of one parsed file restricted to some of its layers, for feeding the bridge only the layers
 * a reload found new.  Every layer and the folder metadata pass through by reference - the readers'
 * documents are private types, so a subset is built beside them rather than copied.
 *
 * @property SourceArt         base   The file as read.
 * @property List<SourceLayer> layers The layers to expose, in the base's own order.
 */
internal class LayerSubsetArt(
	private val base: SourceArt,
	override val layers: List<SourceLayer>,
) : SourceArt {
	override val groups: List<SourceGroup> get() = base.groups
	override val widthPx: Int get() = base.widthPx
	override val heightPx: Int get() = base.heightPx
}