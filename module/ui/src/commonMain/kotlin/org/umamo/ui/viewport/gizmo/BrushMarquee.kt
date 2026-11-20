package org.umamo.ui.viewport.gizmo

import org.umamo.edit.EditorSession
import org.umamo.edit.transform.BrushStroke
import org.umamo.edit.transform.setBrushRadius
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.transform.BrushGeometry
import org.umamo.ui.transform.BrushPreview
import org.umamo.ui.transform.brushPreview
import org.umamo.ui.transform.captureBrushGeometry
import org.umamo.ui.transform.commitBrushStroke

/**
 * The deform brush's gesture machinery: the shared marquee controller, over a stroke that is a log of
 * stamps rather than a selection.
 *
 * The controller's radius argument is the brush's own radius in world units - a brush measures the model,
 * not the screen - so the wheel's rule resizes it in world units as well, and the stamps record the size
 * the brush had where it laid them down.
 *
 * The stroke works on ONE capture of the geometry, taken on the press: a capture bakes the deformer chain,
 * which is too much to repeat on every pointer event of a drag.  Every move publishes the previewed state
 * twice over: into the live preview, and as a model pushed to the renderer, because the renderer draws the
 * art and a previewed rest shape alone does not reach it.
 *
 * @param EditorSession session The session owning the brush latch, the settings, and the model.
 * @param Function publishPreview Publishes what the stroke would do, or null when the stroke ends; the
 *   caller hands it to the live preview.
 * @param Function pushPreview Pushes the model the stroke would leave behind to the renderer.
 * @return MarqueeSelectController The brush's controller.
 */
internal fun brushMarquee(
	session: EditorSession,
	publishPreview: (BrushPreview?) -> Unit,
	pushPreview: (PuppetModel) -> Unit,
): MarqueeSelectController<BrushStroke> {
	// The stroke's capture, taken on the press and kept for the stroke's length.
	var geometry: BrushGeometry? = null

	return MarqueeSelectController(
		seedStroke = { BrushStroke.empty() },
		stampStroke = { stroke, _, center, radiusWorld, stampCamera, stampSize ->
			val (worldX, worldZ) = screenToWorld(center.x, center.y, stampCamera, stampSize)
			stroke.stampedTo(worldX, worldZ, radiusWorld)
		},
		commitStroke = { stroke ->
			commitBrushStroke(session, session.activeBrush.value?.areaId ?: "", stroke, session.brushSettings.value, session.meshSelection.value.owners)
		},
		// The brush has no rubber band: the controller's box rule belongs to the select tools.
		applyBox = { _, _, _, _, _ -> },
		setCircleRadius = { radiusWorld -> session.setBrushRadius(radiusWorld) },
		clearTool = { session.clearBrush() },
		onStrokeBegin = {
			geometry = captureBrushGeometry(session.model.value, session.meshSelection.value.owners)
		},
		previewStroke = { stroke ->
			val captured = geometry
			val preview =
				if (stroke == null || captured == null) {
					null
				} else {
					brushPreview(captured, stroke, session.brushSettings.value)
				}

			publishPreview(preview)
			// The renderer draws the art, so the stroke reaches it as a model: the smeared one while the brush
			// is down, and the committed one once the stroke ends.
			pushPreview(preview?.smearedModel ?: session.model.value)
		},
		setGestureActive = { active -> session.setViewportGestureActive(active) },
	)
}