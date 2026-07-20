package org.umamo.ui.properties

import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the two pure behaviors that carry the Properties data design: which tabs are visible for a given
 * selection ([PropertyTabRegistry.visibleTabs]) and the header-search match predicate ([matchesQuery]).
 * Both are Compose-free, so they exercise the design without a UI harness.
 */
class PropertyTabRegistryTest {
	/** A minimal, empty model - enough to build a [PropertyContext] (the visibility predicates read only the target). */
	private fun emptyPuppet(): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = emptyList(),
			rootChildren = emptyList(),
			rootPartId = null,
		)

	/**
	 * Builds a single-target selection context.
	 *
	 * @param SelectionTarget target The lone active target.
	 * @return PropertyContext The context with that target active.
	 */
	private fun contextFor(target: SelectionTarget): PropertyContext = PropertyContext(emptyPuppet(), Selection(setOf(target), target), target)

	/** With nothing selected, only the always-on Document tab is visible. */
	@Test
	fun documentTabIsTheOnlyTabWithNoSelection() {
		val visible = defaultPropertyTabRegistry().visibleTabs(PropertyContext(emptyPuppet(), Selection(), null)).map { tab -> tab.id }
		assertEquals(listOf(PropertyTabId.Document), visible)
	}

	/** A single active item exposes the per-item Object and Data tabs alongside Document, in fixed order. */
	@Test
	fun perItemTabsAppearForEachSelectionKind() {
		val registry = defaultPropertyTabRegistry()
		val expected = listOf(PropertyTabId.Document, PropertyTabId.Object, PropertyTabId.Data)
		assertEquals(expected, registry.visibleTabs(contextFor(SelectionTarget.Drawable(DrawableId("d1")))).map { it.id })
		assertEquals(expected, registry.visibleTabs(contextFor(SelectionTarget.Deformer(DeformerId("w1")))).map { it.id })
		assertEquals(expected, registry.visibleTabs(contextFor(SelectionTarget.Part(PartId("p1")))).map { it.id })
	}

	/** The Data tab adapts its sections to the active item's type. */
	@Test
	fun dataTabSectionsResolvePerType() {
		assertEquals(
			listOf(MeshSection.id, TextureSection.id, BlendSection.id),
			dataTabSections(contextFor(SelectionTarget.Drawable(DrawableId("d1")))).map { it.id },
		)
		assertEquals(listOf(DeformerSection.id), dataTabSections(contextFor(SelectionTarget.Deformer(DeformerId("w1")))).map { it.id })
		assertEquals(listOf(PartSection.id), dataTabSections(contextFor(SelectionTarget.Part(PartId("p1")))).map { it.id })
	}

	/** The match predicate is case-insensitive, and a blank query matches everything. */
	@Test
	fun matchesQueryIsCaseInsensitiveAndBlankMatchesAll() {
		val haystack = listOf("Canvas", "Size: %1\$s × %2\$s")
		assertTrue(matchesQuery(haystack, ""))
		assertTrue(matchesQuery(haystack, "   "))
		assertTrue(matchesQuery(haystack, "can"))
		assertTrue(matchesQuery(haystack, "SIZE"))
		assertFalse(matchesQuery(haystack, "runtime"))
		assertFalse(matchesQuery(emptyList(), "x"))
	}
}
