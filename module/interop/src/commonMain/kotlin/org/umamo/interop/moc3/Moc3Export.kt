package org.umamo.interop.moc3

import org.umamo.format.moc3.MocDocument
import org.umamo.format.moc3.encode.MocEncoder
import org.umamo.format.moc3.moc.CanvasInfo
import org.umamo.format.moc3.moc.ConstantFlag
import org.umamo.format.moc3.moc.MocVersion
import org.umamo.format.moc3.moc.ParameterType
import org.umamo.format.moc3.moc.Sections
import org.umamo.format.moc3.model.ArtMesh
import org.umamo.format.moc3.model.ArtMeshKeyform
import org.umamo.format.moc3.model.GlueVertexPair
import org.umamo.format.moc3.model.RotationDeformer
import org.umamo.format.moc3.model.RotationKeyform
import org.umamo.format.moc3.model.WarpDeformer
import org.umamo.format.moc3.model.WarpKeyform
import org.umamo.interop.ExportNotice
import org.umamo.interop.ExportReport
import org.umamo.interop.legacyBlendFlagOf
import org.umamo.interop.mocVersion
import org.umamo.interop.packedBlendOf
import org.umamo.runtime.keyform.MeshDeltaInterpolator
import org.umamo.runtime.keyform.RotationPivotInterpolator
import org.umamo.runtime.keyform.WarpLatticeInterpolator
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.partByDrawable
import org.umamo.format.moc3.moc.MocParameter as MocParameter
import org.umamo.format.moc3.model.Glue as MocGlue
import org.umamo.format.moc3.model.Part as MocPart

/**
 * Lowers a [PuppetModel] into a [MocDocument] - the semantic half of writing a `.moc3` from a rig
 * that may never have been one.
 *
 * This is a FULL SYNTHESIS, deliberately unlike the CMO3 export's state-based reconcile.  That
 * reconcile exists to preserve unmodeled XML the writer does not understand; a MOC3 has no such
 * payload once every section index is modeled, so there is nothing to carry and a reference
 * container would only constrain the output.  A CMO3-origin or future UMA-origin document
 * therefore exports exactly like a MOC3-origin one.
 *
 * THE LOAD-BEARING INVARIANT, which every geometry path here depends on:
 * `drawable.mesh.positions[i] + cell.positionDeltas[i]` is the drawable's ABSOLUTE position in its
 * parent-deformer space, for every document origin.  `restMeshesToCanvasSpace` rewrites the base
 * and compensates the deltas so the sum is untouched, and CMO3 stores the same mixed-space
 * convention natively - which is what lets one lowering serve both.
 *
 * An export ALWAYS writes.  Anything it cannot express becomes an [ExportNotice] rather than a
 * silent drop, including hidden objects, which are CARRIED with their flag clear rather than
 * deleted the way the official editor's bake deletes them.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §5.6</a>
 */
object Moc3Export {
	/**
	 * The lowered document plus whatever the lowering could not express.
	 *
	 * @property MocDocument document The document to bake.
	 * @property ExportReport report  The advisory findings; empty for a fully-lowered export.
	 */
	class Lowered(val document: MocDocument, val report: ExportReport)

	/**
	 * Lowers [puppet] into a [MocDocument] at [version], stripping whatever that version cannot carry.
	 *
	 * The strip runs FIRST, on the model (see [Moc3VersionDowngrade]), so everything below this line
	 * works on a rig the target version can express completely - and the loss is reported against
	 * entities the rigger recognises rather than against section indices.
	 *
	 * @param PuppetModel puppet  The rig to export.
	 * @param MocVersion  version The moc version to target; the document's own runtime target by default.
	 * @param CanvasToParentSpace? canvasToParentSpace Inverts the deformer chain for an unkeyed
	 *   drawable; null drops those drawables with a notice instead (see [CanvasToParentSpace]).
	 * @return Lowered The document and its notices.
	 */
	fun toMocDocument(
		puppet: PuppetModel,
		version: MocVersion = puppet.runtimeTarget.mocVersion(),
		canvasToParentSpace: CanvasToParentSpace? = null,
	): Lowered {
		val notices = ArrayList<ExportNotice>()
		val downgraded = Moc3VersionDowngrade.strip(puppet, version)
		notices.addAll(downgraded.notices)
		@Suppress("NAME_SHADOWING")
		val puppet = downgraded.puppet
		// Which drawables survive is decided BEFORE the index plan, because the plan's indices are the
		// file's addressing scheme: a drawable dropped after the plan was built would leave every later
		// index - and every mask reference into them - naming the wrong object.
		// A SKETCH part is a guide overlay - a scan or a rough the rigger traces over - and the official
		// bake leaves it out of the moc entirely.  The whole subtree goes: a guide's drawables are the
		// thing that would otherwise render in the runtime, sitting on top of the puppet.
		val sketchParts = sketchSubtree(puppet)
		val exportableParts = puppet.parts.mapNotNullTo(LinkedHashSet()) { part -> part.id.takeIf { it !in sketchParts } }
		val partByDrawable = puppet.partByDrawable()
		// The org tree's part->parent link, inverted ONCE.  Both of these are asked per object further
		// down, and re-deriving either there turns its pass into a quadratic rescan of the whole tree.
		val partParentById = HashMap<org.umamo.runtime.model.PartId, org.umamo.runtime.model.PartId>()
		for (part in puppet.parts) {
			for (child in part.children) {
				if (child is org.umamo.runtime.model.OrgChild.Part) {
					partParentById[child.id] = part.id
				}
			}
		}
		val dropped = LinkedHashMap<org.umamo.runtime.model.DrawableId, String>()
		for (drawable in puppet.drawables) {
			if (partByDrawable[drawable.id] in sketchParts) {
				dropped[drawable.id] = "a guide-image (sketch) part is not runtime content"
			} else if (drawable.mesh == null) {
				dropped[drawable.id] = "a drawable with no mesh cannot be written"
			} else if (drawable.geometryGrid == null && drawable.parentDeformerId != null && canvasToParentSpace == null) {
				// The rest mesh is CANVAS-space while a parented drawable stores parent-local values, and
				// with no grid there are no deltas to recover the parent-local form from.  Inverting the
				// deformer chain needs :render's damped-Newton warp inverse, which :interop cannot reach -
				// so without the injected seam the drawable is dropped rather than written at the wrong
				// scale, which is what a canvas-space value under a warp would be.
				dropped[drawable.id] = "an unkeyed drawable under a deformer has no parent-space geometry to write"
			}
		}
		val exportable = puppet.drawables.filter { drawable -> drawable.id !in dropped }
		val plan = Moc3IndexPlan.of(puppet, exportable, exportableParts)
		val pixelsPerUnit = mocPixelsPerUnitFor(puppet)
		val canvas = MocCanvasMapping(pixelsPerUnit, puppet.worldOriginX, -puppet.worldOriginY)
		val pool = Moc3KeyformPool { parameterId -> plan.parameterIndex(parameterId) }
		// Per-object multiply/screen colour arrived in Cubism 4.2; below that the tables do not exist and
		// every keyform must carry null rather than an identity, or the lowering would synthesize
		// sections the version cannot address.
		val colorsEnabled = version.byteValue >= 4
		// Offscreen rendering (an isolated part composited as one layer) arrived in Cubism 5.3, as did the
		// extended blend surface.  Two names for one gate: they are separate features that happen to
		// share a version, and a later version bump should be able to move one without the other.
		val offscreensEnabled = version.byteValue >= 6
		val extendedBlendEnabled = version.byteValue >= 6
		val rotationAncestors = rotationAncestorsById(plan.deformers)
		// Id -> object, for the blend-shape pass's owner lookup.  That lookup runs once per blend-shape
		// owner, so resolving it by scanning the plan's lists would make the pass quadratic in rig size.
		val drawableById = plan.drawables.associateBy { drawable -> drawable.id }
		val deformerById = plan.deformers.associateBy { deformer -> deformer.id }

		/**
		 * Records a notice for something the lowering could not express.
		 *
		 * @param String category The entity category.
		 * @param String subject  The entity's id.
		 * @param String detail   What was not lowered.
		 */
		fun unsupported(category: String, subject: String, detail: String) {
			notices.add(ExportNotice.UnsupportedChange(category, subject, detail))
		}

		/**
		 * The ID as a MOC3 can actually store it, reporting anything that had to be cut.
		 *
		 * MOC3 §5.4 makes every id a fixed 64-byte record, so an id whose UTF-8 form does not fit with
		 * room for the terminator cannot be written.  The writer's own precondition rejects one, and an
		 * export that let that reach the caller would throw straight past the report and out of the file
		 * write - a crash where every other unrepresentable condition here produces a notice and a file.
		 * CMO3 places no width limit on these ids, and 22 CJK characters already exceed the record.
		 *
		 * @param String category The entity category, for the notice.
		 * @param String id       The id the model carries.
		 * @return String The id to write.
		 */
		fun mocId(category: String, id: String): String {
			if (id.encodeToByteArray().size < Sections.ID_STRIDE) {
				return id
			}
			// Trimmed by CHARACTER so the result stays valid UTF-8; cutting at a byte offset could land
			// mid-sequence and write a broken code point into the record.
			var fitted = id
			while (fitted.isNotEmpty() && fitted.encodeToByteArray().size >= Sections.ID_STRIDE) {
				fitted = fitted.substring(0, fitted.length - 1)
			}
			unsupported(
				category,
				id,
				"the id does not fit a moc's ${Sections.ID_STRIDE}-byte id record, so it was written " +
					"truncated to \"$fitted\"; shorten it if another object now shares that name",
			)
			return fitted
		}

		/**
		 * Reports every channel a bundle had to drop to its static.
		 *
		 * @param String              category The entity category.
		 * @param String              subject  The entity's id.
		 * @param Moc3ObjectKeyforms? keyforms The lowered keyforms, or null when unrepresentable.
		 */
		fun reportDemotions(category: String, subject: String, keyforms: Moc3ObjectKeyforms?) {
			for (channel in keyforms?.demotedChannels.orEmpty()) {
				unsupported(
					category,
					subject,
					"$channel is keyed over a narrower span than the object's grid, so it was written " +
						"as a constant (MOC3 stores one grid per object)",
				)
			}
		}

		// ---- parameters ----
		val parameters =
			plan.parameters.map { parameter ->
				MocParameter(
					id = mocId("parameter", parameter.id.raw),
					minimumValue = parameter.min,
					maximumValue = parameter.max,
					defaultValue = parameter.default,
					// Parameter.Types is moc 4+; below that every parameter is normal and the section is absent.
					type =
						when {
							version.byteValue < 4 -> null
							parameter.kind == ParameterKind.BLEND_SHAPE -> ParameterType.BLEND_SHAPE
							else -> ParameterType.NORMAL
						},
					repeats = parameter.repeat,
				)
			}

		val renderOrderGroups = lowerRenderOrder(puppet, plan)

		// ---- parts ----
		// An offscreen's keyforms ride its OWNER PART'S grid - Σ of the owner grid sizes is CountInfo 36 -
		// so the part's bundle is built once here and the offscreen lowering reads the same one.  Building
		// it twice would let the two disagree about the grid an offscreen is indexed against.
		val partKeyformsById = HashMap<org.umamo.runtime.model.PartId, Moc3ObjectKeyforms?>()
		val parts =
			plan.parts.map { part ->
				// An isolated part's composite channels ride the same cells as its draw order, so they are
				// bundled together; a non-isolated part has no composite to key.
				val compositeChannels =
					if (offscreensEnabled && part.isIsolated) {
						renderChannels(colorsEnabled)
					} else {
						emptyArray()
					}
				val compositeStatics =
					if (offscreensEnabled && part.isIsolated) {
						renderStatics(
							part.composite.opacity,
							part.composite.multiplyColor,
							part.composite.screenColor,
							colorsEnabled,
						)
					} else {
						emptyMap()
					}
				val keyforms =
					lowerObjectKeyforms(
						pool,
						null as org.umamo.runtime.model.KeyformGrid<Unit>?,
						UnitInterpolator,
						part.channelGrids.onlyChannels(*(compositeChannels + arrayOf(FormChannel.DRAW_ORDER))),
						compositeStatics + mapOf(FormChannel.DRAW_ORDER to ChannelValue.Scalar(part.drawOrder.toFloat())),
						requireGeometry = false,
					)
				reportDemotions("part", part.id.raw, keyforms)
				partKeyformsById[part.id] = keyforms
				val bundle = keyforms?.bundle
				val cellCount = bundle?.cells?.size ?: 0
				MocPart(
					id = mocId("part", part.id.raw),
					parentPartIndex = plan.partIndex(partParentById[part.id]),
					// A static part points at binding 0, which is what the import's `> 0` static test expects.
					keyformBindingIndex = if (cellCount > 1) keyforms!!.bindingIndex else 0,
					drawOrderKeyforms =
						FloatArray(maxOf(cellCount, 1)) { cellIndex ->
							bundle?.let { scalarOf(it, cellIndex, FormChannel.DRAW_ORDER, part.drawOrder.toFloat()) }
								?: part.drawOrder.toFloat()
						},
					isVisible = part.isVisible,
				)
			}

		// ---- deformers ----
		val deformers =
			plan.deformers.map { deformer ->
				val parentPartIndex = plan.partIndex(deformer.partId)
				val parentDeformerIndex = plan.deformerIndex(deformer.parent)
				val space = spaceOfParent(plan, deformer.parent)
				when (deformer) {
					is Deformer.Warp -> {
						val keyforms =
							lowerObjectKeyforms(
								pool,
								deformer.geometryGrid,
								WarpLatticeInterpolator,
								deformer.channelGrids.onlyChannels(*renderChannels(colorsEnabled)),
								renderStatics(
									deformer.opacity,
									deformer.multiplyColor,
									deformer.screenColor,
									colorsEnabled,
								),
								requireGeometry = true,
							)
						reportDemotions("deformer", deformer.id.raw, keyforms)
						if (keyforms == null) {
							unsupported(
								"deformer",
								deformer.id.raw,
								"a warp deformer with no control-point grid has no lattice to write; one empty " +
									"lattice was written in its place so the file stays readable, and everything " +
									"bound to this deformer collapses to the origin",
							)
						}
						val bundle = keyforms?.bundle
						// A MOC3 addresses an object's forms as `gridSize` rows from its own keyform base, and
						// the static binding 0 still declares ONE row - so an object that writes zero rows puts
						// its base on top of the next object's, and the last one sends the reader off the end of
						// the table.  Every object kind writes at least one form for that reason.
						val cellCount = maxOf(bundle?.cells?.size ?: 0, 1)
						// Unlike a drawable, which falls back to its rest mesh, a grid-less warp has no lattice
						// anywhere in the model - so the form it is obliged to write is a correctly SIZED empty
						// one.  The reader slices (rows + 1) x (columns + 1) control points back out by that
						// arithmetic, and a short array would desynchronize every warp block after it.
						val controlPointFloats = (deformer.rows + 1) * (deformer.columns + 1) * 2
						WarpDeformer(
							id = mocId("deformer", deformer.id.raw),
							keyformBindingIndex = keyforms?.bindingIndex ?: 0,
							isVisible = deformer.isVisible,
							isEnabled = deformer.isEnabled,
							parentPartIndex = parentPartIndex,
							parentDeformerIndex = parentDeformerIndex,
							rows = deformer.rows,
							columns = deformer.columns,
							mode = if (deformer.isQuadTransform) 1 else 0,
							keyforms =
								(0 until cellCount).map { cellIndex ->
									val lattice =
										bundle?.cells?.getOrNull(cellIndex)?.geometry
											as? org.umamo.runtime.model.WarpLatticeForm
									WarpKeyform(
										convertPointsToMoc(
											space,
											lattice?.controlPoints ?: FloatArray(controlPointFloats),
											canvas,
										),
										bundle?.let { scalarOf(it, cellIndex, FormChannel.OPACITY, deformer.opacity) }
											?: deformer.opacity,
										colorOf(bundle, cellIndex, FormChannel.MULTIPLY_COLOR, deformer.multiplyColor, colorsEnabled),
										colorOf(bundle, cellIndex, FormChannel.SCREEN_COLOR, deformer.screenColor, colorsEnabled),
									)
								},
						)
					}
					is Deformer.Rotation -> {
						val keyforms =
							lowerObjectKeyforms(
								pool,
								deformer.geometryGrid,
								RotationPivotInterpolator,
								deformer.channelGrids.onlyChannels(
									*(renderChannels(colorsEnabled) + arrayOf(FormChannel.FLIP_X, FormChannel.FLIP_Y)),
								),
								renderStatics(deformer.opacity, deformer.multiplyColor, deformer.screenColor, colorsEnabled) +
									mapOf(
										FormChannel.FLIP_X to ChannelValue.Flag(deformer.flipX),
										FormChannel.FLIP_Y to ChannelValue.Flag(deformer.flipY),
									),
								requireGeometry = true,
							)
						reportDemotions("deformer", deformer.id.raw, keyforms)
						if (keyforms == null) {
							unsupported(
								"deformer",
								deformer.id.raw,
								"a rotation deformer with no pivot grid has no transform to write; the identity " +
									"transform was written in its place",
							)
						}
						val bundle = keyforms?.bundle
						// One form minimum, for the same base-collision reason as the warp branch above.  A
						// pivot-less rotation has a well-defined fallback the warp does not: the identity
						// transform, which is what `pivot == null` already resolves to below.
						val cellCount = maxOf(bundle?.cells?.size ?: 0, 1)
						// Only the FIRST rotation on each root path carries the px->model factor.
						val scaleFactor =
							rotationScaleFactor(rotationAncestors[deformer.id] ?: false, canvas)
						RotationDeformer(
							id = mocId("deformer", deformer.id.raw),
							keyformBindingIndex = keyforms?.bindingIndex ?: 0,
							isVisible = deformer.isVisible,
							isEnabled = deformer.isEnabled,
							parentPartIndex = parentPartIndex,
							parentDeformerIndex = parentDeformerIndex,
							baseAngle = deformer.baseAngle,
							keyforms =
								(0 until cellCount).map { cellIndex ->
									val pivot =
										bundle?.cells?.getOrNull(cellIndex)?.geometry
											as? org.umamo.runtime.model.RotationPivotForm
									val origin =
										convertPointsToMoc(
											space,
											floatArrayOf(pivot?.originX ?: 0f, pivot?.originY ?: 0f),
											canvas,
										)
									RotationKeyform(
										originX = origin[0],
										originY = origin[1],
										angle = pivot?.angle ?: 0f,
										scale = (pivot?.scale ?: 1f) / (if (scaleFactor != 0f) scaleFactor else 1f),
										reflectX =
											bundle?.let { flagOf(it, cellIndex, FormChannel.FLIP_X, deformer.flipX) }
												?: deformer.flipX,
										reflectY =
											bundle?.let { flagOf(it, cellIndex, FormChannel.FLIP_Y, deformer.flipY) }
												?: deformer.flipY,
										opacity =
											bundle?.let { scalarOf(it, cellIndex, FormChannel.OPACITY, deformer.opacity) }
												?: deformer.opacity,
										multiplyColor =
											colorOf(bundle, cellIndex, FormChannel.MULTIPLY_COLOR, deformer.multiplyColor, colorsEnabled),
										screenColor =
											colorOf(bundle, cellIndex, FormChannel.SCREEN_COLOR, deformer.screenColor, colorsEnabled),
									)
								},
						)
					}
				}
			}

		// ---- art meshes ----
		val artMeshes =
			plan.drawables.map { drawable ->
				val mesh = drawable.mesh!!
				val space = spaceOfParent(plan, drawable.parentDeformerId)
				// An unkeyed drawable under a deformer stores its rest mesh in CANVAS space, so the base
				// every keyform is written relative to has to be inverted through the chain first.  A keyed
				// one is already parent-local (the import's rest-mesh pass guarantees base + delta is the
				// absolute parent-space position), so the seam is asked only where it is needed.
				val basePositions =
					if (drawable.geometryGrid == null && drawable.parentDeformerId != null) {
						canvasToParentSpace?.invoke(drawable.id, mesh.positions)?.also { converted ->
							if (converted.size != mesh.positions.size) {
								unsupported(
									"drawable",
									drawable.id.raw,
									"the canvas-to-parent conversion returned ${converted.size} coordinates for " +
										"${mesh.positions.size}; the rest mesh was written unconverted",
								)
							}
						}?.takeIf { converted -> converted.size == mesh.positions.size } ?: mesh.positions
					} else {
						mesh.positions
					}
				val keyforms =
					lowerObjectKeyforms(
						pool,
						drawable.geometryGrid,
						MeshDeltaInterpolator,
						drawable.channelGrids.onlyChannels(
							*(renderChannels(colorsEnabled) + arrayOf(FormChannel.DRAW_ORDER)),
						),
						renderStatics(drawable.opacity, drawable.multiplyColor, drawable.screenColor, colorsEnabled) +
							mapOf(FormChannel.DRAW_ORDER to ChannelValue.Scalar(drawable.drawOrder)),
						requireGeometry = false,
					)
				reportDemotions("drawable", drawable.id.raw, keyforms)
				val bundle = keyforms?.bundle
				val cellCount = maxOf(bundle?.cells?.size ?: 0, 1)
				val triangleIndices =
					ShortArray(mesh.indices.size) { index -> mesh.indices[index].toShort() }
				// -1 is the "no atlas page bound" sentinel and a moc has no way to spell it, so the write
				// clamps to page 0.  On a multi-page rig that is the WRONG texture rather than a near miss,
				// which is worth a notice even though the file it produces is structurally valid.
				if (drawable.texturePage < 0) {
					unsupported(
						"drawable",
						drawable.id.raw,
						"no atlas page is bound to this drawable, so it was written pointing at page 0",
					)
				}
				// A mask naming a drawable this export dropped has no file index to reference.  Filtering it
				// is the only option, but doing so silently would leave the mesh rendering unclipped with
				// nothing in the report to explain why.
				val maskIndices = ArrayList<Int>(drawable.maskedBy.size)
				val unresolvedMasks = ArrayList<String>()
				for (maskId in drawable.maskedBy) {
					val maskIndex = plan.drawableIndex(maskId)
					if (maskIndex >= 0) {
						maskIndices.add(maskIndex)
					} else {
						unresolvedMasks.add(maskId.raw)
					}
				}
				if (unresolvedMasks.isNotEmpty()) {
					unsupported(
						"drawable",
						drawable.id.raw,
						"the clipping mask ${unresolvedMasks.joinToString()} is not in this export, so the mesh " +
							"was written unclipped by it",
					)
				}
				ArtMesh(
					id = mocId("drawable", drawable.id.raw),
					textureIndex = maxOf(drawable.texturePage, 0),
					constantFlags = constantFlagsOf(drawable, extendedBlendEnabled),
					// The 5.3 blend surface; below v6 the mode falls back to the legacy constant-flag bits.
					extendedBlend =
						if (extendedBlendEnabled) packedBlendOf(drawable.blendMode, drawable.alphaBlendMode) else 0,
					isVisible = drawable.isVisible,
					isEnabled = true,
					parentPartIndex = plan.partIndex(partByDrawable[drawable.id]),
					parentDeformerIndex = plan.deformerIndex(drawable.parentDeformerId),
					vertexUvs = mesh.uvs.copyOf(),
					triangleIndices = triangleIndices,
					maskDrawableIndices = maskIndices.toIntArray(),
					keyformBindingIndex = keyforms?.bindingIndex ?: 0,
					keyforms =
						(0 until cellCount).map { cellIndex ->
							// THE load-bearing invariant: base + delta is the absolute parent-space position.
							val deltas =
								(bundle?.cells?.getOrNull(cellIndex)?.geometry as? org.umamo.runtime.model.MeshDeltaForm)
									?.positionDeltas
							val absolute =
								FloatArray(basePositions.size) { coordinate ->
									basePositions[coordinate] + (deltas?.getOrNull(coordinate) ?: 0f)
								}
							ArtMeshKeyform(
								vertexPositions = convertPointsToMoc(space, absolute, canvas),
								opacity =
									bundle?.let { scalarOf(it, cellIndex, FormChannel.OPACITY, drawable.opacity) }
										?: drawable.opacity,
								drawOrder =
									bundle?.let { scalarOf(it, cellIndex, FormChannel.DRAW_ORDER, drawable.drawOrder) }
										?: drawable.drawOrder,
								multiplyColor =
									colorOf(bundle, cellIndex, FormChannel.MULTIPLY_COLOR, drawable.multiplyColor, colorsEnabled),
								screenColor =
									colorOf(bundle, cellIndex, FormChannel.SCREEN_COLOR, drawable.screenColor, colorsEnabled),
							)
						},
				)
			}

		// ---- glues ----
		val glues =
			puppet.glues.mapIndexedNotNull { glueIndex, glue ->
				val meshA = plan.drawableIndex(glue.meshA)
				val meshB = plan.drawableIndex(glue.meshB)
				if (meshA < 0 || meshB < 0) {
					unsupported("glue", glue.id ?: "Glue$glueIndex", "a glue naming an unknown drawable is dropped")
					return@mapIndexedNotNull null
				}
				val keyforms =
					lowerObjectKeyforms(
						pool,
						null as org.umamo.runtime.model.KeyformGrid<Unit>?,
						UnitInterpolator,
						glue.channelGrids.onlyChannels(FormChannel.GLUE_INTENSITY),
						mapOf(FormChannel.GLUE_INTENSITY to ChannelValue.Scalar(glue.intensity)),
						requireGeometry = false,
					)
				// ONE fallback, computed once: a notice naming a subject the file does not contain sends the
				// reader searching the export for an object that was never written under that name.  The
				// drop notice above is the exception on purpose - a dropped glue has no written id to cite,
				// so its ordinal is the only handle left.
				val glueId = glue.id ?: "Glue_${meshA}_${meshB}_"
				reportDemotions("glue", glueId, keyforms)
				val bundle = keyforms?.bundle
				val cellCount = maxOf(bundle?.cells?.size ?: 0, 1)
				MocGlue(
					id = mocId("glue", glueId),
					meshAIndex = meshA,
					meshBIndex = meshB,
					keyformBindingIndex = keyforms?.bindingIndex ?: 0,
					pairs =
						glue.pairs.map { pair ->
							GlueVertexPair(pair.indexA, pair.indexB, pair.weightA, pair.weightB)
						},
					intensityKeyforms =
						FloatArray(cellCount) { cellIndex ->
							bundle?.let { scalarOf(it, cellIndex, FormChannel.GLUE_INTENSITY, glue.intensity) }
								?: glue.intensity
						},
				)
			}

		for ((drawableId, reason) in dropped) {
			unsupported("drawable", drawableId.raw, reason)
		}

		val document =
			MocDocument(
				version = version,
				canvas =
					CanvasInfo(
						pixelsPerUnit = pixelsPerUnit,
						originX = puppet.worldOriginX,
						// The runtime negates the canvas y into world space; storing it re-negates.
						originY = -puppet.worldOriginY,
						width = puppet.canvasWidth,
						height = puppet.canvasHeight,
					),
				parameters = parameters,
				keyformBindings = pool.bindings().associateBy { binding -> binding.index },
				parts = parts,
				deformers = deformers,
				artMeshes = artMeshes,
				glues = glues,
				renderOrderGroups = renderOrderGroups,
				offscreens =
					if (offscreensEnabled) {
						lowerOffscreens(puppet, plan, partKeyformsById, colorsEnabled, ::unsupported)
					} else {
						emptyList()
					},
				// Blend shapes arrived in Cubism 4.2; a lower target simply carries none.
				blendShapes =
					if (version.byteValue < 4) {
						emptyList()
					} else {
						lowerBlendShapes(
							plan.drawables,
							plan.deformers,
							plan.parts,
							plan.parameters,
							plan,
							canvas,
							{ ownerId ->
								// An owner missing from the plan resolves to root space rather than throwing: it
								// means the export dropped the object, and a blend shape on something that was
								// never written is not worth failing the whole file over.
								when (ownerId) {
									is org.umamo.runtime.model.DrawableId ->
										spaceOfParent(plan, drawableById[ownerId]?.parentDeformerId)
									is org.umamo.runtime.model.DeformerId ->
										spaceOfParent(plan, deformerById[ownerId]?.parent)
									else -> PointSpace.ModelRoot
								}
							},
							{ rotation -> rotationScaleFactor(rotationAncestors[rotation.id] ?: false, canvas) },
							colorsEnabled,
						)
					},
			)
		return Lowered(document, ExportReport(notices))
	}

	/**
	 * Lowers [puppet] and bakes it to `.moc3` bytes.
	 *
	 * @param PuppetModel puppet  The rig to export.
	 * @param MocVersion  version The moc version to target; the document's own runtime target by default.
	 * @param CanvasToParentSpace? canvasToParentSpace The unkeyed-drawable space inverse, or null.
	 * @return Pair The bytes and the advisory report.
	 */
	fun write(
		puppet: PuppetModel,
		version: MocVersion = puppet.runtimeTarget.mocVersion(),
		canvasToParentSpace: CanvasToParentSpace? = null,
	): Pair<ByteArray, ExportReport> {
		val lowered = toMocDocument(puppet, version, canvasToParentSpace)
		return MocEncoder.bakeFresh(version, lowered.document) to lowered.report
	}

	/**
	 * The space a child of [parentId] stores its positions in - the export's mirror of the import's
	 * `pointSpaceOf`, resolved through the plan so an unknown parent normalizes to root exactly as the
	 * import normalizes an unresolvable index.
	 *
	 * @param Moc3IndexPlan plan     The index plan.
	 * @param DeformerId?   parentId The owning object's parent deformer.
	 * @return PointSpace The space to store in.
	 */
	private fun spaceOfParent(plan: Moc3IndexPlan, parentId: org.umamo.runtime.model.DeformerId?): PointSpace {
		val index = plan.deformerIndex(parentId)
		return when (plan.deformers.getOrNull(index)) {
			is Deformer.Warp -> PointSpace.WarpLattice
			is Deformer.Rotation -> PointSpace.RotationLocal
			null -> PointSpace.ModelRoot
		}
	}

	/**
	 * The drawable's constant-flag byte.
	 *
	 * Note bit 2 is the INVERSE of culling: the flag means "double sided", so a culled drawable clears
	 * it.  Getting that backwards silently double-draws every back face.
	 *
	 * @param org.umamo.runtime.model.Drawable drawable             The drawable.
	 * @param Boolean                          extendedBlendEnabled Whether the target version carries the
	 *   5.3 extended-blend section, which then states the blend mode instead of the legacy bits.
	 * @return Int The flag bits.
	 */
	private fun constantFlagsOf(drawable: org.umamo.runtime.model.Drawable, extendedBlendEnabled: Boolean): Int {
		// On moc 6 the extended-blend section is authoritative and the editor leaves the legacy 2-bit pair
		// CLEAR even for an additive or multiply mesh - writing both would state the mode twice, and the
		// legacy pair cannot express the other sixteen modes anyway.
		var flags = if (extendedBlendEnabled) 0 else legacyBlendFlagOf(drawable.blendMode)
		if (!drawable.culling) {
			flags = flags or ConstantFlag.IS_DOUBLE_SIDED
		}
		if (drawable.invertMask) {
			flags = flags or ConstantFlag.IS_INVERTED_MASK
		}
		return flags
	}

	/**
	 * The pixels-per-unit a bake of [puppet] should carry.
	 *
	 * A moc's canvas scale is a BAKE parameter, not a project property: every corpus `.cmo3` stores
	 * `CModelInfo.pixelsPerUnit = 1` - a CMO3 works in canvas pixels - while the editor's bake of the
	 * same project writes a real scale, and the rigger picks it in the export dialog.  Its default there
	 * is the canvas WIDTH, which 21 of the 25 corpus bakes use exactly (the four that do not chose their
	 * own: 9000 -> 5000, 9000 -> 3077, 4500 -> 3000, 5134 -> 5000).
	 *
	 * So a CMO3-origin export defaults to the canvas width, and a MOC3-origin one keeps the scale its
	 * file already had.  Writing the project's literal 1 instead is not a smaller choice - it stores the
	 * whole rig at PIXEL scale, which every runtime then draws hundreds of times too large.  A rigger who
	 * picked a different scale at bake time cannot have it recovered from the project; that wants an
	 * export option, on the same surface an omit-hidden-objects toggle would live on.
	 *
	 * The two cases are told apart by [PuppetModel.pixelsPerUnit] being NULL, never by the value's
	 * magnitude: 1 is a legitimate bake scale, so a `> 1` test would silently rescale a moc that baked
	 * at 1 - or at any scale below it - by the canvas width and shrink the rig by that whole factor.
	 *
	 * @param PuppetModel puppet The rig being exported.
	 * @return Float The canvas scale to write.
	 */
	fun mocPixelsPerUnitFor(puppet: PuppetModel): Float {
		puppet.pixelsPerUnit?.let { recorded -> return recorded }
		return puppet.canvasWidth.takeIf { width -> width > 0f } ?: 1f
	}

	/**
	 * Every part id in a sketch part's subtree, the sketch parts themselves included.
	 *
	 * A guide image is usually one part, but nothing stops a rigger from grouping several under it - and
	 * a child of a guide is still a guide.
	 *
	 * @param PuppetModel puppet The rig.
	 * @return Set The part ids to omit.
	 */
	private fun sketchSubtree(puppet: PuppetModel): Set<org.umamo.runtime.model.PartId> {
		val sketches = puppet.parts.filter { part -> part.isSketch }
		if (sketches.isEmpty()) {
			return emptySet()
		}
		val partsById = puppet.parts.associateBy { part -> part.id }
		val omitted = LinkedHashSet<org.umamo.runtime.model.PartId>()
		val pending = ArrayDeque(sketches.map { part -> part.id })
		while (pending.isNotEmpty()) {
			val partId = pending.removeFirst()
			if (!omitted.add(partId)) {
				continue
			}
			for (child in partsById[partId]?.children.orEmpty()) {
				if (child is org.umamo.runtime.model.OrgChild.Part) {
					pending.addLast(child.id)
				}
			}
		}
		return omitted
	}
}

/**
 * The render channels an object's grid should bundle, gated on whether the version carries colours.
 *
 * @param Boolean colorsEnabled Whether the target version has colour tables.
 * @return Array<FormChannel> The channels to bundle.
 */
internal fun renderChannels(colorsEnabled: Boolean): Array<FormChannel> =
	if (colorsEnabled) {
		arrayOf(FormChannel.OPACITY, FormChannel.MULTIPLY_COLOR, FormChannel.SCREEN_COLOR)
	} else {
		arrayOf(FormChannel.OPACITY)
	}

/**
 * The static fallbacks for [renderChannels].
 *
 * Colours are omitted entirely below moc 4 rather than defaulted, so the bundle never manufactures a
 * colour cell the version has nowhere to store.
 *
 * @param Float    opacity       The owner's static opacity.
 * @param ColorRgb multiplyColor The owner's static multiply colour.
 * @param ColorRgb screenColor   The owner's static screen colour.
 * @param Boolean  colorsEnabled Whether the target version has colour tables.
 * @return Map The statics per channel.
 */
internal fun renderStatics(
	opacity: Float,
	multiplyColor: org.umamo.runtime.model.ColorRgb,
	screenColor: org.umamo.runtime.model.ColorRgb,
	colorsEnabled: Boolean,
): Map<FormChannel, ChannelValue> =
	if (colorsEnabled) {
		mapOf(
			FormChannel.OPACITY to ChannelValue.Scalar(opacity),
			FormChannel.MULTIPLY_COLOR to ChannelValue.Color(multiplyColor),
			FormChannel.SCREEN_COLOR to ChannelValue.Color(screenColor),
		)
	} else {
		mapOf(FormChannel.OPACITY to ChannelValue.Scalar(opacity))
	}

/** A geometry interpolator for owners that have no geometry at all (parts, glues). */
internal object UnitInterpolator : org.umamo.runtime.keyform.FormInterpolator<Unit> {
	override fun interpolate(lower: Unit, upper: Unit, fraction: Float) = Unit

	override fun isExactlyEqual(left: Unit, right: Unit): Boolean = true
}

/**
 * This channel set restricted to [channels] - the tracks the owner's moc block can actually store.
 *
 * A runtime entity may carry tracks a given moc version has no field for (a colour track on a v3
 * export), and bundling those in would widen the grid with axes nothing reads.
 *
 * @param FormChannel channels The channels to keep.
 * @return ChannelGrids The restricted set.
 */
internal fun org.umamo.runtime.model.ChannelGrids.onlyChannels(
	vararg channels: FormChannel,
): org.umamo.runtime.model.ChannelGrids {
	val keep = channels.toSet()
	return org.umamo.runtime.model.ChannelGrids(
		gridsByChannel.filterKeys { channel -> channel in keep },
	)
}

/**
 * Inverts a drawable's canvas-space rest mesh into its parent deformer's space.
 *
 * An injected seam rather than a call, because the inverse lives in `:render` (a closed-form rotation
 * inverse and a damped-Newton warp inverse over the evaluated chain) and `:interop` is its sibling
 * over `:runtime`, not its dependent - the same shape as the atlas decode's injected byte reader.
 *
 * Only reached for a drawable with no keyform grid under a deformer: everything else already stores
 * parent-local values.  Returning null (or a differently-sized array) leaves the rest mesh as authored
 * and raises a notice, which is the honest outcome when the chain cannot be inverted at all.
 *
 * @param DrawableId drawable  The drawable being written.
 * @param FloatArray positions Its interleaved canvas-space rest positions.
 * @return FloatArray? The interleaved parent-space positions, or null when the chain cannot invert.
 */
typealias CanvasToParentSpace = (drawable: org.umamo.runtime.model.DrawableId, positions: FloatArray) -> FloatArray?
