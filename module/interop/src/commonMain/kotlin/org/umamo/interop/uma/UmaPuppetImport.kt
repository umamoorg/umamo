package org.umamo.interop.uma

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import org.umamo.format.uma.UmaEntryKind
import org.umamo.format.uma.UmaFormatException
import org.umamo.format.uma.UmaReadFailure
import org.umamo.format.uma.puppet.UmaAxis
import org.umamo.format.uma.puppet.UmaBlendLimit
import org.umamo.format.uma.puppet.UmaChannelGrid
import org.umamo.format.uma.puppet.UmaDeformer
import org.umamo.format.uma.puppet.UmaDeformerBlendShape
import org.umamo.format.uma.puppet.UmaDeformerCell
import org.umamo.format.uma.puppet.UmaDeformerForm
import org.umamo.format.uma.puppet.UmaDeformerGrid
import org.umamo.format.uma.puppet.UmaDeformerKind
import org.umamo.format.uma.puppet.UmaDrawable
import org.umamo.format.uma.puppet.UmaFormChannel
import org.umamo.format.uma.puppet.UmaGlue
import org.umamo.format.uma.puppet.UmaMesh
import org.umamo.format.uma.puppet.UmaMeshBlendShape
import org.umamo.format.uma.puppet.UmaMeshGrid
import org.umamo.format.uma.puppet.UmaOrgRef
import org.umamo.format.uma.puppet.UmaParameter
import org.umamo.format.uma.puppet.UmaParameterNode
import org.umamo.format.uma.puppet.UmaPart
import org.umamo.format.uma.puppet.UmaPartBlendShape
import org.umamo.format.uma.puppet.UmaPartComposite
import org.umamo.format.uma.puppet.UmaPuppet
import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.BlendWeightLimit
import org.umamo.runtime.model.BlendWeightLimitPoint
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.ChannelValueKind
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.DEFAULT_DRAW_ORDER
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.Glue
import org.umamo.runtime.model.GluePair
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.ParameterLink
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartComposite
import org.umamo.runtime.model.PartForm
import org.umamo.runtime.model.PartGroupMode
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RotationForm
import org.umamo.runtime.model.RotationPivotForm
import org.umamo.runtime.model.RuntimeTarget
import org.umamo.runtime.model.WarpForm
import org.umamo.runtime.model.WarpLatticeForm
import org.umamo.runtime.model.withDerivedRenderRoot

/**
 * Builds a [PuppetModel] from the puppet entry (docs/format/UMA.md §4): restores each absent value to the
 * model's default, checks the shapes and geometry invariants the schema's classes cannot enforce on their own
 * (UMA §4.8), and derives the render root from the organizational tree (D13).
 *
 * Ids are taken verbatim and references are not resolved: a reference to an object the entry does not hold
 * is carried as it is, never dropped.
 */
object UmaPuppetImport {
	/**
	 * The model [puppet] describes.
	 *
	 * @param UmaPuppet puppet    The puppet entry's content.
	 * @param String    entryPath The entry's path, for a failure.
	 * @return PuppetModel The model.
	 * @throws UmaFormatException When the entry holds a shape the schema does not allow.
	 */
	fun modelOf(puppet: UmaPuppet, entryPath: String = UmaEntryKind.Puppet.defaultPath): PuppetModel {
		val reader = EntryReader(entryPath)
		val drawables = puppet.drawables.orEmpty().map(reader::drawableOf)
		val vertexCountByDrawable = drawables.associate { drawable -> drawable.id to drawable.mesh?.vertexCount }
		val model =
			PuppetModel(
				parameters = puppet.parameters.orEmpty().map(reader::parameterOf),
				parts = puppet.parts.orEmpty().map(reader::partOf),
				deformers = puppet.deformers.orEmpty().map(reader::deformerOf),
				drawables = drawables,
				glues = puppet.glues.orEmpty().map { glue -> reader.glueOf(glue, vertexCountByDrawable) },
				rootChildren = puppet.rootChildren.orEmpty().mapIndexed { childIndex, reference -> reader.orgChildOf(reference, "rootChildren[$childIndex]") },
				rootPartId = puppet.rootPart?.let(::PartId),
				parameterLinks = puppet.parameterLinks.orEmpty().map { link -> ParameterLink(ParameterId(link.horizontal), ParameterId(link.vertical)) },
				parameterTree = puppet.parameterTree.orEmpty().mapIndexed { nodeIndex, node -> reader.parameterNodeOf(node, "parameterTree[$nodeIndex]") },
				canvasWidth = puppet.canvasWidth ?: 0f,
				canvasHeight = puppet.canvasHeight ?: 0f,
				worldOriginX = puppet.worldOriginX ?: 0f,
				worldOriginY = puppet.worldOriginY ?: 0f,
				pixelsPerUnit = puppet.pixelsPerUnit,
				runtimeTarget = puppet.runtimeTarget?.toRuntime() ?: RuntimeTarget.NoTarget,
				rendersFromSourceLayers = puppet.rendersFromSourceLayers ?: false,
			)
		// UMA §4.2 (D13): the render root is never written; it is always the organizational tree's derivation.
		return model.withDerivedRenderRoot()
	}

	/**
	 * Reads the entry's records, reporting a malformed shape against the entry's path.
	 *
	 * @property String entryPath The entry's path.
	 */
	private class EntryReader(private val entryPath: String) {
		/**
		 * Throws the malformed-entry failure for [detail].
		 *
		 * @param String detail What is wrong, and where.
		 * @return Nothing Never returns.
		 */
		fun malformed(detail: String): Nothing = throw UmaFormatException(UmaReadFailure.MalformedEntry(entryPath, detail))

		/**
		 * UMA §4.3: one parameter.
		 *
		 * @param UmaParameter record The record.
		 * @return Parameter The parameter.
		 */
		fun parameterOf(record: UmaParameter): Parameter =
			Parameter(
				id = ParameterId(record.id),
				name = record.name,
				min = record.min,
				max = record.max,
				default = record.default,
				kind = record.kind?.toRuntime() ?: ParameterKind.NORMAL,
				repeat = record.repeat ?: false,
			)

		/**
		 * UMA §4.3: one parameter-tree node, which is exactly one of a leaf and a group.
		 *
		 * @param UmaParameterNode record The record.
		 * @param String           path   The node's position, for a failure.
		 * @return ParameterNode The node.
		 */
		fun parameterNodeOf(record: UmaParameterNode, path: String): ParameterNode {
			val leaf = record.parameter
			val group = record.group
			return when {
				leaf != null && group == null -> {
					if (record.name != null || record.initiallyOpen != null || record.children != null) {
						malformed("$path is a parameter leaf but carries group fields")
					}
					ParameterNode.Param(ParameterId(leaf))
				}

				group != null && leaf == null ->
					ParameterNode.Group(
						id = ParameterGroupId(group),
						name = record.name ?: malformed("$path is a group without a name"),
						initiallyOpen = record.initiallyOpen ?: false,
						children = record.children.orEmpty().mapIndexed { childIndex, child -> parameterNodeOf(child, "$path.children[$childIndex]") },
					)

				else -> malformed("$path must name exactly one of a parameter and a group")
			}
		}

		/**
		 * UMA §4.2: one organizational child, which is exactly one of a part and a drawable.
		 *
		 * @param UmaOrgRef record The record.
		 * @param String    path   The child's position, for a failure.
		 * @return OrgChild The child.
		 */
		fun orgChildOf(record: UmaOrgRef, path: String): OrgChild {
			val part = record.part
			val drawable = record.drawable
			return when {
				part != null && drawable == null -> OrgChild.Part(PartId(part))
				drawable != null && part == null -> OrgChild.Drawable(DrawableId(drawable))
				else -> malformed("$path must name exactly one of a part and a drawable")
			}
		}

		/**
		 * UMA §4.4: one part.
		 *
		 * @param UmaPart record The record.
		 * @return Part The part.
		 */
		fun partOf(record: UmaPart): Part {
			val path = "parts[${record.id}]"
			return Part(
				id = PartId(record.id),
				name = record.name,
				children = record.children.orEmpty().mapIndexed { childIndex, child -> orgChildOf(child, "$path.children[$childIndex]") },
				isVisible = record.isVisible ?: true,
				isSketch = record.isSketch ?: false,
				isSelectable = record.isSelectable ?: true,
				groupMode = record.groupMode?.toRuntime() ?: PartGroupMode.PassThrough,
				drawOrder = record.drawOrder ?: DEFAULT_DRAW_ORDER,
				composite = compositeOf(record.composite ?: UmaPartComposite(), "$path.composite"),
				channelGrids = channelsOf(record.channels, path),
				blendShapes = record.blendShapes.orEmpty().mapIndexed { bindingIndex, binding -> partBlendShapeOf(binding, "$path.blendShapes[$bindingIndex]") },
			)
		}

		/**
		 * UMA §4.4: a part's composite.
		 *
		 * @param UmaPartComposite record The record.
		 * @param String           path   The composite's position, for a failure.
		 * @return PartComposite The composite.
		 */
		fun compositeOf(record: UmaPartComposite, path: String): PartComposite =
			PartComposite(
				blendMode = record.blendMode?.toRuntime() ?: BlendMode.Normal,
				alphaBlendMode = record.alphaBlendMode?.toRuntime() ?: AlphaBlendMode.Over,
				maskedBy = record.maskedBy.orEmpty().map(::DrawableId),
				maskedByParts = record.maskedByParts.orEmpty().map(::PartId),
				invertMask = record.invertMask ?: false,
				opacity = record.opacity ?: 1f,
				multiplyColor = colorOf(record.multiplyColor, ColorRgb.MultiplyIdentity, "$path.multiplyColor"),
				screenColor = colorOf(record.screenColor, ColorRgb.ScreenIdentity, "$path.screenColor"),
			)

		/**
		 * UMA §4.5: one deformer, whose kind decides which kind-specific fields it must and must not carry.
		 *
		 * @param UmaDeformer record The record.
		 * @return Deformer The deformer.
		 */
		fun deformerOf(record: UmaDeformer): Deformer {
			val path = "deformers[${record.id}]"
			val multiplyColor = colorOf(record.multiplyColor, ColorRgb.MultiplyIdentity, "$path.multiplyColor")
			val screenColor = colorOf(record.screenColor, ColorRgb.ScreenIdentity, "$path.screenColor")
			return when (record.kind) {
				UmaDeformerKind.Warp -> {
					if (record.baseAngle != null || record.flipX != null || record.flipY != null) {
						malformed("$path is a warp but carries rotation fields")
					}
					val rows = record.rows ?: malformed("$path is a warp without rows")
					val columns = record.columns ?: malformed("$path is a warp without columns")
					if (rows < 0 || columns < 0) {
						malformed("$path has a negative lattice size")
					}
					// UMA §4.11: a rows x columns lattice holds (rows + 1) x (columns + 1) control points, counted wide so a
					// huge lattice cannot wrap to a small float count its arrays would then pass.
					val pointFloatsWide = (rows + 1L) * (columns + 1L) * 2L
					if (pointFloatsWide > Int.MAX_VALUE) {
						malformed("$path has a $rows x $columns lattice, too large to hold")
					}
					val pointFloats = pointFloatsWide.toInt()
					Deformer.Warp(
						id = DeformerId(record.id),
						name = record.name,
						parent = record.parent?.let(::DeformerId),
						partId = record.part?.let(::PartId),
						rows = rows,
						columns = columns,
						isQuadTransform = record.isQuadTransform ?: malformed("$path is a warp without isQuadTransform"),
						geometryGrid = record.geometry?.let { grid -> warpGridOf(grid, pointFloats, "$path.geometry") },
						channelGrids = channelsOf(record.channels, path),
						blendShapes = record.blendShapes.orEmpty().mapIndexed { bindingIndex, binding -> warpBlendShapeOf(binding, pointFloats, "$path.blendShapes[$bindingIndex]") },
						opacity = record.opacity ?: 1f,
						multiplyColor = multiplyColor,
						screenColor = screenColor,
						isSelectable = record.isSelectable ?: true,
						isVisible = record.isVisible ?: true,
						isEnabled = record.isEnabled ?: true,
					)
				}

				UmaDeformerKind.Rotation -> {
					if (record.rows != null || record.columns != null || record.isQuadTransform != null) {
						malformed("$path is a rotation but carries warp fields")
					}
					Deformer.Rotation(
						id = DeformerId(record.id),
						name = record.name,
						parent = record.parent?.let(::DeformerId),
						partId = record.part?.let(::PartId),
						baseAngle = record.baseAngle ?: malformed("$path is a rotation without baseAngle"),
						geometryGrid = record.geometry?.let { grid -> rotationGridOf(grid, "$path.geometry") },
						channelGrids = channelsOf(record.channels, path),
						blendShapes = record.blendShapes.orEmpty().mapIndexed { bindingIndex, binding -> rotationBlendShapeOf(binding, "$path.blendShapes[$bindingIndex]") },
						opacity = record.opacity ?: 1f,
						multiplyColor = multiplyColor,
						screenColor = screenColor,
						flipX = record.flipX ?: false,
						flipY = record.flipY ?: false,
						isSelectable = record.isSelectable ?: true,
						isVisible = record.isVisible ?: true,
						isEnabled = record.isEnabled ?: true,
					)
				}
			}
		}

		/**
		 * UMA §4.6: one drawable.
		 *
		 * @param UmaDrawable record The record.
		 * @return Drawable The drawable.
		 */
		fun drawableOf(record: UmaDrawable): Drawable {
			val path = "drawables[${record.id}]"
			val mesh = record.mesh?.let { mesh -> meshOf(mesh, "$path.mesh") }
			return Drawable(
				id = DrawableId(record.id),
				name = record.name,
				parentDeformerId = record.parentDeformer?.let(::DeformerId),
				blendMode = record.blendMode?.toRuntime() ?: BlendMode.Normal,
				maskedBy = record.maskedBy.orEmpty().map(::DrawableId),
				mesh = mesh,
				geometryGrid = record.geometry?.let { grid -> meshGridOf(grid, mesh, "$path.geometry") },
				channelGrids = channelsOf(record.channels, path),
				drawOrder = record.drawOrder ?: DEFAULT_DRAW_ORDER.toFloat(),
				opacity = record.opacity ?: 1f,
				multiplyColor = colorOf(record.multiplyColor, ColorRgb.MultiplyIdentity, "$path.multiplyColor"),
				screenColor = colorOf(record.screenColor, ColorRgb.ScreenIdentity, "$path.screenColor"),
				invertMask = record.invertMask ?: false,
				alphaBlendMode = record.alphaBlendMode?.toRuntime() ?: AlphaBlendMode.Over,
				culling = record.culling ?: false,
				isVisible = record.isVisible ?: true,
				isSelectable = record.isSelectable ?: true,
				textureSourceId = record.textureSource?.let(::DrawableId),
				texturePage = record.texturePage ?: -1,
				atlasTileId = record.atlasTile?.let(::AtlasTileId),
				blendShapes = record.blendShapes.orEmpty().mapIndexed { bindingIndex, binding -> meshBlendShapeOf(binding, mesh, "$path.blendShapes[$bindingIndex]") },
			)
		}

		/**
		 * UMA §4.14: one glue affecter, its vertex indices checked against the meshes it welds.
		 *
		 * @param UmaGlue record               The record.
		 * @param Map     vertexCountByDrawable Each drawable's vertex count, null for a drawable with no mesh.
		 * @return Glue The glue.
		 */
		fun glueOf(record: UmaGlue, vertexCountByDrawable: Map<DrawableId, Int?>): Glue {
			val path = "glues[${record.meshA},${record.meshB}]"
			val pairs = record.pairs
			val pairCount = pairs.indicesA.size
			if (pairs.indicesB.size != pairCount || pairs.weightsA.size != pairCount || pairs.weightsB.size != pairCount) {
				malformed("$path.pairs holds arrays of different lengths")
			}
			val meshA = DrawableId(record.meshA)
			val meshB = DrawableId(record.meshB)
			checkIndices(pairs.indicesA, vertexCountByDrawable[meshA], "$path.pairs.indicesA")
			checkIndices(pairs.indicesB, vertexCountByDrawable[meshB], "$path.pairs.indicesB")
			return Glue(
				meshA = meshA,
				meshB = meshB,
				pairs = List(pairCount) { pairIndex -> GluePair(pairs.indicesA[pairIndex], pairs.indicesB[pairIndex], pairs.weightsA[pairIndex], pairs.weightsB[pairIndex]) },
				channelGrids = channelsOf(record.channels, path),
				intensity = record.intensity ?: 1f,
				id = record.id,
			)
		}

		/**
		 * UMA §4.10: a mesh, its arrays checked against each other.
		 *
		 * @param UmaMesh record The record.
		 * @param String  path   The mesh's position, for a failure.
		 * @return DrawableMesh The mesh.
		 */
		fun meshOf(record: UmaMesh, path: String): DrawableMesh {
			if (record.positions.size % 2 != 0) {
				malformed("$path.positions holds ${record.positions.size} floats, not whole x, y pairs")
			}
			if (record.uvs.size != record.positions.size) {
				malformed("$path.uvs holds ${record.uvs.size} floats against ${record.positions.size} positions")
			}
			if (record.indices.size % 3 != 0) {
				malformed("$path.indices holds ${record.indices.size} indices, not whole triangles")
			}
			checkIndices(record.indices, record.positions.size / 2, "$path.indices")
			return DrawableMesh(record.positions, record.uvs, record.indices)
		}

		/**
		 * Checks every index is a vertex of a mesh with [vertexCount] vertices; nothing to check when the count
		 * is unknown (the mesh is absent).
		 *
		 * @param IntArray indices     The indices.
		 * @param Int?     vertexCount The mesh's vertex count, or null.
		 * @param String   path        Where the indices sit, for a failure.
		 */
		fun checkIndices(indices: IntArray, vertexCount: Int?, path: String) {
			if (vertexCount == null) {
				return
			}
			indices.forEachIndexed { indexIndex, index ->
				if (index !in 0 until vertexCount) {
					malformed("$path[$indexIndex] is $index, outside the mesh's $vertexCount vertices")
				}
			}
		}

		/**
		 * UMA §4.11: a grid's axes, each one's keys ascending.
		 *
		 * @param List<UmaAxis> records The records.
		 * @param String        path    The grid's position, for a failure.
		 * @return List<KeyformAxis> The axes.
		 */
		fun axesOf(records: List<UmaAxis>, path: String): List<KeyformAxis> =
			records.mapIndexed { axisIndex, axis ->
				checkAscending(axis.keys, "$path.axes[$axisIndex].keys")
				KeyformAxis(ParameterId(axis.parameter), axis.keys.toFloatArray())
			}

		/**
		 * Checks [values] never step down: the evaluator brackets a parameter value by scanning keys in order, so a
		 * key below its predecessor would place forms at the wrong values without any error.  Equal neighbors are
		 * allowed; the evaluator treats their empty span as a snap.
		 *
		 * @param List<Float> values The values, in stored order.
		 * @param String      path   Where the list sits; a failure names the element under it.
		 */
		fun checkAscending(values: List<Float>, path: String) {
			for (valueIndex in 1 until values.size) {
				if (values[valueIndex] < values[valueIndex - 1]) {
					malformed("$path[$valueIndex] is ${values[valueIndex]}, below the ${values[valueIndex - 1]} before it")
				}
			}
		}

		/**
		 * UMA §4.11: a cell's coordinate, which names one key on every axis.
		 *
		 * @param List<Int>         coordinate The record's coordinate.
		 * @param List<KeyformAxis> axes       The grid's axes.
		 * @param String            path       The cell's position, for a failure.
		 * @return IntArray The coordinate.
		 */
		fun coordinateOf(coordinate: List<Int>, axes: List<KeyformAxis>, path: String): IntArray {
			if (coordinate.size != axes.size) {
				malformed("$path.coordinate has ${coordinate.size} components for ${axes.size} axes")
			}
			coordinate.forEachIndexed { axisIndex, keyIndex ->
				if (keyIndex !in axes[axisIndex].keys.indices) {
					malformed("$path.coordinate[$axisIndex] is $keyIndex, outside the axis's ${axes[axisIndex].keys.size} keys")
				}
			}
			return coordinate.toIntArray()
		}

		/**
		 * UMA §4.11: a drawable's geometry grid, whose deltas match the mesh's positions.
		 *
		 * @param UmaMeshGrid   record The record.
		 * @param DrawableMesh? mesh   The drawable's mesh, if it has one.
		 * @param String        path   The grid's position, for a failure.
		 * @return KeyformGrid The grid.
		 */
		fun meshGridOf(record: UmaMeshGrid, mesh: DrawableMesh?, path: String): KeyformGrid<MeshDeltaForm> {
			val axes = axesOf(record.axes, path)
			return KeyformGrid(
				axes,
				record.cells.mapIndexed { cellIndex, cell ->
					val cellPath = "$path.cells[$cellIndex]"
					checkDeltas(cell.positionDeltas, mesh, "$cellPath.positionDeltas")
					KeyformCell(coordinateOf(cell.coordinate, axes, cellPath), MeshDeltaForm(cell.positionDeltas))
				},
			)
		}

		/**
		 * Checks position deltas hold one x, y pair per vertex of [mesh]; nothing to check without a mesh.
		 *
		 * @param FloatArray    deltas The deltas.
		 * @param DrawableMesh? mesh   The mesh, if any.
		 * @param String        path   Where the deltas sit, for a failure.
		 */
		fun checkDeltas(deltas: FloatArray, mesh: DrawableMesh?, path: String) {
			if (mesh != null && deltas.size != mesh.positions.size) {
				malformed("$path holds ${deltas.size} floats against the mesh's ${mesh.positions.size} positions")
			}
		}

		/**
		 * UMA §4.11: a warp's geometry grid, whose cells carry a whole lattice and no pivot.
		 *
		 * @param UmaDeformerGrid record      The record.
		 * @param Int             pointFloats The lattice's float count.
		 * @param String          path        The grid's position, for a failure.
		 * @return KeyformGrid The grid.
		 */
		fun warpGridOf(record: UmaDeformerGrid, pointFloats: Int, path: String): KeyformGrid<WarpLatticeForm> {
			val axes = axesOf(record.axes, path)
			return KeyformGrid(
				axes,
				record.cells.mapIndexed { cellIndex, cell ->
					val cellPath = "$path.cells[$cellIndex]"
					KeyformCell(coordinateOf(cell.coordinate, axes, cellPath), WarpLatticeForm(latticeOf(cell, pointFloats, cellPath)))
				},
			)
		}

		/**
		 * A warp cell's lattice, checked to be whole and unaccompanied by pivot fields.
		 *
		 * @param UmaDeformerCell cell        The cell.
		 * @param Int             pointFloats The lattice's float count.
		 * @param String          path        The cell's position, for a failure.
		 * @return FloatArray The control points.
		 */
		fun latticeOf(cell: UmaDeformerCell, pointFloats: Int, path: String): FloatArray {
			if (cell.originX != null || cell.originY != null || cell.angle != null || cell.scale != null) {
				malformed("$path belongs to a warp but carries rotation fields")
			}
			val controlPoints = cell.controlPoints ?: malformed("$path belongs to a warp but has no controlPoints")
			if (controlPoints.size != pointFloats) {
				malformed("$path.controlPoints holds ${controlPoints.size} floats; the lattice needs $pointFloats")
			}
			return controlPoints
		}

		/**
		 * UMA §4.11: a rotation's geometry grid, whose cells carry a whole pivot and no lattice.
		 *
		 * @param UmaDeformerGrid record The record.
		 * @param String          path   The grid's position, for a failure.
		 * @return KeyformGrid The grid.
		 */
		fun rotationGridOf(record: UmaDeformerGrid, path: String): KeyformGrid<RotationPivotForm> {
			val axes = axesOf(record.axes, path)
			return KeyformGrid(
				axes,
				record.cells.mapIndexed { cellIndex, cell ->
					val cellPath = "$path.cells[$cellIndex]"
					if (cell.controlPoints != null) {
						malformed("$cellPath belongs to a rotation but carries controlPoints")
					}
					KeyformCell(
						coordinateOf(cell.coordinate, axes, cellPath),
						RotationPivotForm(
							originX = cell.originX ?: malformed("$cellPath has no originX"),
							originY = cell.originY ?: malformed("$cellPath has no originY"),
							angle = cell.angle ?: malformed("$cellPath has no angle"),
							scale = cell.scale ?: malformed("$cellPath has no scale"),
						),
					)
				},
			)
		}

		/**
		 * UMA §4.12: an owner's channel tracks, each value checked against its channel's kind.
		 *
		 * @param Map?   records The records, or null when the owner keys none.
		 * @param String path    The owner's position, for a failure.
		 * @return ChannelGrids The tracks.
		 */
		fun channelsOf(records: Map<UmaFormChannel, UmaChannelGrid>?, path: String): ChannelGrids {
			if (records.isNullOrEmpty()) {
				return ChannelGrids.Empty
			}
			val tracks = LinkedHashMap<FormChannel, KeyformGrid<ChannelValue>>()
			for ((umaChannel, record) in records) {
				val channel = umaChannel.toRuntime()
				val trackPath = "$path.channels.${umaChannel.wireName()}"
				val axes = axesOf(record.axes, trackPath)
				tracks[channel] =
					KeyformGrid(
						axes,
						record.cells.mapIndexed { cellIndex, cell ->
							val cellPath = "$trackPath.cells[$cellIndex]"
							KeyformCell(coordinateOf(cell.coordinate, axes, cellPath), channelValueOf(cell.value, channel.valueKind, "$cellPath.value"))
						},
					)
			}
			return ChannelGrids(tracks)
		}

		/**
		 * UMA §4.12: one channel value - a number, a color, or a boolean, as the channel's kind requires.
		 *
		 * @param JsonElement      value The JSON.
		 * @param ChannelValueKind kind  The channel's kind.
		 * @param String           path  Where the value sits, for a failure.
		 * @return ChannelValue The value.
		 */
		fun channelValueOf(value: JsonElement, kind: ChannelValueKind, path: String): ChannelValue =
			when (kind) {
				ChannelValueKind.SCALAR -> ChannelValue.Scalar(finiteNumberOf(value) ?: malformed("$path must be a number"))
				ChannelValueKind.COLOR -> {
					val channels = (value as? JsonArray)?.map { component -> finiteNumberOf(component) ?: malformed("$path must be three numbers") }
					if (channels == null || channels.size != 3) {
						malformed("$path must be three numbers")
					}
					ChannelValue.Color(ColorRgb(channels[0], channels[1], channels[2]))
				}

				ChannelValueKind.FLAG -> ChannelValue.Flag((value as? JsonPrimitive)?.takeIf { primitive -> !primitive.isString }?.booleanOrNull ?: malformed("$path must be a boolean"))
			}

		/**
		 * [value] as a finite float, or null when it is not a JSON number or does not fit a finite float.
		 *
		 * @param JsonElement value The JSON.
		 * @return Float? The number.
		 */
		fun finiteNumberOf(value: JsonElement): Float? = (value as? JsonPrimitive)?.takeIf { primitive -> !primitive.isString }?.floatOrNull?.takeIf { number -> number.isFinite() }

		/**
		 * UMA §4.13: the checks every binding shares - one form per key, a neutral key that exists, and keys ascending.
		 *
		 * @param List<Float> keys         The keys.
		 * @param Int         formCount    The number of forms.
		 * @param Int         neutralIndex The neutral key's index.
		 * @param String      path         The binding's position, for a failure.
		 * @return FloatArray The keys.
		 */
		fun bindingKeysOf(keys: List<Float>, formCount: Int, neutralIndex: Int, path: String): FloatArray {
			if (formCount != keys.size) {
				malformed("$path has ${keys.size} keys but $formCount forms")
			}
			if (neutralIndex !in keys.indices) {
				malformed("$path.neutralIndex is $neutralIndex, outside its ${keys.size} keys")
			}
			checkAscending(keys, "$path.keys")
			return keys.toFloatArray()
		}

		/**
		 * UMA §4.13: a binding's limits, each curve's points ascending by value.
		 *
		 * @param List<UmaBlendLimit>? records The records.
		 * @param String               path    The binding's position, for a failure.
		 * @return List<BlendWeightLimit> The limits.
		 */
		fun limitsOf(records: List<UmaBlendLimit>?, path: String): List<BlendWeightLimit> =
			records.orEmpty().mapIndexed { limitIndex, limit ->
				checkAscending(limit.points.map { point -> point.value }, "$path.limits[$limitIndex].points")
				BlendWeightLimit(ParameterId(limit.parameter), limit.points.map { point -> BlendWeightLimitPoint(point.value, point.weight) })
			}

		/**
		 * UMA §4.13: a drawable's blend-shape binding.
		 *
		 * @param UmaMeshBlendShape record The record.
		 * @param DrawableMesh?     mesh   The drawable's mesh, if it has one.
		 * @param String            path   The binding's position, for a failure.
		 * @return BlendShapeBinding The binding.
		 */
		fun meshBlendShapeOf(record: UmaMeshBlendShape, mesh: DrawableMesh?, path: String): BlendShapeBinding<MeshForm> =
			BlendShapeBinding(
				parameterId = ParameterId(record.parameter),
				keys = bindingKeysOf(record.keys, record.forms.size, record.neutralIndex, path),
				neutralIndex = record.neutralIndex,
				forms =
					record.forms.mapIndexed { formIndex, form ->
						form?.let {
							val formPath = "$path.forms[$formIndex]"
							checkDeltas(form.positionDeltas, mesh, "$formPath.positionDeltas")
							MeshForm(
								positionDeltas = form.positionDeltas,
								drawOrder = form.drawOrder ?: DEFAULT_DRAW_ORDER.toFloat(),
								opacity = form.opacity ?: 1f,
								multiplyColor = colorOf(form.multiplyColor, ColorRgb.MultiplyIdentity, "$formPath.multiplyColor"),
								screenColor = colorOf(form.screenColor, ColorRgb.ScreenIdentity, "$formPath.screenColor"),
							)
						}
					},
				limits = limitsOf(record.limits, path),
			)

		/**
		 * UMA §4.13: a warp's blend-shape binding, whose forms carry a whole lattice and no pivot.
		 *
		 * @param UmaDeformerBlendShape record      The record.
		 * @param Int                   pointFloats The lattice's float count.
		 * @param String                path        The binding's position, for a failure.
		 * @return BlendShapeBinding The binding.
		 */
		fun warpBlendShapeOf(record: UmaDeformerBlendShape, pointFloats: Int, path: String): BlendShapeBinding<WarpForm> =
			BlendShapeBinding(
				parameterId = ParameterId(record.parameter),
				keys = bindingKeysOf(record.keys, record.forms.size, record.neutralIndex, path),
				neutralIndex = record.neutralIndex,
				forms =
					record.forms.mapIndexed { formIndex, form ->
						form?.let {
							val formPath = "$path.forms[$formIndex]"
							WarpForm(
								controlPoints = warpFormLatticeOf(form, pointFloats, formPath),
								opacity = form.opacity ?: 1f,
								multiplyColor = colorOf(form.multiplyColor, ColorRgb.MultiplyIdentity, "$formPath.multiplyColor"),
								screenColor = colorOf(form.screenColor, ColorRgb.ScreenIdentity, "$formPath.screenColor"),
							)
						}
					},
				limits = limitsOf(record.limits, path),
			)

		/**
		 * A warp blend form's lattice, checked to be whole and unaccompanied by rotation fields.
		 *
		 * @param UmaDeformerForm form        The form.
		 * @param Int             pointFloats The lattice's float count.
		 * @param String          path        The form's position, for a failure.
		 * @return FloatArray The control points.
		 */
		fun warpFormLatticeOf(form: UmaDeformerForm, pointFloats: Int, path: String): FloatArray {
			if (form.originX != null || form.originY != null || form.angle != null || form.scale != null || form.flipX != null || form.flipY != null) {
				malformed("$path belongs to a warp but carries rotation fields")
			}
			val controlPoints = form.controlPoints ?: malformed("$path belongs to a warp but has no controlPoints")
			if (controlPoints.size != pointFloats) {
				malformed("$path.controlPoints holds ${controlPoints.size} floats; the lattice needs $pointFloats")
			}
			return controlPoints
		}

		/**
		 * UMA §4.13: a rotation's blend-shape binding, whose forms carry a whole pivot and no lattice.
		 *
		 * @param UmaDeformerBlendShape record The record.
		 * @param String                path   The binding's position, for a failure.
		 * @return BlendShapeBinding The binding.
		 */
		fun rotationBlendShapeOf(record: UmaDeformerBlendShape, path: String): BlendShapeBinding<RotationForm> =
			BlendShapeBinding(
				parameterId = ParameterId(record.parameter),
				keys = bindingKeysOf(record.keys, record.forms.size, record.neutralIndex, path),
				neutralIndex = record.neutralIndex,
				forms =
					record.forms.mapIndexed { formIndex, form ->
						form?.let {
							val formPath = "$path.forms[$formIndex]"
							if (form.controlPoints != null) {
								malformed("$formPath belongs to a rotation but carries controlPoints")
							}
							RotationForm(
								originX = form.originX ?: malformed("$formPath has no originX"),
								originY = form.originY ?: malformed("$formPath has no originY"),
								angle = form.angle ?: malformed("$formPath has no angle"),
								scale = form.scale ?: malformed("$formPath has no scale"),
								flipX = form.flipX ?: false,
								flipY = form.flipY ?: false,
								opacity = form.opacity ?: 1f,
								multiplyColor = colorOf(form.multiplyColor, ColorRgb.MultiplyIdentity, "$formPath.multiplyColor"),
								screenColor = colorOf(form.screenColor, ColorRgb.ScreenIdentity, "$formPath.screenColor"),
							)
						}
					},
				limits = limitsOf(record.limits, path),
			)

		/**
		 * UMA §4.13: a part's blend-shape binding.
		 *
		 * @param UmaPartBlendShape record The record.
		 * @param String            path   The binding's position, for a failure.
		 * @return BlendShapeBinding The binding.
		 */
		fun partBlendShapeOf(record: UmaPartBlendShape, path: String): BlendShapeBinding<PartForm> =
			BlendShapeBinding(
				parameterId = ParameterId(record.parameter),
				keys = bindingKeysOf(record.keys, record.forms.size, record.neutralIndex, path),
				neutralIndex = record.neutralIndex,
				forms =
					record.forms.mapIndexed { formIndex, form ->
						form?.let {
							val formPath = "$path.forms[$formIndex]"
							PartForm(
								drawOrder = form.drawOrder,
								opacity = form.opacity ?: 1f,
								multiplyColor = colorOf(form.multiplyColor, ColorRgb.MultiplyIdentity, "$formPath.multiplyColor"),
								screenColor = colorOf(form.screenColor, ColorRgb.ScreenIdentity, "$formPath.screenColor"),
							)
						}
					},
				limits = limitsOf(record.limits, path),
			)

		/**
		 * UMA §4.1: a color, which is exactly three channels, or [identity] when absent.
		 *
		 * @param List<Float>? channels The record's channels.
		 * @param ColorRgb     identity The channel's identity color.
		 * @param String       path     The color's position, for a failure.
		 * @return ColorRgb The color.
		 */
		fun colorOf(channels: List<Float>?, identity: ColorRgb, path: String): ColorRgb {
			if (channels == null) {
				return identity
			}
			if (channels.size != 3) {
				malformed("$path has ${channels.size} channels instead of red, green, and blue")
			}
			return ColorRgb(channels[0], channels[1], channels[2])
		}
	}
}