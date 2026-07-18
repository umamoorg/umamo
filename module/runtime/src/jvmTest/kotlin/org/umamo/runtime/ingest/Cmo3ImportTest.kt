package org.umamo.runtime.ingest

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CDeformerSourceSet
import org.umamo.format.cmo3.model.gen.CDrawableSourceSet
import org.umamo.format.cmo3.model.gen.CParameterSource
import org.umamo.format.cmo3.model.gen.CParameterSourceSet
import org.umamo.format.cmo3.model.gen.CRotationDeformerSource
import org.umamo.format.cmo3.model.gen.CWarpDeformerForm
import org.umamo.format.cmo3.model.gen.CWarpDeformerSource
import org.umamo.format.cmo3.model.gen.KeyFormMorphTarget
import org.umamo.format.cmo3.model.gen.KeyFormMorphTargetSet
import org.umamo.format.cmo3.model.gen.MorphTargetBlendWeightConstraint
import org.umamo.format.cmo3.model.gen.MorphTargetBlendWeightConstraintSet
import org.umamo.format.cmo3.model.gen.Type
import org.umamo.format.cmo3.model.identity.Guid
import org.umamo.format.cmo3.model.identity.Id
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.ParameterLink
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.partByDrawable
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reads a real `.cmo3` corpus sample, maps it to a `PuppetModel`, and checks the structural tree.
 *
 * Self-contained (no editor jar); skips when the sample is absent. Pass
 * `-Dcmo3.sample=/path/to/Model.cmo3`. The printed summary matches the official editor's Parts/Parameters
 * panels; the assertions verify referential integrity (every GUID cross-reference resolves to a real entity).
 */
class Cmo3ImportTest {
	private val sample: File? = System.getProperty("cmo3.sample")?.let(::File)?.takeIf { it.isFile }

	@Test
	fun mapsCorpusModelToStructuralTree() {
		val file = sample
		if (file == null) {
			println("cmo3.sample not present; skipping import test")
			return
		}
		val root = Cmo3.read(file).root as? CModelSource ?: error("root is not a CModelSource")
		val puppet = Cmo3Import.fromModelSource(root)

		val warpCount = puppet.deformers.count { it is Deformer.Warp }
		val rotationCount = puppet.deformers.count { it is Deformer.Rotation }
		println(
			"[Umamo][import] parameters=${puppet.parameters.size} parts=${puppet.parts.size} " +
				"deformers=${puppet.deformers.size} (warp=$warpCount rotation=$rotationCount) " +
				"drawables=${puppet.drawables.size} rootPart=${puppet.rootPartId?.raw}",
		)
		println("[Umamo][import] first params: " + puppet.parameters.take(10).joinToString { it.id.raw })

		val totalVertices = puppet.drawables.sumOf { it.mesh?.vertexCount ?: 0 }
		val totalPolygons = puppet.drawables.sumOf { it.mesh?.triangleCount ?: 0 }
		println("[Umamo][import] vertices=$totalVertices polygons=$totalPolygons")

		val artInterpolations = puppet.drawables.sumOf { it.keyforms?.cells?.size ?: 0 }
		val deformerInterpolations =
			puppet.deformers.sumOf { deformer ->
				when (deformer) {
					is Deformer.Warp -> deformer.keyforms?.cells?.size ?: 0
					is Deformer.Rotation -> deformer.keyforms?.cells?.size ?: 0
				}
			}
		println(
			"[Umamo][import] artInterpolations=$artInterpolations " +
				"deformerInterpolations=$deformerInterpolations",
		)

		// Golden gate: for the EricaTamamo corpus, assert the exact figures from
		// test/corpus/EricaTamamo.stats. Other samples get only the sample-agnostic integrity checks
		// below. (Parameter count is observed - the stats file does not list it.)
		if (file.name.startsWith("EricaTamamo")) {
			assertEquals(116, puppet.parameters.size, "parameters")
			// CMO3 combined-parameter pairs: head Angle X/Y and Eyeball X/Y are the model's only links.
			assertEquals(
				listOf(
					ParameterLink(ParameterId("ParamAngleX"), ParameterId("ParamAngleY")),
					ParameterLink(ParameterId("ParamEyeBallX"), ParameterId("ParamEyeBallY")),
				),
				puppet.parameterLinks,
				"parameter links",
			)

			// CMO3 parameter-panel folders: the rootParameterGroup has 71 panel-order children - 67 loose
			// parameters interleaved with 4 subgroups (two share the display name "Eye", so identity is by id).
			fun walkGroups(nodes: List<ParameterNode>): List<ParameterNode.Group> =
				nodes.filterIsInstance<ParameterNode.Group>().flatMap { group -> listOf(group) + walkGroups(group.children) }

			fun walkLeaves(nodes: List<ParameterNode>): List<ParameterId> =
				nodes.flatMap { node ->
					when (node) {
						is ParameterNode.Param -> listOf(node.id)
						is ParameterNode.Group -> walkLeaves(node.children)
					}
				}

			assertEquals(71, puppet.parameterTree.size, "parameter tree top-level nodes")
			val parameterGroups = walkGroups(puppet.parameterTree)
			assertEquals(
				listOf("ParamGroup", "ParamGroup2", "ParamGroup3", "ParamGroup4"),
				parameterGroups.map { it.id.raw },
				"parameter group ids",
			)
			assertEquals(
				listOf("Eye", "Eye", "Folder 1", "Tail Rotation"),
				parameterGroups.map { it.name },
				"parameter group names",
			)
			assertEquals(
				listOf(3, 3, 40, 3),
				parameterGroups.map { it.children.size },
				"parameter group child counts",
			)
			val parameterTreeLeaves = walkLeaves(puppet.parameterTree)
			assertEquals(116, parameterTreeLeaves.size, "parameter tree leaves")
			assertEquals(
				puppet.parameters.map { it.id }.toSet(),
				parameterTreeLeaves.toSet(),
				"every panel leaf is a real parameter",
			)
			assertEquals(42, puppet.parts.size, "parts")
			assertEquals(350, warpCount, "warp deformers")
			assertEquals(6, rotationCount, "rotation deformers")
			assertEquals(177, puppet.drawables.size, "art meshes")
			assertEquals(31470, totalVertices, "vertices")
			assertEquals(50933, totalPolygons, "polygons")
			assertEquals(337, artInterpolations, "art mesh interpolations")
			assertEquals(1297, deformerInterpolations, "deformer interpolations")
		}

		val partIds = puppet.parts.map { it.id }.toSet()
		val deformerIds = puppet.deformers.map { it.id }.toSet()
		val drawableIds = puppet.drawables.map { it.id }.toSet()

		assertTrue(puppet.parameters.isNotEmpty(), "expected parameters")
		assertTrue(puppet.drawables.isNotEmpty(), "expected drawables")

		// Referential integrity: every org-tree child resolves to a real entity, and every drawable is
		// placed somewhere in the tree (membership lives only in the tree now - the import's safety net
		// guarantees no mesh is orphaned).
		fun checkOrgChildren(children: List<OrgChild>, owner: String) {
			for (child in children) {
				when (child) {
					is OrgChild.Part -> assertTrue(child.id in partIds, "$owner -> unknown sub-part ${child.id.raw}")
					is OrgChild.Drawable -> assertTrue(child.id in drawableIds, "$owner -> unknown mesh ${child.id.raw}")
				}
			}
		}
		checkOrgChildren(puppet.rootChildren, "root")
		for (part in puppet.parts) {
			checkOrgChildren(part.children, "part ${part.id.raw}")
		}
		val placedDrawables = puppet.partByDrawable().keys
		for (drawable in puppet.drawables) {
			assertTrue(drawable.id in placedDrawables, "drawable ${drawable.id.raw} is missing from the org tree")
			drawable.parentDeformerId?.let {
				assertTrue(it in deformerIds, "drawable ${drawable.id.raw} -> unknown deformer ${it.raw}")
			}
			for (mask in drawable.maskedBy) {
				assertTrue(mask in drawableIds, "drawable ${drawable.id.raw} -> unknown mask ${mask.raw}")
			}
		}
		for (deformer in puppet.deformers) {
			deformer.parent?.let {
				assertTrue(it in deformerIds, "deformer ${deformer.id.raw} -> unknown parent ${it.raw}")
			}
			deformer.partId?.let {
				assertTrue(it in partIds, "deformer ${deformer.id.raw} -> unknown part ${it.raw}")
			}
		}
	}

	/**
	 * Corpus-free check that drawable and deformer display names come from CMO3 localName, falling back to
	 * the id when localName is absent - the same rule the outliner relies on to label its rows readably.
	 */
	@Test
	fun capturesLocalNameWithIdFallbackForDrawablesAndDeformers() {
		fun makeId(value: String) = Id("").apply { idstr = value }

		val namedMesh =
			CArtMeshSource().apply {
				id = makeId("ArtMesh1")
				localName = "Front Hair"
			}
		val unnamedMesh = CArtMeshSource().apply { id = makeId("ArtMesh9") } // localName null -> id
		val namedWarp =
			CWarpDeformerSource().apply {
				id = makeId("Warp1")
				localName = "Warp Deformer of Bag front12"
			}
		val unnamedWarp = CWarpDeformerSource().apply { id = makeId("Warp9") } // localName null -> id

		val model =
			CModelSource().apply {
				drawableSourceSet = CDrawableSourceSet().apply { _sources = arrayListOf(namedMesh, unnamedMesh) }
				deformerSourceSet = CDeformerSourceSet().apply { _sources = arrayListOf(namedWarp, unnamedWarp) }
			}

		val puppet = Cmo3Import.fromModelSource(model)

		assertEquals("Front Hair", puppet.drawables.first { it.id.raw == "ArtMesh1" }.name, "named drawable")
		assertEquals("ArtMesh9", puppet.drawables.first { it.id.raw == "ArtMesh9" }.name, "unnamed drawable falls back to id")
		assertEquals("Warp Deformer of Bag front12", puppet.deformers.first { it.id.raw == "Warp1" }.name, "named deformer")
		assertEquals("Warp9", puppet.deformers.first { it.id.raw == "Warp9" }.name, "unnamed deformer falls back to id")
	}

	/**
	 * Corpus-free check that the CMO3 editor lock maps inverted onto the runtime selectable flag: a locked
	 * source ingests as isSelectable = false and an unlocked one as isSelectable = true, for drawables and
	 * both deformer kinds alike (Cubism lock = not selectable).
	 */
	@Test
	fun mapsCmo3LockInvertedOntoSelectable() {
		fun makeId(value: String) = Id("").apply { idstr = value }

		val lockedMesh =
			CArtMeshSource().apply {
				id = makeId("ArtMesh1")
				isLocked = true
			}
		val unlockedMesh = CArtMeshSource().apply { id = makeId("ArtMesh2") }
		val lockedWarp =
			CWarpDeformerSource().apply {
				id = makeId("Warp1")
				isLocked = true
			}
		val lockedRotation =
			CRotationDeformerSource().apply {
				id = makeId("Rotation1")
				isLocked = true
			}
		val unlockedWarp = CWarpDeformerSource().apply { id = makeId("Warp2") }

		val model =
			CModelSource().apply {
				drawableSourceSet = CDrawableSourceSet().apply { _sources = arrayListOf(lockedMesh, unlockedMesh) }
				deformerSourceSet = CDeformerSourceSet().apply { _sources = arrayListOf(lockedWarp, lockedRotation, unlockedWarp) }
			}

		val puppet = Cmo3Import.fromModelSource(model)

		assertFalse(puppet.drawables.first { it.id.raw == "ArtMesh1" }.isSelectable, "locked drawable ingests unselectable")
		assertTrue(puppet.drawables.first { it.id.raw == "ArtMesh2" }.isSelectable, "unlocked drawable ingests selectable")
		assertFalse(puppet.deformers.first { it.id.raw == "Warp1" }.isSelectable, "locked warp ingests unselectable")
		assertFalse(puppet.deformers.first { it.id.raw == "Rotation1" }.isSelectable, "locked rotation ingests unselectable")
		assertTrue(puppet.deformers.first { it.id.raw == "Warp2" }.isSelectable, "unlocked warp ingests selectable")
	}

	// ---- blend shapes (CMO3 MorphTargets) ----

	/** All blend-shape bindings of [puppet], across drawables and both deformer kinds. */
	private fun allBindings(puppet: PuppetModel): List<BlendShapeBinding<*>> =
		puppet.drawables.flatMap { it.blendShapes } +
			puppet.deformers.flatMap { deformer ->
				when (deformer) {
					is Deformer.Warp -> deformer.blendShapes
					is Deformer.Rotation -> deformer.blendShapes
				}
			}

	/**
	 * Probe loop over the whole corpus (`cmo3.probe`, comma-separated; defaults to every corpus
	 * .cmo3): every model ingests with well-formed blend-shape bindings, and the two blend-shape
	 * corpus anchors (Model A, Model B) match their golden counts. Skips gracefully without the corpus.
	 */
	@Test
	fun blendShapeBindingsAcrossCorpus() {
		val files =
			System.getProperty("cmo3.probe")?.split(',')?.map(::File)?.filter { it.isFile }.orEmpty()
		if (files.isEmpty()) {
			println("cmo3.probe not present; skipping blend-shape corpus probe")
			return
		}
		for (file in files) {
			val root = Cmo3.read(file).root as? CModelSource ?: error("${file.name}: root is not a CModelSource")
			val puppet = Cmo3Import.fromModelSource(root)
			val bindings = allBindings(puppet)
			val blendShapeParams = puppet.parameters.filter { it.kind == ParameterKind.BLEND_SHAPE }
			val meshBindings = puppet.drawables.sumOf { it.blendShapes.size }
			val warpBindings = puppet.deformers.filterIsInstance<Deformer.Warp>().sumOf { it.blendShapes.size }
			val rotationBindings = puppet.deformers.filterIsInstance<Deformer.Rotation>().sumOf { it.blendShapes.size }
			val limitCount = bindings.sumOf { it.limits.size }
			val limitPointCount = bindings.sumOf { binding -> binding.limits.sumOf { it.points.size } }
			val formCount = bindings.sumOf { binding -> binding.forms.count { it != null } }
			println(
				"${file.name}: blendShapeParams=${blendShapeParams.size} bindings=${bindings.size} " +
					"(mesh=$meshBindings warp=$warpBindings rotation=$rotationBindings) forms=$formCount " +
					"limits=$limitCount points=$limitPointCount",
			)

			val parameterById = puppet.parameters.associateBy { it.id }
			for (binding in bindings) {
				val parameter = parameterById[binding.parameterId]
				assertTrue(parameter != null, "${file.name}: binding driver ${binding.parameterId.raw} resolves")
				assertEquals(
					ParameterKind.BLEND_SHAPE,
					parameter.kind,
					"${file.name}: binding driver ${binding.parameterId.raw} is a blend-shape parameter",
				)
				assertTrue(
					binding.keys.toList() == binding.keys.toList().distinct().sorted(),
					"${file.name}: binding keys ascending and distinct",
				)
				assertEquals(binding.keys.size, binding.forms.size, "${file.name}: one form slot per key")
				// The neutral sits at parameter VALUE 0, not the default.
				assertEquals(
					0f,
					binding.keys[binding.neutralIndex],
					"${file.name}: neutral key sits at parameter value 0",
				)
				// The neutral form is null when inserted (always, in the corpus - a morph authored
				// exactly at value 0 would occupy the slot but is never read by the evaluator).
				for (formIndex in binding.forms.indices) {
					if (formIndex != binding.neutralIndex) {
						assertTrue(binding.forms[formIndex] != null, "${file.name}: non-neutral form present")
					}
				}
				for (limit in binding.limits) {
					assertTrue(parameterById.containsKey(limit.parameterId), "${file.name}: limit constraint parameter resolves")
					assertTrue(
						limit.points.map { it.value } == limit.points.map { it.value }.sorted(),
						"${file.name}: limit points sorted by constraint value",
					)
				}
			}

			// Golden anchors: the corpus blend-shape models of record.
			if (file.name.startsWith("modelA")) {
				assertEquals(12, blendShapeParams.size, "Model A: blend-shape parameter count")
				assertEquals(60, bindings.size, "Model A: binding count (one per source × driving parameter)")
				assertEquals(118, formCount, "Model A: morph-target form count")
				assertEquals(0, limitCount, "Model A: no populated blend-weight constraints")
			}
			if (file.name.startsWith("modelB")) {
				assertEquals(5, blendShapeParams.size, "Model B: blend-shape parameter count")
				assertEquals(45, bindings.size, "Model B: binding count")
				assertEquals(45, formCount, "Model B: morph-target form count")
				assertEquals(44, limitCount, "Model B: limit curve count")
				assertEquals(88, limitPointCount, "Model B: one point per XML constraint record")
			}
			// Model C: the corpus constraint + edge-case model. 146 XML morph targets ingest as 145
			// forms: exactly one is owned by a CPartSource (part blend shapes, a moc v5 feature) and
			// part morph targets are not ingested yet - deferred with the part/glue target kinds.
			// Model C also authors one morph target AT the parameter default (an explicit neutral form).
			if (file.name.startsWith("modelC")) {
				assertEquals(33, blendShapeParams.size, "Model C: blend-shape parameter count")
				assertEquals(123, bindings.size, "Model C: binding count (mesh+warp+rotation; 1 part-owned target excluded)")
				assertEquals(145, formCount, "Model C: morph-target form count (146 XML records - 1 part-owned)")
				assertEquals(468, limitPointCount, "Model C: one point per XML constraint record")
			}
		}
	}

	/**
	 * Corpus-free check of the binding derivation rule: morph targets group per driving parameter,
	 * keys sort ascending, and the neutral key is inserted at parameter VALUE 0 with a null form -
	 * NOT at the parameter's default (the default here is deliberately 1.0, the Model C
	 * ParamMouthUp shape that discriminates the two readings against the MOC3 bake).
	 */
	@Test
	fun ingestsMorphTargetSetIntoBlendShapeBindings() {
		fun makeId(value: String) = Id("").apply { idstr = value }

		fun makeGuid(value: String) = Guid("").apply { uuid = value }

		val parameter =
			CParameterSource().apply {
				guid = makeGuid("param-a")
				id = makeId("ParamShrink")
				minValue = -1f
				maxValue = 1f
				defaultValue = 1f
				paramType = Type.MORPH_TARGET
			}
		val formAtMinusOne =
			CWarpDeformerForm().apply {
				guid = makeGuid("form-minus")
				positions = floatArrayOf(-1f, -2f)
			}
		val formAtPlusOne =
			CWarpDeformerForm().apply {
				guid = makeGuid("form-plus")
				positions = floatArrayOf(1f, 2f)
			}
		val warp =
			CWarpDeformerSource().apply {
				guid = makeGuid("warp-a")
				id = makeId("Warp1")
				keyforms = arrayListOf(formAtMinusOne, formAtPlusOne)
				keyformMorphTargetSet =
					KeyFormMorphTargetSet().apply {
						_morphTargets =
							arrayListOf(
								// Deliberately unsorted: the +1 key first.
								KeyFormMorphTarget().apply {
									parameterGuid = makeGuid("param-a")
									keyValue = 1f
									keyformGuid = makeGuid("form-plus")
								},
								KeyFormMorphTarget().apply {
									parameterGuid = makeGuid("param-a")
									keyValue = -1f
									keyformGuid = makeGuid("form-minus")
								},
							)
					}
			}
		val model =
			CModelSource().apply {
				parameterSourceSet = CParameterSourceSet().apply { _sources = arrayListOf(parameter) }
				deformerSourceSet = CDeformerSourceSet().apply { _sources = arrayListOf(warp) }
			}

		val puppet = Cmo3Import.fromModelSource(model)

		assertEquals(ParameterKind.BLEND_SHAPE, puppet.parameters.single().kind, "MORPH_TARGET paramType maps to kind")
		val binding = (puppet.deformers.single() as Deformer.Warp).blendShapes.single()
		assertEquals(ParameterId("ParamShrink"), binding.parameterId, "binding driver")
		assertEquals(listOf(-1f, 0f, 1f), binding.keys.toList(), "keys sorted with neutral inserted at value 0")
		assertEquals(1, binding.neutralIndex, "neutral at parameter value 0, not the default (1.0)")
		assertNull(binding.forms[1], "inserted neutral has no source form")
		assertEquals(listOf(-1f, -2f), binding.forms[0]?.controlPoints?.toList(), "form at key -1")
		assertEquals(listOf(1f, 2f), binding.forms[2]?.controlPoints?.toList(), "form at key +1")
	}

	/**
	 * Corpus-free check that flat constraint records group into per-parameter limit curves sorted by
	 * the constraint parameter's value, attached to the driving parameter they cap.
	 */
	@Test
	fun groupsBlendWeightConstraintsIntoLimitCurves() {
		fun makeId(value: String) = Id("").apply { idstr = value }

		fun makeGuid(value: String) = Guid("").apply { uuid = value }

		val morphParameter =
			CParameterSource().apply {
				guid = makeGuid("param-a")
				id = makeId("ParamShrink")
				defaultValue = 0f
				paramType = Type.MORPH_TARGET
			}
		val constraintParameter =
			CParameterSource().apply {
				guid = makeGuid("param-b")
				id = makeId("ParamAngleX")
				defaultValue = 0f
			}
		val form =
			CWarpDeformerForm().apply {
				guid = makeGuid("form-1")
				positions = floatArrayOf(3f, 4f)
			}
		val warp =
			CWarpDeformerSource().apply {
				guid = makeGuid("warp-a")
				id = makeId("Warp1")
				keyforms = arrayListOf(form)
				keyformMorphTargetSet =
					KeyFormMorphTargetSet().apply {
						_morphTargets =
							arrayListOf(
								KeyFormMorphTarget().apply {
									parameterGuid = makeGuid("param-a")
									keyValue = 1f
									keyformGuid = makeGuid("form-1")
								},
							)
						blendWeightConstraintSet =
							MorphTargetBlendWeightConstraintSet().apply {
								_constraints =
									arrayListOf(
										// Deliberately out of order by constraint value.
										MorphTargetBlendWeightConstraint().apply {
											morphTargetParameterGuid = makeGuid("param-a")
											constraintParameterGuid = makeGuid("param-b")
											constraintParameterValue = 30f
											blendWeight = 0.2f
										},
										MorphTargetBlendWeightConstraint().apply {
											morphTargetParameterGuid = makeGuid("param-a")
											constraintParameterGuid = makeGuid("param-b")
											constraintParameterValue = -30f
											blendWeight = 1f
										},
										// A constraint for a DIFFERENT morph parameter: excluded from this binding.
										MorphTargetBlendWeightConstraint().apply {
											morphTargetParameterGuid = makeGuid("param-other")
											constraintParameterGuid = makeGuid("param-b")
											constraintParameterValue = 0f
											blendWeight = 0.5f
										},
									)
							}
					}
			}
		val model =
			CModelSource().apply {
				parameterSourceSet = CParameterSourceSet().apply { _sources = arrayListOf(morphParameter, constraintParameter) }
				deformerSourceSet = CDeformerSourceSet().apply { _sources = arrayListOf(warp) }
			}

		val binding = (Cmo3Import.fromModelSource(model).deformers.single() as Deformer.Warp).blendShapes.single()

		val limit = binding.limits.single()
		assertEquals(ParameterId("ParamAngleX"), limit.parameterId, "limit constraint parameter")
		assertEquals(listOf(-30f, 30f), limit.points.map { it.value }, "points sorted by constraint value")
		assertEquals(listOf(1f, 0.2f), limit.points.map { it.weight }, "weights follow their points")
	}

	/**
	 * Corpus-free check that unresolvable morph targets degrade without crashing: an unknown driving
	 * parameter drops its binding, an unknown form guid drops that key, and a source without morph
	 * targets ingests with empty blend shapes.
	 */
	@Test
	fun skipsUnresolvableMorphTargets() {
		fun makeId(value: String) = Id("").apply { idstr = value }

		fun makeGuid(value: String) = Guid("").apply { uuid = value }

		val parameter =
			CParameterSource().apply {
				guid = makeGuid("param-a")
				id = makeId("ParamShrink")
				defaultValue = 0f
				paramType = Type.MORPH_TARGET
			}
		val form =
			CWarpDeformerForm().apply {
				guid = makeGuid("form-1")
				positions = floatArrayOf(3f, 4f)
			}
		val warp =
			CWarpDeformerSource().apply {
				guid = makeGuid("warp-a")
				id = makeId("Warp1")
				keyforms = arrayListOf(form)
				keyformMorphTargetSet =
					KeyFormMorphTargetSet().apply {
						_morphTargets =
							arrayListOf(
								// Unknown parameter: the whole binding is skipped.
								KeyFormMorphTarget().apply {
									parameterGuid = makeGuid("param-unknown")
									keyValue = 1f
									keyformGuid = makeGuid("form-1")
								},
								// Known parameter, unknown form: the key is dropped, the binding survives.
								KeyFormMorphTarget().apply {
									parameterGuid = makeGuid("param-a")
									keyValue = -1f
									keyformGuid = makeGuid("form-unknown")
								},
								KeyFormMorphTarget().apply {
									parameterGuid = makeGuid("param-a")
									keyValue = 1f
									keyformGuid = makeGuid("form-1")
								},
							)
					}
			}
		val bareWarp =
			CWarpDeformerSource().apply {
				guid = makeGuid("warp-b")
				id = makeId("Warp2")
			}
		val model =
			CModelSource().apply {
				parameterSourceSet = CParameterSourceSet().apply { _sources = arrayListOf(parameter) }
				deformerSourceSet = CDeformerSourceSet().apply { _sources = arrayListOf(warp, bareWarp) }
			}

		val puppet = Cmo3Import.fromModelSource(model)

		val binding = (puppet.deformers.first { it.id.raw == "Warp1" } as Deformer.Warp).blendShapes.single()
		assertEquals(listOf(0f, 1f), binding.keys.toList(), "unresolvable form's key dropped; neutral + resolvable key remain")
		assertTrue(
			(puppet.deformers.first { it.id.raw == "Warp2" } as Deformer.Warp).blendShapes.isEmpty(),
			"source without morph targets ingests empty",
		)
	}
}
