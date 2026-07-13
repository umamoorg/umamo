package org.umamo.format.art

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the format-agnostic visibility cascade ([SourceArt.isEffectivelyVisible]) - the
 * single rule every source-art renderer shares for deciding whether a layer is drawn. These exercise
 * the cascade purely from the neutral model, so they need no corpus file and run on every platform.
 */
class SourceArtVisibilityTest {
	/** A minimal SourceLayer whose only behaviour under test is its visibility and group path. */
	private class FakeLayer(
		override val name: String,
		override val visible: Boolean,
		override val groupPath: String,
	) : SourceLayer {
		override val id: LayerId = LayerId(name)
		override val order: Int = 0
		override val bounds: LayerBounds = LayerBounds(0, 0, 1, 1)
		override val opacity: Float = 1f
		override val clipped: Boolean = false
		override val blend: LayerBlend = LayerBlend.Normal
		override val raster: LayerRaster = LayerRaster(1, 1, ByteArray(4))
	}

	/** A minimal SourceGroup carrying only path + visibility. */
	private class FakeGroup(
		override val path: String,
		override val visible: Boolean,
	) : SourceGroup {
		override val name: String = path.substringAfterLast('/')
		override val opacity: Float = 1f
		override val clipped: Boolean = false
		override val blend: LayerBlend = LayerBlend.Normal
		override val passThrough: Boolean = false
	}

	/** A SourceArt over the given layers and groups. */
	private class FakeArt(
		override val layers: List<SourceLayer>,
		override val groups: List<SourceGroup>,
	) : SourceArt {
		override val widthPx: Int = 1
		override val heightPx: Int = 1
	}

	@Test
	fun visibleLayerWithNoGroupsIsShown() {
		val layer = FakeLayer(name = "leaf", visible = true, groupPath = "")
		val art = FakeArt(layers = listOf(layer), groups = emptyList())
		assertTrue(art.isEffectivelyVisible(layer))
	}

	@Test
	fun hiddenLayerIsNotShownRegardlessOfGroups() {
		val layer = FakeLayer(name = "leaf", visible = false, groupPath = "Folder")
		val art = FakeArt(layers = listOf(layer), groups = listOf(FakeGroup("Folder", visible = true)))
		assertFalse(art.isEffectivelyVisible(layer))
	}

	@Test
	fun layerInsideHiddenFolderIsNotShown() {
		val layer = FakeLayer(name = "leaf", visible = true, groupPath = "Folder")
		val art = FakeArt(layers = listOf(layer), groups = listOf(FakeGroup("Folder", visible = false)))
		assertFalse(art.isEffectivelyVisible(layer))
	}

	@Test
	fun layerIsHiddenWhenAnyAncestorFolderIsHidden() {
		// The immediate folder is visible, but its grandparent is hidden - the cascade must walk up.
		val layer = FakeLayer(name = "leaf", visible = true, groupPath = "A/B/C")
		val art =
			FakeArt(
				layers = listOf(layer),
				groups =
					listOf(
						FakeGroup("A", visible = false),
						FakeGroup("A/B", visible = true),
						FakeGroup("A/B/C", visible = true),
					),
			)
		assertFalse(art.isEffectivelyVisible(layer))
	}

	@Test
	fun layerInsideFullyVisibleNestedFoldersIsShown() {
		val layer = FakeLayer(name = "leaf", visible = true, groupPath = "A/B/C")
		val art =
			FakeArt(
				layers = listOf(layer),
				groups =
					listOf(
						FakeGroup("A", visible = true),
						FakeGroup("A/B", visible = true),
						FakeGroup("A/B/C", visible = true),
					),
			)
		assertTrue(art.isEffectivelyVisible(layer))
	}
}
