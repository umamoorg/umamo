package org.umamo.edit

/**
 * A latched Edit-mode deform brush the gizmo overlay drives - the smear-tool analog of
 * [ActiveSelectTool].  Null when no brush is armed (a plain drag picks elements); a command latches one and
 * the overlay reads it to reinterpret pointer input.  Transient editor state, never snapshotted and never on
 * the change bus, exactly like [SessionToolLatches.activeMeshOperator].
 *
 * @property Float radiusWorld The influence radius, in world units (canvas px).  The brush measures the
 *   model rather than the screen, so the radius is a world quantity and the wheel resizes it in world units.
 * @property String areaId The viewport area that armed the brush (an opaque workspace-leaf id, the
 *   [ActiveOperator] currency): only the arming area's overlay drives the gesture, so a brush in one split
 *   viewport stays inert everywhere else.
 */
data class ActiveBrush(
	val radiusWorld: Float,
	val areaId: String,
)