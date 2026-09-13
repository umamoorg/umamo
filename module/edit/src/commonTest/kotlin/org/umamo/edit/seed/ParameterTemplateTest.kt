package org.umamo.edit.seed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the seed templates: the stored-key round trip and its stale-key fallback, and the humanoid
 * dataset's shape - verbatim standard ids, no repeats - since every Live2D-compatible consumer keys
 * on exactly those ids.
 */
class ParameterTemplateTest {
	@Test
	fun aStoredKeyResolvesAndAStaleOneFallsBackToTheDefault() {
		assertEquals(ParameterTemplate.Humanoid, ParameterTemplate.fromKey("humanoid"))
		assertEquals(ParameterTemplate.None, ParameterTemplate.fromKey("none"))
		assertEquals(ParameterTemplate.Default, ParameterTemplate.fromKey("not-a-template"), "a stale setting falls back to the default")
		assertEquals(ParameterTemplate.Default, ParameterTemplate.fromKey(null), "an absent setting falls back to the default")
		for (template in ParameterTemplate.entries) {
			assertEquals(template, ParameterTemplate.fromKey(template.key), "every entry's key round-trips")
		}
	}

	@Test
	fun noneSeedsNothingAndHumanoidSeedsTheStandardSet() {
		assertTrue(ParameterTemplate.None.parameters.isEmpty())
		assertEquals(HumanoidParameters.list, ParameterTemplate.Humanoid.parameters)
		assertEquals("ParamAngleX", HumanoidParameters.list.first().id.raw, "the standard ids are verbatim")
		assertEquals(
			HumanoidParameters.list.size,
			HumanoidParameters.list.map { parameter -> parameter.id }.toSet().size,
			"no id repeats",
		)
		for (parameter in HumanoidParameters.list) {
			assertTrue(parameter.min < parameter.max, "${parameter.id.raw} is animatable")
			assertTrue(parameter.default in parameter.min..parameter.max, "${parameter.id.raw} rests inside its range")
		}
	}
}