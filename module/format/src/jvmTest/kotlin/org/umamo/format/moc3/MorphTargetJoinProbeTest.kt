package org.umamo.format.moc3

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.ACDeformerSource
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CDeformerSourceSet
import org.umamo.format.cmo3.model.gen.CDrawableSourceSet
import org.umamo.format.cmo3.model.gen.CParameterSource
import org.umamo.format.cmo3.model.gen.CParameterSourceSet
import org.umamo.format.cmo3.model.gen.CRotationDeformerSource
import org.umamo.format.cmo3.model.gen.CWarpDeformerSource
import org.umamo.format.cmo3.model.gen.KeyFormMorphTarget
import org.umamo.format.cmo3.model.gen.KeyFormMorphTargetSet
import org.umamo.format.cmo3.model.gen.Type
import org.umamo.format.cmo3.model.identity.Guid
import org.umamo.format.cmo3.model.identity.Id
import org.umamo.format.moc3.moc.ParameterType
import org.umamo.format.moc3.model.BlendShapeTarget
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Joins a CMO3's MorphTarget sets against the same model's baked MOC3 blend-shape records to
 * pin down the derivation rules the ingest needs - how morph targets group into records, where
 * the neutral key comes from, and whether a NORMAL-typed parameter can drive a morph target.
 *
 * The join is by id for art meshes (moc3 deformers carry no ids), and by document-order index for
 * deformers - the index correspondence is itself one of the hypotheses this probe checks. Keyed on
 * Model A's pair from `cmo3.probe` + `moc3.samples`; skips gracefully when either is absent.
 */
class MorphTargetJoinProbeTest {
	private val modelACmo3: File? =
		System.getProperty("cmo3.probe")?.split(',')?.map(::File)
			?.firstOrNull { it.isFile && it.name.startsWith("modelA") }
	private val modelAMoc3: File? =
		System.getProperty("moc3.samples")?.let(::File)?.takeIf { it.isDirectory }
			?.walkTopDown()?.firstOrNull { it.isFile && it.name == "modelA.moc3" }

	/** One CMO3 morph-target group: everything on one source driven by one parameter. */
	private data class MorphGroup(
		val sourceKind: String,
		val sourceId: String,
		val sourceOrderIndex: Int,
		val parameterId: String,
		val parameterType: String,
		val parameterDefault: Float,
		val keyValues: List<Float>,
	)

	@Test
	fun joinModelAMorphTargetsAgainstBakedRecords() {
		val cmo3File = modelACmo3
		val moc3File = modelAMoc3
		if (cmo3File == null || moc3File == null) {
			println("Model A corpus pair not present; skipping morph-target join probe")
			return
		}
		val root = Cmo3.read(cmo3File).root as? CModelSource ?: error("root is not a CModelSource")

		val parameterSources =
			elements((root.parameterSourceSet as? CParameterSourceSet)?._sources)
				.filterIsInstance<CParameterSource>()
		val parameterByUuid =
			parameterSources.mapNotNull { source ->
				val uuid = (source.guid as? Guid)?.uuid ?: return@mapNotNull null
				uuid to source
			}.toMap()

		val drawableSources =
			elements((root.drawableSourceSet as? CDrawableSourceSet)?._sources)
				.filterIsInstance<CArtMeshSource>()
		val deformerSources =
			elements((root.deformerSourceSet as? CDeformerSourceSet)?._sources)
				.filterIsInstance<ACDeformerSource>()

		/**
		 * Collects the morph-target groups on one controllable source.
		 *
		 * @param String kind         The source kind label (mesh / warp / rotation).
		 * @param Any?   morphSet     The source's keyformMorphTargetSet.
		 * @param Any?   sourceId     The source's id object.
		 * @param Int    orderIndex   The source's document-order index within its kind.
		 * @return List<MorphGroup> One group per driving parameter.
		 */
		fun groupsOf(kind: String, morphSet: Any?, sourceId: Any?, orderIndex: Int): List<MorphGroup> {
			val set = morphSet as? KeyFormMorphTargetSet ?: return emptyList()
			val targets = elements(set._morphTargets).filterIsInstance<KeyFormMorphTarget>()
			if (targets.isEmpty()) {
				return emptyList()
			}
			return targets.groupBy { (it.parameterGuid as? Guid)?.uuid }.mapNotNull { (uuid, group) ->
				val parameter = uuid?.let(parameterByUuid::get) ?: return@mapNotNull null
				MorphGroup(
					sourceKind = kind,
					sourceId = (sourceId as? Id)?.idstr ?: "?",
					sourceOrderIndex = orderIndex,
					parameterId = (parameter.id as? Id)?.idstr ?: "?",
					parameterType = (parameter.paramType as? Type)?.name ?: "null",
					parameterDefault = parameter.defaultValue,
					keyValues = group.map { it.keyValue }.sorted(),
				)
			}
		}

		val meshGroups =
			drawableSources.flatMapIndexed { index, source ->
				groupsOf("mesh", source.keyformMorphTargetSet, source.id, index)
			}
		val warpGroups =
			deformerSources.filterIsInstance<CWarpDeformerSource>().let { warps ->
				warps.flatMapIndexed { index, source ->
					groupsOf("warp", source.keyformMorphTargetSet, source.id, index)
				}
			}
		val rotationGroups =
			deformerSources.filterIsInstance<CRotationDeformerSource>().let { rotations ->
				rotations.flatMapIndexed { index, source ->
					groupsOf("rotation", source.keyformMorphTargetSet, source.id, index)
				}
			}
		val allGroups = meshGroups + warpGroups + rotationGroups

		println("[probe] CMO3 morph groups: total=${allGroups.size} mesh=${meshGroups.size} warp=${warpGroups.size} rotation=${rotationGroups.size}")
		println("[probe] CMO3 morph target records: ${allGroups.sumOf { it.keyValues.size }}")

		// E4: driving-parameter typing.
		val drivingParams = allGroups.map { it.parameterId to it.parameterType }.distinct().sortedBy { it.first }
		val morphTypedParams =
			parameterSources.filter { (it.paramType as? Type) == Type.MORPH_TARGET }
				.mapNotNull { (it.id as? Id)?.idstr }
		println("[probe] E4: distinct driving params=${drivingParams.size}, MORPH_TARGET-typed params=${morphTypedParams.size}")
		for ((parameterId, parameterType) in drivingParams) {
			if (parameterType != "MORPH_TARGET") {
				println("[probe] E4: driving param $parameterId has paramType=$parameterType")
			}
		}
		val unusedMorphParams = morphTypedParams.toSet() - drivingParams.map { it.first }.toSet()
		if (unusedMorphParams.isNotEmpty()) {
			println("[probe] E4: MORPH_TARGET params driving nothing: $unusedMorphParams")
		}

		// MOC3 side.
		val document = Moc3.decode(moc3File.readBytes())
		val records = document.blendShapes
		val warpIndexToDeformer = document.deformers.withIndex().filter { it.value is org.umamo.format.moc3.model.WarpDeformer }.map { it.index }
		val rotationIndexToDeformer = document.deformers.withIndex().filter { it.value is org.umamo.format.moc3.model.RotationDeformer }.map { it.index }
		println("[probe] MOC3 records=${records.size} (params typed BLEND_SHAPE=${document.parameters.count { it.type == ParameterType.BLEND_SHAPE }})")

		// E3 join: meshes by id, deformers by document-order index within their kind.
		var matched = 0
		var neutralAtDefault = 0
		var neutralInCmo3Keys = 0
		var keySetMatchesWithNeutralAdded = 0
		for (record in records) {
			val parameterId = document.parameters[record.parameterIndex].id
			val group =
				when (record.target) {
					BlendShapeTarget.PART -> null // part morph targets are not collected CMO3-side yet
					BlendShapeTarget.ART_MESH ->
						meshGroups.firstOrNull { it.sourceId == document.artMeshes[record.targetIndex].id && it.parameterId == parameterId }
					BlendShapeTarget.WARP ->
						warpGroups.firstOrNull { warpIndexToDeformer.getOrNull(it.sourceOrderIndex) == record.targetIndex && it.parameterId == parameterId }
					BlendShapeTarget.ROTATION ->
						rotationGroups.firstOrNull { rotationIndexToDeformer.getOrNull(it.sourceOrderIndex) == record.targetIndex && it.parameterId == parameterId }
				}
			val mocKeys = record.keyPositions.toList()
			val neutralValue = record.keyPositions.getOrNull(record.neutralKeyIndex)
			val parameterDefault = document.parameters[record.parameterIndex].defaultValue
			if (group == null) {
				println("[probe] UNMATCHED moc record: ${record.target} target=${record.targetIndex} param=$parameterId keys=$mocKeys neutral=$neutralValue")
				continue
			}
			matched++
			if (neutralValue == parameterDefault) {
				neutralAtDefault++
			}
			if (group.keyValues.any { it == neutralValue }) {
				neutralInCmo3Keys++
			}
			if ((group.keyValues + listOfNotNull(neutralValue)).distinct().sorted() == mocKeys.distinct().sorted()) {
				keySetMatchesWithNeutralAdded++
			}
			println(
				"[probe] ${record.target} ${group.sourceId} param=$parameterId cmo3Keys=${group.keyValues} " +
					"mocKeys=$mocKeys neutralIdx=${record.neutralKeyIndex} neutralVal=$neutralValue default=$parameterDefault",
			)
		}
		println(
			"[probe] E3 summary: records=${records.size} matched=$matched groups=${allGroups.size} " +
				"neutralAtDefault=$neutralAtDefault neutralInCmo3Keys=$neutralInCmo3Keys keySetMatchesWithNeutralAdded=$keySetMatchesWithNeutralAdded",
		)
		assertTrue(records.isNotEmpty(), "Model A's moc3 should carry blend-shape records")
		assertTrue(allGroups.isNotEmpty(), "Model A's cmo3 should carry morph-target groups")

		// E3 rule, asserted independent of deformer identity (moc3 deformers carry no ids, and the
		// order-index join above shows CMO3 and MOC3 deformer orders differ): as MULTISETS over
		// (target kind, driving parameter, key positions), the CMO3 groups with the parameter default
		// inserted as the neutral key equal the MOC3 records exactly.
		val kindByLabel = mapOf("mesh" to "ART_MESH", "warp" to "WARP", "rotation" to "ROTATION")
		val cmo3Multiset =
			allGroups.map { group ->
				val keysWithNeutral = (group.keyValues + group.parameterDefault).distinct().sorted()
				Triple(kindByLabel.getValue(group.sourceKind), group.parameterId, keysWithNeutral)
			}.groupingBy { it }.eachCount()
		val mocMultiset =
			records.map { record ->
				Triple(
					record.target.name,
					document.parameters[record.parameterIndex].id,
					record.keyPositions.toList().sorted(),
				)
			}.groupingBy { it }.eachCount()
		kotlin.test.assertEquals(
			cmo3Multiset,
			mocMultiset,
			"E3 rule: moc keys = cmo3 keys + neutral inserted at the parameter default",
		)
		for (record in records) {
			kotlin.test.assertEquals(
				document.parameters[record.parameterIndex].defaultValue,
				record.keyPositions[record.neutralKeyIndex],
				"E3 rule: every record's neutral key sits at the driving parameter's default",
			)
		}
	}

	/**
	 * Flattens a serializer collection payload (list/map/array) into its elements.
	 *
	 * @param Any? collection The raw collection field value.
	 * @return List<Any?> The contained elements, empty when absent.
	 */
	private fun elements(collection: Any?): List<Any?> =
		when (collection) {
			is Map<*, *> -> collection.values.toList()
			is Iterable<*> -> collection.toList()
			is Array<*> -> collection.toList()
			else -> emptyList()
		}

	/**
	 * Verifies the section-122 identity: across every corpus moc3 with blend shapes, (1) each
	 * record's delta block at its NEUTRAL key is all zeros in the shared value tables - the
	 * interpolation includes the neutral key's stored block when bracketing across it, so the
	 * format must store zeros there - and (2) section 122 is a redundant per-record copy of the
	 * record's binding key count (confirmed on Model A v6 + Model B/Model C v5).
	 */
	@Test
	fun neutralDeltaBlocksAreZeroAndSection122IsKeyCount() {
		val samples =
			System.getProperty("moc3.samples")?.let(::File)?.takeIf { it.isDirectory }
				?.walkTopDown()?.filter { it.isFile && it.extension == "moc3" }?.sortedBy { it.name }?.toList()
				.orEmpty()
		if (samples.isEmpty()) {
			println("moc3.samples not present; skipping neutral-delta probe")
			return
		}
		for (file in samples) {
			val model = org.umamo.format.moc3.moc.MocCodec.read(file.readBytes())
			val document = Moc3.decode(model)
			if (document.blendShapes.isEmpty()) {
				continue
			}
			val sections = model.sections
			val positionValues = sections.floatArray(org.umamo.format.moc3.moc.Section.KEYFORM_POSITION_VALUES)
			val meshPositionIndex = sections.intArray(org.umamo.format.moc3.moc.Section.KEYFORM_POSITION_INDEX)
			val warpPositionIndex = sections.intArray(org.umamo.format.moc3.moc.Section.WARP_KEYFORM_INDEX)
			val rotationAngle = sections.floatArray(org.umamo.format.moc3.moc.Section.ROTATION_ANGLE)
			val rotationOriginX = sections.floatArray(org.umamo.format.moc3.moc.Section.ROTATION_ORIGIN_X)
			val rotationOriginY = sections.floatArray(org.umamo.format.moc3.moc.Section.ROTATION_ORIGIN_Y)
			val rotationScale = sections.floatArray(org.umamo.format.moc3.moc.Section.ROTATION_SCALE)

			// (2) Table-level: sec122[record] == BINDING_KEY_COUNT[RECORD_BINDING[record]].
			val recordBinding = sections.intArray(org.umamo.format.moc3.moc.Section.BLENDSHAPE_RECORD_BINDING)
			val bindingKeyCount = sections.intArray(org.umamo.format.moc3.moc.Section.BLENDSHAPE_BINDING_KEY_COUNT)
			val recordKeyCount = sections.intArray(org.umamo.format.moc3.moc.Section.BLENDSHAPE_RECORD_KEY_COUNT)
			for (recordIndex in document.blendShapes.indices) {
				kotlin.test.assertEquals(
					bindingKeyCount[recordBinding[recordIndex]],
					recordKeyCount[recordIndex],
					"${file.name}: section 122 record $recordIndex is the binding key count",
				)
			}

			// (1) Neutral delta blocks are zero, per target kind - checked on the RAW tables, then
			// cross-checked against the decoder's typed extraction (BlendShape.keyforms) so the two
			// read paths can never drift apart.
			var meshChecked = 0
			var warpChecked = 0
			var rotationChecked = 0
			for (record in document.blendShapes) {
				val neutralRow = record.recordBase + record.neutralKeyIndex
				when (record.target) {
					BlendShapeTarget.ART_MESH -> {
						val blockLength = document.artMeshes[record.targetIndex].keyforms.first().vertexPositions.size
						val offset = meshPositionIndex[neutralRow]
						for (valueIndex in offset until offset + blockLength) {
							assertTrue(positionValues[valueIndex] == 0f, "${file.name}: mesh neutral delta zero at $valueIndex")
						}
						for (keyIndex in record.keyforms.indices) {
							val typed = record.keyforms[keyIndex] as org.umamo.format.moc3.model.BlendShapeKeyform.Mesh
							val rawOffset = meshPositionIndex[record.recordBase + keyIndex]
							kotlin.test.assertEquals(blockLength, typed.form.vertexPositions.size, "${file.name}: typed mesh block length")
							for (valueIndex in 0 until blockLength) {
								assertTrue(
									typed.form.vertexPositions[valueIndex] == positionValues[rawOffset + valueIndex],
									"${file.name}: typed mesh delta equals raw at key $keyIndex value $valueIndex",
								)
							}
						}
						meshChecked++
					}
					BlendShapeTarget.WARP -> {
						val warp = document.deformers[record.targetIndex] as org.umamo.format.moc3.model.WarpDeformer
						val blockLength = warp.keyforms.first().controlPoints.size
						val offset = warpPositionIndex[neutralRow]
						for (valueIndex in offset until offset + blockLength) {
							assertTrue(positionValues[valueIndex] == 0f, "${file.name}: warp neutral delta zero at $valueIndex")
						}
						for (keyIndex in record.keyforms.indices) {
							val typed = record.keyforms[keyIndex] as org.umamo.format.moc3.model.BlendShapeKeyform.Warp
							val rawOffset = warpPositionIndex[record.recordBase + keyIndex]
							kotlin.test.assertEquals(blockLength, typed.form.controlPoints.size, "${file.name}: typed warp block length")
							for (valueIndex in 0 until blockLength) {
								assertTrue(
									typed.form.controlPoints[valueIndex] == positionValues[rawOffset + valueIndex],
									"${file.name}: typed warp delta equals raw at key $keyIndex value $valueIndex",
								)
							}
						}
						warpChecked++
					}
					BlendShapeTarget.ROTATION -> {
						assertTrue(rotationAngle[neutralRow] == 0f, "${file.name}: rotation neutral angle zero")
						assertTrue(rotationOriginX[neutralRow] == 0f, "${file.name}: rotation neutral originX zero")
						assertTrue(rotationOriginY[neutralRow] == 0f, "${file.name}: rotation neutral originY zero")
						assertTrue(rotationScale[neutralRow] == 0f, "${file.name}: rotation neutral scale zero")
						for (keyIndex in record.keyforms.indices) {
							val typed = record.keyforms[keyIndex] as org.umamo.format.moc3.model.BlendShapeKeyform.Rotation
							val rawRow = record.recordBase + keyIndex
							assertTrue(typed.form.angle == rotationAngle[rawRow], "${file.name}: typed rotation angle equals raw at key $keyIndex")
							assertTrue(typed.form.originX == rotationOriginX[rawRow], "${file.name}: typed rotation originX equals raw at key $keyIndex")
							assertTrue(typed.form.originY == rotationOriginY[rawRow], "${file.name}: typed rotation originY equals raw at key $keyIndex")
							assertTrue(typed.form.scale == rotationScale[rawRow], "${file.name}: typed rotation scale equals raw at key $keyIndex")
						}
						rotationChecked++
					}
					BlendShapeTarget.PART -> {
						// MOC3 v5+ §5.6: part delta rows are draw-order floats in section 58 at
						// recordBase (verified on Model C: [-900, 0], neutral zero) - the typed
						// extraction is asserted by MocDecodeTest's Model C golden.
					}
				}
			}
			println("[probe] ${file.name}: neutral-zero + typed-extraction verified mesh=$meshChecked warp=$warpChecked rotation=$rotationChecked")
		}
	}
}
