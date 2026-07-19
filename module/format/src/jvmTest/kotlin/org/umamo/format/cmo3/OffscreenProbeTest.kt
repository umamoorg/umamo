package org.umamo.format.cmo3

import org.umamo.format.cmo3.caff.CaffArchive
import org.umamo.format.cmo3.caff.CaffCodec
import org.umamo.format.cmo3.model.custom.CFloatColor
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CDrawableSourceSet
import org.umamo.format.cmo3.model.gen.CParameterSource
import org.umamo.format.cmo3.model.gen.CParameterSourceSet
import org.umamo.format.cmo3.model.gen.CPartForm
import org.umamo.format.cmo3.model.gen.CPartSource
import org.umamo.format.cmo3.model.gen.CPartSourceSet
import org.umamo.format.cmo3.model.gen.KeyOnParameter
import org.umamo.format.cmo3.model.gen.KeyformBindingSource
import org.umamo.format.cmo3.model.gen.KeyformGridAccessKey
import org.umamo.format.cmo3.model.gen.KeyformGridSource
import org.umamo.format.cmo3.model.gen.KeyformOnGrid
import org.umamo.format.cmo3.model.identity.Guid
import org.umamo.format.cmo3.model.identity.Id
import org.umamo.format.cmo3.model.type.CColor
import java.io.File
import kotlin.test.Test

/**
 * Print-only probe for the CMO3 offscreen-drawing representation (Cubism 5.3 part feature): which
 * parts carry useOffscreen, their color/alpha composition enums, clipping lists, and keyformed
 * opacity/color payloads (CPartForm), plus the raw main.xml spellings.  Feeds the offscreen support
 * scratchpad (docs/plan/offscreen-support.md); pins nothing.  Skips gracefully without samples.
 */
class OffscreenProbeTest {
	private fun samples(): List<File> =
		System.getProperty("cmo3.probe")?.split(',')?.map(::File)?.filter { it.isFile } ?: emptyList()

	/**
	 * Flattens a CMO3 collection field to a plain list (mirrors Cmo3Import.elementsOf).
	 *
	 * @param Any? collection The raw collection field, held as Any?.
	 * @return List<Any?> The contained elements.
	 */
	private fun elementsOf(collection: Any?): List<Any?> =
		when (collection) {
			is Map<*, *> -> collection.values.toList()
			is Iterable<*> -> collection.toList()
			is Array<*> -> collection.toList()
			else -> emptyList()
		}

	private fun uuidOf(value: Any?): String? = (value as? Guid)?.uuid?.takeIf { it.isNotEmpty() }

	private fun idOf(value: Any?): String? = (value as? Id)?.idstr

	private fun colorHex(value: Any?): String =
		when (value) {
			is CColor -> "#%08X".format(value.argb)
			is CFloatColor -> "rgba(${value.red}, ${value.green}, ${value.blue}, ${value.alpha})"
			null -> "null"
			else -> value.toString()
		}

	@Test
	fun probeOffscreenParts() {
		val files = samples()
		if (files.isEmpty()) {
			println("cmo3.probe not present; skipping offscreen probe")
			return
		}
		for (file in files) {
			val root = Cmo3.read(file).root as? CModelSource ?: continue
			val partSources =
				elementsOf((root.partSourceSet as? CPartSourceSet)?._sources).filterIsInstance<CPartSource>()
			val drawableSources =
				elementsOf((root.drawableSourceSet as? CDrawableSourceSet)?._sources)
			val parameterSources =
				elementsOf((root.parameterSourceSet as? CParameterSourceSet)?._sources).filterIsInstance<CParameterSource>()
			val parameterNameByUuid =
				parameterSources.associateBy(keySelector = { uuidOf(it.guid) }, valueTransform = { idOf(it.id) ?: it.name ?: "?" })
			// Both parts and drawables are legal clip targets; index them all by guid for resolution.
			val nameByUuid = HashMap<String?, String>()
			for (part in partSources) {
				nameByUuid[uuidOf(part.guid)] = "part:" + (part.localName ?: idOf(part.id) ?: "?")
			}
			for (drawableSource in drawableSources) {
				val mesh = drawableSource as? CArtMeshSource ?: continue
				nameByUuid[uuidOf(mesh.guid)] = "mesh:" + (mesh.localName ?: idOf(mesh.id) ?: "?")
			}

			val offscreenParts = partSources.filter { it.useOffscreen }
			println("=== ${file.name}: parts=${partSources.size} offscreen=${offscreenParts.size} ===")

			for (part in offscreenParts) {
				println("  part '${part.localName}' id=${idOf(part.id)}")
				println("    enableDrawOrderGroup=${part.enableDrawOrderGroup} isSketch=${part.isSketch}")
				println("    colorComposition=${part.colorComposition} alphaComposition=${part.alphaComposition}")
				val clipNames = elementsOf(part.clipGuidList).map { nameByUuid[uuidOf(it)] ?: "unresolved" }
				println("    invertClippingMask=${part.invertClippingMask} clip=$clipNames")
				// The part's keyform grid: axes + each CPartForm cell's payload (the keyformed channels).
				val grid = part.keyformGridSource as? KeyformGridSource
				val bindings = elementsOf(grid?.keyformBindings).filterIsInstance<KeyformBindingSource>()
				val axisSummary =
					bindings.joinToString { binding ->
						val keys = elementsOf(binding.keys).map { (it as? Number)?.toFloat() }
						"${parameterNameByUuid[uuidOf(binding.parameterGuid)]}=$keys"
					}
				println("    axes: [$axisSummary]")
				val axisOfBinding = bindings.withIndex().associate { (index, binding) -> binding to index }
				val formByUuid = elementsOf(part.keyforms).associateBy { form -> uuidOf((form as? CPartForm)?.guid) }
				for (cell in elementsOf(grid?.keyformsOnGrid).filterIsInstance<KeyformOnGrid>()) {
					val form = formByUuid[uuidOf(cell.keyformGuid)] as? CPartForm ?: continue
					val coordinate = IntArray(bindings.size)
					val keyList = (cell.accessKey as? KeyformGridAccessKey)?._keyOnParameterList
					for (keyOnParameter in elementsOf(keyList).filterIsInstance<KeyOnParameter>()) {
						val axisIndex = axisOfBinding[keyOnParameter.binding] ?: continue
						coordinate[axisIndex] = keyOnParameter.keyIndex
					}
					println(
						"    cell ${coordinate.toList()}: drawOrder=${form.drawOrder} opacity=${form.opacity}" +
							" multiply=${colorHex(form.multiplyColor)} screen=${colorHex(form.screenColor)}",
					)
				}
			}

			// Enum tallies: every composition value observed, split by owner class, plus the same fields
			// on NON-offscreen parts (do the fields ever appear when the checkbox is off?).
			val partColor = partSources.groupingBy { "${it.useOffscreen}/${it.colorComposition}" }.eachCount()
			val partAlpha = partSources.groupingBy { "${it.useOffscreen}/${it.alphaComposition}" }.eachCount()
			println("  part colorComposition (useOffscreen/value -> count): $partColor")
			println("  part alphaComposition (useOffscreen/value -> count): $partAlpha")
			val meshes = drawableSources.filterIsInstance<CArtMeshSource>()
			println("  mesh colorComposition values: ${meshes.groupingBy { it.colorComposition?.toString() }.eachCount()}")
			println("  mesh alphaComposition values: ${meshes.groupingBy { it.alphaComposition?.toString() }.eachCount()}")
			// Per-mesh detail wherever a drawable authors a non-default blend or culling - the
			// drawable-level extended-blend / culling extraction evidence.
			for (mesh in meshes) {
				val colorValue = mesh.colorComposition?.toString()
				val alphaValue = mesh.alphaComposition?.toString()
				val nonDefault =
					mesh.culling || (colorValue != null && colorValue != "NORMAL") || (alphaValue != null && alphaValue != "OVER")
				if (nonDefault) {
					println(
						"  mesh '${mesh.localName}' id=${idOf(mesh.id)} color=$colorValue alpha=$alphaValue" +
							" culling=${mesh.culling} clip=${elementsOf(mesh.clipGuidList).map { nameByUuid[uuidOf(it)] ?: "unresolved" }}",
					)
				}
			}

			// Raw main.xml spellings for the offscreen-related fields (exact serialization shape).
			val mainXml = CaffCodec.read(file.readBytes()).firstByTag(CaffArchive.TAG_MAIN_XML)?.content ?: continue
			val keywords =
				listOf(
					"useOffscreen",
					"clipGuidList",
					"invertClippingMask",
					"colorComposition",
					"alphaComposition",
					"multiplyColor",
					"screenColor",
				)
			val seenFragments = LinkedHashSet<String>()
			for (line in String(mainXml, Charsets.UTF_8).lineSequence()) {
				if (keywords.any { keyword -> keyword in line }) {
					seenFragments.add(line.trim())
					if (seenFragments.size >= 24) {
						break
					}
				}
			}
			seenFragments.forEach { fragment -> println("  xml: $fragment") }
		}
	}
}
