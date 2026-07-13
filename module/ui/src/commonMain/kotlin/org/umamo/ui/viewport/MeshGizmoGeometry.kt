package org.umamo.ui.viewport

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.ActiveMeshElement
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshSelectMode
import org.umamo.edit.MeshSelection
import org.umamo.edit.MeshSelectionOps
import org.umamo.edit.MeshTopology
import org.umamo.render.ViewportCamera
import org.umamo.render.pick.distanceToSegment
import org.umamo.runtime.model.DrawableId

/**
 * One mesh's geometry as the gizmo machinery sees it: positions plus topology, agnostic of WHERE the
 * positions came from.  The Edit overlay feeds deformer-projected world shapes; a UV editor feeds raw
 * texture coordinates mapped into its own space.  The shared element queries below and the wireframe
 * draw take this record, so a new editor space reuses them by constructing one - never by
 * re-implementing the hit / box / circle rules.
 *
 * @property DrawableId drawableId The drawable the mesh belongs to (the selection key).
 * @property IntArray indices The triangle vertex indices (three per triangle).
 * @property List<MeshElement.Edge> edges The unique edges, in first-encounter order.
 * @property FloatArray positions The interleaved (x, y) vertex positions in the hosting space's
 *   world units (what the area camera projects).
 */
internal class GizmoMeshGeometry(
	val drawableId: DrawableId,
	val indices: IntArray,
	val edges: List<MeshElement.Edge>,
	val positions: FloatArray,
)

/**
 * The nearest element of the given domain under the pointer across every mesh, or null.  Each mesh's
 * own hit test already picks its nearest candidate; this compares the survivors across meshes by the
 * same screen-space metric each domain uses (vertex distance, point-to-segment distance, face
 * centroid distance), so overlapping meshes resolve to the visually closest element.
 *
 * @param MeshSelectMode selectMode The element domain to pick in.
 * @param List<GizmoMeshGeometry> geometries The meshes' gizmo geometry.
 * @param Offset pointer The cursor in screen pixels.
 * @param ViewportCamera camera The area camera.
 * @param IntSize size The area size in pixels.
 * @return ActiveMeshElement? The hit element with its mesh, or null.
 */
internal fun hitTestMeshes(
	selectMode: MeshSelectMode,
	geometries: List<GizmoMeshGeometry>,
	pointer: Offset,
	camera: ViewportCamera,
	size: IntSize,
): ActiveMeshElement? {
	var best: ActiveMeshElement? = null
	var bestMetric = Float.MAX_VALUE
	for (geometry in geometries) {
		when (selectMode) {
			MeshSelectMode.Vertex -> {
				val vertexIndex = hitTestVertex(geometry.positions, pointer, camera, size) ?: continue
				val screen = worldToScreen(geometry.positions[vertexIndex * 2], geometry.positions[vertexIndex * 2 + 1], camera, size)
				val metric = (screen - pointer).getDistance()
				if (metric < bestMetric) {
					bestMetric = metric
					best = ActiveMeshElement(geometry.drawableId, MeshElement.Vertex(vertexIndex))
				}
			}

			MeshSelectMode.Edge -> {
				val edge = hitTestEdge(geometry.positions, geometry.edges, pointer, camera, size) ?: continue
				val start = worldToScreen(geometry.positions[edge.endpointLow * 2], geometry.positions[edge.endpointLow * 2 + 1], camera, size)
				val end = worldToScreen(geometry.positions[edge.endpointHigh * 2], geometry.positions[edge.endpointHigh * 2 + 1], camera, size)
				val metric = distanceToSegment(pointer.x, pointer.y, start.x, start.y, end.x, end.y)
				if (metric < bestMetric) {
					bestMetric = metric
					best = ActiveMeshElement(geometry.drawableId, edge)
				}
			}

			MeshSelectMode.Face -> {
				val face = hitTestFace(geometry.positions, geometry.indices, pointer, camera, size) ?: continue
				val cornerA = geometry.indices[face.triangleIndex * 3]
				val cornerB = geometry.indices[face.triangleIndex * 3 + 1]
				val cornerC = geometry.indices[face.triangleIndex * 3 + 2]
				val screenA = worldToScreen(geometry.positions[cornerA * 2], geometry.positions[cornerA * 2 + 1], camera, size)
				val screenB = worldToScreen(geometry.positions[cornerB * 2], geometry.positions[cornerB * 2 + 1], camera, size)
				val screenC = worldToScreen(geometry.positions[cornerC * 2], geometry.positions[cornerC * 2 + 1], camera, size)
				val centroid = Offset((screenA.x + screenB.x + screenC.x) / 3f, (screenA.y + screenB.y + screenC.y) / 3f)
				val metric = (centroid - pointer).getDistance()
				if (metric < bestMetric) {
					bestMetric = metric
					best = ActiveMeshElement(geometry.drawableId, face)
				}
			}
		}
	}
	return best
}

/**
 * The elements of one mesh enclosed by a rubber-band box, in the given domain (vertex inside, edge
 * both-endpoints, face centroid - the shared Blender rules).
 *
 * @param MeshSelectMode selectMode The element domain to enclose.
 * @param GizmoMeshGeometry geometry The mesh's gizmo geometry.
 * @param Offset cornerA One box corner in screen pixels.
 * @param Offset cornerB The opposite box corner.
 * @param ViewportCamera camera The area camera.
 * @param IntSize size The area size in pixels.
 * @return Set<MeshElement> The enclosed elements.
 */
internal fun elementsInBox(
	selectMode: MeshSelectMode,
	geometry: GizmoMeshGeometry,
	cornerA: Offset,
	cornerB: Offset,
	camera: ViewportCamera,
	size: IntSize,
): Set<MeshElement> =
	when (selectMode) {
		MeshSelectMode.Vertex ->
			verticesInBox(geometry.positions, cornerA, cornerB, camera, size)
				.map { vertexIndex -> MeshElement.Vertex(vertexIndex) }
				.toSet<MeshElement>()

		MeshSelectMode.Edge -> edgesInBox(geometry.positions, geometry.edges, cornerA, cornerB, camera, size)

		MeshSelectMode.Face -> facesInBox(geometry.positions, geometry.indices, cornerA, cornerB, camera, size)
	}

/**
 * Computes the Circle-select result for one brush stamp: enclose the elements of [working]'s select mode
 * within [radiusPx] of [center] (screen space) on EVERY mesh, and either add them (a paint stroke) or
 * remove them (an erase stroke).
 *
 * @param MeshSelection working The stroke's accumulating selection (seeded from the committed selection).
 * @param Boolean erasing True to remove the enclosed elements, false to add them.
 * @param Offset center The brush centre in screen pixels.
 * @param Float radiusPx The brush radius in screen pixels.
 * @param List<GizmoMeshGeometry> geometries The meshes' gizmo geometry.
 * @param ViewportCamera camera The area camera.
 * @param IntSize size The area size in pixels.
 * @return MeshSelection The updated working selection.
 */
internal fun circleSelection(
	working: MeshSelection,
	erasing: Boolean,
	center: Offset,
	radiusPx: Float,
	geometries: List<GizmoMeshGeometry>,
	camera: ViewportCamera,
	size: IntSize,
): MeshSelection {
	val insideByDrawable =
		geometries.associate { geometry ->
			val inside: Set<MeshElement> =
				when (working.selectMode) {
					MeshSelectMode.Vertex ->
						verticesInCircle(geometry.positions, center, radiusPx, camera, size)
							.map { vertexIndex -> MeshElement.Vertex(vertexIndex) }
							.toSet<MeshElement>()

					MeshSelectMode.Edge -> edgesInCircle(geometry.positions, geometry.edges, center, radiusPx, camera, size)

					MeshSelectMode.Face -> facesInCircle(geometry.positions, geometry.indices, center, radiusPx, camera, size)
				}
			geometry.drawableId to inside
		}
	return if (erasing) {
		MeshSelectionOps.remove(working, insideByDrawable)
	} else {
		MeshSelectionOps.box(working, insideByDrawable, additive = true)
	}
}

/**
 * The per-domain highlight state the wireframe draw renders: the stored selection projected onto
 * vertices, edges, and faces. Only the current select mode's domain comes straight from the selection;
 * the others are derived with Blender's display rules (an edge from both endpoints, a face from all of
 * its own vertices or edges, a face's edges flushing down in face mode).
 *
 * @property Set<Int> selectedVertexIndices Vertices drawn selected (vertex mode only).
 * @property Set<MeshElement.Edge> selectedEdges Edges drawn selected (stored or derived).
 * @property Set<Int> selectedFaceIndices Triangle ordinals drawn filled (stored or derived).
 * @property Int? activeVertexIndex The active vertex (vertex mode), or null.
 * @property MeshElement.Edge? activeEdge The active edge (edge mode), or null.
 * @property Int? activeFaceIndex The active face's triangle ordinal (face mode), or null.
 */
internal class MeshHighlightSets(
	val selectedVertexIndices: Set<Int>,
	val selectedEdges: Set<MeshElement.Edge>,
	val selectedFaceIndices: Set<Int>,
	val activeVertexIndex: Int?,
	val activeEdge: MeshElement.Edge?,
	val activeFaceIndex: Int?,
)

/**
 * Projects the stored selection into per-domain highlight sets for the wireframe draw, applying
 * Blender's derive-up rules (vertex mode lights edges with both endpoints selected and faces with all
 * vertices selected; edge mode lights faces with all three own edges selected) and flush-down display
 * (face mode lights the selected faces' own edges).
 *
 * @param Set<MeshElement> elements One mesh's stored elements (the current select mode's domain).
 * @param MeshElement? active The active element when it lives on this mesh, or null.
 * @param MeshSelectMode selectMode The current select mode.
 * @param IntArray triangleIndices The mesh triangle vertex indices (three per triangle).
 * @return MeshHighlightSets The projected highlight state for this mesh.
 */
internal fun buildHighlightSets(
	elements: Set<MeshElement>,
	active: MeshElement?,
	selectMode: MeshSelectMode,
	triangleIndices: IntArray,
): MeshHighlightSets =
	when (selectMode) {
		MeshSelectMode.Vertex -> {
			val vertexIndices = elements.filterIsInstance<MeshElement.Vertex>().map { it.index }.toSet()
			MeshHighlightSets(
				selectedVertexIndices = vertexIndices,
				selectedEdges = MeshTopology.edgesWithBothEndpointsSelected(triangleIndices, vertexIndices),
				selectedFaceIndices =
					MeshTopology.facesWithAllVerticesSelected(triangleIndices, vertexIndices)
						.map { it.triangleIndex }
						.toSet(),
				activeVertexIndex = (active as? MeshElement.Vertex)?.index,
				activeEdge = null,
				activeFaceIndex = null,
			)
		}

		MeshSelectMode.Edge -> {
			val selectedEdges = elements.filterIsInstance<MeshElement.Edge>().toSet()
			MeshHighlightSets(
				selectedVertexIndices = emptySet(),
				selectedEdges = selectedEdges,
				selectedFaceIndices =
					MeshTopology.facesWithAllEdgesSelected(triangleIndices, selectedEdges)
						.map { it.triangleIndex }
						.toSet(),
				activeVertexIndex = null,
				activeEdge = active as? MeshElement.Edge,
				activeFaceIndex = null,
			)
		}

		MeshSelectMode.Face -> {
			val faceIndices = elements.filterIsInstance<MeshElement.Face>().map { it.triangleIndex }.toSet()
			MeshHighlightSets(
				selectedVertexIndices = emptySet(),
				selectedEdges = faceIndices.flatMap { faceIndex -> MeshTopology.edgesOfTriangle(triangleIndices, faceIndex) }.toSet(),
				selectedFaceIndices = faceIndices,
				activeVertexIndex = null,
				activeEdge = null,
				activeFaceIndex = (active as? MeshElement.Face)?.triangleIndex,
			)
		}
	}
