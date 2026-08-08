package org.umamo.format.cmo3

import java.io.File
import kotlin.test.Test

/**
 * Debugging aid: re-saves the -Dcmo3.resave files through Cmo3.read -> Cmo3.write and dumps the
 * results to build/resave-<name>.cmo3 for byte-level comparison against the originals and for
 * manual official-editor acceptance runs.  The property is explicit-only (no corpus default) so a
 * plain test run does not dump the whole corpus into build/.
 */
class Cmo3ResaveDumpTest {
	@Test
	fun dumpResaves() {
		val spec =
			System.getProperty("cmo3.resave")
				?: run {
					println("cmo3.resave not present; skipping resave dump")
					return
				}
		for (file in spec.split(',').map { File(it.trim()) }.filter { it.isFile }) {
			val resaved = Cmo3.write(Cmo3.read(file.readBytes()))
			val target = File("build/resave-${file.name}")
			target.parentFile.mkdirs()
			target.writeBytes(resaved)
			println("wrote ${target.absolutePath} (${resaved.size} bytes, original ${file.length()})")
		}
	}
}