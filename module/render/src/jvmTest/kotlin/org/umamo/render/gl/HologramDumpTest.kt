package org.umamo.render.gl

import org.umamo.format.moc3.Moc3
import org.umamo.interop.moc3.Moc3Import
import org.umamo.render.GridColors
import org.umamo.render.PuppetTextures
import org.umamo.render.SupersampledSurface
import org.umamo.render.ViewportCamera
import org.umamo.render.buildPuppetTextures
import org.umamo.render.puppet.PuppetRenderer
import org.umamo.render.restMeshesToCanvasSpace
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** Dumps modelA renders to PNGs (app path: supersample + grid) so they can be inspected directly. */
class HologramDumpTest {
	private val display = 768
	private val supersample = 2

	@Test
	fun dump() {
		val out = System.getenv("HOLO_DUMP_DIR") ?: return
		val mocFile =
			System.getProperty("moc3.samples")?.let(::File)?.takeIf { it.isDirectory }
				?.walkTopDown()?.firstOrNull { it.name == "modelA.moc3" } ?: return
		val atlasPng = File(mocFile.parentFile, "modelA.4096/texture_00.png")
		requireHeadlessGl("[holo-dump]")
		val mocDocument = Moc3.decode(mocFile.readBytes())
		val puppet = restMeshesToCanvasSpace(Moc3Import.fromMocDocument(mocDocument, null))
		val textures = if (atlasPng.isFile) buildPuppetTextures(listOf(atlasPng.readBytes()), mocDocument.artMeshes.associate { it.id to 0 }) else null
		for (hologram in listOf(0f, 1f)) {
			val rgba = render(puppet, textures, mapOf(ParameterId("ParamHologram") to hologram))
			writePng(rgba, File(out, "modelA_holo_$hologram.png"))
			println("[holo-dump] wrote modelA_holo_$hologram.png")
		}
	}

	private fun render(source: PuppetModel, textures: PuppetTextures?, pose: Map<ParameterId, Float>): IntArray {
		val device = GlRenderDevice()
		val renderer = PuppetRenderer(source, textures ?: PuppetTextures(emptyList(), emptyMap(), premultipliedAlpha = false), device)
		renderer.initGl()
		renderer.setRenderScale(supersample.toFloat())
		renderer.setGrid(GridColors.Classic, 1f, 4)
		renderer.setPose(pose)
		val surface = SupersampledSurface(device, supersample)
		val drawTarget = surface.ensure(display, display)
		val camera = ViewportCamera.fit(renderer.contentBounds(), display, display)
		renderer.setCamera(camera.copy(zoom = camera.zoom * supersample))
		renderer.render(drawTarget, display * supersample, display * supersample)
		surface.resolve()
		val image = device.readPixels(surface.resolveTarget)
		return IntArray(image.rgba.size) { image.rgba[it].toInt() and 0xFF }
	}

	private fun writePng(rgba: IntArray, file: File) {
		val image = BufferedImage(display, display, BufferedImage.TYPE_INT_ARGB)
		var pixel = 0
		var index = 0
		while (index < rgba.size) {
			val x = pixel % display
			val y = pixel / display
			val argb = (rgba[index + 3] shl 24) or (rgba[index] shl 16) or (rgba[index + 1] shl 8) or rgba[index + 2]
			image.setRGB(x, y, argb)
			pixel++
			index += 4
		}
		ImageIO.write(image, "png", file)
	}
}
