package org.umamo.format.art

import org.umamo.format.clip.ClipReader
import org.umamo.format.kra.KraReader
import org.umamo.format.psd.PsdReader
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Visibility regression tests against purpose-built fixtures whose hidden layers and folders are
 * known up front. These are the only tests that prove the readers actually parse visibility with the
 * right bit and the right sense from real files: the pure-model cascade test (SourceArtVisibilityTest)
 * exercises the algorithm over fakes but never touches a reader, and the structural reader tests only
 * assert that wiring is self-consistent - neither can catch a reader that reads the wrong flag, inverts
 * hidden/shown, or drops folder visibility. A fixture with a layer the artist deliberately hid does.
 *
 * Corpus-gated: each fixture self-skips when absent, so CI stays green without the binaries committed.
 */
class ArtVisibilityFixtureTest {
	private fun corpusFile(relativePath: String): File? {
		var directory: File? = File(System.getProperty("user.dir"))
		while (directory != null) {
			val candidate = File(directory, relativePath)
			if (candidate.isFile) {
				return candidate
			}
			directory = directory.parentFile
		}
		return null
	}

	/**
	 * EricaVisibilityTest.psd has one folder ("Front hair") and one leaf ("Ear R/3") toggled off in
	 * Photoshop. It checks per-layer flag parsing, folder-header flag parsing, and the ancestor cascade
	 * in one shot.
	 */
	@Test
	fun psdFixtureHasKnownHiddenFolderAndLayer() {
		val file = corpusFile("test/corpus/EricaVisibilityTest.psd")
		if (file == null) {
			println("no test/corpus/EricaVisibilityTest.psd; skipping PSD visibility fixture test")
			return
		}
		val art = PsdReader.read(file.readBytes())

		// Folder-header flag: the "Front hair" folder was hidden, every other folder left shown.
		val frontHair = art.groups.single { group -> group.path == "Front hair" }
		assertFalse(frontHair.visible, "Front hair folder should be parsed as hidden")
		val otherFolders = art.groups.filter { group -> group.path != "Front hair" }
		assertTrue(otherFolders.all { group -> group.visible }, "no folder other than Front hair is hidden")

		// Cascade: the children keep their own eye on, but the hidden parent makes them not shown.
		val frontHairChildren = art.layers.filter { layer -> layer.groupPath == "Front hair" }
		assertTrue(frontHairChildren.isNotEmpty(), "Front hair should contain layers")
		assertTrue(frontHairChildren.all { layer -> layer.visible }, "Front hair children keep their own eye on")
		assertTrue(
			frontHairChildren.none { layer -> art.isEffectivelyVisible(layer) },
			"layers under the hidden Front hair folder are not effectively visible",
		)

		// Per-layer flag (independent of folders): the "3" leaf in "Ear R" was hidden directly.
		val hiddenLeaf = art.layers.single { layer -> layer.groupPath == "Ear R" && layer.name == "3" }
		assertFalse(hiddenLeaf.visible, "Ear R/3 should be parsed as hidden")
		assertFalse(art.isEffectivelyVisible(hiddenLeaf), "a hidden leaf is not effectively visible")

		// Positive control: a layer in a shown folder is both visible and effectively visible, so the
		// reader is not simply returning false everywhere.
		val shownLeaf = art.layers.first { layer -> layer.groupPath == "Tail" }
		assertTrue(shownLeaf.visible && art.isEffectivelyVisible(shownLeaf), "Tail layers stay shown")
	}

	/**
	 * KritaLayerVisibility.kra nests a shown folder ("Group 7") inside a hidden one ("Group 6"), so it
	 * exercises the multi-level cascade: a leaf whose own eye and whose immediate folder are both on is
	 * still not shown because a grandparent folder is hidden. It also carries a directly-hidden root leaf.
	 */
	@Test
	fun kraFixtureCascadesThroughNestedGroups() {
		val file = corpusFile("test/corpus/KritaLayerVisibility.kra")
		if (file == null) {
			println("no test/corpus/KritaLayerVisibility.kra; skipping KRA visibility fixture test")
			return
		}
		val art = KraReader.read(file.readBytes())

		// Folder flags: the outer "Group 6" is hidden, the inner "Group 6/Group 7" is shown.
		assertFalse(art.groups.single { group -> group.path == "Group 6" }.visible, "Group 6 hidden")
		assertTrue(art.groups.single { group -> group.path == "Group 6/Group 7" }.visible, "Group 7 shown")

		// The nested leaf keeps its own eye on and sits in a shown folder, yet the hidden grandparent
		// folder makes it not shown - the cascade must walk all the way up, not just to the parent.
		val nestedLeaf = art.layers.single { layer -> layer.groupPath == "Group 6/Group 7" }
		assertTrue(nestedLeaf.visible, "the nested leaf keeps its own eye on")
		assertFalse(art.isEffectivelyVisible(nestedLeaf), "hidden grandparent folder hides the nested leaf")

		// Leaves directly in the hidden folder are likewise not shown despite their own eye on.
		val inHiddenFolder = art.layers.filter { layer -> layer.groupPath == "Group 6" }
		assertTrue(inHiddenFolder.isNotEmpty() && inHiddenFolder.all { layer -> layer.visible })
		assertTrue(inHiddenFolder.none { layer -> art.isEffectivelyVisible(layer) }, "hidden folder hides its leaves")

		// A directly-hidden root leaf (own eye off, no folder involved).
		val hiddenRootLeaf = art.layers.single { layer -> layer.groupPath.isEmpty() && !layer.visible }
		assertFalse(art.isEffectivelyVisible(hiddenRootLeaf), "a directly-hidden root leaf is not shown")

		// Positive control: shown root leaves are visible and effectively visible.
		val shownRootLeaves = art.layers.filter { layer -> layer.groupPath.isEmpty() && layer.visible }
		assertTrue(shownRootLeaves.isNotEmpty() && shownRootLeaves.all { layer -> art.isEffectivelyVisible(layer) })
	}

	/**
	 * Greyscale.clip carries a hidden leaf ("Layer 2") alongside a shown one ("Layer 1") - a regression
	 * guard that CLIP's LayerVisibility bitfield is read with the right sense.
	 */
	@Test
	fun clipFixtureHasKnownHiddenLayer() {
		val file = corpusFile("test/corpus/Greyscale.clip")
		if (file == null) {
			println("no test/corpus/Greyscale.clip; skipping CLIP visibility fixture test")
			return
		}
		val art = ClipReader.read(file.readBytes())

		assertEquals(true, art.layers.single { layer -> layer.name == "Layer 1" }.visible, "Layer 1 shown")
		assertEquals(false, art.layers.single { layer -> layer.name == "Layer 2" }.visible, "Layer 2 hidden")
	}
}
