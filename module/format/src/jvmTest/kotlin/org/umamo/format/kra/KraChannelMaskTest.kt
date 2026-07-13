package org.umamo.format.kra

import org.umamo.format.art.ChannelMask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Unit tests for the channelflags to ChannelMask mapping. channelflags is a per-channel enable mask
 * ('0' disables, else enables) in channel-index order, which for RGB is Blue, Green, Red, Alpha and
 * for GRAYA is Gray, Alpha. A disabled alpha is Krita's "Inherit Alpha" (the clipped signal).
 * Values like "1110" are real Krita output - its comic templates ship inherit-alpha layers.
 */
class KraChannelMaskTest {
	private val rgba = KraPixelFormat(channelCount = 4, depth = KraColorDepth.UnsignedByte, isRgb = true)
	private val graya = KraPixelFormat(channelCount = 2, depth = KraColorDepth.UnsignedByte, isRgb = false)

	@Test
	fun emptyOrFullMaskEnablesEverything() {
		assertEquals(ChannelMask.ALL, channelMaskFrom("", rgba))
		assertEquals(ChannelMask.ALL, channelMaskFrom(null, rgba))
		assertEquals(ChannelMask.ALL, channelMaskFrom("1111", rgba))
	}

	@Test
	fun rgbaAlphaOffIsInheritAlpha() {
		// "1110": R,G,B enabled, alpha (index 3) disabled - real Krita comic-template value.
		val mask = channelMaskFrom("1110", rgba)
		assertEquals(ChannelMask(red = true, green = true, blue = true, alpha = false), mask)
		assertFalse(mask.alpha) // the caller maps !alpha onto clipped
	}

	@Test
	fun rgbaColorChannelsFollowBgraIndexOrder() {
		// Index order is Blue(0), Green(1), Red(2), Alpha(3). "0111" disables index 0 = blue.
		assertEquals(ChannelMask(red = true, green = true, blue = false, alpha = true), channelMaskFrom("0111", rgba))
		// "1101" disables index 2 = red.
		assertEquals(ChannelMask(red = false, green = true, blue = true, alpha = true), channelMaskFrom("1101", rgba))
	}

	@Test
	fun grayaGrayChannelMapsToRgbTogether() {
		// GRAYA index order is Gray(0), Alpha(1). "10" = gray on, alpha off.
		assertEquals(ChannelMask(red = true, green = true, blue = true, alpha = false), channelMaskFrom("10", graya))
		// "01" = gray off (so R, G, B all off), alpha on.
		assertEquals(ChannelMask(red = false, green = false, blue = false, alpha = true), channelMaskFrom("01", graya))
	}
}
