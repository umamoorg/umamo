package org.umamo.format.cmo3

import org.umamo.format.cmo3.model.gen.ACLayerEntry
import org.umamo.format.cmo3.model.gen.ACParameterControllableSource
import org.umamo.format.cmo3.model.gen.CPartSource
import org.umamo.format.cmo3.serialize.annotations.DontSerializeIfDefault
import kotlin.reflect.KClass
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.findAnnotation
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards the four generated fields that must ALWAYS serialize.
 *
 * `@DontSerializeIfDefault` means "the corpus does not always carry this field", which is a fact
 * about the SAMPLES.  For these four the official editor's custom deserializers dereference the
 * field unconditionally and NPE at load when it is absent - the corpus merely includes older-era
 * samples that predate them.  Marking any of these would emit a file Cubism cannot open, and the
 * failure is a load-time NPE in the editor rather than anything a round-trip gate here would see.
 *
 * The rule that produces them lives in ModelGenerator.alwaysSerializedFields so a regeneration
 * reproduces it; this test is the backstop that catches the annotation coming back by ANY route -
 * a regeneration against a changed generator, a merge, or a hand-edit of the generated file.
 */
class AlwaysSerializedFieldsTest {
	/** The generated properties the editor reads unconditionally, as (owner, property name). */
	private val alwaysSerialized: List<Pair<KClass<*>, String>> =
		listOf(
			ACLayerEntry::class to "isTransparencyShapesLayer",
			ACParameterControllableSource::class to "internalColor_direct_argb",
			CPartSource::class to "useOffscreen",
			CPartSource::class to "invertClippingMask",
		)

	@Test
	fun theEditorsMandatoryFieldsAreNeverConditionallySerialized() {
		val violations = ArrayList<String>()
		for ((owner, propertyName) in alwaysSerialized) {
			val property =
				owner.declaredMemberProperties.firstOrNull { candidate -> candidate.name == propertyName }
			if (property == null) {
				violations.add("${owner.simpleName}.$propertyName no longer exists")
				continue
			}
			if (property.findAnnotation<DontSerializeIfDefault>() != null) {
				violations.add(
					"${owner.simpleName}.$propertyName carries @DontSerializeIfDefault - the official editor " +
						"NPEs when the field is absent (docs/format/CMO3.md, Created-Entity Conventions)",
				)
			}
		}
		// The class-level annotation would suppress every property just as effectively.
		for (owner in alwaysSerialized.map { (ownerClass, _) -> ownerClass }.distinct()) {
			if (owner.findAnnotation<DontSerializeIfDefault>() != null) {
				violations.add("${owner.simpleName} carries a CLASS-level @DontSerializeIfDefault")
			}
		}
		assertTrue(violations.isEmpty(), "mandatory fields became conditional:\n" + violations.joinToString("\n"))
	}
}
