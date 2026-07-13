package org.umamo.format.cmo3.serialize

import org.umamo.format.cmo3.caff.CaffArchive
import org.umamo.format.cmo3.caff.CaffCodec
import org.umamo.format.cmo3.xml.XmlCodec
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Documents and guards model coverage: with the full cubismEngine, exactly these tags remain
 * verbatim (any tag without a registered typed serializer). Growing this set is a regression.
 */
class CoverageGateTest {
	private val sample: File? = System.getProperty("cmo3.sample")?.let(::File)?.takeIf { it.isFile }

	private val knownVerbatim = sortedSetOf<String>() // every tag in the sample is now typed

	@Test
	fun onlyKnownCustomTagsRemainVerbatim() {
		val file =
			sample ?: run {
				println("cmo3.sample not present; skipping coverage gate")
				return
			}
		val mainXml = CaffCodec.read(file.readBytes()).firstByTag(CaffArchive.TAG_MAIN_XML)!!.content
		val unmodeled = sortedSetOf<String>()
		cubismEngine { tag, _ -> unmodeled += tag }.readModel(XmlCodec.parse(mainXml))
		assertEquals(knownVerbatim, unmodeled, "unmodeled tag set changed")
	}
}
