package org.umamo.ui.document

import org.umamo.format.FileRole
import org.umamo.format.FormatRegistry
import org.umamo.storage.UmamoLog
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.workspace.commands.ImportArtworkRequest

/**
 * What a set of dropped files means: which way in the gesture takes, and which of its files that way
 * leaves behind.
 *
 * A drop is one gesture, so it gets one meaning.  The FIRST file decides which - a rigger dragging a
 * document in and a rigger dragging artwork in are doing two different things, and there is no honest
 * reading of a gesture that does both at once.  Whatever the chosen way cannot use is reported as
 * [ignored] rather than silently dropped, so a rigger who grabbed more than they meant to can see it.
 *
 * Classified by extension alone, deliberately.  The extension is enough to pick the route, and the route's
 * own loader then detects the real format from the bytes - so a mislabelled file still ends up refused by a
 * reader rather than accepted by a router.  That is the same division the command line already uses for a
 * path handed over by the operating system.
 */
internal sealed interface DropRoute {
	/** The files the chosen way in cannot use, in the order they were dropped. */
	val ignored: List<String>

	/**
	 * Open [path] as the whole document, replacing what is open.
	 *
	 * Also where a file of no recognized kind lands: the loader is what tells a rigger their file is not
	 * one Umamo reads, and it already does that with a named alert.
	 *
	 * @property String       path    The file to open.
	 * @property List<String> ignored The rest of the drop - only one document can be open.
	 */
	class OpenDocument(val path: String, override val ignored: List<String>) : DropRoute

	/**
	 * Add [paths] to the open document as source artwork.
	 *
	 * @property List<String> paths   The artwork files to add, in the order they were dropped.
	 * @property List<String> ignored Everything in the drop that was not artwork.
	 */
	class AddArtwork(val paths: List<String>, override val ignored: List<String>) : DropRoute

	/** Nothing to do - the drop carried no files. */
	data object None : DropRoute {
		override val ignored: List<String> = emptyList()
	}
}

/**
 * Reads what a drop of [paths] means (see [DropRoute] for why the first file decides).
 *
 * @param List<String> paths The dropped files' stored paths, in the order the platform reported them.
 * @return DropRoute The way in, and what it leaves behind.
 */
internal fun dropRouteFor(paths: List<String>): DropRoute {
	val first = paths.firstOrNull() ?: return DropRoute.None
	if (FormatRegistry.kindForFileName(first)?.role == FileRole.Artwork) {
		val artwork = paths.filter { path -> FormatRegistry.kindForFileName(path)?.role == FileRole.Artwork }
		return DropRoute.AddArtwork(artwork, paths - artwork.toSet())
	}
	return DropRoute.OpenDocument(first, paths.drop(1))
}

/**
 * Opens what was dropped on the editor, by dispatching the command its [DropRoute] calls for.
 *
 * Through the registry rather than into a controller, for two reasons: the artwork add needs the hovered
 * area its operation strip shows in, which only the shell's routing can answer at dispatch, and a document
 * open needs the unsaved-changes gate that `file.openPath` is defined to carry.  Both are already what
 * those ids do, so a drop is one more way in to them and not a path of its own.
 *
 * @param List<String>    paths    The dropped files' stored paths, in the order the platform reported them.
 * @param CommandRegistry commands The registry the two file commands are registered in.
 */
internal fun openDroppedFiles(paths: List<String>, commands: CommandRegistry) {
	val route = dropRouteFor(paths)
	if (route.ignored.isNotEmpty()) {
		UmamoLog.info("drag and drop: ${route.ignored.size} dropped file(s) were not used - ${route.ignored.joinToString()}")
	}
	when (route) {
		is DropRoute.OpenDocument -> commands.invoke("file.openPath", route.path)
		is DropRoute.AddArtwork -> commands.invoke("file.importArtwork", ImportArtworkRequest(route.paths))
		DropRoute.None -> Unit
	}
}