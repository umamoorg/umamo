package org.umamo.ui.workspace.spaces.sources

import androidx.compose.ui.unit.dp
import org.umamo.runtime.model.ArtSource
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.ArtSourceLayer
import org.umamo.runtime.model.SourceLayerRef
import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.kit.menu.MenuItem
import org.umamo.ui.workspace.commands.IgnoreLayerRequest
import org.umamo.ui.workspace.commands.ReloadScope
import org.umamo.ui.workspace.commands.ReplaceRequest
import org.umamo.ui.workspace.commands.registerAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins the entries of every menu a row and its chip show, in order, and what each entry asks for: the
 * command and the request it dispatches, or the very callback handed in.
 */
class SourcesMenusTest {
	private val labels =
		SourcesLabels(
			unboundArt = "Unbound Art",
			fileActions = "File Actions",
			replaceArtwork = "Replace Artwork",
			reloadFile = "Reload This File",
			layerActions = "Layer Actions",
			ignoreLayer = "Ignore Layer",
			stopIgnoring = "Stop Ignoring",
			review = "Review this binding",
			relinkByHand = "Relink Manually",
			leave = "Leave for Now",
			relink = "Relink to a Layer",
			unbind = "Unbind",
			deleteArt = "Delete Art",
			noMatches = "No layer matches.",
		)
	private val artA = ArtSourceId("art-0")
	private val artB = ArtSourceId("art-1")
	private val ref = SourceLayerRef(artA, "lyid:1", true)

	/** What the registry was asked to run, by command id. */
	private val dispatched = ArrayList<Pair<String, Any?>>()

	/** The calls a menu made on the callbacks handed to it. */
	private val calls = ArrayList<String>()

	/**
	 * A registry that records what each of the Sources row commands is handed.
	 *
	 * @return CommandRegistry The registry.
	 */
	private fun recordingRegistry(): CommandRegistry {
		val registry = CommandRegistry()
		val ids = listOf("sources.replaceArtwork", "document.reloadArtwork", "sources.ignoreLayer")
		registry.registerAll(ids.map { id -> Command(id, title = null) { argument -> dispatched.add(id to argument) } })

		return registry
	}

	private fun layer(key: String, name: String): ArtSourceLayer = ArtSourceLayer(key, name, "", 0, 0, 4, 4, true)

	/**
	 * The relink list's groups: two files, the first with two layers.
	 *
	 * @return List<RelinkGroup> The groups.
	 */
	private fun groups(): List<RelinkGroup> =
		listOf(
			RelinkGroup(ArtSource(artA, "a.psd", null, "psd"), listOf(layer("lyid:1", "Hair"), layer("name:Eye", "Eye"))),
			RelinkGroup(ArtSource(artB, "b.clip", null, "clip"), listOf(layer("uuid-9", "Wing"))),
		)

	/**
	 * The relink menu over [groups], with its callbacks recorded.
	 *
	 * @param SourceLayerRef? current     The binding the picker starts from.
	 * @param Boolean         offerUnbind Whether Unbind is offered.
	 * @param Boolean         offerDelete Whether Delete Art is offered.
	 * @param List            listed      The groups to list.
	 * @return List<MenuItem> The menu.
	 */
	private fun relinkMenu(current: SourceLayerRef? = null, offerUnbind: Boolean = false, offerDelete: Boolean = false, listed: List<RelinkGroup> = groups()): List<MenuItem> =
		relinkMenuItems(
			labels = labels,
			groups = listed,
			current = current,
			query = "ha",
			onQueryChange = { updated -> calls.add("query:$updated") },
			onUnbind = if (offerUnbind) ({ calls.add("unbind") }) else null,
			onDelete = if (offerDelete) ({ calls.add("delete") }) else null,
			onPick = { sourceId, layerKey -> calls.add("pick:${sourceId.raw}/$layerKey") },
		)

	/**
	 * What a menu lists, top to bottom: an action or a heading by its label, a separator and the search box by name.
	 *
	 * @param List items The menu.
	 * @return List<String> One word per entry.
	 */
	private fun listingOf(items: List<MenuItem>): List<String> =
		items.map { item ->
			when (item) {
				is MenuItem.Action -> item.label
				is MenuItem.Heading -> "[${item.label}]"
				is MenuItem.Separator -> "---"
				is MenuItem.Search -> "(search)"
				is MenuItem.Submenu -> "> ${item.label}"
			}
		}

	/**
	 * The action labelled [label].
	 *
	 * @param List   items The menu.
	 * @param String label The entry's label.
	 * @return MenuItem.Action The entry.
	 */
	private fun actionOf(items: List<MenuItem>, label: String): MenuItem.Action = items.filterIsInstance<MenuItem.Action>().first { item -> item.label == label }

	/** A file row offers Replace Artwork, then Reload This File, each naming the row's file. */
	@Test
	fun aFileRowReplacesAndReloadsItsFile() {
		val items = sourceFileMenuItems(artA, labels, recordingRegistry())
		assertEquals(listOf("Replace Artwork", "Reload This File"), listingOf(items))

		actionOf(items, "Replace Artwork").onSelect()
		actionOf(items, "Reload This File").onSelect()

		assertEquals(listOf("sources.replaceArtwork", "document.reloadArtwork"), dispatched.map { (id, _) -> id })
		assertEquals(artA, assertIs<ReplaceRequest>(dispatched[0].second).sourceId)
		assertEquals(setOf(artA), assertIs<ReloadScope>(dispatched[1].second).sourceIds, "the one file, not every file")
	}

	/** An unbound layer offers to be ignored, and an ignored one to stop: one entry either way, asking for the opposite of what is. */
	@Test
	fun aLayerRowTogglesItsIgnore() {
		val registry = recordingRegistry()
		val unbound = layerMenuItems(ref, ignored = false, labels = labels, commands = registry)
		val ignored = layerMenuItems(ref, ignored = true, labels = labels, commands = registry)
		assertEquals(listOf("Ignore Layer"), listingOf(unbound))
		assertEquals(listOf("Stop Ignoring"), listingOf(ignored))

		actionOf(unbound, "Ignore Layer").onSelect()
		actionOf(ignored, "Stop Ignoring").onSelect()

		val marked = assertIs<IgnoreLayerRequest>(dispatched[0].second)
		assertEquals(ref, marked.ref)
		assertTrue(marked.ignored)
		assertFalse(assertIs<IgnoreLayerRequest>(dispatched[1].second).ignored)
	}

	/** A review row offers its proposal first, then the relink by hand, then leaving it; each runs the callback handed in. */
	@Test
	fun aReviewRowOffersItsProposalFirst() {
		val items = reviewMenuItems(labels, "Accept Brow (92%)", onAccept = { calls.add("accept") }, onRelinkByHand = { calls.add("byHand") })
		assertEquals(listOf("Accept Brow (92%)", "Relink Manually", "Leave for Now"), listingOf(items))

		actionOf(items, "Accept Brow (92%)").onSelect()
		actionOf(items, "Relink Manually").onSelect()
		actionOf(items, "Leave for Now").onSelect()

		assertEquals(listOf("accept", "byHand"), calls, "leaving asks for nothing")
	}

	/** A review row nothing is proposed for offers no Accept. */
	@Test
	fun aReviewRowWithNoProposalOffersNoAccept() {
		val items = reviewMenuItems(labels, acceptLabel = null, onAccept = { calls.add("accept") }, onRelinkByHand = { calls.add("byHand") })

		assertEquals(listOf("Relink Manually", "Leave for Now"), listingOf(items))
	}

	/** The relink list opens on its search box, then every file as a heading over its layers. */
	@Test
	fun theRelinkListGroupsTheLayersUnderTheirFiles() {
		val items = relinkMenu()

		assertEquals(listOf("(search)", "[a.psd]", "Hair", "Eye", "[b.clip]", "Wing"), listingOf(items))
		val search = assertIs<MenuItem.Search>(items.first())
		assertEquals("ha", search.value)
		assertEquals(SOURCES_RELINK_MENU_WIDTH, search.width)
		assertEquals(320.dp, search.width, "wide enough for a layer's name, and fixed so the rows stay put")
		search.onValueChange("hai")
		assertEquals(listOf("query:hai"), calls)
	}

	/** Unbind and Delete Art lead the list when offered, set off from the layers by one separator. */
	@Test
	fun unbindAndDeleteLeadTheListWhenOffered() {
		assertEquals(listOf("(search)", "Unbind", "Delete Art", "---", "[a.psd]", "Hair", "Eye", "[b.clip]", "Wing"), listingOf(relinkMenu(offerUnbind = true, offerDelete = true)))
		assertEquals(listOf("(search)", "Unbind", "---", "[a.psd]", "Hair", "Eye", "[b.clip]", "Wing"), listingOf(relinkMenu(offerUnbind = true)))
		assertEquals(listOf("(search)", "Delete Art", "---", "[a.psd]", "Hair", "Eye", "[b.clip]", "Wing"), listingOf(relinkMenu(offerDelete = true)))

		val items = relinkMenu(offerUnbind = true, offerDelete = true)
		actionOf(items, "Unbind").onSelect()
		actionOf(items, "Delete Art").onSelect()

		assertEquals(listOf("unbind", "delete"), calls)
	}

	/** A pick names the layer by its file and key, and leaves the binding's strength to whoever asked. */
	@Test
	fun aPickNamesTheLayerByFileAndKey() {
		val items = relinkMenu()

		actionOf(items, "Eye").onSelect()
		actionOf(items, "Wing").onSelect()

		assertEquals(listOf("pick:art-0/name:Eye", "pick:art-1/uuid-9"), calls)
	}

	/** The layer the tile is bound to already is listed and inert; the same key in another file is not it. */
	@Test
	fun theCurrentBindingIsInert() {
		val sameKeyElsewhere = listOf(RelinkGroup(ArtSource(artA, "a.psd", null, "psd"), listOf(layer("lyid:1", "Hair"))), RelinkGroup(ArtSource(artB, "b.clip", null, "clip"), listOf(layer("lyid:1", "Tail"))))

		val items = relinkMenu(current = ref, listed = sameKeyElsewhere)

		assertFalse(actionOf(items, "Hair").enabled)
		assertTrue(actionOf(items, "Tail").enabled)
	}

	/** A search that leaves no layer shows one inert line saying so, under the search box. */
	@Test
	fun aListWithNothingLeftSaysSo() {
		val items = relinkMenu(offerUnbind = true, listed = emptyList())

		assertEquals(listOf("(search)", "Unbind", "---", "No layer matches."), listingOf(items))
		assertFalse(actionOf(items, "No layer matches.").enabled)
	}
}