package org.umamo.ui.viewport

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshSelectMode
import org.umamo.render.ViewportCamera

/**
 * Draws one mesh's wireframe with its selection highlights - the shared gizmo mesh rendering: face
 * fills first (under the wireframe), then edges, then vertex dots in vertex mode and face centroid
 * dots in face mode (the click affordances, like Blender's).  Geometry-source agnostic: the Edit
 * overlay feeds deformer-projected world shapes, and a UV editor feeds raw texture coordinates over
 * the atlas image - both through the same positions-plus-topology arguments.
 *
 * Every element batches by color into one path or point-set, so a mesh draws in a handful of commands
 * regardless of vertex count.  This matters beyond the lambda's own run cost: the window surface
 * replays these recorded draw commands on every frame a sibling live viewport produces, so a per-edge
 * or per-vertex command count would be re-rasterized every frame, not just on an edit.  The single
 * active edge, vertex, or face dot draws on its own so it layers over its batched neighbours.
 *
 * When [objectOverlay] is set (the UV editor's read-only Object-mode preview) the draw is the
 * Blender object-overlay style regardless of [selectMode]: every face filled idle plus the edges, and
 * NO click-affordance dots (neither vertex dots nor face centroid dots) - there is nothing to pick in a
 * read-only preview, so the handles would only add clutter.
 *
 * @param FloatArray positions The interleaved (x, y) vertex positions in world units.
 * @param IntArray indices The triangle vertex indices (three per triangle).
 * @param List<MeshElement.Edge> edges The unique edges to stroke.
 * @param MeshHighlightSets highlight The selection highlights to render.
 * @param MeshSelectMode selectMode The current select mode (gates the dot affordances and fill rules).
 * @param MeshEditColors colors The settings-backed gizmo palette.
 * @param ViewportCamera camera The area camera (world<->screen affine).
 * @param IntSize size The area size in pixels.
 * @param Boolean objectOverlay Draw the read-only object-overlay style (all faces filled, edges, no dots).
 */
internal fun DrawScope.drawMeshWireframe(
	positions: FloatArray,
	indices: IntArray,
	edges: List<MeshElement.Edge>,
	highlight: MeshHighlightSets,
	selectMode: MeshSelectMode,
	colors: MeshEditColors,
	camera: ViewportCamera,
	size: IntSize,
	objectOverlay: Boolean = false,
) {
	val vertexRadius = 3.5.dp.toPx()
	val edgeWidth = 1.dp.toPx()
	val faceDotRadius = 2.5.dp.toPx()
	val vertexCount = positions.size / 2
	val screenPoints =
		Array(vertexCount) { vertexIndex ->
			worldToScreen(positions[vertexIndex * 2], positions[vertexIndex * 2 + 1], camera, size)
		}
	val triangleCount = indices.size / 3

	/**
	 * The three screen corners of a triangle, or null when the triangle ordinal or the mesh indices
	 * outrun the given geometry.  The highlight sets can derive from a newer mesh than the drawn one
	 * (right after a topology edit that appends triangles, before the async frame catches up), so they
	 * can name ordinals this geometry lacks; skipping self-heals next frame.
	 *
	 * @param Int triangleIndex The triangle ordinal.
	 * @return Triple? The three screen corners, or null when out of range.
	 */
	fun triangleScreenCorners(triangleIndex: Int): Triple<Offset, Offset, Offset>? {
		if (triangleIndex < 0 || triangleIndex * 3 + 2 >= indices.size) {
			return null
		}
		val cornerA = indices[triangleIndex * 3]
		val cornerB = indices[triangleIndex * 3 + 1]
		val cornerC = indices[triangleIndex * 3 + 2]
		if (cornerA >= vertexCount || cornerB >= vertexCount || cornerC >= vertexCount) {
			return null
		}
		return Triple(screenPoints[cornerA], screenPoints[cornerB], screenPoints[cornerC])
	}

	/**
	 * Appends one triangle's closed outline to [target]; a no-op for an out-of-range ordinal.  Disjoint
	 * mesh triangles never overlap, so batching many into one path and filling once matches per-face fills.
	 *
	 * @param Path target The path to append to.
	 * @param Int triangleIndex The triangle ordinal.
	 */
	fun addFace(target: Path, triangleIndex: Int) {
		val corners = triangleScreenCorners(triangleIndex) ?: return
		target.moveTo(corners.first.x, corners.first.y)
		target.lineTo(corners.second.x, corners.second.y)
		target.lineTo(corners.third.x, corners.third.y)
		target.close()
	}

	// Face fills first, under the wireframe.  The object overlay fills every face idle (a read-only tint,
	// no selection); otherwise Face mode fills every face (the face is the click target) split by
	// selection, and vertex and edge modes fill only the derived-selected faces.  One path per color.
	val idleFaceFill = Path()
	val selectedFaceFill = Path()
	if (objectOverlay) {
		for (triangleIndex in 0 until triangleCount) {
			addFace(idleFaceFill, triangleIndex)
		}
	} else if (selectMode == MeshSelectMode.Face) {
		for (triangleIndex in 0 until triangleCount) {
			if (triangleIndex in highlight.selectedFaceIndices) {
				addFace(selectedFaceFill, triangleIndex)
			} else {
				addFace(idleFaceFill, triangleIndex)
			}
		}
	} else {
		for (triangleIndex in highlight.selectedFaceIndices) {
			addFace(selectedFaceFill, triangleIndex)
		}
	}
	drawPath(path = idleFaceFill, color = colors.faceIdle)
	drawPath(path = selectedFaceFill, color = colors.faceSelected)

	// Edges, batched by color into one path each; the single active edge draws on top of them.
	val idleEdges = Path()
	val selectedEdges = Path()
	var activeEdgeStart: Offset? = null
	var activeEdgeEnd: Offset? = null
	for (edge in edges) {
		if (edge.endpointLow >= vertexCount || edge.endpointHigh >= vertexCount) {
			continue
		}
		val start = screenPoints[edge.endpointLow]
		val end = screenPoints[edge.endpointHigh]
		when {
			edge == highlight.activeEdge -> {
				activeEdgeStart = start
				activeEdgeEnd = end
			}
			edge in highlight.selectedEdges -> {
				selectedEdges.moveTo(start.x, start.y)
				selectedEdges.lineTo(end.x, end.y)
			}
			else -> {
				idleEdges.moveTo(start.x, start.y)
				idleEdges.lineTo(end.x, end.y)
			}
		}
	}
	val edgeStroke = Stroke(width = edgeWidth)
	drawPath(path = idleEdges, color = colors.edgeIdle, style = edgeStroke)
	drawPath(path = selectedEdges, color = colors.edgeSelected, style = edgeStroke)
	val resolvedActiveEdgeStart = activeEdgeStart
	val resolvedActiveEdgeEnd = activeEdgeEnd
	if (resolvedActiveEdgeStart != null && resolvedActiveEdgeEnd != null) {
		drawLine(color = colors.edgeActive, start = resolvedActiveEdgeStart, end = resolvedActiveEdgeEnd, strokeWidth = edgeWidth)
	}

	// Vertex dots only in vertex mode (Blender hides them in edge / face modes), batched by color; the
	// active vertex draws on top.  The read-only object overlay draws no handles at all.
	if (!objectOverlay && selectMode == MeshSelectMode.Vertex) {
		val idleVertices = ArrayList<Offset>()
		val selectedVertices = ArrayList<Offset>()
		for (vertexIndex in 0 until vertexCount) {
			when {
				vertexIndex == highlight.activeVertexIndex -> Unit
				vertexIndex in highlight.selectedVertexIndices -> selectedVertices.add(screenPoints[vertexIndex])
				else -> idleVertices.add(screenPoints[vertexIndex])
			}
		}
		drawDots(idleVertices, colors.vertexIdle, vertexRadius)
		drawDots(selectedVertices, colors.vertexSelected, vertexRadius)
		val activeVertexIndex = highlight.activeVertexIndex
		if (activeVertexIndex != null && activeVertexIndex < vertexCount) {
			drawCircle(color = colors.vertexActive, radius = vertexRadius, center = screenPoints[activeVertexIndex])
		}
	}

	// Face centroid dots only in face mode - the click affordance, like Blender's face dots.  The
	// alpha in the face colors is meant for fills, so dots render fully opaque.  Batched by color.
	// The read-only object overlay draws no handles at all.
	if (!objectOverlay && selectMode == MeshSelectMode.Face) {
		val idleFaceDots = ArrayList<Offset>()
		val selectedFaceDots = ArrayList<Offset>()
		var activeFaceDot: Offset? = null
		for (triangleIndex in 0 until triangleCount) {
			val corners = triangleScreenCorners(triangleIndex) ?: continue
			val centroid =
				Offset(
					(corners.first.x + corners.second.x + corners.third.x) / 3f,
					(corners.first.y + corners.second.y + corners.third.y) / 3f,
				)
			when {
				triangleIndex == highlight.activeFaceIndex -> activeFaceDot = centroid
				triangleIndex in highlight.selectedFaceIndices -> selectedFaceDots.add(centroid)
				else -> idleFaceDots.add(centroid)
			}
		}
		drawDots(idleFaceDots, colors.faceIdle.copy(alpha = 1f), faceDotRadius)
		drawDots(selectedFaceDots, colors.faceSelected.copy(alpha = 1f), faceDotRadius)
		val resolvedActiveFaceDot = activeFaceDot
		if (resolvedActiveFaceDot != null) {
			drawCircle(color = colors.faceActive.copy(alpha = 1f), radius = faceDotRadius, center = resolvedActiveFaceDot)
		}
	}
}

/**
 * Draws a batch of same-color round dots in one drawPoints command; the round stroke cap gives each
 * point the filled-circle look of an individual drawCircle of the given radius, and an empty batch is
 * a no-op.
 *
 * @param List<Offset> centers The dot centers in screen pixels.
 * @param Color color The dot color.
 * @param Float radius The dot radius in pixels.
 */
private fun DrawScope.drawDots(centers: List<Offset>, color: Color, radius: Float) {
	if (centers.isEmpty()) {
		return
	}
	drawPoints(points = centers, pointMode = PointMode.Points, color = color, strokeWidth = radius * 2f, cap = StrokeCap.Round)
}
