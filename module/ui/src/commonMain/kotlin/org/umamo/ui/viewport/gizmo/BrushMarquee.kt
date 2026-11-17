package org.umamo.ui.viewport.gizmo

import org.umamo.edit.EditorSession
import org.umamo.edit.transform.BrushStroke
import org.umamo.edit.transform.setBrushRadius
import org.umamo.ui.transform.BrushPreview
import org.umamo.ui.transform.brushPreview
import org.umamo.ui.transform.commitBrushStroke

/**
 * The deform brush's gesture machinery: the shared marquee controller, over a stroke that is a log of
 * stamps rather than a selection.
 *
 * The controller's radius argument is the brush's own radius in world units - a brush measures the model,
 * not the screen - so the wheel's rule resizes it in world units as well, and the stamps record the size
 * the brush had where it laid them down.
 *
 * @param EditorSession session The session owning the brush latch, the settings, and the model.
 * @param Function publishPreview Publishes what the stroke would do, or null when the stroke ends; the
 *   caller hands it to the live preview.
 * @return MarqueeSelectController The brush's controller.
 */
internal fun brushMarquee(
	session: EditorSession,
	publishPreview: (BrushPreview?) -> Unit,
): MarqueeSelectController<BrushStroke> =
	MarqueeSelectController(
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
		previewStroke = { stroke ->
			publishPreview(
				stroke?.let { brush ->
					brushPreview(session.model.value, brush, session.brushSettings.value, session.meshSelection.value.owners)
				},
			)
		},
		setGestureActive = { active -> session.setViewportGestureActive(active) },
	)