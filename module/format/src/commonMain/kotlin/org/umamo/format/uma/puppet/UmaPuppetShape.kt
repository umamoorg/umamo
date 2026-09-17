package org.umamo.format.uma.puppet

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull

/** UMA §4.12: what a channel track's cells hold. */
internal enum class UmaChannelValueKind {
	/** A finite float. */
	Number,

	/** Exactly three finite floats. */
	Color,

	/** A boolean. */
	Flag,
}

/** UMA §4.12: the value kind each channel's cells hold. */
internal val UmaFormChannel.valueKind: UmaChannelValueKind
	get() =
		when (this) {
			UmaFormChannel.DrawOrder, UmaFormChannel.Opacity, UmaFormChannel.GlueIntensity -> UmaChannelValueKind.Number
			UmaFormChannel.MultiplyColor, UmaFormChannel.ScreenColor -> UmaChannelValueKind.Color
			UmaFormChannel.FlipX, UmaFormChannel.FlipY -> UmaChannelValueKind.Flag
		}

/**
 * The puppet entry's shape rules the schema classes cannot enforce on their own (docs/format/UMA.md §4.8, §4.10-4.14):
 * the fields that belong to one kind of object and not another, the arrays that must agree in size, and the indices
 * and keys that must stay in range and in order.
 *
 * The reader refuses a file that breaks one, and the writer refuses to save one, so a document Umamo saves always
 * reopens.  Each problem names the value's path the way the reader reports it (`drawables[D].mesh.indices[2]`).
 */
internal object UmaPuppetShape {
	/**
	 * Where [puppet] first breaks a shape rule.
	 *
	 * @param UmaPuppet puppet The puppet.
	 * @return String? A description of the first problem, or null when every rule holds.
	 */
	fun firstProblem(puppet: UmaPuppet): String? =
		try {
			checkPuppet(puppet)
			null
		} catch (problem: ShapeProblem) {
			problem.detail
		}

	/**
	 * The first problem found, carried out of the walk that found it.
	 *
	 * @property String detail What is wrong, and where.
	 */
	private class ShapeProblem(val detail: String) : RuntimeException(detail)

	/**
	 * Ends the walk with [detail] as the problem.
	 *
	 * @param String detail What is wrong, and where.
	 * @return Nothing Never returns.
	 */
	private fun problem(detail: String): Nothing = throw ShapeProblem(detail)

	/**
	 * Checks every rule over the whole puppet.
	 *
	 * @param UmaPuppet puppet The puppet.
	 */
	private fun checkPuppet(puppet: UmaPuppet) {
		val drawables = puppet.drawables.orEmpty()
		for (drawable in drawables) {
			checkDrawable(drawable)
		}
		puppet.parameterTree.orEmpty().forEachIndexed { nodeIndex, node -> checkParameterNode(node, "parameterTree[$nodeIndex]") }
		for (part in puppet.parts.orEmpty()) {
			checkPart(part)
		}
		for (deformer in puppet.deformers.orEmpty()) {
			checkDeformer(deformer)
		}
		// UMA §4.14: a glue's indices are checked against the meshes it welds, when those meshes are in the entry.
		val vertexCountByDrawable = drawables.associate { drawable -> drawable.id to drawable.mesh?.let { mesh -> mesh.positions.size / 2 } }
		for (glue in puppet.glues.orEmpty()) {
			checkGlue(glue, vertexCountByDrawable)
		}
		puppet.rootChildren.orEmpty().forEachIndexed { childIndex, child -> checkOrgRef(child, "rootChildren[$childIndex]") }
	}

	/**
	 * UMA §4.3: a parameter-tree node, which is exactly one of a leaf and a named group.
	 *
	 * @param UmaParameterNode node The node.
	 * @param String           path The node's position.
	 */
	private fun checkParameterNode(node: UmaParameterNode, path: String) {
		val isLeaf = node.parameter != null
		val isGroup = node.group != null
		if (isLeaf == isGroup) {
			problem("$path must name exactly one of a parameter and a group")
		}
		if (isLeaf) {
			if (node.name != null || node.initiallyOpen != null || node.children != null) {
				problem("$path is a parameter leaf but carries group fields")
			}
			return
		}
		if (node.name == null) {
			problem("$path is a group without a name")
		}
		node.children.orEmpty().forEachIndexed { childIndex, child -> checkParameterNode(child, "$path.children[$childIndex]") }
	}

	/**
	 * UMA §4.2: an organizational child, which is exactly one of a part and a drawable.
	 *
	 * @param UmaOrgRef reference The reference.
	 * @param String    path      The reference's position.
	 */
	private fun checkOrgRef(reference: UmaOrgRef, path: String) {
		if ((reference.part != null) == (reference.drawable != null)) {
			problem("$path must name exactly one of a part and a drawable")
		}
	}

	/**
	 * UMA §4.4: a part, its children, composite, tracks, and blend shapes.
	 *
	 * @param UmaPart part The part.
	 */
	private fun checkPart(part: UmaPart) {
		val path = "parts[${part.id}]"
		part.children.orEmpty().forEachIndexed { childIndex, child -> checkOrgRef(child, "$path.children[$childIndex]") }
		part.composite?.let { composite ->
			checkColor(composite.multiplyColor, "$path.composite.multiplyColor")
			checkColor(composite.screenColor, "$path.composite.screenColor")
		}
		checkChannels(part.channels, path)
		part.blendShapes.orEmpty().forEachIndexed { bindingIndex, binding ->
			val bindingPath = "$path.blendShapes[$bindingIndex]"
			checkBinding(binding.keys, binding.forms.size, binding.neutralIndex, binding.limits, bindingPath)
			binding.forms.forEachIndexed { formIndex, form ->
				form?.let {
					checkColor(form.multiplyColor, "$bindingPath.forms[$formIndex].multiplyColor")
					checkColor(form.screenColor, "$bindingPath.forms[$formIndex].screenColor")
				}
			}
		}
	}

	/**
	 * UMA §4.5: a deformer, whose kind decides which fields it must and must not carry and what its forms hold.
	 *
	 * @param UmaDeformer deformer The deformer.
	 */
	private fun checkDeformer(deformer: UmaDeformer) {
		val path = "deformers[${deformer.id}]"
		checkColor(deformer.multiplyColor, "$path.multiplyColor")
		checkColor(deformer.screenColor, "$path.screenColor")
		when (deformer.kind) {
			UmaDeformerKind.Warp -> {
				if (deformer.baseAngle != null || deformer.flipX != null || deformer.flipY != null) {
					problem("$path is a warp but carries rotation fields")
				}
				val rows = deformer.rows ?: problem("$path is a warp without rows")
				val columns = deformer.columns ?: problem("$path is a warp without columns")
				if (rows < 0 || columns < 0) {
					problem("$path has a negative lattice size")
				}
				// UMA §4.11: a rows x columns lattice holds (rows + 1) x (columns + 1) control points, counted wide so a
				// huge lattice cannot wrap to a small float count its arrays would then pass.
				val pointFloatsWide = (rows + 1L) * (columns + 1L) * 2L
				if (pointFloatsWide > Int.MAX_VALUE) {
					problem("$path has a $rows x $columns lattice, too large to hold")
				}
				val pointFloats = pointFloatsWide.toInt()
				if (deformer.isQuadTransform == null) {
					problem("$path is a warp without isQuadTransform")
				}
				deformer.geometry?.let { grid ->
					checkGrid(grid.axes, grid.cells.map { cell -> cell.coordinate }, "$path.geometry")
					grid.cells.forEachIndexed { cellIndex, cell ->
						val cellPath = "$path.geometry.cells[$cellIndex]"
						if (cell.originX != null || cell.originY != null || cell.angle != null || cell.scale != null) {
							problem("$cellPath belongs to a warp but carries rotation fields")
						}
						checkLattice(cell.controlPoints, pointFloats, cellPath)
					}
				}
				checkChannels(deformer.channels, path)
				deformer.blendShapes.orEmpty().forEachIndexed { bindingIndex, binding ->
					val bindingPath = "$path.blendShapes[$bindingIndex]"
					checkBinding(binding.keys, binding.forms.size, binding.neutralIndex, binding.limits, bindingPath)
					binding.forms.forEachIndexed { formIndex, form ->
						form?.let {
							val formPath = "$bindingPath.forms[$formIndex]"
							if (form.originX != null || form.originY != null || form.angle != null || form.scale != null || form.flipX != null || form.flipY != null) {
								problem("$formPath belongs to a warp but carries rotation fields")
							}
							checkLattice(form.controlPoints, pointFloats, formPath)
							checkColor(form.multiplyColor, "$formPath.multiplyColor")
							checkColor(form.screenColor, "$formPath.screenColor")
						}
					}
				}
			}

			UmaDeformerKind.Rotation -> {
				if (deformer.rows != null || deformer.columns != null || deformer.isQuadTransform != null) {
					problem("$path is a rotation but carries warp fields")
				}
				if (deformer.baseAngle == null) {
					problem("$path is a rotation without baseAngle")
				}
				deformer.geometry?.let { grid ->
					checkGrid(grid.axes, grid.cells.map { cell -> cell.coordinate }, "$path.geometry")
					grid.cells.forEachIndexed { cellIndex, cell ->
						val cellPath = "$path.geometry.cells[$cellIndex]"
						if (cell.controlPoints != null) {
							problem("$cellPath belongs to a rotation but carries controlPoints")
						}
						checkPivot(cell.originX, cell.originY, cell.angle, cell.scale, cellPath)
					}
				}
				checkChannels(deformer.channels, path)
				deformer.blendShapes.orEmpty().forEachIndexed { bindingIndex, binding ->
					val bindingPath = "$path.blendShapes[$bindingIndex]"
					checkBinding(binding.keys, binding.forms.size, binding.neutralIndex, binding.limits, bindingPath)
					binding.forms.forEachIndexed { formIndex, form ->
						form?.let {
							val formPath = "$bindingPath.forms[$formIndex]"
							if (form.controlPoints != null) {
								problem("$formPath belongs to a rotation but carries controlPoints")
							}
							checkPivot(form.originX, form.originY, form.angle, form.scale, formPath)
							checkColor(form.multiplyColor, "$formPath.multiplyColor")
							checkColor(form.screenColor, "$formPath.screenColor")
						}
					}
				}
			}
		}
	}

	/**
	 * UMA §4.6: a drawable, its mesh, and everything sized against the mesh.
	 *
	 * @param UmaDrawable drawable The drawable.
	 */
	private fun checkDrawable(drawable: UmaDrawable) {
		val path = "drawables[${drawable.id}]"
		val mesh = drawable.mesh
		mesh?.let { checkMesh(mesh, "$path.mesh") }
		drawable.geometry?.let { grid ->
			checkGrid(grid.axes, grid.cells.map { cell -> cell.coordinate }, "$path.geometry")
			grid.cells.forEachIndexed { cellIndex, cell -> checkDeltas(cell.positionDeltas, mesh, "$path.geometry.cells[$cellIndex].positionDeltas") }
		}
		checkChannels(drawable.channels, path)
		checkColor(drawable.multiplyColor, "$path.multiplyColor")
		checkColor(drawable.screenColor, "$path.screenColor")
		drawable.blendShapes.orEmpty().forEachIndexed { bindingIndex, binding ->
			val bindingPath = "$path.blendShapes[$bindingIndex]"
			checkBinding(binding.keys, binding.forms.size, binding.neutralIndex, binding.limits, bindingPath)
			binding.forms.forEachIndexed { formIndex, form ->
				form?.let {
					val formPath = "$bindingPath.forms[$formIndex]"
					checkDeltas(form.positionDeltas, mesh, "$formPath.positionDeltas")
					checkColor(form.multiplyColor, "$formPath.multiplyColor")
					checkColor(form.screenColor, "$formPath.screenColor")
				}
			}
		}
	}

	/**
	 * UMA §4.10: a mesh's arrays, checked against each other.
	 *
	 * @param UmaMesh mesh The mesh.
	 * @param String  path The mesh's position.
	 */
	private fun checkMesh(mesh: UmaMesh, path: String) {
		if (mesh.positions.size % 2 != 0) {
			problem("$path.positions holds ${mesh.positions.size} floats, not whole x, y pairs")
		}
		if (mesh.uvs.size != mesh.positions.size) {
			problem("$path.uvs holds ${mesh.uvs.size} floats against ${mesh.positions.size} positions")
		}
		if (mesh.indices.size % 3 != 0) {
			problem("$path.indices holds ${mesh.indices.size} indices, not whole triangles")
		}
		checkIndices(mesh.indices, mesh.positions.size / 2, "$path.indices")
	}

	/**
	 * Checks every index names a vertex of a mesh with [vertexCount] vertices; nothing to check when the count is
	 * unknown (the mesh is absent).
	 *
	 * @param IntArray indices     The indices.
	 * @param Int?     vertexCount The mesh's vertex count, or null.
	 * @param String   path        Where the indices sit.
	 */
	private fun checkIndices(indices: IntArray, vertexCount: Int?, path: String) {
		if (vertexCount == null) {
			return
		}
		indices.forEachIndexed { indexIndex, index ->
			if (index !in 0 until vertexCount) {
				problem("$path[$indexIndex] is $index, outside the mesh's $vertexCount vertices")
			}
		}
	}

	/**
	 * Checks position deltas hold one x, y pair per vertex of [mesh]; nothing to check without a mesh.
	 *
	 * @param FloatArray deltas The deltas.
	 * @param UmaMesh?   mesh   The drawable's mesh, if any.
	 * @param String     path   Where the deltas sit.
	 */
	private fun checkDeltas(deltas: FloatArray, mesh: UmaMesh?, path: String) {
		if (mesh != null && deltas.size != mesh.positions.size) {
			problem("$path holds ${deltas.size} floats against the mesh's ${mesh.positions.size} positions")
		}
	}

	/**
	 * Checks a warp form's lattice is there and whole.
	 *
	 * @param FloatArray? controlPoints The control points, or null.
	 * @param Int         pointFloats   The lattice's float count.
	 * @param String      path          The form's position.
	 */
	private fun checkLattice(controlPoints: FloatArray?, pointFloats: Int, path: String) {
		if (controlPoints == null) {
			problem("$path belongs to a warp but has no controlPoints")
		}
		if (controlPoints.size != pointFloats) {
			problem("$path.controlPoints holds ${controlPoints.size} floats; the lattice needs $pointFloats")
		}
	}

	/**
	 * Checks a rotation form carries its whole pivot.
	 *
	 * @param Float? originX The pivot's x.
	 * @param Float? originY The pivot's y.
	 * @param Float? angle   The angle.
	 * @param Float? scale   The scale.
	 * @param String path    The form's position.
	 */
	private fun checkPivot(originX: Float?, originY: Float?, angle: Float?, scale: Float?, path: String) {
		when {
			originX == null -> problem("$path has no originX")
			originY == null -> problem("$path has no originY")
			angle == null -> problem("$path has no angle")
			scale == null -> problem("$path has no scale")
		}
	}

	/**
	 * UMA §4.11: a grid's axes, each one's keys ascending, and its cells' coordinates, each naming one key on every
	 * axis.
	 *
	 * @param List<UmaAxis>   axes        The axes.
	 * @param List<List<Int>> coordinates The cells' coordinates, in cell order.
	 * @param String          path        The grid's position.
	 */
	private fun checkGrid(axes: List<UmaAxis>, coordinates: List<List<Int>>, path: String) {
		axes.forEachIndexed { axisIndex, axis -> checkAscending(axis.keys, "$path.axes[$axisIndex].keys") }
		coordinates.forEachIndexed { cellIndex, coordinate ->
			val cellPath = "$path.cells[$cellIndex]"
			if (coordinate.size != axes.size) {
				problem("$cellPath.coordinate has ${coordinate.size} components for ${axes.size} axes")
			}
			coordinate.forEachIndexed { axisIndex, keyIndex ->
				if (keyIndex !in axes[axisIndex].keys.indices) {
					problem("$cellPath.coordinate[$axisIndex] is $keyIndex, outside the axis's ${axes[axisIndex].keys.size} keys")
				}
			}
		}
	}

	/**
	 * Checks [values] never step down: the evaluator brackets a parameter value by scanning keys in order, so a key
	 * below its predecessor would place forms at the wrong values without any error.  Equal neighbors are allowed;
	 * the evaluator treats their empty span as a snap.
	 *
	 * @param List<Float> values The values, in stored order.
	 * @param String      path   Where the list sits; a problem names the element under it.
	 */
	private fun checkAscending(values: List<Float>, path: String) {
		for (valueIndex in 1 until values.size) {
			if (values[valueIndex] < values[valueIndex - 1]) {
				problem("$path[$valueIndex] is ${values[valueIndex]}, below the ${values[valueIndex - 1]} before it")
			}
		}
	}

	/**
	 * UMA §4.12: an owner's channel tracks, each a grid whose values match their channel's kind.
	 *
	 * @param Map?   channels The tracks, or null when the owner keys none.
	 * @param String path     The owner's position.
	 */
	private fun checkChannels(channels: Map<UmaFormChannel, UmaChannelGrid>?, path: String) {
		for ((channel, track) in channels.orEmpty()) {
			val trackPath = "$path.channels.${UmaFormChannel.serializer().descriptor.getElementName(channel.ordinal)}"
			checkGrid(track.axes, track.cells.map { cell -> cell.coordinate }, trackPath)
			track.cells.forEachIndexed { cellIndex, cell -> checkChannelValue(cell.value, channel.valueKind, "$trackPath.cells[$cellIndex].value") }
		}
	}

	/**
	 * UMA §4.12: one channel value, which is a finite number, three of them, or a boolean, as its channel requires.
	 *
	 * @param JsonElement         value The value.
	 * @param UmaChannelValueKind kind  The channel's value kind.
	 * @param String              path  Where the value sits.
	 */
	private fun checkChannelValue(value: JsonElement, kind: UmaChannelValueKind, path: String) {
		when (kind) {
			UmaChannelValueKind.Number -> {
				if (finiteNumberOf(value) == null) {
					problem("$path must be a number")
				}
			}

			UmaChannelValueKind.Color -> {
				val components = value as? JsonArray
				if (components == null || components.size != 3 || components.any { component -> finiteNumberOf(component) == null }) {
					problem("$path must be three numbers")
				}
			}

			UmaChannelValueKind.Flag -> {
				if ((value as? JsonPrimitive)?.takeIf { primitive -> !primitive.isString }?.booleanOrNull == null) {
					problem("$path must be a boolean")
				}
			}
		}
	}

	/**
	 * [value] as a finite float, or null when it is not a JSON number or does not fit a finite float.
	 *
	 * @param JsonElement value The value.
	 * @return Float? The number.
	 */
	private fun finiteNumberOf(value: JsonElement): Float? = (value as? JsonPrimitive)?.takeIf { primitive -> !primitive.isString }?.floatOrNull?.takeIf { number -> number.isFinite() }

	/**
	 * UMA §4.13: the rules every blend-shape binding shares - one form per key, a neutral key that exists, keys
	 * ascending, and each limit's points ascending by value.
	 *
	 * @param List<Float>          keys         The keys.
	 * @param Int                  formCount    The number of forms.
	 * @param Int                  neutralIndex The neutral key's index.
	 * @param List<UmaBlendLimit>? limits       The limits, or null.
	 * @param String               path         The binding's position.
	 */
	private fun checkBinding(keys: List<Float>, formCount: Int, neutralIndex: Int, limits: List<UmaBlendLimit>?, path: String) {
		if (formCount != keys.size) {
			problem("$path has ${keys.size} keys but $formCount forms")
		}
		if (neutralIndex !in keys.indices) {
			problem("$path.neutralIndex is $neutralIndex, outside its ${keys.size} keys")
		}
		checkAscending(keys, "$path.keys")
		limits.orEmpty().forEachIndexed { limitIndex, limit -> checkAscending(limit.points.map { point -> point.value }, "$path.limits[$limitIndex].points") }
	}

	/**
	 * UMA §4.14: a glue's pairs, whose arrays agree in length and whose indices name vertices of the meshes it welds.
	 *
	 * @param UmaGlue glue                  The glue.
	 * @param Map     vertexCountByDrawable Each drawable's vertex count, null for a drawable with no mesh.
	 */
	private fun checkGlue(glue: UmaGlue, vertexCountByDrawable: Map<String, Int?>) {
		val path = "glues[${glue.meshA},${glue.meshB}]"
		val pairs = glue.pairs
		val pairCount = pairs.indicesA.size
		if (pairs.indicesB.size != pairCount || pairs.weightsA.size != pairCount || pairs.weightsB.size != pairCount) {
			problem("$path.pairs holds arrays of different lengths")
		}
		checkIndices(pairs.indicesA, vertexCountByDrawable[glue.meshA], "$path.pairs.indicesA")
		checkIndices(pairs.indicesB, vertexCountByDrawable[glue.meshB], "$path.pairs.indicesB")
		checkChannels(glue.channels, path)
	}

	/**
	 * UMA §4.1: an optional color, which is exactly three channels when present.
	 *
	 * @param List<Float>? channels The color's channels, or null.
	 * @param String       path     Where the color sits.
	 */
	private fun checkColor(channels: List<Float>?, path: String) {
		if (channels != null && channels.size != 3) {
			problem("$path has ${channels.size} channels instead of red, green, and blue")
		}
	}
}