package org.umamo.format.moc3

import org.umamo.format.moc3.encode.MocLowering
import org.umamo.format.moc3.io.LittleEndianReader
import org.umamo.format.moc3.moc.CanvasInfo
import org.umamo.format.moc3.moc.MocParameter
import org.umamo.format.moc3.moc.MocVersion
import org.umamo.format.moc3.moc.ParameterType
import org.umamo.format.moc3.moc.Section
import org.umamo.format.moc3.model.KeyformAxis
import org.umamo.format.moc3.model.KeyformBinding
import org.umamo.format.moc3.model.Part
import org.umamo.format.moc3.model.RenderOrderGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Lowering invariants the golden corpus structurally cannot check.
 *
 * Every corpus model is dense in its keyform bindings and none carries a blend-shape-typed parameter
 * that owns no record, so the choices those shapes would expose are invisible to the byte-exact gate:
 * a lowering that traversed the binding map by entry rather than by index, or that wrote the wrong
 * empty-slot filler, reproduces the corpus byte-for-byte and would only break on a file we emitted.
 * These documents are hand-built to have exactly the shapes the corpus lacks, and run unconditionally.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §5.6</a>
 */
class MocLoweringInvariantsTest {
	/**
	 * Reads a lowered section back as an [IntArray].
	 *
	 * @param Map        sections The lowered section map, keyed by table index.
	 * @param Section    section  The section to read.
	 * @param MocVersion version  The version whose table index applies.
	 * @return IntArray The decoded values.
	 */
	private fun intsOf(sections: Map<Int, ByteArray>, section: Section, version: MocVersion): IntArray {
		val bytes = sections.getValue(section.indexIn(version))
		val reader = LittleEndianReader(bytes)
		return IntArray(bytes.size / 4) { reader.readInt32() }
	}

	/**
	 * Builds a parameter with one axis worth of range.
	 *
	 * @param String         id   The parameter id.
	 * @param ParameterType? type The parameter type, or null when the version stores none.
	 * @return MocParameter The parameter.
	 */
	private fun parameter(id: String, type: ParameterType?) =
		MocParameter(
			id = id,
			minimumValue = -1f,
			maximumValue = 1f,
			defaultValue = 0f,
			repeats = false,
			type = type,
		)

	/**
	 * Builds a document around [keyformBindings], with no deformers, meshes, or blend records.
	 *
	 * @param Map<Int, KeyformBinding> keyformBindings The binding map, sparse or dense.
	 * @param List<MocParameter>       parameters      The parameter list.
	 * @return MocDocument The document.
	 */
	private fun documentWith(
		keyformBindings: Map<Int, KeyformBinding>,
		parameters: List<MocParameter>,
		renderOrderGroups: List<RenderOrderGroup> = emptyList(),
	) = MocDocument(
		version = MocVersion.V50,
		canvas = CanvasInfo(pixelsPerUnit = 1f, originX = 0f, originY = 0f, width = 10f, height = 10f),
		parameters = parameters,
		keyformBindings = keyformBindings,
		parts = listOf(Part("Part0", -1, 0, floatArrayOf(0f))),
		deformers = emptyList(),
		artMeshes = emptyList(),
		glues = emptyList(),
		renderOrderGroups = renderOrderGroups,
	)

	@Test
	fun countInfoSizesTheBindingTableByWidthNotByEntryCount() {
		val version = MocVersion.V50
		val parameters = listOf(parameter("ParamA", ParameterType.NORMAL))
		// A HOLE at index 1: three entries, but the table is four slots wide.  The runtime allocates its
		// keyform-binding array from CountInfo[12] and then indexes it by an object's raw
		// keyformBindingIndex, which reaches 3 here - so a count of 3 sizes the array one slot short of
		// the index that will be used against it, and the read runs off the end inside the official core.
		val sparse =
			mapOf(
				0 to KeyformBinding(index = 0, axes = listOf(KeyformAxis(0, floatArrayOf(-1f, 0f, 1f)))),
				2 to KeyformBinding(index = 2, axes = listOf(KeyformAxis(0, floatArrayOf(0f, 1f)))),
				3 to KeyformBinding(index = 3, axes = emptyList()),
			)
		val document = documentWith(sparse, parameters)
		val lowered = MocLowering.lower(document)
		val countInfo = intsOf(lowered, Section.COUNT_INFO, version)

		val bindingStart = intsOf(lowered, Section.KEYFORM_BINDING_START, version)
		val bindingCount = intsOf(lowered, Section.KEYFORM_BINDING_COUNT, version)
		assertEquals(4, bindingStart.size, "s73 covers every table slot, including the hole")
		assertEquals(4, bindingCount.size, "s74 covers every table slot, including the hole")
		assertEquals(
			bindingStart.size,
			countInfo[12],
			"CountInfo[12] sizes the runtime's binding array to the table width the sections actually carry",
		)
		// The hole lowers to a zero-axis binding, which the decoder reads back as a static binding -
		// degraded but loadable.  It must not shift the rows after it.
		assertEquals(0, bindingCount[1], "the hole contributes no axes")
	}

	@Test
	fun blendFreeParameterRunsCarryTheTypeSpecificEmptyFiller() {
		val version = MocVersion.V50
		// A blend-shape-typed parameter that owns no record stores begin = -1; a normal one stores 0.
		// Both fillers mean "no run", and no corpus model has the first shape on a blend-FREE document -
		// every v4+ blend-free sample is all-normal - so nothing else can tell the two apart.
		val parameters =
			listOf(
				parameter("ParamNormal", ParameterType.NORMAL),
				parameter("ParamMorph", ParameterType.BLEND_SHAPE),
			)
		val document = documentWith(emptyMap(), parameters)
		val lowered = MocLowering.lower(document)

		val parameterBegin = intsOf(lowered, Section.BLENDSHAPE_PARAMETER_BEGIN, version)
		val parameterCount = intsOf(lowered, Section.BLENDSHAPE_PARAMETER_COUNT, version)
		assertEquals(listOf(0, -1), parameterBegin.toList(), "s115 fillers are type-specific on a blend-free document")
		assertEquals(listOf(0, 0), parameterCount.toList(), "s116 counts no bindings for either parameter")
	}

	@Test
	fun parameterlessDocumentEmitsNoPerParameterBlendColumns() {
		val version = MocVersion.V50
		// The columns are per-parameter.  Emitting them zero-length would look harmless, but
		// `MocEncoder.bake` overrides a reference file's section with whatever the lowering produced -
		// so an empty section here erases one that was going to be carried.
		val document = documentWith(emptyMap(), emptyList())
		val lowered = MocLowering.lower(document)

		assertEquals(null, lowered[Section.BLENDSHAPE_PARAMETER_BEGIN.indexIn(version)], "s115 stays absent")
		assertEquals(null, lowered[Section.BLENDSHAPE_PARAMETER_COUNT.indexIn(version)], "s116 stays absent")
	}

	@Test
	fun everyProducerRunsThroughTheStrictMerge() {
		// `lower` throws when two producers claim one section index, so reaching it at all is the pass
		// condition - but only if every producer actually ran.  A document that reaches none of them
		// would return an empty map and "prove" disjointness vacuously, so this asserts a section OWNED
		// BY EACH producer is present: whatever they wrote, they wrote it through the strict merge.
		val parameters = listOf(parameter("ParamA", ParameterType.NORMAL), parameter("ParamB", ParameterType.BLEND_SHAPE))
		val bindings =
			mapOf(
				0 to KeyformBinding(index = 0, axes = listOf(KeyformAxis(0, floatArrayOf(-1f, 0f, 1f)))),
				1 to KeyformBinding(index = 1, axes = emptyList()),
			)
		// A childless render-order group is the cheapest thing that reaches the auxiliary producer -
		// its glue, render-order, and offscreen blocks are each guarded on a non-empty list.
		val document = documentWith(bindings, parameters, renderOrderGroups = listOf(RenderOrderGroup(emptyList())))
		val version = MocVersion.V50
		val lowered = MocLowering.lower(document)

		val ownedByProducer =
			mapOf(
				"structural" to Section.PARAM_ID,
				"valueTables" to Section.PART_DRAW_ORDER,
				"auxiliary" to Section.RENDER_ORDER_CHILD_COUNT,
				"keyformGrid" to Section.KEY_POSITIONS,
				"blendShapes" to Section.BLENDSHAPE_PARAMETER_BEGIN,
				"runtimeSlots" to Section.PART_RUNTIME_SLOT,
				"derivedIndexes" to Section.PARAM_SNAP_TYPE,
				"countInfo" to Section.COUNT_INFO,
			)
		for ((producerName, section) in ownedByProducer) {
			assertTrue(
				lowered.containsKey(section.indexIn(version)),
				"$producerName ran: ${section.name} is present in the merged map",
			)
		}
	}

	@Test
	fun countInfoCountsEveryFloatWrittenIntoKeyPositions() {
		val version = MocVersion.V50
		val parameters = listOf(parameter("ParamA", ParameterType.NORMAL), parameter("ParamB", ParameterType.NORMAL))
		// Two parameters, each with two distinct key-lists, so the main-grid dedup region AND the
		// per-parameter union region are both non-empty.  Every v4+ blend-free corpus model has zero
		// parameter bindings, so this shape is the one the corpus cannot produce.
		val bindings =
			mapOf(
				0 to KeyformBinding(index = 0, axes = listOf(KeyformAxis(0, floatArrayOf(-1f, 0f, 1f)))),
				1 to KeyformBinding(index = 1, axes = listOf(KeyformAxis(0, floatArrayOf(-1f, 1f)))),
				2 to KeyformBinding(index = 2, axes = listOf(KeyformAxis(1, floatArrayOf(0f, 0.5f, 1f)))),
			)
		val document = documentWith(bindings, parameters)
		val lowered = MocLowering.lower(document)
		val countInfo = intsOf(lowered, Section.COUNT_INFO, version)

		val keyPositions = lowered.getValue(Section.KEY_POSITIONS.indexIn(version))
		val parameterKeyStart = intsOf(lowered, Section.PARAM_KEY_START, version)
		val parameterKeyCount = intsOf(lowered, Section.PARAM_KEY_COUNT, version)
		assertEquals(
			keyPositions.size / 4,
			countInfo[14],
			"CountInfo[14] declares exactly the float count section 77 carries",
		)
		// 103/104 address the union region; the runtime sizes its parameter key store from CountInfo[14]
		// and then reads those runs out of it, so a run ending past the declared count reads past the
		// buffer the runtime just allocated.
		val lastRunEnd = parameterKeyStart.last() + parameterKeyCount.last()
		assertEquals(
			countInfo[14],
			lastRunEnd,
			"the last parameter key run ends exactly at the declared KEY_POSITIONS extent",
		)
	}
}
