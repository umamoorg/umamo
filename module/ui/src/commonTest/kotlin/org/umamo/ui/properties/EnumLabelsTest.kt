package org.umamo.ui.properties

import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.PartGroupMode
import kotlin.test.Test
import kotlin.test.assertEquals

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
		assertEquals(PartGroupModeKind.Isolated, PartGroupMode.Isolated.kind())
	}

	@Test
	fun partGroupModeOfProjectsEveryKind() {
		// The modes are payload-free (the composite lives latently on the part), so this is a plain
		// projection: each kind maps to its mode and round-trips through kind().
		assertEquals(PartGroupMode.PassThrough, partGroupModeOf(PartGroupModeKind.PassThrough))
		assertEquals(PartGroupMode.Grouped, partGroupModeOf(PartGroupModeKind.Grouped))
		assertEquals(PartGroupMode.Isolated, partGroupModeOf(PartGroupModeKind.Isolated))
		for (kind in PartGroupModeKind.entries) {
			assertEquals(kind, partGroupModeOf(kind).kind())
		}
	}
}
