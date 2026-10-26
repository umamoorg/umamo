package org.umamo.edit.mesh

import org.umamo.edit.EditorSession
import org.umamo.edit.MeshChange
import org.umamo.edit.MeshSelectionOps
import org.umamo.geometry.locate.TriangleLocator
import org.umamo.geometry.mesh.PlanarTriangleMesh
import org.umamo.geometry.predicate.orient2d
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.applyUvAffine
import org.umamo.runtime.model.invertUvAffine
import org.umamo.runtime.model.storedToArtAffineForTile

/*
 * Replacing a drawable's mesh with one generated from its art, keeping the rig built on the old one.
 *
 * The generated mesh arrives in the tile's art pixels.  The old mesh is laid out in the same pixels
 * (its UVs mapped into the art frame), and every new vertex is placed by where it falls there - art
 * coordinates, because they are what both meshes share and what moving a drawable at rest or in
 * Object mode leaves alone.  One set of weights then builds everything the vertex carries:
 *
 *   - A PINNED vertex (an old vertex some glue pair names and some triangle uses, handed to the mesher
 *     as a fixed point together with the old triangles around it, which the mesher counts as art so the
 *     pin stays joined to the surface) is that old vertex: it copies its geometry, deltas, and UV, and
 *     keeps its glue pairs.
 *   - A CARRIED vertex (an old vertex some glue pair names but no triangle uses - a split mesh keeps
 *     these so its glue can pull the piece that does draw there) is not part of the surface, so it is
 *     never meshed around: it follows the generated vertices, still used by no triangle, as that old
 *     vertex with its glue pairs.
 *   - A vertex exactly on an old vertex copies it the same way but claims no glue.
 *   - A vertex inside the old mesh blends the corners of the triangle holding it: canvas position,
 *     keyform-space base, every keyform cell's deltas, and every blend shape's deltas alike.
 *   - A vertex outside takes its deltas from the nearest boundary point, so no motion is ever
 *     amplified, and continues the geometry past that point with the boundary's own linear map
 *     (BoundaryExtrapolator).
 *
 * New UVs are the art coordinates lowered back to the stored frame.  The triangles keep the old mesh's
 * winding in the art frame, so a mirrored drawable keeps the side it faces.
 */

/** Why a drawable's mesh cannot be generated from its art. */
enum class RegenerationRefusal {
	/** The drawable is missing or has no mesh. */
	NoMesh,

	/** The drawable samples no atlas tile, so there is no art to mesh. */
	NoTile,

	/** The tile has no usable art frame: a zero size, or a stored-to-art mapping that cannot be formed or inverted. */
	NoArtFrame,

	/** The old mesh cannot place new vertices: malformed arrays, no triangle with area in the art frame, or (only when it must extrapolate) under a pixel across. */
	DegenerateOldMesh,

	/** The generated mesh does not fit the input it was made for: the model changed since, or a pin moved or went missing. */
	PinMismatch,

	/**
	 * Glue holds more than half the vertices of the drawable's surface (the split-mesh pieces and line art
	 * welded to its fill), not just a seam: the generated vertices between the glued ones would carry no
	 * glue, so the drawable would no longer move with what it is glued to as it does.
	 */
	GluedThroughout,
}

/** What generating one drawable's mesh needs before any art is meshed, or why it cannot be done. */
sealed interface RegenerationInput {
	/**
	 * Everything the mesher needs: the tile whose raster to mesh (at exactly the tile's size), the
	 * points it must keep as vertices, and the old mesh laid out in the same pixels for the guide that
	 * keeps the rig.  A plain class: it holds arrays.
	 *
	 * @property DrawableId  drawableId            The drawable.
	 * @property AtlasTileId tileId                The tile whose art to mesh.
	 * @property Int         tileWidth             The tile's width in art pixels.
	 * @property Int         tileHeight            The tile's height in art pixels.
	 * @property DoubleArray pinPoints             The pins, x0, y0, ..., in the tile's art pixels.
	 * @property IntArray    pinnedOldVertices     The old vertex each pin stands for, ascending.
	 * @property IntArray    carriedOldVertices    The glued old vertices no triangle uses, ascending: carried over after the generated vertices, never meshed.
	 * @property DoubleArray pinSurroundings       The old triangles that use a pin, six coordinates each, in the tile's art pixels: the mesher counts them as art.
	 * @property FloatArray  oldVertexPoints       Every old vertex in the tile's art pixels, x0, y0, ...: what the pins were read from, and what a guide is laid over.
	 * @property IntArray    referencedOldVertices The old vertices some triangle uses, ascending.
	 */
	class Ready(
		val drawableId: DrawableId,
		val tileId: AtlasTileId,
		val tileWidth: Int,
		val tileHeight: Int,
		val pinPoints: DoubleArray,
		val pinnedOldVertices: IntArray,
		val carriedOldVertices: IntArray,
		val pinSurroundings: DoubleArray,
		val oldVertexPoints: FloatArray,
		val referencedOldVertices: IntArray,
	) : RegenerationInput

	/**
	 * The drawable cannot be regenerated.
	 *
	 * @property RegenerationRefusal refusal Why.
	 */
	data class Refused(val refusal: RegenerationRefusal) : RegenerationInput
}

/** The outcome of carrying a drawable's rig onto a generated mesh. */
sealed interface MeshRegeneration {
	/**
	 * The topology edit that replaces the mesh, ready to commit.  A plain class: it holds an array.
	 *
	 * @property MeshTopologyEdit edit                 The edit.
	 * @property IntArray         extrapolatedVertices The new vertices placed outside the old mesh, ascending.
	 */
	class Built(val edit: MeshTopologyEdit, val extrapolatedVertices: IntArray) : MeshRegeneration

	/**
	 * The rig could not be carried over.
	 *
	 * @property RegenerationRefusal refusal Why.
	 */
	data class Refused(val refusal: RegenerationRefusal) : MeshRegeneration
}

/**
 * What generating a drawable's mesh from its art needs, or why it cannot be done - asked before any
 * art is meshed.  The pins are every old vertex a glue pair names on this drawable's side (both sides
 * of a glue with itself) that some triangle uses, at their art-pixel positions, with the old triangles
 * around them; a glued vertex no triangle uses lies off the drawable's surface, often off its art, and
 * is carried instead.  A drawable whose surface glue holds more than half of is refused.
 *
 * @param DrawableId drawableId The drawable.
 * @return RegenerationInput The input, or the refusal.
 */
fun PuppetModel.regenerationInputFor(drawableId: DrawableId): RegenerationInput =
	when (val source = prepareSource(drawableId)) {
		is SourcePreparation.Unusable -> RegenerationInput.Refused(source.refusal)
		is SourcePreparation.Prepared -> source.input
	}

/**
 * Carries a drawable's rig onto a mesh generated from its art: the replacement mesh and one vertex
 * source per new vertex, so committing it rebuilds every keyform cell, blend shape, and glue pair.
 *
 * The generated mesh must be in the input tile's art pixels and must have kept every pin where it was
 * given.  The input is checked against the model again first, so a model that changed while the art
 * was meshed refuses instead of misplacing the rig.
 *
 * @param RegenerationInput.Ready input     The input the mesh was generated for.
 * @param PlanarTriangleMesh      generated The generated mesh.
 * @return MeshRegeneration The edit, or the refusal.
 */
fun PuppetModel.meshRegeneration(input: RegenerationInput.Ready, generated: PlanarTriangleMesh): MeshRegeneration {
	val source =
		when (val preparation = prepareSource(input.drawableId)) {
			is SourcePreparation.Unusable -> return MeshRegeneration.Refused(preparation.refusal)
			is SourcePreparation.Prepared -> preparation
		}

	if (!source.input.matches(input) || !keepsPins(generated, input)) {
		return MeshRegeneration.Refused(RegenerationRefusal.PinMismatch)
	}

	return transfer(source, generated) ?: MeshRegeneration.Refused(RegenerationRefusal.DegenerateOldMesh)
}

/**
 * Replaces several drawables' meshes with generated ones as ONE undo step.  The element selection of
 * every replaced mesh is cleared - its vertex numbers now name different vertices - while the session's
 * meshes, active drawable, select mode, and every other mesh's elements stay, and the remembered
 * elements of the replaced meshes are forgotten for the same reason.
 *
 * @param Map<DrawableId, MeshTopologyEdit> edits The edit for each drawable, from [meshRegeneration].
 * @return Boolean True when a step was recorded; false for no edits or an edit that does not apply.
 */
fun EditorSession.commitMeshRegenerate(edits: Map<DrawableId, MeshTopologyEdit>): Boolean {
	if (edits.isEmpty()) {
		return false
	}

	val base = model.value
	val newModel = base.withMeshTopologyEdits(edits) ?: return false

	return commitGeneratedMeshes(base, newModel, edits.keys.toList())
}

/**
 * Commits a model that already carries generated meshes (and any atlas change they needed) as ONE undo
 * step - built off the UI thread from [base], landed only while [base] is still the session's model, so
 * nothing the rigger did meanwhile is overwritten.  The element selection and the remembered elements
 * of every listed drawable are cleared, whether or not its mesh changed in this run: a later adjustment
 * may replace a different set of them and keeps this step's selection.
 *
 * @param PuppetModel        base        The model the generation started from.
 * @param PuppetModel        generated   The model with the generated meshes.
 * @param List<DrawableId>   drawableIds Every drawable the operation targets.
 * @param MeshGenerationKind kind        What the meshes were generated as, which names the step.
 * @return Boolean True when a step was recorded; false when the model moved or nothing changed.
 */
fun EditorSession.commitGeneratedMeshes(base: PuppetModel, generated: PuppetModel, drawableIds: List<DrawableId>, kind: MeshGenerationKind = MeshGenerationKind.FromArt): Boolean {
	if (model.value !== base || generated === base) {
		return false
	}

	val targets = drawableIds.toSet()
	commitStep(
		generationChangeOf(kind, drawableIds),
		model = generated,
		meshSelection = MeshSelectionOps.clearElementsOf(meshSelection.value, targets),
	)
	elementMemory.forget(targets)

	return true
}

/**
 * Records a mesh generation step that changes nothing, as ONE undo step, so the operation that generated
 * nothing can still be adjusted: the strip opens on this step, and a rerun that generates lands over it
 * through [amendLastCommit].  Lands only while [base] is still the session's model.
 *
 * @param PuppetModel        base        The model the generation started from.
 * @param List<DrawableId>   drawableIds Every drawable the operation targets.
 * @param MeshGenerationKind kind        What the meshes would have been generated as, which names the step.
 * @return Boolean True when the step was recorded; false when the model moved.
 */
fun EditorSession.commitUnchangedGeneration(base: PuppetModel, drawableIds: List<DrawableId>, kind: MeshGenerationKind = MeshGenerationKind.FromArt): Boolean {
	if (model.value !== base) {
		return false
	}

	commitStep(generationChangeOf(kind, drawableIds), model = base)

	return true
}

/** What a mesh generation makes, which names its undo step. */
enum class MeshGenerationKind {
	/** Meshes generated from the art's shape (Generate Mesh from Art). */
	FromArt,

	/** Quads over the art's bounds (Generate Quad Mesh). */
	Quad,
}

/**
 * The change a mesh generation records.
 *
 * @param MeshGenerationKind kind        What the meshes were generated as.
 * @param List<DrawableId>   drawableIds Every drawable the operation targets.
 * @return MeshChange The change.
 */
private fun generationChangeOf(kind: MeshGenerationKind, drawableIds: List<DrawableId>): MeshChange =
	when (kind) {
		MeshGenerationKind.FromArt -> MeshChange.GenerateFromArt(drawableIds)
		MeshGenerationKind.Quad -> MeshChange.GenerateQuad(drawableIds)
	}

/** A drawable's old mesh made ready for a transfer, or why it cannot be. */
private sealed interface SourcePreparation {
	/**
	 * The old mesh and its art frame; its vertices in art pixels, and those some triangle uses, are the
	 * input's ([RegenerationInput.Ready.oldVertexPoints], [RegenerationInput.Ready.referencedOldVertices]).
	 *
	 * @property DrawableMesh            mesh        The old mesh.
	 * @property FloatArray              artToStored Maps art-frame UVs to the stored frame.
	 * @property RegenerationInput.Ready input       The input this preparation answers with.
	 */
	class Prepared(
		val mesh: DrawableMesh,
		val artToStored: FloatArray,
		val input: RegenerationInput.Ready,
	) : SourcePreparation

	/**
	 * The drawable cannot be regenerated.
	 *
	 * @property RegenerationRefusal refusal Why.
	 */
	class Unusable(val refusal: RegenerationRefusal) : SourcePreparation
}

/**
 * Checks a drawable's mesh and tile and lays the mesh out in art pixels.
 *
 * @param DrawableId drawableId The drawable.
 * @return SourcePreparation The prepared source, or why it is unusable.
 */
private fun PuppetModel.prepareSource(drawableId: DrawableId): SourcePreparation {
	val drawable = drawables.firstOrNull { candidate -> candidate.id == drawableId } ?: return SourcePreparation.Unusable(RegenerationRefusal.NoMesh)
	val mesh = drawable.mesh ?: return SourcePreparation.Unusable(RegenerationRefusal.NoMesh)
	val tileId = drawable.atlasTileId ?: return SourcePreparation.Unusable(RegenerationRefusal.NoTile)
	val tile = atlas.tileById[tileId] ?: return SourcePreparation.Unusable(RegenerationRefusal.NoTile)

	// The stored-to-art map of a tile with no placement is the identity whatever its size, so the size
	// is checked on its own.
	if (tile.width <= 0 || tile.height <= 0) {
		return SourcePreparation.Unusable(RegenerationRefusal.NoArtFrame)
	}

	val storedToArt = atlas.storedToArtAffineForTile(tileId) ?: return SourcePreparation.Unusable(RegenerationRefusal.NoArtFrame)
	val artToStored = invertUvAffine(storedToArt) ?: return SourcePreparation.Unusable(RegenerationRefusal.NoArtFrame)

	if (!isWellFormed(mesh)) {
		return SourcePreparation.Unusable(RegenerationRefusal.DegenerateOldMesh)
	}

	val rasterPoints = rasterPointsOf(mesh, storedToArt, tile.width, tile.height)

	if (!hasAreaInArtFrame(rasterPoints, mesh.indices)) {
		return SourcePreparation.Unusable(RegenerationRefusal.DegenerateOldMesh)
	}

	val vertexCount = mesh.vertexCount
	val pinned = BooleanArray(vertexCount)

	for (glue in glues) {
		for (pair in glue.pairs) {
			if (glue.meshA == drawableId && pair.indexA in 0 until vertexCount) {
				pinned[pair.indexA] = true
			}

			if (glue.meshB == drawableId && pair.indexB in 0 until vertexCount) {
				pinned[pair.indexB] = true
			}
		}
	}

	val used = BooleanArray(vertexCount)

	for (corner in mesh.indices) {
		used[corner] = true
	}

	val pinnedOldVertices = (0 until vertexCount).filter { vertex -> pinned[vertex] && used[vertex] }.toIntArray()
	val carriedOldVertices = (0 until vertexCount).filter { vertex -> pinned[vertex] && !used[vertex] }.toIntArray()
	val pinPoints = DoubleArray(2 * pinnedOldVertices.size)

	for ((pin, vertex) in pinnedOldVertices.withIndex()) {
		pinPoints[2 * pin] = rasterPoints[2 * vertex].toDouble()
		pinPoints[2 * pin + 1] = rasterPoints[2 * vertex + 1].toDouble()
	}

	val referenced = (0 until vertexCount).filter { vertex -> used[vertex] }.toIntArray()

	if (2 * pinnedOldVertices.size > referenced.size) {
		return SourcePreparation.Unusable(RegenerationRefusal.GluedThroughout)
	}

	val pinSurroundings = surroundingsOf(rasterPoints, mesh.indices, pinned)
	val input = RegenerationInput.Ready(drawableId, tileId, tile.width, tile.height, pinPoints, pinnedOldVertices, carriedOldVertices, pinSurroundings, rasterPoints, referenced)

	return SourcePreparation.Prepared(mesh, artToStored, input)
}

/**
 * The old triangles that use at least one pinned vertex, each once, in art pixels.
 *
 * @param FloatArray   rasterPoints The old vertices in art pixels.
 * @param IntArray     triangles    The old triangles.
 * @param BooleanArray pinned       Which old vertices a glue pair names.
 * @return DoubleArray The triangles, six coordinates each.
 */
private fun surroundingsOf(rasterPoints: FloatArray, triangles: IntArray, pinned: BooleanArray): DoubleArray {
	val surroundings = ArrayList<Double>()

	for (triangle in 0 until triangles.size / 3) {
		val corners = intArrayOf(triangles[3 * triangle], triangles[3 * triangle + 1], triangles[3 * triangle + 2])

		if (corners.none { corner -> pinned[corner] }) {
			continue
		}

		for (corner in corners) {
			surroundings.add(rasterPoints[2 * corner].toDouble())
			surroundings.add(rasterPoints[2 * corner + 1].toDouble())
		}
	}

	return surroundings.toDoubleArray()
}

/**
 * Whether a mesh's arrays agree with each other: whole pairs, a UV per vertex, whole triangles, every
 * corner a vertex.
 *
 * @param DrawableMesh mesh The mesh.
 * @return Boolean True when the arrays are consistent.
 */
private fun isWellFormed(mesh: DrawableMesh): Boolean {
	if (mesh.positions.size % 2 != 0 || mesh.uvs.size != mesh.positions.size || mesh.indices.size % 3 != 0 || mesh.indices.isEmpty()) {
		return false
	}

	return mesh.indices.all { corner -> corner in 0 until mesh.vertexCount }
}

/**
 * A mesh's vertices in its tile's art pixels: the stored UVs mapped to the art frame, scaled by the
 * tile's size, and rounded to Float - the one formula both the pins and the transfer read, so they
 * agree to the bit.
 *
 * @param DrawableMesh mesh        The mesh.
 * @param FloatArray   storedToArt Maps stored UVs to the art frame.
 * @param Int          tileWidth   The tile's width in pixels.
 * @param Int          tileHeight  The tile's height in pixels.
 * @return FloatArray The points, x0, y0, ...
 */
private fun rasterPointsOf(mesh: DrawableMesh, storedToArt: FloatArray, tileWidth: Int, tileHeight: Int): FloatArray {
	val artUvs = applyUvAffine(mesh.uvs, storedToArt)

	return FloatArray(artUvs.size) { component ->
		val size = if (component % 2 == 0) tileWidth else tileHeight

		(artUvs[component].toDouble() * size).toFloat()
	}
}

/**
 * Whether any triangle has area, decided exactly.
 *
 * @param FloatArray points    The vertices.
 * @param IntArray   triangles The triangles.
 * @return Boolean True when at least one triangle is not degenerate.
 */
private fun hasAreaInArtFrame(points: FloatArray, triangles: IntArray): Boolean {
	for (triangle in 0 until triangles.size / 3) {
		if (signedDoubleArea(points, triangles, triangle) != 0.0) {
			return true
		}
	}

	return false
}

/**
 * Twice a triangle's signed area with an exact sign (orient2d), positive for the generated meshes' winding.
 *
 * @param FloatArray points    The vertices.
 * @param IntArray   triangles The triangles.
 * @param Int        triangle  The triangle.
 * @return Double The signed area, doubled.
 */
private fun signedDoubleArea(points: FloatArray, triangles: IntArray, triangle: Int): Double {
	val first = triangles[3 * triangle]
	val second = triangles[3 * triangle + 1]
	val third = triangles[3 * triangle + 2]

	return orient2d(
		points[2 * first].toDouble(),
		points[2 * first + 1].toDouble(),
		points[2 * second].toDouble(),
		points[2 * second + 1].toDouble(),
		points[2 * third].toDouble(),
		points[2 * third + 1].toDouble(),
	)
}

/**
 * Whether a fresh input still describes the same drawable, tile, and pins as the one a mesh was made for.
 *
 * @param RegenerationInput.Ready other The input the mesh was made for.
 * @return Boolean True when they agree.
 */
private fun RegenerationInput.Ready.matches(other: RegenerationInput.Ready): Boolean =
	drawableId == other.drawableId &&
		tileId == other.tileId &&
		tileWidth == other.tileWidth &&
		tileHeight == other.tileHeight &&
		pinnedOldVertices.contentEquals(other.pinnedOldVertices) &&
		pinPoints.contentEquals(other.pinPoints) &&
		carriedOldVertices.contentEquals(other.carriedOldVertices) &&
		pinSurroundings.contentEquals(other.pinSurroundings)

/**
 * Whether a generated mesh kept every pin as a vertex at exactly the point it was given.
 *
 * @param PlanarTriangleMesh      generated The generated mesh.
 * @param RegenerationInput.Ready input     The input it was made for.
 * @return Boolean True when every pin is where it was given.
 */
private fun keepsPins(generated: PlanarTriangleMesh, input: RegenerationInput.Ready): Boolean {
	if (generated.pinVertices.size != input.pinnedOldVertices.size) {
		return false
	}

	for (pin in generated.pinVertices.indices) {
		val vertex = generated.pinVertices[pin]

		if (generated.positions[2 * vertex] != input.pinPoints[2 * pin].toFloat() || generated.positions[2 * vertex + 1] != input.pinPoints[2 * pin + 1].toFloat()) {
			return false
		}
	}

	return true
}

/**
 * Builds the replacement mesh and the vertex sources.
 *
 * @param SourcePreparation.Prepared source    The prepared old mesh.
 * @param PlanarTriangleMesh         generated The generated mesh, pins already checked.
 * @return MeshRegeneration.Built? The edit, or null when a vertex outside the old mesh has no neighbourhood to extrapolate from.
 */
private fun transfer(source: SourcePreparation.Prepared, generated: PlanarTriangleMesh): MeshRegeneration.Built? {
	val oldMesh = source.mesh
	val rasterPoints = source.input.oldVertexPoints
	val sharedBase = oldMesh.localPositions === oldMesh.positions
	val generatedCount = generated.vertexCount
	val carried = source.input.carriedOldVertices
	val newCount = generatedCount + carried.size
	val sources = arrayOfNulls<VertexSource>(newCount)
	val newPositions = FloatArray(2 * newCount)
	val newLocal = if (sharedBase) newPositions else FloatArray(2 * newCount)
	// The stored UV each new vertex copies from an old one, or -1 for a UV lowered from its art point.
	val copiedUvFrom = IntArray(newCount) { -1 }
	val extrapolated = ArrayList<Int>()

	// Pins first: a pinned vertex IS the old vertex (or, for pins that landed on one point, all of
	// them), so it claims their glue pairs.
	val pinnedOldByNew = arrayOfNulls<MutableList<Int>>(generatedCount)

	for (pin in generated.pinVertices.indices) {
		val newVertex = generated.pinVertices[pin]
		val oldVertices = pinnedOldByNew[newVertex] ?: mutableListOf<Int>().also { created -> pinnedOldByNew[newVertex] = created }
		oldVertices.add(source.input.pinnedOldVertices[pin])
	}

	for (vertex in 0 until generatedCount) {
		val oldVertices = pinnedOldByNew[vertex] ?: continue
		sources[vertex] = if (oldVertices.size == 1) VertexSource.FromOld(oldVertices[0]) else VertexSource.AverageOf(oldVertices.toList())
		averageInto(newPositions, vertex, oldMesh.positions, oldVertices)

		if (!sharedBase) {
			averageInto(newLocal, vertex, oldMesh.localPositions, oldVertices)
		}

		copiedUvFrom[vertex] = oldVertices[0]
	}

	val oldVertexAt = HashMap<Long, Int>()

	for (vertex in source.input.referencedOldVertices) {
		oldVertexAt.getOrPut(pointKey(rasterPoints[2 * vertex], rasterPoints[2 * vertex + 1])) { vertex }
	}

	val locator = TriangleLocator(rasterPoints, oldMesh.indices)
	val extrapolator = BoundaryExtrapolator(rasterPoints, oldMesh.positions, if (sharedBase) null else oldMesh.localPositions, source.input.referencedOldVertices)
	val weights = DoubleArray(3)
	val corners = IntArray(3)

	for (vertex in 0 until generatedCount) {
		if (sources[vertex] != null) {
			continue
		}

		val pointX = generated.positions[2 * vertex]
		val pointY = generated.positions[2 * vertex + 1]
		val coincident = oldVertexAt[pointKey(pointX, pointY)]

		if (coincident != null) {
			sources[vertex] = VertexSource.Weighted(intArrayOf(coincident), floatArrayOf(1f))
			copyInto(newPositions, vertex, oldMesh.positions, coincident)

			if (!sharedBase) {
				copyInto(newLocal, vertex, oldMesh.localPositions, coincident)
			}

			copiedUvFrom[vertex] = coincident

			continue
		}

		val location = locator.locate(pointX.toDouble(), pointY.toDouble()) ?: return null
		corners[0] = oldMesh.indices[3 * location.triangleIndex]
		corners[1] = oldMesh.indices[3 * location.triangleIndex + 1]
		corners[2] = oldMesh.indices[3 * location.triangleIndex + 2]
		weights[0] = location.weightA.coerceIn(0.0, 1.0)
		weights[1] = location.weightB.coerceIn(0.0, 1.0)
		weights[2] = location.weightC.coerceIn(0.0, 1.0)
		val weighted = weightedSource(corners, weights)
		sources[vertex] = weighted

		if (location.isInside) {
			blendInto(newPositions, vertex, oldMesh.positions, weighted)

			if (!sharedBase) {
				blendInto(newLocal, vertex, oldMesh.localPositions, weighted)
			}

			continue
		}

		val firstCorner = weighted.oldIndices[0]
		val secondCorner = weighted.oldIndices[weighted.oldIndices.size - 1]
		val fit = extrapolator.fitFor(firstCorner, secondCorner) ?: return null
		var boundaryX = 0.0
		var boundaryY = 0.0

		for (term in weighted.oldIndices.indices) {
			boundaryX += weighted.weights[term] * rasterPoints[2 * weighted.oldIndices[term]].toDouble()
			boundaryY += weighted.weights[term] * rasterPoints[2 * weighted.oldIndices[term] + 1].toDouble()
		}

		val offsetU = pointX - boundaryX
		val offsetV = pointY - boundaryY
		extrapolateInto(newPositions, vertex, oldMesh.positions, weighted, fit.canvasJacobian, offsetU, offsetV)

		if (!sharedBase) {
			extrapolateInto(newLocal, vertex, oldMesh.localPositions, weighted, fit.localJacobian ?: return null, offsetU, offsetV)
		}

		extrapolated.add(vertex)
	}

	// Carried vertices follow the generated ones, each the old vertex itself and used by no triangle.
	for ((offset, oldVertex) in carried.withIndex()) {
		val vertex = generatedCount + offset
		sources[vertex] = VertexSource.FromOld(oldVertex)
		copyInto(newPositions, vertex, oldMesh.positions, oldVertex)

		if (!sharedBase) {
			copyInto(newLocal, vertex, oldMesh.localPositions, oldVertex)
		}

		copiedUvFrom[vertex] = oldVertex
	}

	val newUvs = newUvsOf(generated, source, copiedUvFrom)
	val newIndices = generated.triangles.copyOf()

	if (windingSign(rasterPoints, oldMesh.indices) * windingSign(generated.positions, newIndices) < 0) {
		for (triangle in 0 until newIndices.size / 3) {
			val second = newIndices[3 * triangle + 1]
			newIndices[3 * triangle + 1] = newIndices[3 * triangle + 2]
			newIndices[3 * triangle + 2] = second
		}
	}

	val newMesh =
		if (sharedBase) {
			DrawableMesh.withLocalEqualToCanvas(newPositions, newUvs, newIndices)
		} else {
			DrawableMesh(newPositions, newLocal, newUvs, newIndices)
		}
	val vertexSources = sources.map { vertexSource -> vertexSource ?: return null }

	return MeshRegeneration.Built(MeshTopologyEdit(newMesh, vertexSources), extrapolated.toIntArray())
}

/**
 * The weighted source for the corners a location names: weights clamped to [0, 1] and normalized,
 * rounded to Float once, and zero terms dropped.
 *
 * @param IntArray    corners The triangle's three old vertices.
 * @param DoubleArray weights Their clamped weights.
 * @return VertexSource.Weighted The source.
 */
private fun weightedSource(corners: IntArray, weights: DoubleArray): VertexSource.Weighted {
	val total = weights[0] + weights[1] + weights[2]
	val keptCorners = ArrayList<Int>(3)
	val keptWeights = ArrayList<Float>(3)

	for (corner in 0 until 3) {
		val weight = (weights[corner] / total).toFloat()

		if (weight != 0f) {
			keptCorners.add(corners[corner])
			keptWeights.add(weight)
		}
	}

	return VertexSource.Weighted(keptCorners.toIntArray(), keptWeights.toFloatArray())
}

/**
 * Writes one new vertex's pair as the mean of several old vertices' pairs.
 *
 * @param FloatArray target     The new array.
 * @param Int        vertex     The new vertex.
 * @param FloatArray old        The old array.
 * @param List<Int>  oldIndices The old vertices.
 */
private fun averageInto(target: FloatArray, vertex: Int, old: FloatArray, oldIndices: List<Int>) {
	if (oldIndices.size == 1) {
		copyInto(target, vertex, old, oldIndices[0])

		return
	}

	var sumX = 0.0
	var sumY = 0.0

	for (oldIndex in oldIndices) {
		sumX += old[2 * oldIndex]
		sumY += old[2 * oldIndex + 1]
	}

	target[2 * vertex] = (sumX / oldIndices.size).toFloat()
	target[2 * vertex + 1] = (sumY / oldIndices.size).toFloat()
}

/**
 * Copies one old vertex's pair into a new vertex.
 *
 * @param FloatArray target   The new array.
 * @param Int        vertex   The new vertex.
 * @param FloatArray old      The old array.
 * @param Int        oldIndex The old vertex.
 */
private fun copyInto(target: FloatArray, vertex: Int, old: FloatArray, oldIndex: Int) {
	target[2 * vertex] = old[2 * oldIndex]
	target[2 * vertex + 1] = old[2 * oldIndex + 1]
}

/**
 * Writes a new vertex's pair as a weighted blend of old ones, measured from the first term so a large
 * coordinate keeps its precision.
 *
 * @param FloatArray            target   The new array.
 * @param Int                   vertex   The new vertex.
 * @param FloatArray            old      The old array.
 * @param VertexSource.Weighted weighted The blend.
 */
private fun blendInto(target: FloatArray, vertex: Int, old: FloatArray, weighted: VertexSource.Weighted) {
	val (blendX, blendY) = blendOf(old, weighted)
	target[2 * vertex] = blendX.toFloat()
	target[2 * vertex + 1] = blendY.toFloat()
}

/**
 * Writes a new vertex's pair past the boundary: the blend at the boundary point plus the boundary's
 * linear map applied to the offset from it.
 *
 * @param FloatArray            target   The new array.
 * @param Int                   vertex   The new vertex.
 * @param FloatArray            old      The old array.
 * @param VertexSource.Weighted weighted The boundary point's blend.
 * @param DoubleArray           jacobian The linear map, row-major (dxdu, dxdv, dydu, dydv).
 * @param Double                offsetU  The offset from the boundary point, art x.
 * @param Double                offsetV  The offset, art y.
 */
private fun extrapolateInto(target: FloatArray, vertex: Int, old: FloatArray, weighted: VertexSource.Weighted, jacobian: DoubleArray, offsetU: Double, offsetV: Double) {
	val (boundaryX, boundaryY) = blendOf(old, weighted)
	target[2 * vertex] = (boundaryX + jacobian[0] * offsetU + jacobian[1] * offsetV).toFloat()
	target[2 * vertex + 1] = (boundaryY + jacobian[2] * offsetU + jacobian[3] * offsetV).toFloat()
}

/**
 * The weighted blend of old pairs, as the first term plus each other term's weighted difference from it.
 *
 * @param FloatArray            old      The old array.
 * @param VertexSource.Weighted weighted The blend.
 * @return Pair<Double, Double> The blended x and y.
 */
private fun blendOf(old: FloatArray, weighted: VertexSource.Weighted): Pair<Double, Double> {
	val firstX = old[2 * weighted.oldIndices[0]].toDouble()
	val firstY = old[2 * weighted.oldIndices[0] + 1].toDouble()
	var blendX = firstX
	var blendY = firstY

	for (term in 1 until weighted.oldIndices.size) {
		blendX += weighted.weights[term] * (old[2 * weighted.oldIndices[term]] - firstX)
		blendY += weighted.weights[term] * (old[2 * weighted.oldIndices[term] + 1] - firstY)
	}

	return blendX to blendY
}

/**
 * The new mesh's stored UVs: a copied vertex's old stored UV, every other vertex's art point divided
 * by the tile's size and lowered to the stored frame.  The new mesh may run past the generated
 * vertices (the carried ones); each of those copies its UV.
 *
 * @param PlanarTriangleMesh         generated    The generated mesh.
 * @param SourcePreparation.Prepared source       The prepared old mesh.
 * @param IntArray                   copiedUvFrom The old vertex each new vertex copies its UV from, or -1, one per new vertex.
 * @return FloatArray The UVs, u0, v0, ...
 */
private fun newUvsOf(generated: PlanarTriangleMesh, source: SourcePreparation.Prepared, copiedUvFrom: IntArray): FloatArray {
	val width = source.input.tileWidth.toDouble()
	val height = source.input.tileHeight.toDouble()
	val generatedComponents = generated.positions.size
	val artUvs =
		FloatArray(2 * copiedUvFrom.size) { component ->
			if (component < generatedComponents) {
				(generated.positions[component] / if (component % 2 == 0) width else height).toFloat()
			} else {
				0f
			}
		}
	val storedUvs = applyUvAffine(artUvs, source.artToStored)

	for (vertex in copiedUvFrom.indices) {
		if (copiedUvFrom[vertex] >= 0) {
			copyInto(storedUvs, vertex, source.mesh.uvs, copiedUvFrom[vertex])
		}
	}

	return storedUvs
}

/**
 * The sign of a mesh's total signed area: which way its triangles mostly wind.
 *
 * @param FloatArray points    The vertices.
 * @param IntArray   triangles The triangles.
 * @return Int 1, -1, or 0 for a mesh whose areas cancel.
 */
private fun windingSign(points: FloatArray, triangles: IntArray): Int {
	var total = 0.0

	for (triangle in 0 until triangles.size / 3) {
		total += signedDoubleArea(points, triangles, triangle)
	}

	return when {
		total > 0.0 -> 1
		total < 0.0 -> -1
		else -> 0
	}
}

/**
 * A point's lookup key: its two Float coordinates' bits, with negative zero folded into zero.
 *
 * @param Float x The point's x.
 * @param Float y The point's y.
 * @return Long The key.
 */
private fun pointKey(x: Float, y: Float): Long = ((x + 0f).toRawBits().toLong() shl 32) or ((y + 0f).toRawBits().toLong() and 0xFFFFFFFFL)