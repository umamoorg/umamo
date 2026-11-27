package org.umamo.runtime.model

/*
 * The rigger's coordinate frame.  Every X or Z the rigger reads or types is measured from the world axes:
 * (0, 0) is where the viewport's axis lines cross, the world origin, with X+ right and Z+ up.
 *
 * World space itself does not move.  Its zero stays at the canvas's top-left corner (canvas x, negated
 * canvas y), because the evaluator, the renderer, every importer and exporter, UMA, and every undo snapshot
 * share it, and the rigger would see no difference if it were re-based.  Only the numbers that cross the
 * display boundary convert, through these helpers, and nothing shows a raw world coordinate.
 *
 * The Origin fields are the one reading that cannot be measured from the origin, because they place it.
 * They are measured on the canvas instead, from its bottom-left corner with Z up, so they count the same
 * direction as every other number.
 *
 * World z already grows upward, so the panel's Z is world z with only the origin subtracted - no sign flip.
 */

/**
 * The origin-relative X of a world x: how far right of the world axes it sits.
 *
 * @param Float worldX A world-space x.
 * @return Float The X the rigger reads.
 */
fun PuppetModel.originRelativeX(worldX: Float): Float = worldX - worldOriginX

/**
 * The origin-relative Z of a world z: how far above the world axes it sits.
 *
 * @param Float worldZ A world-space z (grows upward).
 * @return Float The Z the rigger reads.
 */
fun PuppetModel.originRelativeZ(worldZ: Float): Float = worldZ - worldOriginZ

/**
 * The world x of an origin-relative X - the inverse of [originRelativeX].
 *
 * @param Float originRelativeX An X measured from the world axes.
 * @return Float The world-space x.
 */
fun PuppetModel.worldXFromOriginRelative(originRelativeX: Float): Float = originRelativeX + worldOriginX

/**
 * The world z of an origin-relative Z - the inverse of [originRelativeZ].
 *
 * @param Float originRelativeZ A Z measured from the world axes.
 * @return Float The world-space z.
 */
fun PuppetModel.worldZFromOriginRelative(originRelativeZ: Float): Float = originRelativeZ + worldOriginZ

/**
 * How far the world origin sits from the canvas's left edge, in canvas pixels - the Origin X reading.
 *
 * @return Float The origin's distance from the canvas's left edge.
 */
fun PuppetModel.originFromCanvasLeft(): Float = worldOriginX

/**
 * How far the world origin sits above the canvas's bottom edge, in canvas pixels - the Origin Z reading.
 *
 * The canvas grows and shrinks at its bottom edge while the origin keeps its world point, so a canvas height
 * change moves this reading by the same amount.  With no canvas (height 0) it is the raw world z.
 *
 * @return Float The origin's height above the canvas's bottom edge.
 */
fun PuppetModel.originFromCanvasBottom(): Float = worldOriginZ + canvasHeight

/**
 * The world-origin x for an Origin X reading - the inverse of [originFromCanvasLeft].  The canvas's left edge
 * is world x 0, so this is the identity; it exists so both Origin rows convert the same way.
 *
 * @param Float fromCanvasLeft The origin's distance from the canvas's left edge.
 * @return Float The world-origin x to store.
 */
fun PuppetModel.worldOriginXFromCanvasLeft(fromCanvasLeft: Float): Float = fromCanvasLeft

/**
 * The world-origin z for an Origin Z reading - the inverse of [originFromCanvasBottom].
 *
 * @param Float fromCanvasBottom The origin's height above the canvas's bottom edge.
 * @return Float The world-origin z to store.
 */
fun PuppetModel.worldOriginZFromCanvasBottom(fromCanvasBottom: Float): Float = fromCanvasBottom - canvasHeight

/**
 * The world-origin x of a canvas's center - the default origin every importer and a new document derive.
 *
 * @param Float canvasWidth The canvas width, in canvas pixels.
 * @return Float The world-origin x.
 */
fun canvasCenterWorldOriginX(canvasWidth: Float): Float = canvasWidth / 2f

/**
 * The world-origin z of a canvas's center - the default origin every importer and a new document derive.
 * The center is half the height down from the canvas's top edge, and world z is the negated canvas y.
 *
 * @param Float canvasHeight The canvas height, in canvas pixels.
 * @return Float The world-origin z.
 */
fun canvasCenterWorldOriginZ(canvasHeight: Float): Float = -(canvasHeight / 2f)

/**
 * Whether the world origin sits exactly on the canvas center, the default [canvasCenterWorldOriginX] and
 * [canvasCenterWorldOriginZ] derive.  A CMO3 stores no authored origin and its import derives the center, so
 * only a centered origin survives a CMO3 round trip.
 *
 * @return Boolean True when the origin is the canvas center.
 */
fun PuppetModel.isOriginAtCanvasCenter(): Boolean =
	worldOriginX == canvasCenterWorldOriginX(canvasWidth) && worldOriginZ == canvasCenterWorldOriginZ(canvasHeight)