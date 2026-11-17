package org.umamo.ui.viewport.gizmo

import org.umamo.edit.EditorSession
import org.umamo.edit.transform.BrushStroke
import org.umamo.edit.transform.setBrushRadius
import org.umamo.runtime.model.DeformerId
import org.umamo.ui.transform.brushLatticeWorld
import org.umamo.ui.transform.brushWarpIds
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
 * @param Function publishPreview Publishes the world points a stroke would give the lattices, or null when
 *   the stroke ends; the caller hands them to the live preview.
 * @return MarqueeSelectController The brush's controller.
 */
internal fun brushMarquee(
	session: EditorSession,
	publishPreview: (Map<DeformerId, FloatArray>?) -> Unit,
): MarqueeSelectController<BrushStroke> =
	MarqueeSelectController(
		seedStroke = { BrushStroke.empty() },
		stampStroke = { stroke, _, center, radiusWorld, stampCamera, stampSize ->
			val (worldX, worldZ) = screenToWorld(center.x, center.y, stampCamera, stampSize)
			stroke.stampedTo(worldX, worldZ, radiusWorld)
		},
		commitStroke = { stroke ->
			val areaId = session.activeBrush.value?.areaId
			commitBrushStroke(session, areaId ?: "", stroke, session.brushSettings.value, brushWarpIds(session))
		},
		// The brush has no rubber band: the controller's box rule belongs to the select tools.
		applyBox = { _, _, _, _, _ -> },
		setCircleRadius = { radiusWorld -> session.setBrushRadius(radiusWorld) },
		clearTool = { session.clearBrush() },
		previewStroke = { stroke ->
			publishPreview(
				stroke?.let { brush ->
					brushLatticeWorld(session.model.value, brush, session.brushSettings.value, brushWarpIds(session))
				},
			)
		},
		setGestureActive = { active -> session.setViewportGestureActive(active) },
	)