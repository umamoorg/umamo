package org.umamo.ui.document

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.absolutePath
import io.github.vinceglb.filekit.write
import okio.FileSystem
import okio.Path.Companion.toPath
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.interop.moc3.Moc3Sidecars
import org.umamo.interop.moc3.export.CanvasToParentSpace
import org.umamo.interop.moc3.export.Moc3Export
import org.umamo.render.DecodedImage
import org.umamo.render.PuppetTextures
import org.umamo.render.eval.drawableSpaceMapping
import org.umamo.runtime.model.PuppetModel
import org.umamo.storage.UmamoLog

/*
 * Writes an exported moc family to disk, and classifies the sidecars an import retained.
 *
 * The picker hands back ONE destination - a `.moc3` - but a baked model is a family, so everything
 * else lands beside it under the manifest-relative names the bundle assigned.  That mirrors the
 * import, which discovers the same family by reading siblings of the picked moc, and it inherits the
 * same platform limit: an Android SAF `content://` handle has no resolvable parent directory, so the
 * family cannot be written there yet (the MOC3 itself still is, through the picker's own handle).
 */

/**
 * Writes every file of [bundle] beside [destination].
 *
 * The moc goes through the picker's own handle - that is the one path the user actually chose, and on
 * a sandboxed platform the only one the app is permitted to write.  The rest resolve against its
 * parent directory, creating subdirectories (`motion/`) as the source layout requires.
 *
 * @param PlatformFile destination The picked `.moc3` handle.
 * @param Bundle       bundle      The family to write.
 * @return Int How many files were written, including the moc.
 */
suspend fun writeMoc3Bundle(destination: PlatformFile, bundle: Moc3Sidecars.Bundle): Int {
	// The bundle names its own moc rather than being matched against the picked file's name.  Matching
	// by name silently degrades to "whatever is first" the moment the two disagree - which they do as
	// soon as the picked extension differs in case from the one the bundle generated.
	val mocFile = bundle.files.first { file -> file.name == bundle.mocFileName }
	destination.write(mocFile.bytes)

	val directory = runCatching { destination.absolutePath().toPath().parent }.getOrNull()
	if (directory == null) {
		// The moc is written; the family is not.  Loud, because the result is a file no runtime can
		// open on its own - and silent partial success is exactly what a rigger would not check for.
		UmamoLog.error("exported ${destination.absolutePath()} alone: its directory is not writable from here")
		return 1
	}
	var written = 1
	for (file in bundle.files) {
		if (file === mocFile) {
			continue
		}
		val target = directory / file.name
		runCatching {
			target.parent?.let { parent -> FileSystem.SYSTEM.createDirectories(parent) }
			FileSystem.SYSTEM.write(target) { write(file.bytes) }
			written++
		}.onFailure { failure ->
			UmamoLog.error("could not write ${file.name} beside the exported moc", failure)
		}
	}
	return written
}

/**
 * The pass-through sidecars of [document], already classified by the manifest section that named
 * them (see `Moc3Document.sidecars`).
 *
 * Nothing is re-derived here.  Classifying by file name instead would drop any sidecar the rigger
 * named off-convention into the motion catch-all, and the export's manifest would stop pointing at
 * it - the file copied beside the moc, but silently unwired from the rig.
 *
 * @param Moc3Document? document The imported document, or null for a non-MOC3 origin.
 * @return List The sidecars to re-emit, empty when there are none.
 */
fun passThroughSidecars(document: Moc3Document?): List<Moc3Sidecars.PassThroughSidecar> =
	document?.sidecars.orEmpty()

/**
 * Re-encodes a decoded atlas page as PNG, for a document that has no original page bytes.
 *
 * Only a CMO3-origin document reaches this: a MOC3-origin one retains the source PNGs and writes those
 * verbatim, which is both faster and lossless.
 *
 * @param DecodedImage atlas The decoded page.
 * @return ByteArray The PNG bytes.
 */
fun encodeAtlasPng(atlas: DecodedImage): ByteArray =
	PngCodec.write(RasterImage(width = atlas.width, height = atlas.height, rgba = atlas.rgba))

/**
 * The [Moc3Export] space seam for [puppet]: inverts a drawable's canvas-space rest mesh through its
 * parent-deformer chain at the NEUTRAL pose.
 *
 * The neutral pose is the right one because that is the pose the rest mesh is defined at - the import's
 * own canvas-space pass evaluates the default parameters to produce it, so inverting at the same pose
 * is that pass read backwards.  A drawable the chain cannot map (a deformer with no lattice anywhere)
 * returns null, which the export turns into a notice rather than a silently mis-scaled mesh.
 *
 * @param PuppetModel puppet The rig being exported.
 * @return CanvasToParentSpace The seam to hand the export.
 */
fun canvasToParentSpaceFor(puppet: PuppetModel): CanvasToParentSpace =
	{ drawableId, positions ->
		drawableSpaceMapping(puppet, emptyMap(), drawableId)?.let { mapping ->
			// worldToLocal expects the renderer's Y-negated world space, and every vertex is solved.
			val world = FloatArray(positions.size) { index -> if (index % 2 == 0) positions[index] else -positions[index] }
			// The seed matters only for the warp inverse, and it must be a LATTICE UV, not a canvas
			// coordinate: seeding Newton with the canvas-space value starts it hundreds of units outside
			// the [0,1] lattice, where the damped step cannot walk back.  The lattice center is the
			// neutral seed - at most half a lattice away from any target, which the damped step covers.
			val seed = FloatArray(positions.size) { LATTICE_CENTRE }
			mapping.worldToLocal(world, seed, positions.indices.step(2).map { index -> index / 2 }.toSet())
		}
	}

/** The warp inverse's neutral seed: the middle of the normalized [0,1] lattice. */
private const val LATTICE_CENTRE: Float = 0.5f

/**
 * [puppet] with every drawable's atlas page taken from [textures].
 *
 * A moc addresses its pages by index on the art mesh, so the export needs one on every drawable.  A
 * MOC3-origin document already carries them (the import reads them straight off the art mesh), but a
 * CMO3 has no page index at all - its pixels are embedded per drawable - so those documents reach the
 * export with every drawable still on the -1 sentinel, which the moc lowering clamps to page 0,
 * pointing a multi-page rig at one atlas.  The decoded texture set is what knows the answer either
 * way, so it is the one asked.
 *
 * @param PuppetModel    puppet   The rig being exported.
 * @param PuppetTextures textures The document's decoded atlas set.
 * @return PuppetModel The rig with its page bindings resolved, or [puppet] when none moved.
 */
fun withTexturePagesFrom(puppet: PuppetModel, textures: PuppetTextures): PuppetModel {
	var moved = false
	val drawables =
		puppet.drawables.map { drawable ->
			val page = textures.atlasIndexByDrawableId[drawable.id.raw] ?: return@map drawable
			if (page == drawable.texturePage) {
				drawable
			} else {
				moved = true
				drawable.copy(texturePage = page)
			}
		}
	return if (moved) puppet.copy(drawables = drawables) else puppet
}
