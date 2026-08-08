package org.umamo.ui.workspace

import org.umamo.interop.ExportEntityCategory
import org.umamo.interop.ExportNoticeReason
import org.umamo.interop.KeyformBundleRejection
import org.umamo.runtime.model.FormChannel
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the export report's localization coverage.
 *
 * The phrase mappings are exhaustive `when`s, so a reason with no sentence does not compile.  What the
 * compiler cannot catch is the two failure modes this covers: a reason whose sentence is a copy of
 * another's (so the dialog explains the wrong thing), and a phrase whose argument count disagrees with
 * its template's placeholders (so the dialog prints a literal %1$d).
 *
 * The sample list below is the forcing function for the first: a sealed interface has no `entries`, so
 * coverage is checked against the reflected subclass list and a new case fails here until it is added.
 */
class ExportNoticeLabelsTest {
	/**
	 * One instance of every [ExportNoticeReason] case.  Field values are arbitrary - only the case
	 * identity and the argument count matter here.
	 */
	private val sampleReasons: List<ExportNoticeReason> =
		listOf(
			ExportNoticeReason.DrawableHasNoMesh,
			ExportNoticeReason.ChannelDemotedToStatic(FormChannel.OPACITY),
			ExportNoticeReason.SketchPartIsNotRuntimeContent,
			ExportNoticeReason.HiddenPartOmittedByExportOption,
			ExportNoticeReason.HiddenDrawableOmittedByExportOption,
			ExportNoticeReason.UnkeyedDrawableUnderDeformerHasNoParentGeometry,
			ExportNoticeReason.RestMeshConversionSizeMismatch(6, 8),
			ExportNoticeReason.NoAtlasPageBound,
			ExportNoticeReason.ClippingMaskNotInExport(listOf("Mask01")),
			ExportNoticeReason.OffscreenMaskNotInExport,
			ExportNoticeReason.WarpDeformerHasNoLattice,
			ExportNoticeReason.RotationDeformerHasNoPivot,
			ExportNoticeReason.GlueNamesAnUnknownDrawable,
			ExportNoticeReason.IdTruncated(64, "Param01"),
			ExportNoticeReason.IdTruncatedAndDisambiguated(64, "Param01-2"),
			ExportNoticeReason.NoMatchingSourceToReconcile,
			ExportNoticeReason.ParameterKindChangeNotLowered,
			ExportNoticeReason.DeformerKindChangeNotLowered,
			ExportNoticeReason.DeformerEditNeedsWarpSource,
			ExportNoticeReason.DeformerEditNeedsRotationSource,
			ExportNoticeReason.TextureSourceRebindingIsEditorOnly,
			ExportNoticeReason.BaseGeometryVertexCountMismatch,
			ExportNoticeReason.NoUvsToReconcile,
			ExportNoticeReason.DeformerHasNoPartToMoveTo,
			ExportNoticeReason.StaticDrawOrderShadowedByKeyforms,
			ExportNoticeReason.CompositeStaticsShadowedByKeyforms,
			ExportNoticeReason.PartMasksFlattenToDrawables,
			ExportNoticeReason.NoSourceSetToCreateInto,
			ExportNoticeReason.CreatedEntityHasNoSourceYet,
			ExportNoticeReason.CreatedGroupIsNotInTheEditedTree,
			ExportNoticeReason.CreatedDrawableHasNoTextureSource,
			ExportNoticeReason.GluedDrawableHasNoSource,
			ExportNoticeReason.VertexCountExceedsEdgeTable(40000, 32767),
			ExportNoticeReason.GluedMeshHasNoUidTable,
			ExportNoticeReason.GluePairIndexesPastUidTable,
			ExportNoticeReason.KeyformCannotBundle(KeyformBundleRejection.KeysOutsideGeometrySpan),
			ExportNoticeReason.AxisParameterHasNoSource("ParamAngleX"),
			ExportNoticeReason.BlendShapeParameterHasNoSource("ParamSmile"),
			ExportNoticeReason.KeyformsWithoutBaseMesh,
			ExportNoticeReason.FractionalDrawOrderNotStorable,
			ExportNoticeReason.StaticGlueIntensityWithoutKeyforms,
			ExportNoticeReason.NoAuthoredWorldOrigin,
			ExportNoticeReason.NoRootParameterGroup,
			ExportNoticeReason.NoRootPart,
			ExportNoticeReason.NoParameterSourceSet,
			ExportNoticeReason.CombinedPairReordered("ParamAngleX", "ParamAngleY"),
			ExportNoticeReason.NoCanvasToReconcile,
			ExportNoticeReason.FractionalCanvasSizeNotStorable,
		)

	private val sampleRejections: List<KeyformBundleRejection> =
		listOf(
			KeyformBundleRejection.KeysOutsideGeometrySpan,
			KeyformBundleRejection.ChannelKeysWithoutGeometry,
			KeyformBundleRejection.KeysOutsideChannelSpan(FormChannel.OPACITY),
		)

	@Test
	fun everySampleCoversItsSealedHierarchy() {
		val declaredReasons = ExportNoticeReason::class.sealedSubclasses.map { it.simpleName }.toSet()
		val declaredRejections = KeyformBundleRejection::class.sealedSubclasses.map { it.simpleName }.toSet()
		// Reflection over a sealed hierarchy yields an empty list when kotlin-reflect is off the test
		// classpath, which would turn both assertions below into tautologies.  Fail loudly instead.
		assertTrue(declaredReasons.isNotEmpty(), "sealedSubclasses is empty; is kotlin-reflect missing?")
		assertTrue(declaredRejections.isNotEmpty(), "sealedSubclasses is empty; is kotlin-reflect missing?")

		assertEquals(
			declaredReasons,
			sampleReasons.map { reason -> reason::class.simpleName }.toSet(),
			"add the new ExportNoticeReason case to sampleReasons",
		)
		assertEquals(
			declaredRejections,
			sampleRejections.map { rejection -> rejection::class.simpleName }.toSet(),
			"add the new KeyformBundleRejection case to sampleRejections",
		)
	}

	@Test
	fun everyReasonMapsToADistinctSentence() {
		val resources = sampleReasons.map { reason -> exportNoticeReasonPhrase(reason).resource }
		assertEquals(sampleReasons.size, resources.toSet().size, "two reasons share one sentence")

		val categoryResources =
			ExportEntityCategory.entries.map { category -> exportEntityCategoryLabelRes(category) }
		assertEquals(ExportEntityCategory.entries.size, categoryResources.toSet().size)
	}

	@Test
	fun everyPhraseSuppliesExactlyThePlaceholdersItsTemplateHas() {
		val placeholderCounts = placeholderCountsByKey()
		for (reason in sampleReasons) {
			val phrase = exportNoticeReasonPhrase(reason)
			val key = phrase.resource.key
			assertEquals(
				placeholderCounts[key],
				phrase.arguments.size,
				"$key: template placeholders disagree with the arguments ${reason::class.simpleName} supplies",
			)
		}
	}

	/**
	 * The distinct `%N$` placeholder count of every string in the default catalog.
	 *
	 * Read from the source XML rather than the packed resource because the test needs the template
	 * text, which the runtime accessor only exposes through a Compose environment.
	 *
	 * @return Map<String, Int> The placeholder count per resource key.
	 */
	private fun placeholderCountsByKey(): Map<String, Int> {
		val catalog = File("src/commonMain/composeResources/values/strings.xml")
		assertTrue(catalog.isFile, "the default string catalog is missing at ${catalog.absolutePath}")
		val text = catalog.readText()
		val counts = HashMap<String, Int>()
		val stringPattern = Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
		val placeholderPattern = Regex("""%(\d+)\$""")
		for (match in stringPattern.findAll(text)) {
			val key = match.groupValues[1]
			val body = match.groupValues[2]
			counts[key] = placeholderPattern.findAll(body).map { it.groupValues[1] }.toSet().size
		}
		return counts
	}
}