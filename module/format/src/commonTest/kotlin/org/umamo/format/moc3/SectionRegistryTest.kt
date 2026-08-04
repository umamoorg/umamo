package org.umamo.format.moc3

import org.umamo.format.moc3.encode.MocEncoder
import org.umamo.format.moc3.moc.MocVersion
import org.umamo.format.moc3.moc.Section
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Structural invariants of the [Section] registry itself, independent of any corpus file.
 *
 * The registry is the single source of truth for what a `.moc3` contains: one entry per section
 * index, carrying its element type, sizing rule, and per-version slot.  A second untyped registry of
 * bare index constants used to sit beside it, and the sections named only there were invisible to
 * both the typed decode and the lossless gate - so "is this index modeled?" had two answers.  These
 * tests keep it to one.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §section map</a>
 */
class SectionRegistryTest {
	/**
	 * No two sections may claim the same table slot in the same version.
	 *
	 * A collision is silent otherwise: both entries decode, one lowering overwrites the other, and the
	 * bake emits whichever ran last.
	 */
	@Test
	fun noTwoSectionsShareAnIndexInAnyVersion() {
		for (version in MocVersion.entries) {
			val owners = HashMap<Int, Section>()
			for (section in Section.entries) {
				val index = section.indexIn(version)
				if (index < 0) {
					continue
				}
				val existing = owners.put(index, section)
				assertEquals(null, existing, "${version.label}: index $index claimed by both $existing and $section")
			}
		}
	}

	/**
	 * Every slot the version's table defines is modeled - the registry has no holes.
	 *
	 * [MocEncoder.sectionCount] is the editor's table length per version, so the indices a file can
	 * legitimately carry are exactly `0 until sectionCount`.  Any gap here is a section a bake would
	 * have to carry verbatim from a reference container, which is precisely what makes a fresh
	 * synthesis impossible - so a gap is a real capability limit, not a cosmetic one.
	 */
	@Test
	fun everyTableSlotIsModeledForEveryVersion() {
		for (version in MocVersion.entries) {
			val modeled = Section.entries.map { it.indexIn(version) }.filter { it >= 0 }.toSet()
			val expected = (0 until MocEncoder.sectionCount(version)).toSet()
			assertEquals(
				emptySet(),
				expected - modeled,
				"${version.label}: unmodeled table slots",
			)
			assertEquals(
				emptySet(),
				modeled - expected,
				"${version.label}: sections claim slots past the version's table",
			)
		}
	}

	/**
	 * A section present in one version keeps the same index in every later version.
	 *
	 * MOC3 only ever APPENDS: a newer version adds higher indices rather than renumbering, which is
	 * what lets one index mean one thing across the whole family.  A section that moved would break
	 * every version-agnostic reader, so the registry should never express one.
	 */
	@Test
	fun sectionIndicesNeverMoveOnceIntroduced() {
		val versions = MocVersion.entries.sortedBy { it.byteValue }
		for (section in Section.entries) {
			var introduced = -1
			for (version in versions) {
				val index = section.indexIn(version)
				if (index < 0) {
					// Absent after being present would mean the format REMOVED a section; none does.
					assertTrue(introduced < 0, "$section disappears again at ${version.label}")
					continue
				}
				if (introduced < 0) {
					introduced = index
				}
				assertEquals(introduced, index, "$section moves from $introduced to $index at ${version.label}")
			}
		}
	}
}
