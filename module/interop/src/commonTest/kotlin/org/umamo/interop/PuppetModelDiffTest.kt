package org.umamo.interop

import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
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
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RuntimeTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pins diffPuppetModels' contract directly - the reconcile backbone every CMO3 export round-trip
 * test exercises only end to end.  Covers each category's Created/Deleted set-difference, the exact
 * Changed field sets (ParameterField and GlueField exhaustively; representative shallow/deep/
 * reference fields for parts, deformers, and drawables), the glue ordered-pair + ordinal keying,
 * bit-exact float comparison, and baseline-order emission.
 */
class PuppetModelDiffTest {
	private val angleX = ParameterId("ParamAngleX")

	private fun parameter(id: String, min: Float = -1f, max: Float = 1f, default: Float = 0f): Parameter =
		Parameter(ParameterId(id), id, min = min, max = max, default = default)

	/** A one-triangle drawable with no keyforms and no deformer parent. */
	private fun drawable(id: String): Drawable =
		Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh =
				DrawableMesh(
					positions = floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f),
					uvs = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f),
					indices = intArrayOf(0, 1, 2),
				),
			geometryGrid = null,
		)

	private fun part(id: String): Part = Part(id = PartId(id), name = id, children = emptyList())

	/** A one-axis mesh-delta grid on angleX whose single interesting delta is [firstDelta]. */
	private fun deltaGrid(firstDelta: Float): KeyformGrid<MeshDeltaForm> =
		KeyformGrid(
			listOf(KeyformAxis(angleX, floatArrayOf(0f, 1f))),
			listOf(
				KeyformCell(intArrayOf(0), MeshDeltaForm(floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f))),
				KeyformCell(intArrayOf(1), MeshDeltaForm(floatArrayOf(firstDelta, 0f, 0f, 0f, 0f, 0f))),
			),
		)

	/** A one-axis opacity track on angleX ending at [lastValue]. */
	private fun opacityTrack(lastValue: Float): ChannelGrids =
		ChannelGrids(
			mapOf(
				FormChannel.OPACITY to
					KeyformGrid(
						listOf(KeyformAxis(angleX, floatArrayOf(0f, 1f))),
						listOf(
							KeyformCell(intArrayOf(0), ChannelValue.Scalar(1f) as ChannelValue),
							KeyformCell(intArrayOf(1), ChannelValue.Scalar(lastValue)),
						),
					),
			),
		)

	/**
	 * A minimal model over explicit entity lists.  rootChildren stays empty on purpose, so mutating an
	 * entity list never doubles as a ROOT_CHILDREN document diff.
	 */
	private fun puppet(
		parameters: List<Parameter> = emptyList(),
		parts: List<Part> = emptyList(),
		deformers: List<Deformer> = emptyList(),
		drawables: List<Drawable> = emptyList(),
		glues: List<Glue> = emptyList(),
		parameterTree: List<ParameterNode> = emptyList(),
	): PuppetModel =
		PuppetModel(
			parameters = parameters,
			parts = parts,
			deformers = deformers,
			drawables = drawables,
			rootChildren = emptyList(),
			rootPartId = null,
			glues = glues,
			parameterTree = parameterTree,
			canvasWidth = 100f,
			canvasHeight = 100f,
		)

	/** The field set of a category's single Changed entry, asserting it is the only diff. */
	private fun <TId, TField> onlyChangedFields(diffs: List<EntityDiff<TId, TField>>): Set<TField> {
		assertEquals(1, diffs.size)
		return when (val entry = diffs.single()) {
			is EntityDiff.Changed -> entry.fields
			else -> fail("expected a Changed entry, got $entry")
		}
	}

	/** The same instance on both sides is semantically identical. */
	@Test
	fun identicalModelInstanceProducesEmptyDiff() {
		val model = puppet(parameters = listOf(parameter("ParamA")), drawables = listOf(drawable("d1")))
		assertTrue(diffPuppetModels(model, model).isEmpty)
	}

	/** Field-equal copies (fresh instances) still diff empty - equality is by value, not identity. */
	@Test
	fun fieldEqualCopyProducesEmptyDiff() {
		val baseline = puppet(parameters = listOf(parameter("ParamA")), drawables = listOf(drawable("d1")))
		val edited = baseline.copy(drawables = baseline.drawables.map { entity -> entity.copy() })
		assertTrue(diffPuppetModels(baseline, edited).isEmpty)
	}

	/** Creation and deletion are id set-difference: one-sided ids yield Created/Deleted entries. */
	@Test
	fun parameterCreatedAndDeletedById() {
		val kept = parameter("ParamKept")
		val baseline = puppet(parameters = listOf(kept, parameter("ParamGone")))
		val edited = puppet(parameters = listOf(kept, parameter("ParamNew")))
		val diff = diffPuppetModels(baseline, edited)
		assertEquals(
			listOf(EntityDiff.Deleted(ParameterId("ParamGone")), EntityDiff.Created(ParameterId("ParamNew"))),
			diff.parameters,
		)
	}

	/** Each ParameterField flags exactly its own edit - the full enum, one field per case. */
	@Test
	fun parameterChangedFieldsAreExact() {
		val baseline = puppet(parameters = listOf(parameter("ParamA")))

		fun fieldsAfter(edit: (Parameter) -> Parameter): Set<ParameterField> =
			onlyChangedFields(diffPuppetModels(baseline, puppet(parameters = listOf(edit(baseline.parameters.single())))).parameters)
		assertEquals(setOf(ParameterField.NAME), fieldsAfter { entity -> entity.copy(name = "Renamed") })
		assertEquals(setOf(ParameterField.RANGE), fieldsAfter { entity -> entity.copy(max = 2f) })
		assertEquals(setOf(ParameterField.KIND), fieldsAfter { entity -> entity.copy(kind = ParameterKind.BLEND_SHAPE) })
	}

	/** A one-ULP float nudge is a reported change - the diff is bit-exact, never tolerance-based. */
	@Test
	fun oneUlpFloatDifferenceIsReported() {
		val baseline = puppet(parameters = listOf(parameter("ParamA", min = 0.5f)))
		val nudgedMin = Float.fromBits(0.5f.toRawBits() + 1)
		val edited = puppet(parameters = listOf(baseline.parameters.single().copy(min = nudgedMin)))
		assertEquals(setOf(ParameterField.RANGE), onlyChangedFields(diffPuppetModels(baseline, edited).parameters))
	}

	/** A group rename flags only that group; its unchanged identity keeps the document tree quiet. */
	@Test
	fun parameterGroupChangedFieldsAreExact() {
		val groupId = ParameterGroupId("Group1")

		fun treeOf(group: ParameterNode.Group): List<ParameterNode> = listOf(group)
		val baselineGroup = ParameterNode.Group(groupId, "Face", initiallyOpen = true, children = listOf(ParameterNode.Param(angleX)))
		val baseline = puppet(parameterTree = treeOf(baselineGroup))

		fun diffAfter(edited: ParameterNode.Group): PuppetDiff = diffPuppetModels(baseline, puppet(parameterTree = treeOf(edited)))
		val renamed = diffAfter(baselineGroup.copy(name = "Body"))
		assertEquals(setOf(ParameterGroupField.NAME), onlyChangedFields(renamed.parameterGroups))
		assertEquals(emptySet(), renamed.document, "a rename keeps the tree's identity sequence")
		val collapsed = diffAfter(baselineGroup.copy(initiallyOpen = false))
		assertEquals(setOf(ParameterGroupField.INITIALLY_OPEN), onlyChangedFields(collapsed.parameterGroups))
		val emptied = diffAfter(baselineGroup.copy(children = emptyList()))
		assertEquals(setOf(ParameterGroupField.CHILDREN), onlyChangedFields(emptied.parameterGroups))
		assertEquals(emptySet(), emptied.document, "group content is reported per group, not as a tree change")
	}

	/** Adding a group is a Created entry AND a document tree change - the top level gained a node. */
	@Test
	fun parameterGroupCreatedFlagsTheDocumentTree() {
		val baseline = puppet(parameterTree = listOf(ParameterNode.Param(angleX)))
		val added = ParameterNode.Group(ParameterGroupId("Group1"), "Face", initiallyOpen = true, children = emptyList())
		val diff = diffPuppetModels(baseline, puppet(parameterTree = listOf(ParameterNode.Param(angleX), added)))
		assertEquals(listOf<EntityDiff<ParameterGroupId, ParameterGroupField>>(EntityDiff.Created(added.id)), diff.parameterGroups)
		assertEquals(setOf(DocumentField.PARAMETER_TREE), diff.document)
	}

	/** Part diffs: a shallow scalar (NAME), a reference list (CHILDREN), plus Created and Deleted. */
	@Test
	fun partChangesAndSetDifference() {
		val keptPart = part("PartKept")
		val editablePart = part("PartEdited")
		val baseline = puppet(parts = listOf(keptPart, editablePart, part("PartGone")))
		val edited =
			puppet(
				parts =
					listOf(
						keptPart,
						editablePart.copy(name = "Renamed", children = listOf(OrgChild.Drawable(DrawableId("d1")))),
						part("PartNew"),
					),
			)
		val diff = diffPuppetModels(baseline, edited)
		assertEquals(
			listOf(
				EntityDiff.Changed(editablePart.id, setOf(PartField.NAME, PartField.CHILDREN)),
				EntityDiff.Deleted(PartId("PartGone")),
				EntityDiff.Created(PartId("PartNew")),
			),
			diff.parts,
		)
	}

	/** Deformer diffs: warp lattice/geometry/statics fields, and a kind swap reports only KIND. */
	@Test
	fun deformerChangesAndKindSwap() {
		val warp =
			Deformer.Warp(
				id = DeformerId("Warp1"),
				name = "Warp1",
				parent = null,
				partId = null,
				rows = 2,
				columns = 2,
				isQuadTransform = false,
				geometryGrid = null,
			)
		val baseline = puppet(deformers = listOf(warp))

		fun fieldsAfter(edited: Deformer): Set<DeformerField> =
			onlyChangedFields(diffPuppetModels(baseline, puppet(deformers = listOf(edited))).deformers)
		assertEquals(setOf(DeformerField.LATTICE), fieldsAfter(warp.copy(rows = 3)))
		assertEquals(setOf(DeformerField.STATICS), fieldsAfter(warp.copy(opacity = 0.5f)))
		assertEquals(setOf(DeformerField.PARENT), fieldsAfter(warp.copy(parent = DeformerId("Other"))))
		val rotationUnderSameId =
			Deformer.Rotation(
				id = warp.id,
				name = warp.name,
				parent = null,
				partId = null,
				baseAngle = 0f,
				geometryGrid = null,
			)
		assertEquals(setOf(DeformerField.KIND), fieldsAfter(rotationUnderSameId), "a kind swap short-circuits the field walk")
	}

	/** Drawable diffs: geometry grid, channel tracks, and the topology-vs-positions mesh split. */
	@Test
	fun drawableChangedFieldsAreExact() {
		val gridded = drawable("d1").copy(geometryGrid = deltaGrid(1f), channelGrids = opacityTrack(0.5f))
		val baseline = puppet(drawables = listOf(gridded))

		fun fieldsAfter(edited: Drawable): Set<DrawableField> =
			onlyChangedFields(diffPuppetModels(baseline, puppet(drawables = listOf(edited))).drawables)
		assertEquals(setOf(DrawableField.BLEND_MODE), fieldsAfter(gridded.copy(blendMode = BlendMode.Multiply)))
		assertEquals(setOf(DrawableField.GEOMETRY), fieldsAfter(gridded.copy(geometryGrid = deltaGrid(2f))))
		assertEquals(setOf(DrawableField.CHANNELS), fieldsAfter(gridded.copy(channelGrids = opacityTrack(0.25f))))
		val mesh = gridded.mesh!!
		val movedVertex =
			DrawableMesh(
				positions = floatArrayOf(0f, 0f, 10f, 0f, 5f, 10f),
				uvs = mesh.uvs,
				indices = mesh.indices,
			)
		assertEquals(setOf(DrawableField.MESH_POSITIONS), fieldsAfter(gridded.copy(mesh = movedVertex)))
		val rewound =
			DrawableMesh(
				positions = mesh.positions,
				uvs = mesh.uvs,
				indices = intArrayOf(2, 1, 0),
			)
		assertEquals(setOf(DrawableField.MESH_TOPOLOGY), fieldsAfter(gridded.copy(mesh = rewound)))
	}

	/** A glue is keyed by its ORDERED mesh pair - reversing the pair is a delete plus a create. */
	@Test
	fun glueKeyedByOrderedPairAndOrdinal() {
		val meshA = DrawableId("a")
		val meshB = DrawableId("b")
		val weld = Glue(meshA, meshB, listOf(GluePair(0, 0, 0.5f, 0.5f)))
		val baseline = puppet(drawables = listOf(drawable("a"), drawable("b")), glues = listOf(weld))
		val reversed = puppet(drawables = baseline.drawables, glues = listOf(Glue(meshB, meshA, weld.pairs)))
		assertEquals(
			setOf(GlueDiff.Deleted(meshA, meshB, 0), GlueDiff.Created(meshB, meshA, 0)),
			diffPuppetModels(baseline, reversed).glues.toSet(),
		)
		val doubled = puppet(drawables = baseline.drawables, glues = listOf(weld, Glue(meshA, meshB, weld.pairs)))
		assertEquals(listOf(GlueDiff.Created(meshA, meshB, 1)), diffPuppetModels(baseline, doubled).glues)
	}

	/** Each GlueField flags exactly its own edit - the full enum, one field per case. */
	@Test
	fun glueChangedFieldsAreExact() {
		val meshA = DrawableId("a")
		val meshB = DrawableId("b")
		val weld = Glue(meshA, meshB, listOf(GluePair(0, 0, 0.5f, 0.5f)))
		val baseline = puppet(drawables = listOf(drawable("a"), drawable("b")), glues = listOf(weld))

		fun fieldsAfter(edited: Glue): Set<GlueField> {
			val diff = diffPuppetModels(baseline, puppet(drawables = baseline.drawables, glues = listOf(edited))).glues
			return (diff.single() as GlueDiff.Changed).fields
		}
		assertEquals(setOf(GlueField.PAIRS), fieldsAfter(Glue(meshA, meshB, listOf(GluePair(0, 0, 0.9f, 0.5f)))))
		assertEquals(setOf(GlueField.INTENSITY), fieldsAfter(Glue(meshA, meshB, weld.pairs, intensity = 0.5f)))
		assertEquals(setOf(GlueField.CHANNELS), fieldsAfter(Glue(meshA, meshB, weld.pairs, channelGrids = opacityTrack(0.5f))))
	}

	/** Document-level edits flag their own field and nothing else. */
	@Test
	fun documentFieldChangesAreExact() {
		val baseline = puppet(parameters = listOf(parameter("ParamA"), parameter("ParamB")))
		val resized = diffPuppetModels(baseline, baseline.copy(canvasWidth = 200f))
		assertEquals(setOf(DocumentField.CANVAS_SIZE), resized.document)
		assertTrue(resized.parameters.isEmpty())
		val retargeted = diffPuppetModels(baseline, baseline.copy(runtimeTarget = RuntimeTarget.entries.first { target -> target != RuntimeTarget.NoTarget }))
		assertEquals(setOf(DocumentField.RUNTIME_TARGET), retargeted.document)
		val reordered = diffPuppetModels(baseline, baseline.copy(parameters = baseline.parameters.reversed()))
		assertEquals(setOf(DocumentField.PARAMETER_ORDER), reordered.document)
		assertTrue(reordered.parameters.isEmpty(), "a pure reorder changes no parameter entity")
	}

	/** Diffs emit in BASELINE order regardless of the edited list's order, with creations appended. */
	@Test
	fun changedEntitiesEmitInBaselineOrder() {
		val partA = part("PartA")
		val partB = part("PartB")
		val partC = part("PartC")
		val baseline = puppet(parts = listOf(partA, partB, partC))
		val edited =
			puppet(
				parts =
					listOf(
						part("PartNew"),
						partC.copy(name = "RenamedC"),
						partA,
						partB.copy(name = "RenamedB"),
					),
			)
		val diff = diffPuppetModels(baseline, edited)
		assertEquals(listOf(PartId("PartB"), PartId("PartC"), PartId("PartNew")), diff.parts.map { entry -> entry.id })
	}
}