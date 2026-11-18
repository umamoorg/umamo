package org.umamo.interop.cmo3

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.Cmo3Model
import org.umamo.format.cmo3.caff.CaffArchive
import org.umamo.format.cmo3.caff.CaffCodec
import org.umamo.format.cmo3.caff.CaffEntry
import org.umamo.format.cmo3.edit
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CPartSource
import org.umamo.format.cmo3.model.gen.KeyFormMorphTargetSet
import org.umamo.format.cmo3.model.gen.KeyformGridSource
import org.umamo.format.cmo3.type.CArrayList
import org.umamo.interop.cmo3.export.Cmo3FreshFile
import org.umamo.interop.cmo3.export.Cmo3SkeletonBuilder
import org.umamo.interop.cmo3.export.GraphListKind
import org.umamo.interop.cmo3.export.appendToGraphList
import org.umamo.interop.cmo3.export.freshGuid
import org.umamo.interop.cmo3.export.graphListFor
import org.umamo.interop.cmo3.export.writeGraphList
import org.umamo.interop.cmo3TargetVersionNo
import org.umamo.runtime.model.RuntimeTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pins the one way an export writes a list field of a read graph.  A list the document carries is
 * changed in place; a list the document omitted is created as the class the call asks for, which is
 * the tag it is written with, and lands in the written document where the call anchors it.
 *
 * The documents are a blank skeleton read back, with one list element cut out of its main.xml first,
 * so the owner is a read object with no slot recorded for that field.
 */
class Cmo3GraphListWritesTest {
	private val bindingsElement = Regex("""<array_list xs\.n="keyformBindings"[^>]*/>""")
	private val morphTargetsElement = Regex("""<carray_list xs\.n="_morphTargets"[^>]*/>""")
	private val childGuidsElement = Regex("""<carray_list xs\.n="_childGuids"[^>]*/>""")

	/** A blank document read back, with the first main.xml element matching [omitted] cut out. */
	private fun blankWithout(omitted: Regex?): Cmo3Model {
		val skeleton = Cmo3SkeletonBuilder.buildBlank("Untitled Model", 64, 64, RuntimeTarget.Cubism53.cmo3TargetVersionNo())
		val bytes =
			Cmo3FreshFile.assemble(
				skeleton.root,
				skeleton.iconEntries,
				obfuscateKey = 0x49C74776.toInt(),
			)
		val archive = CaffCodec.read(bytes)

		if (omitted == null) {
			return Cmo3.read(archive)
		}

		val entries =
			archive.entries.map { entry ->
				if (entry.tag != CaffArchive.TAG_MAIN_XML) {
					return@map entry
				}

				val xml = entry.content.decodeToString()
				assertTrue(omitted.containsMatchIn(xml), "the blank's main.xml carries the element this test cuts out")

				CaffEntry(entry.path, entry.tag, omitted.replaceFirst(xml, "").encodeToByteArray(), entry.compression, entry.obfuscated)
			}

		return Cmo3.read(archive.withEntries(entries))
	}

	private fun rootPartOf(model: Cmo3Model): CPartSource = (model.root as CModelSource).rootPart as CPartSource

	private fun mainXmlOf(model: Cmo3Model): String =
		CaffCodec.read(Cmo3.write(model)).firstByTag(CaffArchive.TAG_MAIN_XML)!!.content.decodeToString()

	@Test
	fun aListTheDocumentCarriesIsRewrittenInPlace() {
		val model = blankWithout(null)
		val gridSource = rootPartOf(model).keyformGridSource as KeyformGridSource
		val carried = mutableGraphListOf(gridSource.keyformsOnGrid) ?: fail("the blank's root part carries its grid cells")
		assertEquals(1, carried.size, "the blank's root part has its one default cell")

		// The kind is the class of a list that has to be created, so it is not consulted here.
		model.edit().writeGraphList(gridSource, "KeyformGridSource", "keyformsOnGrid", null, gridSource.keyformsOnGrid, listOf("first", "second"), GraphListKind.CArrayList) {
			fail("a carried list is never replaced")
		}

		assertSame(carried, gridSource.keyformsOnGrid, "the field still holds the list that was read")
		assertEquals(listOf<Any?>("first", "second"), carried.toList(), "its elements were replaced")
		assertFalse(carried is CArrayList<*>, "and it is still the class it was read as")
	}

	@Test
	fun anOmittedListIsCreatedAsTheKindAsked() {
		for (kind in GraphListKind.entries) {
			val model = blankWithout(bindingsElement)
			val gridSource = rootPartOf(model).keyformGridSource as KeyformGridSource
			assertNull(gridSource.keyformBindings, "$kind: the cut field reads as absent")

			model.edit().writeGraphList(gridSource, "KeyformGridSource", "keyformBindings", null, gridSource.keyformBindings, emptyList(), kind) { created ->
				gridSource.keyformBindings = created
			}

			val xml = mainXmlOf(model)
			val writtenAsArrayList = xml.contains("""<array_list xs.n="keyformBindings"""")
			val writtenAsCArrayList = xml.contains("""<carray_list xs.n="keyformBindings"""")
			assertEquals(kind == GraphListKind.CArrayList, gridSource.keyformBindings is CArrayList<*>, "$kind: the created list's class")
			assertEquals(kind == GraphListKind.ArrayList, writtenAsArrayList, "$kind: written as array_list")
			assertEquals(kind == GraphListKind.CArrayList, writtenAsCArrayList, "$kind: written as carray_list")
		}
	}

	@Test
	fun aCreatedListLandsBeforeTheSiblingItIsAnchoredTo() {
		for (anchor in listOf("blendWeightConstraintSet", null)) {
			val model = blankWithout(morphTargetsElement)
			val targetSet = rootPartOf(model).keyformMorphTargetSet as KeyFormMorphTargetSet
			assertNull(targetSet._morphTargets, "the cut field reads as absent")

			model.edit().writeGraphList(targetSet, "KeyFormMorphTargetSet", "_morphTargets", anchor, targetSet._morphTargets, emptyList(), GraphListKind.CArrayList) { created ->
				targetSet._morphTargets = created
			}

			val xml = mainXmlOf(model)
			val listAt = xml.indexOf("""xs.n="_morphTargets"""")
			val siblingAt = xml.indexOf("""xs.n="blendWeightConstraintSet"""")
			assertTrue(listAt >= 0 && siblingAt >= 0, "anchor $anchor: both elements are written")
			assertEquals(anchor != null, listAt < siblingAt, "anchor $anchor: the list precedes its sibling only when anchored to it")
		}
	}

	@Test
	fun appendingCreatesAnOmittedListAndExtendsACarriedOne() {
		val model = blankWithout(childGuidsElement)
		val rootPart = rootPartOf(model)
		assertNull(rootPart._childGuids, "the cut field reads as absent")
		val editor = model.edit()
		val firstGuid = freshGuid("CDrawableGuid")
		val secondGuid = freshGuid("CDrawableGuid")

		editor.appendToGraphList(rootPart, "CPartSource", "_childGuids", rootPart._childGuids, firstGuid, GraphListKind.CArrayList) { created ->
			rootPart._childGuids = created
		}

		val created = rootPart._childGuids
		assertTrue(created is CArrayList<*>, "the created list is the kind asked")
		assertEquals(listOf<Any?>(firstGuid), mutableGraphListOf(created)?.toList(), "it holds the appended element")

		editor.appendToGraphList(rootPart, "CPartSource", "_childGuids", rootPart._childGuids, secondGuid, GraphListKind.CArrayList) {
			fail("a carried list is never replaced")
		}

		assertSame(created, rootPart._childGuids, "the second append extends the same list")
		assertEquals(listOf<Any?>(firstGuid, secondGuid), mutableGraphListOf(created)?.toList(), "in append order")
		assertTrue(mainXmlOf(model).contains("""<carray_list xs.n="_childGuids" count="2">"""), "and the list is written in its slot")
	}

	@Test
	fun aListIsFetchedAsCarriedOrCreatedEmpty() {
		val carriedModel = blankWithout(null)
		val carriedSet = rootPartOf(carriedModel).keyformMorphTargetSet as KeyFormMorphTargetSet
		val carried = mutableGraphListOf(carriedSet._morphTargets) ?: fail("the blank's root part carries its morph targets")
		val fetched =
			carriedModel.edit().graphListFor(carriedSet, "KeyFormMorphTargetSet", "_morphTargets", carriedSet._morphTargets, GraphListKind.CArrayList) {
				fail("a carried list is never replaced")
			}
		assertSame(carried, fetched, "a carried list is handed back as it is")

		val omittedModel = blankWithout(morphTargetsElement)
		val omittedSet = rootPartOf(omittedModel).keyformMorphTargetSet as KeyFormMorphTargetSet
		val created =
			omittedModel.edit().graphListFor(omittedSet, "KeyFormMorphTargetSet", "_morphTargets", omittedSet._morphTargets, GraphListKind.CArrayList) { list ->
				omittedSet._morphTargets = list
			}
		assertTrue(created.isEmpty(), "an omitted list is created empty")
		assertSame(created, omittedSet._morphTargets, "and stored on its owner")
		assertTrue(mainXmlOf(omittedModel).contains("""<carray_list xs.n="_morphTargets""""), "with its slot, so it is written")
	}
}