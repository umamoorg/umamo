package org.umamo.render

/**
 * What one frame draws over and around the scene beyond its [FrameBackdrop]: the grid lines, the
 * world-origin axes, and the mesh overlay the renderer holds.  A value per frame rather than renderer
 * state, because the editor's areas differ in what they show while sharing one renderer: two viewports
 * of one document can hide different overlays, and a frame must draw what ITS area asks for.
 *
 * The data behind each stays shared and renderer-wide.  Grid lines off keeps the grid pass (the opaque
 * fill that clears the frame) and paints its lines in the background color, so a UV scene's surround and
 * page frame stay; the mesh overlay's device buffers are brought to the held overlay whether or not a
 * frame draws it, so an area that hides it costs the next area that shows it no upload.
 *
 * @property Boolean gridLines   Whether the grid's major and minor lines draw; false paints the flat
 *   background in their place.
 * @property Boolean axes        Whether the world-origin axis lines draw after the grid.  Off by default,
 *   so a headless render stays line-free; the editor's areas ask for them.
 * @property Boolean meshOverlay Whether the mesh overlay the renderer holds is drawn this frame.
 * @property Boolean wireframe   Whether the wireframe draws this frame: an Object-mode wireframe overlay
 *   whole, its deform capture pass included, and the meshes outside the edit that an Edit overlay carries
 *   as plain wireframes.  The Edit cage itself draws under [meshOverlay] alone.  True by default, as
 *   [meshOverlay] is: a headless render draws what the renderer holds, and the editor's areas say otherwise.
 * @property Boolean selectionTint Whether the selected and active drawables draw tinted this frame.  Off,
 *   the frame draws the art as a capture does, with no selection at all, while the renderer's selection
 *   stays what it is for the next area that tints.
 * @property Float wireframeOpacity The alpha scale, 0 to 1, of the mesh overlay drawn outside an edit: an
 *   Object-mode wireframe whole, an Edit overlay's plain wireframe meshes, and a UV scene's islands, each
 *   drawn at its palette alpha times this.  The Edit cage keeps the palette.  At 0 none of it draws.
 */
data class FrameOverlays(
	val gridLines: Boolean = true,
	val axes: Boolean = false,
	val meshOverlay: Boolean = true,
	val wireframe: Boolean = true,
	val selectionTint: Boolean = true,
	val wireframeOpacity: Float = 1f,
)