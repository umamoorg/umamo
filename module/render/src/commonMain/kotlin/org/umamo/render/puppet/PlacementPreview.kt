package org.umamo.render.puppet

import org.umamo.render.DecodedImage
import org.umamo.runtime.model.PuppetAtlas

/**
 * One tile crop of a placement preview: a mover's trim, or a committed move's ghost, as a quad on the shown
 * page.  The renderer samples it through the tile's layer texture when one is resident (source-art display
 * mode uploads the whole tile), and otherwise uploads [crop], so the drag never needs the tile decoded twice.
 * Both images hold the same pixels: a tile id's art never changes.
 *
 * @property String tileKey The tile's layer key (its atlas tile id), naming the layer texture to reuse.
 * @property DecodedImage? crop The trim's straight-alpha pixels, uploaded when no layer texture is
 *   resident, or null when the gesture could not cut one (the quad then draws only from a layer texture).
 * @property FloatArray quadToWorld The trim's unit-corner-to-display affine at the placement the quad shows,
 *   rows first (m00 m01 m02 m10 m11 m12).
 * @property FloatArray sampleAffine The quad's V-flipped unit coordinates to the whole tile's texture
 *   coordinates, rows first, for sampling through the layer texture; an uploaded [crop] is the trim itself
 *   and samples at the identity.
 */
class PlacementCropQuad(
	val tileKey: String,
	val crop: DecodedImage?,
	val quadToWorld: FloatArray,
	val sampleAffine: FloatArray,
)

/**
 * The placement drag's preview, drawn in a UV area's scene over its page and under its islands: a scrim over
 * each mover's old trim (the art is leaving it), each mover's crop where the gesture now puts it, and a
 * committed move's crops as ghosts at their committed spots while the pages for that commit are composed.
 * It rides the area's page content, so it reaches the render thread with the page it is drawn over.
 *
 * Identity equality, like the overlay beside it: a new value is a new publish.  Nothing in it is mutated
 * after it is published.
 *
 * @property OverlayColor scrimColor The scrim's straight-alpha color.
 * @property List<FloatArray> scrimQuads Each mover's old trim as a unit-corner-to-display affine, rows first.
 * @property List<PlacementCropQuad> crops Each mover's crop at its new placement.
 * @property PuppetAtlas? ghostAtlas The atlas the ghost crops were committed into, or null without a ghost:
 *   the engine stops drawing them on the first frame whose applied pages belong to it (decision D20).
 * @property List<PlacementCropQuad> ghostCrops The committed move's crops at their committed placements.
 */
class PlacementPreview(
	val scrimColor: OverlayColor,
	val scrimQuads: List<FloatArray>,
	val crops: List<PlacementCropQuad>,
	val ghostAtlas: PuppetAtlas?,
	val ghostCrops: List<PlacementCropQuad>,
) {
	/**
	 * The preview without its ghost: what a frame draws once the pages it applies are the ghost's.
	 *
	 * @return PlacementPreview The preview with no ghost crops.
	 */
	fun withoutGhost(): PlacementPreview = PlacementPreview(scrimColor, scrimQuads, crops, null, emptyList())
}