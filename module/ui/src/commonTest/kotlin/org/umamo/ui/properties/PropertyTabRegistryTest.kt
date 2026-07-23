package org.umamo.ui.properties

import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.properties_tab_document
import org.umamo.ui.resources.properties_tab_object
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
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
	private fun contextFor(target: SelectionTarget): PropertyContext = PropertyContext(emptyPuppet(), Selection(setOf(target), target), target, session = null)

	/** With nothing selected, only the always-on Document tab is visible. */
	@Test
	fun documentTabIsTheOnlyTabWithNoSelection() {
		val visible = defaultPropertyTabRegistry().visibleTabs(PropertyContext(emptyPuppet(), Selection(), null, session = null)).map { tab -> tab.id }
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

	/**
	 * Per-row visibility: a blank query or a section-title match shows every row; a field query keeps only
	 * the matching rows; and a stacked row (several terms) survives when any of its terms match.
	 */
	@Test
	fun sectionVisibilityHidesNonMatchingRows() {
		val rows = listOf(listOf("Opacity"), listOf("Blend Mode"), listOf("Invert Mask"))

		// Blank query: the whole section, every row.
		sectionVisibility("Compositing", rows, "").let { result ->
			assertTrue(result.shown)
			assertEquals(listOf(0, 1, 2), result.visibleRowIndices)
		}
		// A field-name query keeps only the matching row.
		sectionVisibility("Compositing", rows, "blend").let { result ->
			assertTrue(result.shown)
			assertEquals(listOf(1), result.visibleRowIndices)
		}
		// A section-title match shows every row (the whole section is relevant), even when no row matches.
		sectionVisibility("Compositing", rows, "compos").let { result ->
			assertTrue(result.shown)
			assertEquals(listOf(0, 1, 2), result.visibleRowIndices)
		}
		// No match anywhere hides the section outright.
		sectionVisibility("Compositing", rows, "zzz").let { result ->
			assertFalse(result.shown)
			assertTrue(result.visibleRowIndices.isEmpty())
		}
		// A stacked row (canvas width + height) matches on any of its terms.
		sectionVisibility("Canvas", listOf(listOf("Width", "Height")), "height").let { result ->
			assertTrue(result.shown)
			assertEquals(listOf(0), result.visibleRowIndices)
		}
	}

	/**
	 * A section that built no rows for the current context never shows - not on a blank query, and not on a
	 * title match either.  This is how Transform opts out for an item with no transform (a Part, a warp
	 * deformer) instead of drawing an empty card, so a title match must not resurrect it.
	 */
	@Test
	fun sectionVisibilityHidesASectionThatBuiltNoRows() {
		val noRows = emptyList<List<String>>()

		sectionVisibility("Transform", noRows, "").let { result ->
			assertFalse(result.shown, "an empty section is not shown on a blank query")
			assertTrue(result.visibleRowIndices.isEmpty())
		}
		sectionVisibility("Transform", noRows, "transf").let { result ->
			assertFalse(result.shown, "a title match must not resurrect an empty section")
			assertTrue(result.visibleRowIndices.isEmpty())
		}
		sectionVisibility("Transform", noRows, "zzz").let { result ->
			assertFalse(result.shown)
			assertTrue(result.visibleRowIndices.isEmpty())
		}
	}

	/** A same-id override replaces its tab in place (keeping strip position); a new id appends after. */
	@Test
	fun withOverridesReplacesInPlaceAndAppends() {
		val original = PropertyTab(PropertyTabId.Document, Res.string.properties_tab_document, { error("icon unused") }, { true }, { emptyList() })
		val base = PropertyTabRegistry(listOf(original))
		val replacement = PropertyTab(PropertyTabId.Document, Res.string.properties_tab_document, { error("icon unused") }, { true }, { emptyList() })
		val appended = PropertyTab(PropertyTabId.Object, Res.string.properties_tab_object, { error("icon unused") }, { true }, { emptyList() })

		val merged = base.withOverrides(listOf(replacement, appended))

		val visibleContext = PropertyContext(emptyPuppet(), Selection(), null, session = null)
		assertEquals(listOf(PropertyTabId.Document, PropertyTabId.Object), merged.visibleTabs(visibleContext).map { it.id })
		assertSame(replacement, merged.tab(PropertyTabId.Document))
		assertSame(appended, merged.tab(PropertyTabId.Object))
		// The base registry is untouched - withOverrides returns a new registry.
		assertSame(original, base.tab(PropertyTabId.Document))
	}
}
