package org.umamo.format

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CFloatColor
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.ACDeformerForm
import org.umamo.format.cmo3.model.gen.ACDrawableForm
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CDeformerSourceSet
import org.umamo.format.cmo3.model.gen.CDrawableSourceSet
import org.umamo.format.cmo3.model.gen.CRotationDeformerSource
import org.umamo.format.cmo3.model.gen.CWarpDeformerSource
import org.umamo.format.cmo3.model.identity.Id
import org.umamo.format.cmo3.model.type.CColor
import org.umamo.format.moc3.Moc3
import org.umamo.format.moc3.model.Rgb
import org.umamo.format.moc3.model.RotationDeformer
import org.umamo.format.moc3.model.WarpDeformer
import java.io.File
import kotlin.test.Test

/**
 * Print-only probe answering one question across the whole corpus: does any real model author a
 * non-default opacity, multiply color, or screen color on a WARP or ROTATION DEFORMER keyform?
 *
 * Both format layers already decode those channels - CMO3 `ACDeformerForm.opacity`/`multiplyColor`/
 * `screenColor` (inherited by `CWarpDeformerForm` and `CRotationDeformerForm`), and MOC3
 * `WarpKeyform`/`RotationKeyform`.  The runtime ingest is what drops them: `WarpForm` holds only
 * control points and `RotationForm` only the pivot transform, so a deformer-level opacity is
 * silently ignored at render time.  This probe measures how much that costs on real art before the
 * fix is scoped.
 *
 * Prints the drawable-side channels alongside as the control group - those ARE ingested
 * (`MeshForm.opacity`/`multiplyColor`/`screenColor`), so a model showing deformer channels in the
 * same range as its drawable channels is direct evidence the gap matters.
 *
 * Pins nothing; skips gracefully without a corpus.  Feeds TODO § Puppet Model, CMO3, MOC3.
 */
class DeformerChannelProbeTest {
	/** Cubism's identity values: fully opaque, white multiply, black screen. */
	private val opaque = 1f
	private val multiplyIdentity = Rgb(1f, 1f, 1f)
	private val screenIdentity = Rgb(0f, 0f, 0f)

	private fun cmo3Samples(): List<File> =
		System.getProperty("cmo3.probe")?.split(',')?.map(::File)?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()

	private fun moc3Samples(): List<File> =
		System.getProperty("moc3.samples")
			?.let(::File)
			?.takeIf { it.isDirectory }
			?.walkTopDown()
			// work/ holds OUR round-trip output (baked-*/docbaked-*), not authored art - scanning it
			// would double-count every source model under a different name.
			?.filter { it.isFile && it.extension.equals("moc3", ignoreCase = true) && !it.path.contains("/work/") }
			?.sortedBy { it.name }
			?.toList()
			?: emptyList()

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

	private fun idOf(value: Any?): String? = (value as? Id)?.idstr

	/**
	 * Reads a CMO3 color field as float RGB, or null when absent (the @DontSerializeIfDefault identity).
	 *
	 * @param Any? value The raw multiplyColor / screenColor field.
	 * @return Rgb? The channels, or null when the field was omitted.
	 */
	private fun rgbOf(value: Any?): Rgb? =
		when (value) {
			is CFloatColor -> Rgb(value.red, value.green, value.blue)
			// The older argb integer form; unobserved on 5.3 forms but decoded for completeness.
			is CColor ->
				Rgb(
					((value.argb shr 16) and 0xFF) / 255f,
					((value.argb shr 8) and 0xFF) / 255f,
					(value.argb and 0xFF) / 255f,
				)

			else -> null
		}

	/** Running tally of how many keyforms deviate from identity on each channel. */
	private class ChannelTally {
		var keyformCount = 0
		var nonOpaque = 0
		var nonIdentityMultiply = 0
		var nonIdentityScreen = 0
		var minimumOpacity = Float.MAX_VALUE

		val hasAnything: Boolean get() = nonOpaque > 0 || nonIdentityMultiply > 0 || nonIdentityScreen > 0

		override fun toString(): String =
			"keyforms=$keyformCount nonOpaque=$nonOpaque" +
				(if (nonOpaque > 0) " (min ${"%.3f".format(minimumOpacity)})" else "") +
				" multiply!=identity=$nonIdentityMultiply screen!=identity=$nonIdentityScreen"
	}

	/**
	 * Folds one keyform's three channels into [tally].
	 *
	 * @param ChannelTally tally         The running tally.
	 * @param Float        opacity       The keyform's opacity.
	 * @param Rgb?         multiplyColor The keyform's multiply color, or null when absent.
	 * @param Rgb?         screenColor   The keyform's screen color, or null when absent.
	 */
	private fun record(tally: ChannelTally, opacity: Float, multiplyColor: Rgb?, screenColor: Rgb?) {
		tally.keyformCount++
		if (opacity != opaque) {
			tally.nonOpaque++
			tally.minimumOpacity = minOf(tally.minimumOpacity, opacity)
		}
		if (multiplyColor != null && multiplyColor != multiplyIdentity) {
			tally.nonIdentityMultiply++
		}
		if (screenColor != null && screenColor != screenIdentity) {
			tally.nonIdentityScreen++
		}
	}

	@Test
	fun probeCmo3DeformerChannels() {
		val files = cmo3Samples()
		if (files.isEmpty()) {
			println("cmo3.probe not present; skipping CMO3 deformer-channel probe")
			return
		}
		println("=== CMO3 deformer channels (opacity / multiplyColor / screenColor on ACDeformerForm) ===")
		val corpusWarp = ChannelTally()
		val corpusRotation = ChannelTally()
		val corpusDrawable = ChannelTally()
		for (file in files) {
			val root = Cmo3.read(file).root as? CModelSource ?: continue
			val deformerSources = elementsOf((root.deformerSourceSet as? CDeformerSourceSet)?._sources)
			val warp = ChannelTally()
			val rotation = ChannelTally()
			val drawable = ChannelTally()
			// Per-deformer detail, collected so a hit can be named rather than just counted.
			val offenders = mutableListOf<String>()

			for (source in deformerSources) {
				val isWarp = source is CWarpDeformerSource
				if (!isWarp && source !is CRotationDeformerSource) {
					continue
				}
				val tally = if (isWarp) warp else rotation
				val before = tally.nonOpaque + tally.nonIdentityMultiply + tally.nonIdentityScreen
				val forms =
					when (source) {
						is CWarpDeformerSource -> elementsOf(source.keyforms)
						is CRotationDeformerSource -> elementsOf(source.keyforms)
						else -> emptyList()
					}
				for (form in forms.filterIsInstance<ACDeformerForm>()) {
					record(tally, form.opacity, rgbOf(form.multiplyColor), rgbOf(form.screenColor))
					record(if (isWarp) corpusWarp else corpusRotation, form.opacity, rgbOf(form.multiplyColor), rgbOf(form.screenColor))
				}
				val after = tally.nonOpaque + tally.nonIdentityMultiply + tally.nonIdentityScreen
				if (after > before) {
					val name =
						when (source) {
							is CWarpDeformerSource -> source.localName ?: idOf(source.id)
							is CRotationDeformerSource -> source.localName ?: idOf(source.id)
							else -> null
						}
					offenders += "${if (isWarp) "warp" else "rotation"} '$name'"
				}
			}

			// Art meshes are the only drawable kind carrying keyforms; ACDrawableSource itself has no
			// `keyforms` field, so match the concrete type rather than reaching for it reflectively.
			for (source in elementsOf((root.drawableSourceSet as? CDrawableSourceSet)?._sources).filterIsInstance<CArtMeshSource>()) {
				for (form in elementsOf(source.keyforms).filterIsInstance<ACDrawableForm>()) {
					record(drawable, form.opacity, rgbOf(form.multiplyColor), rgbOf(form.screenColor))
					record(corpusDrawable, form.opacity, rgbOf(form.multiplyColor), rgbOf(form.screenColor))
				}
			}

			val flag = if (warp.hasAnything || rotation.hasAnything) "  <<< DEFORMER CHANNELS IN USE" else ""
			println("--- ${file.name}$flag")
			println("    warp     : $warp")
			println("    rotation : $rotation")
			println("    drawable : $drawable   (control group - already ingested)")
			if (offenders.isNotEmpty()) {
				println("    deformers with non-identity channels: ${offenders.take(12)}${if (offenders.size > 12) " …+${offenders.size - 12}" else ""}")
			}
		}
		println("=== CMO3 corpus totals ===")
		println("    warp     : $corpusWarp")
		println("    rotation : $corpusRotation")
		println("    drawable : $corpusDrawable")
	}

	@Test
	fun probeMoc3DeformerChannels() {
		val files = moc3Samples()
		if (files.isEmpty()) {
			println("moc3.samples not present; skipping MOC3 deformer-channel probe")
			return
		}
		println("=== MOC3 deformer channels (WarpKeyform / RotationKeyform opacity + colors) ===")
		val corpusWarp = ChannelTally()
		val corpusRotation = ChannelTally()
		val corpusArtMesh = ChannelTally()
		for (file in files) {
			val decoded =
				runCatching { Moc3.decode(file.readBytes()) }.getOrElse { failure ->
					println("--- ${file.name}: decode failed (${failure::class.simpleName}: ${failure.message})")
					null
				}
			val document = decoded ?: continue
			val warp = ChannelTally()
			val rotation = ChannelTally()
			val artMesh = ChannelTally()
			for (deformer in document.deformers) {
				when (deformer) {
					is WarpDeformer ->
						for (keyform in deformer.keyforms) {
							record(warp, keyform.opacity, keyform.multiplyColor, keyform.screenColor)
							record(corpusWarp, keyform.opacity, keyform.multiplyColor, keyform.screenColor)
						}

					is RotationDeformer ->
						for (keyform in deformer.keyforms) {
							record(rotation, keyform.opacity, keyform.multiplyColor, keyform.screenColor)
							record(corpusRotation, keyform.opacity, keyform.multiplyColor, keyform.screenColor)
						}
				}
			}
			for (mesh in document.artMeshes) {
				for (keyform in mesh.keyforms) {
					record(artMesh, keyform.opacity, keyform.multiplyColor, keyform.screenColor)
					record(corpusArtMesh, keyform.opacity, keyform.multiplyColor, keyform.screenColor)
				}
			}
			val flag = if (warp.hasAnything || rotation.hasAnything) "  <<< DEFORMER CHANNELS IN USE" else ""
			println("--- ${file.name} (v${document.version})$flag")
			println("    warp     : $warp")
			println("    rotation : $rotation")
			println("    artMesh  : $artMesh   (control group - already ingested)")
		}
		println("=== MOC3 corpus totals ===")
		println("    warp     : $corpusWarp")
		println("    rotation : $corpusRotation")
		println("    artMesh  : $corpusArtMesh")
	}
}
