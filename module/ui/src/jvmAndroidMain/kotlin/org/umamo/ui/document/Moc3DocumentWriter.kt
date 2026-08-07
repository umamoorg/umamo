package org.umamo.ui.document

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.absolutePath
import io.github.vinceglb.filekit.write
import okio.FileSystem
import okio.Path.Companion.toPath
import org.umamo.interop.moc3.Moc3Sidecars
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
 * The bundle files that already exist at [destination], under the names the bundle would write.
 *
 * The safety check behind the pre-export overwrite warning: the native save dialog confirms
 * replacing the PICKED file only, while the family lands a manifest, a cdi3, atlas pages (in their
 * subfolder), and sidecars beside it that the dialog never mentioned.  Every bundle name is
 * checked - the moc included, since a picked NEW name can still collide with an existing family's
 * moc when only the extension's case differs.  On a platform whose handle has no resolvable parent
 * (Android SAF) nothing beside the moc will be written anyway, so there is nothing to warn about.
 *
 * @param PlatformFile destination The picked `.moc3` handle.
 * @param Bundle       bundle      The family that would be written.
 * @return List The relative names that already exist, in the bundle's own order.
 */
fun existingBundleFiles(destination: PlatformFile, bundle: Moc3Sidecars.Bundle): List<String> {
	val directory = runCatching { destination.absolutePath().toPath().parent }.getOrNull() ?: return emptyList()
	return bundle.files
		.map { file -> file.name }
		.filter { name -> FileSystem.SYSTEM.exists(directory / name) }
}

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
