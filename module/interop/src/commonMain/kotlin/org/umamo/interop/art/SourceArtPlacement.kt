package org.umamo.interop.art

import org.umamo.format.art.LayerBounds
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceGroup
import org.umamo.format.art.SourceLayer
import org.umamo.runtime.model.ArtSource

/*
 * Placing a file's art on the document canvas.  Every SourceArt the model sees - the bridge's, the
 * reload planner's, the matcher's - is in the DOCUMENT canvas frame, and the placement happens ONCE,
 * where the file is read: the add path places a new file by its Align and Offset rows, and every
 * later read of a listed file is placed by the offset its record keeps.  That keeps the inventory
 * rows, the birth meshes, and the reload's frame comparisons in one frame with the drawables'
 * positions, so nothing downstream knows a file's own canvas ever differed from the document's.
 */

/**
 * A translation from a file's own canvas to the document canvas, in pixels along the viewport's world
 * axes: x to the right and z UP.  A file whose top-left corner sits below the document's carries a
 * negative z, and a layer's canvas top (y down) moves by -z.
 *
 * @property Int x How far right of the document canvas's top-left corner the file's top-left corner sits.
 * @property Int z How far up from the document canvas's top-left corner the file's top-left corner sits.
 */
data class CanvasOffset(val x: Int, val z: Int) {
	/** Whether this offset moves nothing. */
	val isZero: Boolean get() = x == 0 && z == 0

	companion object {
		/** The offset of a file that shares the document's frame. */
		val Zero: CanvasOffset = CanvasOffset(0, 0)
	}
}

/**
 * This art with every layer's bounds shifted by [offset], the file's canvas placed on the document's.
 *
 * The layers delegate everything but their bounds to the originals, so each layer's raster is the
 * SAME instance: the add path's decode memo and the renderer's texture cache both key on that
 * identity.  The file's own canvas size and its folders pass through unchanged.  A zero offset
 * returns this art itself.
 *
 * @param CanvasOffset offset The translation.
 * @return SourceArt The placed art.
 */
fun SourceArt.placedBy(offset: CanvasOffset): SourceArt {
	if (offset.isZero) {
		return this
	}
	return PlacedSourceArt(this, offset)
}

/**
 * This art placed by [source]'s recorded offset - what every read of a listed file goes through
 * before the model sees it.
 *
 * @param ArtSource source The file's record.
 * @return SourceArt The placed art.
 */
fun SourceArt.placedFor(source: ArtSource): SourceArt = placedBy(CanvasOffset(source.offsetX, source.offsetZ))

/** A file's art shifted onto the document canvas; see [placedBy]. */
private class PlacedSourceArt(
	private val original: SourceArt,
	offset: CanvasOffset,
) : SourceArt {
	override val layers: List<SourceLayer> = original.layers.map { layer -> PlacedSourceLayer(layer, offset) }
	override val groups: List<SourceGroup> get() = original.groups
	override val widthPx: Int get() = original.widthPx
	override val heightPx: Int get() = original.heightPx
}

/** One layer shifted onto the document canvas: the original in every respect but where it sits. */
private class PlacedSourceLayer(
	private val original: SourceLayer,
	offset: CanvasOffset,
) : SourceLayer by original {
	override val bounds: LayerBounds =
		LayerBounds(
			left = original.bounds.left + offset.x,
			// Canvas y runs down and z runs up, so a file placed higher has a smaller top.
			top = original.bounds.top - offset.z,
			width = original.bounds.width,
			height = original.bounds.height,
		)
}