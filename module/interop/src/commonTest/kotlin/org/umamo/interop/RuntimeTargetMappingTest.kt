package org.umamo.interop

import org.umamo.format.cmo3.Cmo3TargetVersion
import org.umamo.format.moc3.moc.MocVersion
import org.umamo.runtime.model.RuntimeTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the RuntimeTarget ↔ format-version mapping per version boundary: the moc version each
 * target bakes, the CMO3 target version each target persists as, and the target each decoded
 * version selects on ingest.  (The capability matrix itself is pinned in :runtime's
 * RuntimeTargetTest.)
 */
class RuntimeTargetMappingTest {
	@Test
	fun mocVersionsMatchEachTargetsLevel() {
		assertEquals(MocVersion.V53, RuntimeTarget.NoTarget.mocVersion(), "nothing restricted, bake latest")
		assertEquals(MocVersion.V50, RuntimeTarget.Ayagami.mocVersion(), "Ayagami's effective level")
		assertEquals(MocVersion.V30, RuntimeTarget.Cubism30.mocVersion())
		assertEquals(MocVersion.V33, RuntimeTarget.Cubism33.mocVersion())
		assertEquals(MocVersion.V40, RuntimeTarget.Cubism40.mocVersion())
		assertEquals(MocVersion.V42, RuntimeTarget.Cubism42.mocVersion())
		assertEquals(MocVersion.V50, RuntimeTarget.Cubism50.mocVersion())
		assertEquals(MocVersion.V53, RuntimeTarget.Cubism53.mocVersion())
	}

	@Test
	fun cmo3TargetVersionsRoundTripPerTarget() {
		assertNull(RuntimeTarget.NoTarget.cmo3TargetVersion(), "NoTarget has no target VERSION, only the sentinel")
		assertEquals(
			RuntimeTarget.Cubism50.cmo3TargetVersion(),
			RuntimeTarget.Ayagami.cmo3TargetVersion(),
			"Ayagami persists at its effective Cubism level",
		)
		val cubismTargets =
			listOf(
				RuntimeTarget.Cubism30,
				RuntimeTarget.Cubism33,
				RuntimeTarget.Cubism40,
				RuntimeTarget.Cubism42,
				RuntimeTarget.Cubism50,
				RuntimeTarget.Cubism53,
			)
		cubismTargets.forEach { target ->
			assertEquals(target, runtimeTargetOfCmo3Target(target.cmo3TargetVersion()), "round trip for $target")
		}
		// Ayagami's identity cannot survive CMO3 - it reopens as its effective Cubism level.
		assertEquals(RuntimeTarget.Cubism50, runtimeTargetOfCmo3Target(RuntimeTarget.Ayagami.cmo3TargetVersion()))
	}

	@Test
	fun persistedVersionNoCoversEveryTarget() {
		assertEquals(
			Cmo3TargetVersion.LATEST_VERSION_NO,
			RuntimeTarget.NoTarget.cmo3TargetVersionNo(),
			"NoTarget persists as the SDK(N/A)/Latest sentinel",
		)
		assertEquals(5_000_000, RuntimeTarget.Ayagami.cmo3TargetVersionNo(), "Ayagami persists as Cubism 5.0")
		assertEquals(4_020_000, RuntimeTarget.Cubism42.cmo3TargetVersionNo(), "a Cubism target persists its literal")
		// The sentinel round-trips through ingest: it decodes to no version, which maps to NoTarget.
		assertEquals(
			RuntimeTarget.NoTarget,
			runtimeTargetOfCmo3Target(Cmo3TargetVersion.fromVersionNo(RuntimeTarget.NoTarget.cmo3TargetVersionNo())),
		)
	}

	@Test
	fun unknownCmo3TargetFallsBackToNoTarget() {
		assertEquals(RuntimeTarget.NoTarget, runtimeTargetOfCmo3Target(null))
		assertEquals(RuntimeTarget.Cubism42, runtimeTargetOfCmo3Target(Cmo3TargetVersion.V42))
	}

	@Test
	fun mocVersionsMapToTheirCubismTargets() {
		assertEquals(RuntimeTarget.Cubism30, runtimeTargetOfMocVersion(MocVersion.V30))
		assertEquals(RuntimeTarget.Cubism33, runtimeTargetOfMocVersion(MocVersion.V33))
		assertEquals(RuntimeTarget.Cubism40, runtimeTargetOfMocVersion(MocVersion.V40))
		assertEquals(RuntimeTarget.Cubism42, runtimeTargetOfMocVersion(MocVersion.V42))
		assertEquals(RuntimeTarget.Cubism50, runtimeTargetOfMocVersion(MocVersion.V50))
		assertEquals(RuntimeTarget.Cubism53, runtimeTargetOfMocVersion(MocVersion.V53))
	}
}
