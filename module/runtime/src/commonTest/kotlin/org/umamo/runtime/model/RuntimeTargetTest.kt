package org.umamo.runtime.model

import org.umamo.format.cmo3.Cmo3TargetVersion
import org.umamo.format.moc3.moc.MocVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the runtime repository's capability matrix to the official Cubism Editor's "Model target
 * version selection" dialog, both sides of every version boundary.
 */
class RuntimeTargetTest {
	@Test
	fun featureBoundariesMatchTheOfficialDialog() {
		// New warp deformer method: SDK3.3+.
		assertTrue(!RuntimeTarget.Cubism30.supports(RuntimeFeature.WarpQuadTransform))
		assertTrue(RuntimeTarget.Cubism33.supports(RuntimeFeature.WarpQuadTransform))
		// Reversed Mask: SDK4.0+.
		assertTrue(!RuntimeTarget.Cubism33.supports(RuntimeFeature.ReversedMask))
		assertTrue(RuntimeTarget.Cubism40.supports(RuntimeFeature.ReversedMask))
		// Blend Shape (art mesh + warp), blend-shape parameters, multiply color, screen color: SDK4.2+.
		listOf(
			RuntimeFeature.MeshWarpBlendShapes,
			RuntimeFeature.BlendShapeParameters,
			RuntimeFeature.MultiplyColor,
			RuntimeFeature.ScreenColor,
		).forEach { feature ->
			assertTrue(!RuntimeTarget.Cubism40.supports(feature), "$feature is 4.2+, not 4.0")
			assertTrue(RuntimeTarget.Cubism42.supports(feature), "$feature is supported at 4.2")
		}
		// Blend Shape (part / rotation / glue / scalar) and motion-sync: SDK5.0+.
		listOf(RuntimeFeature.ExtendedBlendShapes, RuntimeFeature.MotionSync).forEach { feature ->
			assertTrue(!RuntimeTarget.Cubism42.supports(feature), "$feature is 5.0+, not 4.2")
			assertTrue(RuntimeTarget.Cubism50.supports(feature), "$feature is supported at 5.0")
		}
		// Blend mode, offscreen drawing, parameter repeat: SDK5.3+.
		listOf(RuntimeFeature.ExtendedBlendModes, RuntimeFeature.PartComposite, RuntimeFeature.ParameterRepeat)
			.forEach { feature ->
				assertTrue(!RuntimeTarget.Cubism50.supports(feature), "$feature is 5.3+, not 5.0")
				assertTrue(RuntimeTarget.Cubism53.supports(feature), "$feature is supported at 5.3")
			}
		// ArtPath is "Latest Cubism only": no Cubism ceiling allows it.
		assertTrue(!RuntimeTarget.Cubism53.supports(RuntimeFeature.ArtPath), "ArtPath is beyond every Cubism target")
		assertTrue(RuntimeTarget.NoTarget.supports(RuntimeFeature.ArtPath), "ArtPath is authorable with no target")
	}

	@Test
	fun restrictedListsDeriveFromTheMatrix() {
		assertEquals(emptyList(), RuntimeTarget.NoTarget.restrictedFeatures(), "NoTarget restricts nothing")
		assertEquals(
			RuntimeTarget.Cubism50.restrictedFeatures(),
			RuntimeTarget.Ayagami.restrictedFeatures(),
			"Ayagami is effectively Cubism 5.0 at present",
		)
		assertEquals(listOf(RuntimeFeature.ArtPath), RuntimeTarget.Cubism53.restrictedFeatures())
		assertEquals(
			RuntimeFeature.entries.toList(),
			RuntimeTarget.Cubism30.restrictedFeatures(),
			"every feature postdates Cubism 3.0",
		)
	}

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
	fun cmo3TargetVersionsRoundTripForCubismTargetsOnly() {
		assertNull(RuntimeTarget.NoTarget.cmo3TargetVersion(), "NoTarget has no CMO3 encoding")
		assertNull(RuntimeTarget.Ayagami.cmo3TargetVersion(), "Ayagami has no CMO3 encoding")
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
	}

	@Test
	fun persistedVersionNoCoversEveryTargetButAyagami() {
		assertEquals(
			Cmo3TargetVersion.LATEST_VERSION_NO,
			RuntimeTarget.NoTarget.cmo3TargetVersionNo(),
			"NoTarget persists as the SDK(N/A)/Latest sentinel",
		)
		assertNull(RuntimeTarget.Ayagami.cmo3TargetVersionNo(), "Ayagami has no CMO3 encoding")
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

	@Test
	fun displayNamesAreDistinctAndNonBlank() {
		val names = RuntimeTarget.entries.map { target -> target.displayName }
		assertEquals(names.size, names.toSet().size, "display names are distinct")
		assertTrue(names.none { name -> name.isBlank() }, "display names are non-blank")
	}
}
