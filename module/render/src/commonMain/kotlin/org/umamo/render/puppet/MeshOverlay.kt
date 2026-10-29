package org.umamo.render.puppet

import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ElementOwner

/** The flag of a primitive nobody selected: drawn in the domain's idle color. */
const val OVERLAY_FLAG_IDLE: Byte = 0

/** The flag of a selected primitive: drawn in the domain's selected color. */
const val OVERLAY_FLAG_SELECTED: Byte = 1

/** The flag of the one active primitive of a domain: drawn last, in the domain's active color. */
const val OVERLAY_FLAG_ACTIVE: Byte = 2

/**
 * What a mesh overlay is for, which decides what it draws: the Edit-mode wireframe with its per-element
 * flags; the plain object wireframe of every listed mesh's edges in the idle color, with no fills and no
 * dots; a UV area's Object-mode islands, each mesh filled and outlined whole in the colors of its own
 * [IslandStyle], island by island in the overlay's order (back to front), with no dots; or the 2D viewport's
 * warp lattices, each lattice's grid lines and control-point dots in the color of its own [LatticeRole], with
 * no fills.
 */
enum class MeshOverlayKind {
	Edit,
	ObjectWireframe,
	Islands,
	Lattice,
}

/**
 * Whether a mesh in an overlay of this kind draws as the wireframe, the part of the overlay a frame's
 * wireframe opacity fades and its culling leaves out under the art in front: every mesh of an object
 * wireframe, and an Edit overlay's plain meshes outside the edit.  The Edit cage never does; the UV
 * islands fade on a path of their own and never cull.
 *
 * @param Boolean wireframeOnly The mesh's [MeshOverlayMesh.wireframeOnly] flag, read for an Edit overlay alone.
 * @return Boolean True when the mesh draws as the wireframe.
 */
internal fun MeshOverlayKind.drawsAsWireframe(wireframeOnly: Boolean): Boolean =
	this == MeshOverlayKind.ObjectWireframe || (this == MeshOverlayKind.Edit && wireframeOnly)

/** Which face color an island fills with: the idle fill, or the selected fill (a selected or active island). */
enum class IslandFillRole {
	Idle,
	Selected,
}

/**
 * Which color an island outlines in: the idle, selected, or active edge color, the warning color of a tile
 * in a placement collision, or the pinned color of a pinned tile.
 */
enum class IslandEdgeRole {
	Idle,
	Selected,
	Active,
	Warning,
	Pinned,
}

/**
 * One island's colors, as roles the renderer resolves against the palette, so a palette change re-colors
 * every island without a new overlay.
 *
 * @property IslandFillRole fill The fill role.
 * @property IslandEdgeRole edge The outline role.
 */
data class IslandStyle(
	val fill: IslandFillRole,
	val edge: IslandEdgeRole,
)

/**
 * The Edit-mode select mode the overlay mirrors, which decides the dot kind and the fill rule: vertex
 * dots in Vertex mode, face-centroid dots and every face filled in Face mode, neither in Edge mode.
 */
enum class MeshOverlaySelectMode {
	Vertex,
	Edge,
	Face,
}

/**
 * The overlay's sizes in DISPLAY pixels; the renderer multiplies by the render scale at draw time, the
 * way the grid line width already does, so a supersampled frame keeps the same on-screen size.
 *
 * @property Float vertexDotRadiusPx The vertex dot radius.
 * @property Float edgeWidthPx The edge line width.
 * @property Float faceDotRadiusPx The face-centroid dot radius.
 */
data class MeshOverlaySizes(
	val vertexDotRadiusPx: Float,
	val edgeWidthPx: Float,
	val faceDotRadiusPx: Float,
)

/**
 * One mesh's share of an overlay: its unique edges and the flags of its vertices, edges, and faces, all
 * in the mesh's OWN indices.  The renderer derives nothing from the selection; the producer ships these
 * as data, derived with the edit module's rules, so the two cannot drift.
 *
 * The value pairs with the model it was built against through [vertexCount] (and the face flag count,
 * in an Edit overlay): a mesh whose resident disagrees is skipped for that frame and self-heals on the next
 * publish, rather than drawn against ordinals a newer highlight set named.  A UV scene's overlay pairs
 * each mesh with its own positions and indices instead ([DirectMeshOverlay]), under the same rule.  A producer
 * that keeps a flag array's INSTANCE across publishes avoids a re-upload, since the renderer compares
 * the arrays by identity.
 *
 * A glue mesh draws at its pre-weld positions, the cage Edit mode edits and every pick and transform
 * reads: the weld happens in the art's glue draw, and the overlay reads the store its own capture wrote.
 * So a seam vertex's dot sits up to its weld displacement from the art's seam, most of all on a vertex the
 * weld pulls fully onto its partner.
 *
 * @property ElementOwner owner The mesh's owner: a drawable, whose resident a 2D overlay pairs with, or - in a
 *   direct overlay alone - a deformer's lattice.
 * @property Int vertexCount The vertex count the data was built against.
 * @property IntArray edgeEndpoints Two local vertex indices per unique edge, low index first, in the
 *   edit module's first-encounter order.
 * @property ByteArray vertexFlags One flag per vertex, or empty when nothing is flagged (the object
 *   wireframe, the islands).
 * @property ByteArray edgeFlags One flag per edge, parallel to [edgeEndpoints], or empty.
 * @property ByteArray faceFlags One flag per triangle of the mesh, in triangle order, or empty.
 * @property Int? activeVertex The active vertex, or null; also flagged active in [vertexFlags].
 * @property Int? activeEdge The active edge's ordinal in [edgeEndpoints], or null.
 * @property Int? activeFace The active triangle's ordinal, or null.
 * @property IslandStyle? islandStyle The island's colors in an [MeshOverlayKind.Islands] overlay; null
 *   there reads as idle, and every other kind ignores it.
 * @property Boolean wireframeOnly In an [MeshOverlayKind.Edit] overlay, a mesh outside the edit drawn as a
 *   plain wireframe: its edges in the idle color, no fills and no dots, ahead of the cage's edges so the
 *   cage stays on top, and only in a frame whose wireframe flag is on.  The other kinds ignore it.
 * @property LatticeRole? latticeRole The lattice's color in a [MeshOverlayKind.Lattice] overlay; null there
 *   reads as idle, and every other kind ignores it.
 * @property IntArray? faceDotCorners Three vertex indices per triangle whose centroid is where that triangle's face
 *   dot draws, parallel to the mesh's triangle list, in a direct overlay; null draws each dot at its own triangle's
 *   centroid.  A lattice's Edit cage points both triangles of a cell at the cell's appended center vertex, so the
 *   cell, a quad, shows one dot where its center is.
 * @property Int vertexDotCount How many of the vertices, from the first, are the mesh's own and draw a vertex dot;
 *   the vertices after them are face-dot anchors only.  Every vertex by default.
 */
class MeshOverlayMesh(
	val owner: ElementOwner,
	val vertexCount: Int,
	val edgeEndpoints: IntArray,
	val vertexFlags: ByteArray,
	val edgeFlags: ByteArray,
	val faceFlags: ByteArray,
	val activeVertex: Int?,
	val activeEdge: Int?,
	val activeFace: Int?,
	val islandStyle: IslandStyle? = null,
	val wireframeOnly: Boolean = false,
	val latticeRole: LatticeRole? = null,
	val faceDotCorners: IntArray? = null,
	val vertexDotCount: Int = vertexCount,
) {
	/** How many unique edges the mesh carries. */
	val edgeCount: Int get() = edgeEndpoints.size / 2

	init {
		require(edgeEndpoints.size % 2 == 0) { "edge endpoints come in pairs, got ${edgeEndpoints.size} for $owner" }

		for (endpoint in edgeEndpoints) {
			require(endpoint in 0 until vertexCount) { "edge endpoint $endpoint is outside the $vertexCount vertices of $owner" }
		}

		require(vertexFlags.isEmpty() || vertexFlags.size == vertexCount) { "vertex flags must be empty or one per vertex for $owner" }
		require(edgeFlags.isEmpty() || edgeFlags.size == edgeCount) { "edge flags must be empty or one per edge for $owner" }
		require(activeVertex == null || activeVertex in 0 until vertexCount) { "active vertex $activeVertex is outside $owner" }
		require(activeEdge == null || activeEdge in 0 until edgeCount) { "active edge $activeEdge is outside $owner" }
		require(activeFace == null || activeFace in faceFlags.indices) { "active face $activeFace is outside the flagged faces of $owner" }
		require(vertexDotCount in 0..vertexCount) { "$vertexDotCount vertex dots are more than the $vertexCount vertices of $owner" }
		require(faceDotCorners == null || faceDotCorners.all { corner -> corner in 0 until vertexCount }) { "a face dot corner is outside the vertices of $owner" }
	}
}

/**
 * The drawable an overlay mesh pairs with when the 2D overlay reads its positions from a resident drawable, or null
 * for a lattice, which has no resident and so only a direct overlay (its own positions) can carry.
 */
internal val MeshOverlayMesh.pairedDrawableId: DrawableId? get() = (owner as? ElementOwner.Drawable)?.id

/**
 * The mesh overlay the renderer draws over the art: the Edit-mode wireframe, dots, and face fills (with
 * the plain wireframe of the meshes outside the edit, when the producer lists them), the object wireframe,
 * a UV area's Object-mode islands, or the 2D viewport's warp lattices (a layer the renderer builds itself,
 * from a [LatticeOverlay]).  Immutable; the producer builds a new value when the selection, the
 * mode, the shown meshes, or the session's meshes change, never when a preview push moves positions.
 *
 * The mesh ORDER defines the overlay's position-store layout, the way the glue layout's walk order does.
 *
 * @property MeshOverlayKind kind What the overlay draws.
 * @property MeshOverlaySelectMode selectMode The select mode mirrored (read for [MeshOverlayKind.Edit]).
 * @property List<MeshOverlayMesh> meshes The meshes shown, in layout order.
 * @property MeshOverlaySizes sizes The dot and line sizes in display pixels.
 */
class MeshOverlay(
	val kind: MeshOverlayKind,
	val selectMode: MeshOverlaySelectMode,
	val meshes: List<MeshOverlayMesh>,
	val sizes: MeshOverlaySizes,
) {
	/** Whether any of the meshes draws as the wireframe ([drawsAsWireframe]), decided once per overlay. */
	internal val carriesWireframe: Boolean = meshes.any { mesh -> kind.drawsAsWireframe(mesh.wireframeOnly) }
}