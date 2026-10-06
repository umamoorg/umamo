package org.umamo.editor.desktop.viewport

import org.umamo.render.puppet.PlacementPreview
import org.umamo.runtime.model.PuppetAtlas

/**
 * The placement preview a UV area's frame draws, given the pages the engine has applied: the area's own
 * preview, less its ghost once those pages belong to the atlas the ghost was committed into (decision D20).
 * A committed move's crops stand in at their new spots while the session recomposes the page; the frame
 * that first shows the recomposed pixels is therefore the first without the ghost, so the art is never shown
 * twice and never missing.  The atlases compare by identity, then by equality: an equal atlas has the same
 * placements, so its pages hold the same pixels.
 *
 * Pure, so the engine applies it at render time, where the applied binding is known.
 *
 * @param PlacementPreview? placement The area's published preview, or null for none.
 * @param PuppetAtlas appliedAtlas The atlas the engine's applied pages belong to.
 * @return PlacementPreview? The preview to draw: the same instance while its ghost still stands, a copy
 *   without the ghost once it has landed, or null for none.
 */
internal fun placementToDraw(placement: PlacementPreview?, appliedAtlas: PuppetAtlas): PlacementPreview? {
	val ghostAtlas = placement?.ghostAtlas ?: return placement
	return if (ghostAtlas === appliedAtlas || ghostAtlas == appliedAtlas) {
		placement.withoutGhost()
	} else {
		placement
	}
}