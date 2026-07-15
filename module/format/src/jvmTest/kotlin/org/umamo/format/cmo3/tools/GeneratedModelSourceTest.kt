package org.umamo.format.cmo3.tools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the merge machinery ModelGenerator relies on to be additive.
 *
 * Worth its own test because a parser that quietly under-reports is the exact failure that makes a
 * "merge" delete things: it cannot see what it is about to drop.  An earlier brace-matching parser did
 * precisely that -- a brace-less `class X : Y()` swallowed the declarations after it, and eleven
 * present classes read as missing.  These assertions run against the real generated file, so they
 * fail if the emitted shape ever drifts away from what the parser understands.
 */
class GeneratedModelSourceTest {
	private val generatedModel =
		File("src/commonMain/kotlin/org/umamo/format/cmo3/model/gen/GeneratedModel.kt")

	@Test
	fun parsesEveryDeclarationInTheRealGeneratedModel() {
		if (!generatedModel.isFile) {
			println("GeneratedModel.kt not found at ${generatedModel.absolutePath}; skipping")
			return
		}
		val model = parseGeneratedModel(generatedModel)
		// Cross-check against a source the parser cannot influence: the raw @SerialTag count.
		val declaredTags = generatedModel.readLines().count { it.startsWith("@SerialTag(") }
		assertEquals(
			declaredTags,
			model.enums.size + model.classes.size,
			"every @SerialTag must parse into exactly one enum or class -- a shortfall means the parser " +
				"is silently dropping declarations, which would make a merge delete them",
		)
		assertTrue(model.enums.isNotEmpty(), "enums must parse")
		assertTrue(model.classes.size > 100, "classes must parse; got ${model.classes.size}")
	}

	@Test
	fun parsesTheBracelessEmptyClassForm() {
		if (!generatedModel.isFile) {
			return
		}
		val model = parseGeneratedModel(generatedModel)
		// `public class CBlend_Normal : ACBlend()` has no body -- the shape that broke the old parser.
		val bodyless = model.classes.values.filter { it.isBodyless }
		assertTrue(bodyless.isNotEmpty(), "the brace-less form must be recognized, not skipped")
		assertTrue(bodyless.all { it.fields.isEmpty() }, "a brace-less class has no fields to collect")
	}

	@Test
	fun capturesFieldsInDeclarationOrderWithTheirAnnotations() {
		if (!generatedModel.isFile) {
			return
		}
		val model = parseGeneratedModel(generatedModel)
		val withFields = model.classes.values.filter { it.fields.isNotEmpty() }
		assertTrue(withFields.isNotEmpty(), "classes with fields must parse")
		// Every field's captured lines must actually contain its own declaration.
		for (modelClass in withFields) {
			for (field in modelClass.fields) {
				assertTrue(
					field.lines.any { it.contains("var `") },
					"${modelClass.tag}.${field.serialName} captured no property line",
				)
			}
		}
		assertTrue(
			model.classes.values.any { modelClass -> modelClass.fields.any { it.dontSerializeIfDefault } },
			"@DontSerializeIfDefault must be carried through, or a merge would drop it",
		)
	}

	@Test
	fun mergeKeepsEveryFieldFromBothOrders() {
		val existing = listOf("alpha", "bravo", "charlie")
		val inferred = listOf("alpha", "delta", "charlie")
		val merged = mergeFieldOrder(existing, inferred)
		assertEquals(listOf("alpha", "bravo", "delta", "charlie"), merged, "the union, with both orders intact")
	}

	@Test
	fun mergeInsertsANewFieldInPositionRatherThanAppending() {
		// The load-bearing case: the serializer emits fields in declaration order, so a new field
		// appended to the end would move it in the XML and break byte-identity.
		val existing = listOf("alpha", "charlie")
		val inferred = listOf("alpha", "bravo", "charlie")
		assertEquals(listOf("alpha", "bravo", "charlie"), mergeFieldOrder(existing, inferred))
	}

	@Test
	fun mergeIsIdentityWhenNothingIsNew() {
		val order = listOf("alpha", "bravo", "charlie")
		assertEquals(order, mergeFieldOrder(order, order), "a re-run with no new samples must not churn the file")
		assertEquals(order, mergeFieldOrder(order, emptyList()), "a class no sample exercises keeps its order")
		assertEquals(order, mergeFieldOrder(emptyList(), order), "a brand-new class takes the inferred order")
	}

	@Test
	fun mergeNeverDropsAFieldEvenWhenTheOrdersDisagree() {
		// Contradictory orders cannot both be honoured; what matters is that nothing vanishes.
		val existing = listOf("alpha", "bravo", "charlie")
		val inferred = listOf("charlie", "bravo", "alpha")
		val merged = mergeFieldOrder(existing, inferred)
		assertEquals(setOf("alpha", "bravo", "charlie"), merged.toSet(), "no field may be lost")
		assertEquals(merged.size, merged.distinct().size, "no field may be duplicated")
	}
}
