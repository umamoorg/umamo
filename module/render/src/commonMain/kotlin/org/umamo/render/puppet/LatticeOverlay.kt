package org.umamo.render.puppet

import org.umamo.render.FrameOverlays
import org.umamo.render.eval.DeformerWorld
import org.umamo.render.eval.WarpWorld
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.ElementOwner
import org.umamo.runtime.model.WarpLatticeLayout

/**
 * Which color a warp's lattice draws in: the idle color of a lattice shown only because its area shows every
 * deformer, the selected color, or the active color.  The layer draws the roles in this order, so a selected
 * lattice lands over an idle one and the active lattice over both.
 */
enum class LatticeRole {
	Idle,
	Selected,
	Active,
}

/**
 * The warps whose lattices the 2D viewport draws over the art, each in its role, and the sizes it draws them
 * at.  The value names the warps and nothing more: the renderer reads each one's control points from the pose
 * it is drawing, so a lattice follows its art through every pose without a new value, and the clicks that read
 * the same pose land on what is drawn.
 *
 * An [LatticeRole.Idle] lattice draws only in a frame whose [org.umamo.render.FrameOverlays.allLattices] is set;
 * a selected or active one draws in every frame that draws the mesh overlay.
 *
 * @property Map<DeformerId, LatticeRole> roles The warps to draw, each in its role.  A deformer that is not a
 *   warp, or that the pose hides, is left out.
 * @property MeshOverlaySizes sizes The control-point dot radius and the grid line width, in display pixels.
 * @property Set<DeformerId> pendingIds The warps holding a Pose-mode lattice not yet keyed, drawn in the palette's
 *   pending color whatever their role.
 */
class LatticeOverlay(
	val roles: Map<DeformerId, LatticeRole>,
	val sizes: MeshOverlaySizes,
	val pendingIds: Set<DeformerId> = emptySet(),
)

/**
 * One warp's lattice as the current pose draws it: its layout and its control points in world space, x right
 * and z up, the space the 2D viewport projects and picks in.  Immutable; a pose that leaves a lattice where it
 * was keeps the same instance, so whatever compares it by identity (the GPU upload) does nothing.
 *
 * @property WarpLatticeLayout layout The lattice's points, grid lines, and cells.
 * @property FloatArray world The control points, interleaved x, z, in the layout's point order.
 */
class LatticePoints(
	val layout: WarpLatticeLayout,
	val world: FloatArray,
)

/** The triangle list every lattice carries in the layer: none, since a lattice never fills and never draws a diagonal. */
private val NO_TRIANGLES = IntArray(0)

/**
 * Every warp's lattice the pose built, in the model's deformer order, from the pose's baked worlds: each warp's
 * world control points ([WarpWorld.worldControlPoints]).  A warp the pose hides is absent, as it is from the bake.
 *
 * Arrays carry over from [previous] wherever they still hold: a lattice whose points did not move keeps its
 * instance, and one whose size did not change keeps its layout, so the GPU upload and buffers that compare them by
 * identity are left alone.
 *
 * @param List<Deformer> deformers The model's deformers, in model order.
 * @param Map<DeformerId, DeformerWorld> worlds The pose's baked deformer worlds.
 * @param Map<DeformerId, LatticePoints> previous The lattices the previous pose produced, or empty.
 * @return Map<DeformerId, LatticePoints> The lattices, in model order.
 */
internal fun latticePointsOf(
	deformers: List<Deformer>,
	worlds: Map<DeformerId, DeformerWorld>,
	previous: Map<DeformerId, LatticePoints>,
): Map<DeformerId, LatticePoints> {
	val lattices = LinkedHashMap<DeformerId, LatticePoints>()

	for (deformer in deformers) {
		if (deformer !is Deformer.Warp) {
			continue
		}

		val warpWorld = worlds[deformer.id] as? WarpWorld ?: continue
		val earlier = previous[deformer.id]
		val layout =
			earlier?.layout?.takeIf { held -> held.rows == warpWorld.rows && held.columns == warpWorld.cols }
				?: WarpLatticeLayout(warpWorld.rows, warpWorld.cols)

		if (warpWorld.cp.size != layout.pointCount * 2) {
			continue
		}

		val world = warpWorld.worldControlPoints()

		val unmoved = earlier != null && earlier.layout === layout && earlier.world.contentEquals(world)
		lattices[deformer.id] = if (unmoved) earlier else LatticePoints(layout, world)
	}

	return lattices
}

/**
 * The lattice layer the renderer draws: a direct overlay of kind [MeshOverlayKind.Lattice] holding each warp
 * [overlay] lists that [lattices] holds, in model order, each styled with its role and pending mark
 * ([MeshStyle.Lattice]), with its grid lines as edges and no triangles.  The encoder draws them role by role, so a
 * stronger role lands over a weaker one.  Null when none of them is drawn.
 *
 * [previous] is handed back whole when nothing in it changed, and each mesh value in it is reused while its style
 * and layout hold, so a frame over an unchanged pose and selection uploads nothing.
 *
 * @param LatticeOverlay overlay The warps to draw and their roles.
 * @param Map<DeformerId, LatticePoints> lattices The current pose's lattices ([latticePointsOf]), in model order.
 * @param DirectMeshOverlay? previous The layer the previous call produced, or null.
 * @return DirectMeshOverlay? The layer, or null when no listed warp is drawn.
 */
internal fun latticeLayerOf(
	overlay: LatticeOverlay,
	lattices: Map<DeformerId, LatticePoints>,
	previous: DirectMeshOverlay?,
): DirectMeshOverlay? {
	val previousByOwner = previous?.overlay?.meshes?.associateBy { mesh -> mesh.owner } ?: emptyMap()
	val meshes = ArrayList<MeshOverlayMesh>()
	val positionsByOwner = HashMap<ElementOwner, FloatArray>()
	val trianglesByOwner = HashMap<ElementOwner, IntArray>()

	for ((deformerId, lattice) in lattices) {
		val role = overlay.roles[deformerId] ?: continue
		val owner = ElementOwner.Deformer(deformerId)
		val held = previousByOwner[owner]
		val style = MeshStyle.Lattice(role, deformerId in overlay.pendingIds)
		val mesh =
			if (held != null && held.style == style && held.edgeEndpoints === lattice.layout.edgeEndpoints) {
				held
			} else {
				MeshOverlayMesh(
					owner = owner,
					vertexCount = lattice.layout.pointCount,
					edgeEndpoints = lattice.layout.edgeEndpoints,
					vertexFlags = ByteArray(0),
					edgeFlags = ByteArray(0),
					faceFlags = ByteArray(0),
					activeVertex = null,
					activeEdge = null,
					activeFace = null,
					style = style,
				)
			}
		meshes.add(mesh)
		positionsByOwner[owner] = lattice.world
		trianglesByOwner[owner] = NO_TRIANGLES
	}

	if (meshes.isEmpty()) {
		return null
	}

	if (previous != null && previous.overlay.sizes == overlay.sizes && sameLayer(previous, meshes, positionsByOwner)) {
		return previous
	}

	return DirectMeshOverlay(
		MeshOverlay(MeshOverlayKind.Lattice, MeshOverlaySelectMode.Vertex, meshes, overlay.sizes),
		positionsByOwner,
		trianglesByOwner,
	)
}

/**
 * Whether [previous] already holds exactly these meshes, in this order, over these position arrays.
 *
 * @param DirectMeshOverlay previous The earlier layer.
 * @param List<MeshOverlayMesh> meshes The meshes the layer would hold now.
 * @param Map<ElementOwner, FloatArray> positionsByOwner Their positions.
 * @return Boolean True when every mesh and array is the same instance.
 */
private fun sameLayer(previous: DirectMeshOverlay, meshes: List<MeshOverlayMesh>, positionsByOwner: Map<ElementOwner, FloatArray>): Boolean {
	val previousMeshes = previous.overlay.meshes

	if (previousMeshes.size != meshes.size) {
		return false
	}

	for (meshIndex in meshes.indices) {
		val mesh = meshes[meshIndex]

		if (previousMeshes[meshIndex] !== mesh || previous.positionsByOwner[mesh.owner] !== positionsByOwner[mesh.owner]) {
			return false
		}
	}

	return true
}

/** Every lattice role, what a frame showing every deformer draws. */
internal val EVERY_LATTICE_ROLE: Set<LatticeRole> = LatticeRole.entries.toSet()

/** The roles a frame that shows only the picked deformers draws. */
private val PICKED_LATTICE_ROLES: Set<LatticeRole> = setOf(LatticeRole.Selected, LatticeRole.Active)

/**
 * The lattice roles a frame drawing with [overlays] draws: none when the frame hides the mesh overlay, every role
 * when it shows every deformer, and only the selected and active ones otherwise.  The one rule both the renderer's
 * lattice draws and the picks over them ([latticesDrawnIn]) read, so a pick never lands on a lattice the area does
 * not draw.
 *
 * @param FrameOverlays overlays The frame's overlay options.
 * @return Set<LatticeRole> The drawn roles.
 */
fun latticeRolesDrawnIn(overlays: FrameOverlays): Set<LatticeRole> =
	when {
		!overlays.meshOverlay -> emptySet()
		overlays.allLattices -> EVERY_LATTICE_ROLE
		else -> PICKED_LATTICE_ROLES
	}

/**
 * The lattices a frame drawing with [overlays] shows of [lattices]: each listed warp whose role the frame draws
 * ([latticeRolesDrawnIn]).  What a click in that frame can pick.
 *
 * @param LatticeOverlay? overlay The lattices the renderer holds, or null.
 * @param Map<DeformerId, LatticePoints> lattices The current pose's lattices, in model order.
 * @param FrameOverlays overlays The frame's overlay options.
 * @return Map<DeformerId, LatticePoints> The drawn lattices, in model order.
 */
fun latticesDrawnIn(
	overlay: LatticeOverlay?,
	lattices: Map<DeformerId, LatticePoints>,
	overlays: FrameOverlays,
): Map<DeformerId, LatticePoints> {
	val roles = latticeRolesDrawnIn(overlays)

	if (overlay == null || roles.isEmpty()) {
		return emptyMap()
	}

	return lattices.filter { (deformerId, _) -> overlay.roles[deformerId] in roles }
}