package org.umamo.edit.transform

import org.umamo.runtime.model.PuppetModel
import kotlin.math.round

/*
 * Grid snapping arithmetic: a value snaps to the lattice anchored on the world origin, so the snap commands
 * land on the lines the viewport draws.
 */

/**
 * Rounds a world coordinate to the nearest grid line, measured from [origin] (the world origin the grid
 * is drawn around) rather than from world 0, so a snap lands on the same lines the backdrop grid draws.
 *
 * @param Float value  The world coordinate to snap.
 * @param Float origin The world origin the grid lattice is anchored on (a line passes through it).
 * @param Float step   The grid snap increment (see [GridConfig.snapStep]).
 * @return Float The snapped world coordinate.
 */
fun snapToGrid(value: Float, origin: Float, step: Float): Float = round((value - origin) / step) * step + origin

/**
 * Rounds a world point to the nearest intersection of the model's world grid - the lattice the backdrop
 * draws, anchored on the world origin.  Both axes in one call, so a caller cannot anchor x on the origin's
 * z or the reverse.
 *
 * @param Float worldX The world x to snap.
 * @param Float worldZ The world z (up) to snap.
 * @param Float step   The grid snap increment (see [GridConfig.snapStep]).
 * @return Pair<Float, Float> The snapped world (x, z).
 */
fun PuppetModel.snapToWorldGrid(worldX: Float, worldZ: Float, step: Float): Pair<Float, Float> =
	snapToGrid(worldX, worldOriginX, step) to snapToGrid(worldZ, worldOriginZ, step)