package org.umamo.ui.document

import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandAvailability
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.workspace.commands.ImportArtworkRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * Pins what a drop MEANS, which is the whole of the decision a drop makes before any file is read: a
 * document replaces the open one, artwork is added to it, and the first file settles which of the two the
 * gesture was.  Everything the chosen way cannot use has to come back as ignored rather than disappear -
 * a rigger who grabbed one file too many should be able to find out.
 */
class DropRouteTest {
	@Test
	fun aDropOfNothingMeansNothing() {
		assertSame(DropRoute.None, dropRouteFor(emptyList()))
	}

	@Test
	fun aDocumentOpensAndTakesNothingElseWithIt() {
		val route = assertIs<DropRoute.OpenDocument>(dropRouteFor(listOf("/rigs/Erica.uma", "/rigs/Other.cmo3")))

		assertEquals("/rigs/Erica.uma", route.path)
		assertEquals(listOf("/rigs/Other.cmo3"), route.ignored, "only one document can be open")
	}

	@Test
	fun everyImportedModelFormatOpens() {
		assertIs<DropRoute.OpenDocument>(dropRouteFor(listOf("/rigs/Erica.cmo3")))
		assertIs<DropRoute.OpenDocument>(dropRouteFor(listOf("/rigs/Erica.moc3")))
	}

	@Test
	fun artworkFirstAddsEveryArtworkFileInTheDrop() {
		val route = assertIs<DropRoute.AddArtwork>(dropRouteFor(listOf("/art/face.psd", "/art/hair.clip", "/art/eyes.PNG")))

		assertEquals(listOf("/art/face.psd", "/art/hair.clip", "/art/eyes.PNG"), route.paths, "in the order they were dropped")
		assertEquals(emptyList(), route.ignored)
	}

	@Test
	fun artworkFirstLeavesADocumentInTheSameDropBehind() {
		val route = assertIs<DropRoute.AddArtwork>(dropRouteFor(listOf("/art/face.psd", "/rigs/Erica.uma", "/art/hair.psd")))

		assertEquals(listOf("/art/face.psd", "/art/hair.psd"), route.paths)
		assertEquals(listOf("/rigs/Erica.uma"), route.ignored, "the gesture was an add; the document is not quietly opened instead")
	}

	/**
	 * A file of no kind the registry claims still routes to the open, which is what puts it in front of the
	 * loader - and the loader is what tells the rigger their file is not one Umamo reads.  Routing it to the
	 * add would swallow it into a reader that has no alert of its own for "this is not artwork".
	 */
	@Test
	fun anUnrecognizedFileRoutesToTheOpenSoItsFailureIsReported() {
		val route = assertIs<DropRoute.OpenDocument>(dropRouteFor(listOf("/downloads/notes.txt", "/art/face.psd")))

		assertEquals("/downloads/notes.txt", route.path)
		assertEquals(listOf("/art/face.psd"), route.ignored)
	}

	/**
	 * A registry holding the two commands a drop dispatches, recording what each was run with.
	 *
	 * @param Boolean artworkApplies Whether the open document can take artwork.
	 * @param MutableList<Any?> opened Collects what the open was run with.
	 * @param MutableList<Any?> added Collects what the artwork add was run with.
	 * @return CommandRegistry The registry.
	 */
	private fun dropRegistry(
		artworkApplies: Boolean,
		opened: MutableList<Any?>,
		added: MutableList<Any?>,
	): CommandRegistry =
		CommandRegistry().also { registry ->
			registry.register(Command("file.openPath", title = null, handler = { argument -> opened.add(argument) }))
			registry.register(Command("file.importArtwork", title = null, availability = CommandAvailability { artworkApplies }, handler = { argument -> added.add(argument) }))
		}

	/** Dropped artwork is added through the artwork command, with the files it was handed, and nothing is turned away. */
	@Test
	fun droppedArtworkIsAddedThroughItsCommand() {
		val added = ArrayList<Any?>()
		var refusals = 0
		openDroppedFiles(listOf("/art/face.psd", "/art/hair.psd"), dropRegistry(artworkApplies = true, ArrayList(), added)) { refusals++ }

		assertEquals(listOf("/art/face.psd", "/art/hair.psd"), (added.single() as ImportArtworkRequest).paths)
		assertEquals(0, refusals)
	}

	/**
	 * Artwork dropped on a document that cannot take it is handed back to be explained: the menu row for the same
	 * command is grayed there, and a drop has no row to read.
	 */
	@Test
	fun droppedArtworkTheDocumentCannotTakeIsHandedBack() {
		val added = ArrayList<Any?>()
		var refusals = 0
		openDroppedFiles(listOf("/art/face.psd"), dropRegistry(artworkApplies = false, ArrayList(), added)) { refusals++ }

		assertEquals(emptyList(), added, "the command does not apply, so it never ran")
		assertEquals(1, refusals)
	}

	/** A dropped document opens through its own command and is no business of the artwork refusal. */
	@Test
	fun aDroppedDocumentOpensThroughItsCommand() {
		val opened = ArrayList<Any?>()
		var refusals = 0
		openDroppedFiles(listOf("/rigs/Erica.uma"), dropRegistry(artworkApplies = false, opened, ArrayList())) { refusals++ }

		assertEquals(listOf<Any?>("/rigs/Erica.uma"), opened)
		assertEquals(0, refusals)
	}
}