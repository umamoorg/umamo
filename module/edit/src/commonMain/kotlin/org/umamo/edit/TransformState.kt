package org.umamo.edit

/**
 * The 2D cursor: a placeable world-space anchor, the 2D analog of Blender's 3D cursor.  Placed with
 * Shift+RightClick in the viewport, drawn by the HUD overlay, usable as a transform pivot
 * ([TransformPivotMode.Cursor]) and as the source / target of the Shift+S snap operations.  Session
 * state, transient by design: cursor moves are deliberately NOT undo steps (they ride outside
 * [EditorSnapshot]), and the snap menu makes recovering a lost placement cheap.
 *
 * @property Float worldX The cursor's world-space x.
 * @property Float worldZ The cursor's world-space z (up).
 */
data class Cursor2d(val worldX: Float, val worldZ: Float)

/**
 * The UV editor's own 2D cursor: a placeable anchor in normalized atlas coordinates, the texture-space
 * sibling of [Cursor2d] (Blender's UV editor likewise carries its own cursor, separate from the 3D
 * one).  Placed with Shift+RightClick in the UV editor, drawn by its overlay, and read as the UV
 * transform pivot in [TransformPivotMode.Cursor].  Session state, transient by design like [Cursor2d]:
 * cursor moves are deliberately NOT undo steps.
 *
 * @property Float u The cursor's normalized atlas u coordinate.
 * @property Float v The cursor's normalized atlas v coordinate.
 */
data class UvCursor(val u: Float, val v: Float)

/**
 * What a modal Scale / Rotate turns the selection about (Blender's pivot point selector, the Period
 * pie).  MedianPoint is the covered vertices' centroid (the default); IndividualOrigins splits the
 * selection into connectivity islands (edit mode) or per drawable (object mode), each turning about
 * its own centroid; ActiveElement anchors on the active element (or active drawable); Cursor anchors
 * on the 2D cursor.
 */
enum class TransformPivotMode {
	MedianPoint,
	IndividualOrigins,
	ActiveElement,
	Cursor,
}

/**
 * The axis a modal Grab / Scale is locked to, toggled by pressing X or Z during the gesture (Blender's
 * axis constraint).  Named by the DISPLAYED axes: the project presents the 2D plane as X (horizontal)
 * and Z (vertical, world +y) per the Y+ forward, Z+ up convention, so AxisZ constrains the position
 * arrays' y coordinates.
 */
enum class TransformAxisConstraint {
	AxisX,
	AxisZ,
}

/**
 * How deep each point sits before a depth rotation, each surface's radius the pivot's depth.  Flat keeps the art a
 * plane, so the turn squashes it and slides it by the pivot's depth; Cylinder bends it around a cylinder through the
 * pivot along the rotation axis, so every row of a turn about Z (every column of a tilt about X) curves alike; Sphere
 * lays it over a sphere around the pivot, so its middle moves furthest - the look of a head turning.  The three agree
 * at depth 0.  The declaration order is the order T cycles them in during a rotation ([next]).
 */
enum class DepthSurface {
	Flat,
	Cylinder,
	Sphere,
}

/**
 * The surface after this one in T's cycle: Flat, Cylinder, Sphere, then Flat again.
 *
 * @return DepthSurface The next surface.
 */
fun DepthSurface.next(): DepthSurface = DepthSurface.entries[(ordinal + 1) % DepthSurface.entries.size]

/** The farthest the Depth row pushes a depth rotation's pivot either way, in world px. */
const val PIVOT_DEPTH_LIMIT = 100_000f

/**
 * [depthWorld] clamped into the Depth row's range - the one clamp every write of the pivot depth goes through (a
 * mid-gesture wheel push, the strip's write-back, and a saved document's seed).  A NaN lands on 0, the depth a fresh
 * session starts at.
 *
 * @param Float depthWorld The requested depth in world px.
 * @return Float The depth within -[PIVOT_DEPTH_LIMIT]..[PIVOT_DEPTH_LIMIT].
 */
fun clampPivotDepth(depthWorld: Float): Float = if (depthWorld.isNaN()) 0f else depthWorld.coerceIn(-PIVOT_DEPTH_LIMIT, PIVOT_DEPTH_LIMIT)

/**
 * Which radial pie menu is open over the viewport, or none (null in the session flow).  The pie
 * entries dispatch through the command registry; the session only coordinates which pie is showing
 * (a transient latch like the modal operators).
 */
enum class PieMenuKind {
	PivotMode,
	Snap,
	UvSnap,
	MergeTarget,
	InsertKey,
}

/**
 * A latched modal transform operator together with the viewport area that initiated it.  The area id
 * is an opaque workspace-leaf id (the same currency as [SessionToolLatches.zoomRegionArmedArea]): the
 * session never interprets it, but the UI gates gesture capture, HUD drawing, and confirm delivery to
 * the initiating area, so a gesture latched in one split viewport can never be driven or committed
 * from another.  Both fields publish atomically in one flow emission - a paired flow could tear.
 *
 * @property MeshOperatorKind kind The latched operator (Grab / Scale / Rotate / VertexSlide).
 * @property String areaId The initiating viewport's area id.
 */
data class ActiveOperator(
	val kind: MeshOperatorKind,
	val areaId: String,
)