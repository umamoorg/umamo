package org.umamo.edit.transform

import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.clampBrushRadius

/*
 * The deform brush's session entry points: arming it and resizing it.  The brush is armed like a select
 * tool but is not one, so it has its own latch; the guards live here so every caller - the command table,
 * the overlay - reaches the same ones.
 */

/**
 * Arms the deform brush for [areaId], so the area's overlay drives the smear over the geometry the session
 * edits.
 *
 * The brush is an Edit-mode tool: it shapes the model at rest, whether that is a mesh's vertices or a warp's
 * lattice.  A session in another mode has no rest geometry to smear, so the brush does nothing there.
 *
 * @param String areaId The arming viewport's area id (only its overlay drives the brush).
 */
fun EditorSession.beginBrush(areaId: String) {
	if (mode.value != EditorMode.Edit) {
		return
	}

	latches.armBrush(areaId, brushSettings.value.radiusWorld)
}

/**
 * Sets the deform brush's radius, clamped, and keeps the live brush's radius in step with it, so the
 * overlay draws the brush at the size the rigger asked for.
 *
 * @param Float radiusWorld The requested radius, in world units (canvas px).
 */
fun EditorSession.setBrushRadius(radiusWorld: Float) {
	val clamped = clampBrushRadius(radiusWorld)
	setBrushSettings(brushSettings.value.copy(radiusWorld = clamped))
	latches.setActiveBrushRadius(clamped)
}