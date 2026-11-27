package org.umamo.interop.art.mesh

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The presets' stored keys: each key finds its own preset again, the keys are distinct, and an unknown
 * or missing key falls back to the default.
 */
class ArtMeshPresetTest {
	@Test
	fun everyKeyFindsItsOwnPreset() {
		for (preset in ArtMeshPreset.entries) {
			assertEquals(preset, ArtMeshPreset.fromKey(preset.key))
		}

		assertEquals(ArtMeshPreset.entries.size, ArtMeshPreset.entries.map { preset -> preset.key }.toSet().size)
	}

	@Test
	fun anUnknownOrMissingKeyIsTheDefault() {
		assertEquals(ArtMeshPreset.Standard, ArtMeshPreset.Default)
		assertEquals(ArtMeshPreset.Default, ArtMeshPreset.fromKey("dense"))
		assertEquals(ArtMeshPreset.Default, ArtMeshPreset.fromKey(null))
	}
}