package org.umamo.ui.document

import org.umamo.edit.EditorSession
import org.umamo.edit.ParameterChange
import org.umamo.edit.SelectionOps
import org.umamo.edit.SelectionTarget
import org.umamo.render.SourceArtRasters
import org.umamo.ui.model.DrawableThumbnailer
import org.umamo.ui.model.SessionAtlasPages
import java.io.File
import java.lang.ref.WeakReference
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How long to keep prodding the collector before declaring a reference leaked. */
private const val GC_WAIT_MILLIS = 10_000L

/** The pause between collection prods, so the poll loop is not a busy spin. */
private const val GC_POLL_MILLIS = 50L

/**
 * A replaced document must become garbage: opening a new file drops the previous [Cmo3Document]
 * (its retained CMO3 graph with every embedded PNG, the runtime puppet, the decoded atlas pages)
 * and its [EditorSession] (the undo history) on the floor, and nothing static may keep any of it
 * reachable.  A single strongly-held reference here - a companion cache, a registry, a listener -
 * pins tens to hundreds of megabytes per document swap, so this test IS the leak detector for the
 * whole load-and-session layer.
 *
 * The shell's swap is mirrored without composition: the outgoing document gets the session, the
 * undo step, the selection, the atlas page resolver, and the no-viewport thumbnailer a real open
 * creates, then every strong reference is released while the incoming document stays held - exactly
 * what EditorApp's onOpen does.  Reads the corpus sample (`-Dcmo3.sample`, defaulted to the local
 * corpus by the build) and self-skips without it, so CI stays green on a fresh clone.
 */
class DocumentSwapRetentionTest {
	private val sample: File? = System.getProperty("cmo3.sample")?.let(::File)?.takeIf { it.isFile }

	@Test
	fun aReplacedDocumentAndItsSessionBecomeUnreachable() {
		val file = sample ?: return
		val replacedReferences = openUseAndRelease(file)
		// The incoming document is held strongly across the whole check, as the shell holds its
		// current document while the previous one is discarded.
		val currentDocument = loadCmo3Document(file)
		awaitCollection(replacedReferences)
		for ((label, reference) in replacedReferences) {
			assertNull(reference.get(), "$label is still strongly reachable after the document swap")
		}
		assertTrue(currentDocument.puppet.drawables.isNotEmpty(), "the replacement document stays live")
	}

	/**
	 * Opens the corpus file, exercises it the way an open document is exercised, and returns ONLY weak
	 * references to its pieces.  A separate method on purpose: the strong references live in this
	 * frame, which is popped on return, so the caller's GC poll observes true reachability rather than
	 * a stale stack slot.
	 *
	 * @param File file The corpus `.cmo3` to open.
	 * @return List<Pair<String, WeakReference<Any>>> A labeled weak reference per document piece.
	 */
	private fun openUseAndRelease(file: File): List<Pair<String, WeakReference<Any>>> {
		val document = loadCmo3Document(file)
		val session = EditorSession(document.puppet, document.liveParams.values)
		val thumbnails = DrawableThumbnailer(document.puppet, document.textures)
		// The session's page resolver holds the baseline pages plus one derived page set - the largest
		// thing a repack leaves behind - so it has to go with the session it follows.
		val sessionAtlasPages = SessionAtlasPages(session, document.puppet.atlas, document.textures, document.artRasters)
		val firstDrawable = document.puppet.drawables.first()
		session.setSelection(SelectionOps.replace(SelectionTarget.Drawable(firstDrawable.id)))
		// One committed pose puts a real step on the undo history, so the history stack is part of
		// what must become unreachable.
		val firstParameter = document.puppet.parameters.first()
		session.commitPose(
			ParameterChange.SetValue(listOf(firstParameter.id)),
			mapOf(firstParameter.id to firstParameter.default + 1f),
		)
		val references =
			mutableListOf(
				"Cmo3Document" to WeakReference<Any>(document),
				"Cmo3Model (retained graph)" to WeakReference<Any>(document.cmo3),
				"PuppetModel" to WeakReference<Any>(document.puppet),
				"PuppetTextures" to WeakReference<Any>(document.textures),
				"EditorSession" to WeakReference<Any>(session),
				"DrawableThumbnailer" to WeakReference<Any>(thumbnails),
				"SessionAtlasPages" to WeakReference<Any>(sessionAtlasPages),
			)
		// The shared EMPTY store is a permanent singleton, so it only counts when this document built
		// its own.
		if (document.artRasters !== SourceArtRasters.EMPTY) {
			references.add("SourceArtRasters" to WeakReference<Any>(document.artRasters))
		}
		return references
	}

	/**
	 * Loads [file] through the same byte-core path the shell uses and asserts it opens as a CMO3.
	 *
	 * @param File file The corpus `.cmo3` to load.
	 * @return Cmo3Document The loaded document.
	 */
	private fun loadCmo3Document(file: File): Cmo3Document {
		val load = loadDocument(file.readBytes(), file.name, file.path)
		return assertIs<Cmo3Document>(assertIs<DocumentLoad.Loaded>(load).document)
	}

	/**
	 * Prods the collector until every reference clears or the deadline passes.  Returning without all
	 * of them cleared is not itself a failure - the caller's assertions name the survivors.
	 *
	 * @param List<Pair<String, WeakReference<Any>>> references The references to wait on.
	 */
	private fun awaitCollection(references: List<Pair<String, WeakReference<Any>>>) {
		val deadlineNanos = System.nanoTime() + GC_WAIT_MILLIS * 1_000_000L
		while (System.nanoTime() < deadlineNanos) {
			System.gc()
			if (references.all { (_, reference) -> reference.get() == null }) {
				return
			}
			Thread.sleep(GC_POLL_MILLIS)
		}
	}
}