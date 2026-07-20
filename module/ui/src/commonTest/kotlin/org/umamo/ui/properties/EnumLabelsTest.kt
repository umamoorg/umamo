package org.umamo.ui.properties

import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.PartComposite
import org.umamo.runtime.model.PartGroupMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the enum-label coverage and the part group-mode projection.  The label resolvers are exhaustive
 * `when`s (a missing entry would not compile), so this asserts each enum maps to a distinct resource per
 * entry, and that the three-way group-mode projection round-trips while preserving an Isolated composite.
 */
class EnumLabelsTest {
	@Test
	fun everyEnumEntryMapsToADistinctLabelResource() {
		val blendResources = BlendMode.entries.map { mode -> blendModeLabelRes(mode) }
		assertEquals(BlendMode.entries.size, blendResources.toSet().size)

		val alphaResources = AlphaBlendMode.entries.map { mode -> alphaBlendModeLabelRes(mode) }
		assertEquals(AlphaBlendMode.entries.size, alphaResources.toSet().size)

		val groupResources = PartGroupModeKind.entries.map { kind -> partGroupModeLabelRes(kind) }
		assertEquals(PartGroupModeKind.entries.size, groupResources.toSet().size)
	}

	@Test
	fun groupModeKindProjectsEveryCase() {
		assertEquals(PartGroupModeKind.PassThrough, PartGroupMode.PassThrough.kind())
		assertEquals(PartGroupModeKind.Grouped, PartGroupMode.Grouped.kind())
		assertEquals(PartGroupModeKind.Isolated, PartGroupMode.Isolated(PartComposite()).kind())
	}

	@Test
	fun partGroupModeOfRoundTripsAndPreservesTheComposite() {
		// Each kind rebuilds to a mode of that same kind.
		for (kind in PartGroupModeKind.entries) {
			assertEquals(kind, partGroupModeOf(kind, PartGroupMode.PassThrough).kind())
		}

		// Re-selecting Isolated keeps the existing composite; arriving from a non-Isolated mode gets a default.
		val existing = PartGroupMode.Isolated(PartComposite(opacity = 0.25f))
		val kept = partGroupModeOf(PartGroupModeKind.Isolated, existing) as PartGroupMode.Isolated
		assertSame(existing.composite, kept.composite)

		val fresh = partGroupModeOf(PartGroupModeKind.Isolated, PartGroupMode.PassThrough) as PartGroupMode.Isolated
		assertTrue(fresh.composite.opacity == PartComposite().opacity)
	}
}
