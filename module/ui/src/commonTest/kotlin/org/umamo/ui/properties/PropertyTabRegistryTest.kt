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
import kotlin.test.assertSame

/**
 * Covers which tabs the Properties panel shows for a given selection ([PropertyTabRegistry.visibleTabs]), the
 * sections the Data tab adapts to, and how an override layers over the base registry.  All Compose-free, so they
 * exercise the design without a UI harness.
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
			listOf(MeshSection.id, TextureSection.id, BlendSection.id, BlendShapesSection.id),
			dataTabSections(contextFor(SelectionTarget.Drawable(DrawableId("d1")))).map { it.id },
		)
		assertEquals(listOf(DeformerSection.id, BlendShapesSection.id), dataTabSections(contextFor(SelectionTarget.Deformer(DeformerId("w1")))).map { it.id })
		assertEquals(listOf(PartSection.id), dataTabSections(contextFor(SelectionTarget.Part(PartId("p1")))).map { it.id })
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