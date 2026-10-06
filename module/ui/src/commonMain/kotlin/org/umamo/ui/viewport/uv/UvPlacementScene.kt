package org.umamo.ui.viewport.uv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.umamo.format.art.LayerBounds
import org.umamo.render.DecodedImage
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetAtlas

/*
 * The placement gesture's share of its UV area's scene: what the Object overlay's placement transform
 * publishes for the renderer to draw under the islands (the scrims, the crops, and the moving islands'
 * positions while a drag runs; the ghost crops after a landing), held per area by the host, which owns the
 * scene publish (UvSceneOverlay.kt).  The overlay itself keeps only the collision outlines and the chrome.
 */

/**
 * One drive of a placement gesture as the area's scene shows it.
 *
 * @property PlacementGesture gesture The frozen gesture (its movers' trims, crops, and old placements).
 * @property PlacementDragResult result The drive's evaluation (the new placements and the collisions).
 * @property Map<DrawableId, FloatArray> previewPositionsById Each moving island's display positions under it.
 */
internal class PlacementDragView(
	val gesture: PlacementGesture,
	val result: PlacementDragResult,
	val previewPositionsById: Map<DrawableId, FloatArray>,
)

/**
 * What the placement gesture shows in its area's scene, one value per change.
 *
 * @property PlacementDragView? drag The drag in flight, or null.
 * @property PlacementGhost? ghost The last landing's ghost, or null.
 */
internal class UvPlacementScene(
	val drag: PlacementDragView?,
	val ghost: PlacementGhost?,
)

/**
 * The host-owned placement scene of one UV area: the Object overlay's placement transform writes it per
 * drive and at a landing, and clears it as the gesture ends or the overlay leaves; the area's scene publish
 * reads it.  Snapshot state, so the publish follows it without the overlay recomposing anything.
 */
internal class UvPlacementSceneState {
	/** The drag in flight, or null. */
	var drag by mutableStateOf<PlacementDragView?>(null)

	/** The last landing's ghost, or null. */
	var ghost by mutableStateOf<PlacementGhost?>(null)

	/**
	 * The scene as one value.
	 *
	 * @return UvPlacementScene The drag and the ghost as they are now.
	 */
	fun snapshot(): UvPlacementScene = UvPlacementScene(drag, ghost)
}

/**
 * One tile crop drawn at a committed placement while the page resolver is still composing the pixels
 * for that commit.
 *
 * @property AtlasTileId    tileId    The tile, naming the layer texture the scene may sample instead.
 * @property DecodedImage   crop      The trim's straight-alpha pixels.
 * @property LayerBounds    trim      The trim the crop covers, raster-local.
 * @property AtlasPlacement placement Where the tile was committed to.
 */
internal class GhostCrop(
	val tileId: AtlasTileId,
	val crop: DecodedImage,
	val trim: LayerBounds,
	val placement: AtlasPlacement,
)

/**
 * A committed placement move's crops, drawn at their new spots until the pages for the committed atlas are
 * applied - so the islands never sit over the OLD pixels for the derivation's duration.  The engine retires
 * the crops on the first frame whose applied pages are this atlas's (decision D20); the UI keeps the value
 * only while its atlas is still the committed one.
 *
 * @property PuppetAtlas atlas      The atlas instance the commit published (the resolver keys by identity).
 * @property Int         pageHeight The page height, for the display flip.
 * @property List        crops      The crops and where they were committed.
 */
internal class PlacementGhost(
	val atlas: PuppetAtlas,
	val pageHeight: Int,
	val crops: List<GhostCrop>,
)

/**
 * The ghost to keep publishing: a committed move's crops stand while the committed atlas is the very instance
 * they were published for.  By IDENTITY - an undo restores the very instance a snapshot holds, and any other
 * commit makes a new one.  Whether its pages have landed is the engine's call, made against the pages it has
 * applied (decision D20).
 *
 * @param PlacementGhost? ghost The last published ghost, or null.
 * @param PuppetAtlas committedAtlas The session's committed atlas.
 * @return PlacementGhost? The ghost, or null when there is none or its atlas is no longer committed.
 */
internal fun activePlacementGhost(ghost: PlacementGhost?, committedAtlas: PuppetAtlas): PlacementGhost? =
	ghost?.takeIf { pending -> pending.atlas === committedAtlas }