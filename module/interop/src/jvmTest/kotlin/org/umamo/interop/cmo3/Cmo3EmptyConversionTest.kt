package org.umamo.interop.cmo3

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RuntimeTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A puppet with nothing in it converts to a CMO3 that writes and reads back: the installed app's self-check runs
 * exactly this to prove the bundled runtime carries the XML stack and the reflective serializer, so the case it
 * exercises is pinned here rather than first met on a rigger's machine.
 */
class Cmo3EmptyConversionTest {
	@Test
	fun anEmptyPuppetConvertsWritesAndReadsBack() {
		val puppet =
			PuppetModel(
				parameters = emptyList(),
				parts = emptyList(),
				deformers = emptyList(),
				drawables = emptyList(),
				rootChildren = emptyList(),
				rootPartId = null,
				canvasWidth = 64f,
				canvasHeight = 64f,
				worldOriginX = 32f,
				worldOriginZ = -32f,
				runtimeTarget = RuntimeTarget.Cubism53,
			)

		val converted = Cmo3Conversion.freshCmo3(puppet, emptyList(), emptyMap(), "empty", nowMillis = 0L, obfuscateKey = 0)
		val reread = Cmo3.read(Cmo3.write(converted.model))
		val reimported = Cmo3Import.fromModelSource(assertIs<CModelSource>(reread.root))

		assertTrue(reimported.drawables.isEmpty(), "no drawables appear")
		assertTrue(reimported.parameters.isEmpty(), "no parameters appear")
		assertEquals(64f, reimported.canvasWidth, "the canvas survives")
		assertEquals(64f, reimported.canvasHeight, "the canvas survives")
	}
}