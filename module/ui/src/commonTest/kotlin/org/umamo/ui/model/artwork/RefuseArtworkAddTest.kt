package org.umamo.ui.model.artwork

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.umamo.edit.EditorSession
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayer
import org.umamo.interop.art.ArtSourceDescriptor
import org.umamo.interop.art.SourceArtImportOptions
import org.umamo.render.SourceArtRasters
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.model.repack.AtlasRepackHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins which documents refuse Import Artwork: one with a drawable that has no tile renders that drawable from
 * the atlas pages alone, and the add's pack would replace those pages, so the add changes nothing and says why.
 */
class RefuseArtworkAddTest {
	private val tileId = AtlasTileId("tile")

	@Test
	fun aDrawableWithNoTileRefusesTheAdd() =
		runTest {
			val session = EditorSession(modelOf(atlasTileId = null))
			val before = session.model.value

			assertFalse(runAddArtwork(hostOf(session, this), requestOf(), areaId = null), "the add is refused")
			assertSame(before, session.model.value, "the document is untouched")
			assertEquals("notice.import.bakedAtlas", session.notice.value?.messageKey)
		}

	@Test
	fun aDocumentWhoseDrawablesAllHaveTilesTakesTheAdd() {
		val session = EditorSession(modelOf(atlasTileId = tileId))

		assertFalse(refuseArtworkAdd(session))
		assertNull(session.notice.value, "nothing is said")
	}

	@Test
	fun aDocumentWithNoDrawablesTakesTheAdd() {
		val session = EditorSession(modelOf(atlasTileId = tileId).copy(drawables = emptyList(), rootChildren = emptyList()))

		assertFalse(refuseArtworkAdd(session))
	}

	@Test
	fun oneDrawableWithNoTileAmongBoundOnesRefuses() {
		val bound = modelOf(atlasTileId = tileId)
		val baked = bound.drawables.single().copy(id = DrawableId("baked"), atlasTileId = null)
		val session = EditorSession(bound.copy(drawables = bound.drawables + baked, rootChildren = bound.rootChildren + OrgChild.Drawable(baked.id)))

		assertTrue(refuseArtworkAdd(session))
	}

	/**
	 * A document of one drawable, bound to the one tile or to none.
	 *
	 * @param AtlasTileId? atlasTileId The drawable's tile, or null for a drawable that renders from the pages alone.
	 * @return PuppetModel The model.
	 */
	private fun modelOf(atlasTileId: AtlasTileId?): PuppetModel {
		val drawable =
			Drawable(
				id = DrawableId("drawable"),
				name = "Drawable",
				parentDeformerId = null,
				blendMode = BlendMode.Normal,
				maskedBy = emptyList(),
				mesh = null,
				geometryGrid = null,
				atlasTileId = atlasTileId,
			)

		return PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = listOf(drawable),
			rootChildren = listOf(OrgChild.Drawable(drawable.id)),
			rootPartId = null,
			atlas = PuppetAtlas(pages = emptyList(), tiles = if (atlasTileId == null) emptyList() else listOf(AtlasTile(tileId, "Tile", 16, 16))),
		)
	}

	/**
	 * A host over [session] with no pixels and no page resolver, since a refused add reads neither.
	 *
	 * @param EditorSession session The session.
	 * @param CoroutineScope scope  Where an adjustment would launch.
	 * @return AtlasRepackHost The host.
	 */
	private fun hostOf(session: EditorSession, scope: CoroutineScope): AtlasRepackHost =
		AtlasRepackHost(
			session = session,
			artRasters = SourceArtRasters { null },
			sessionAtlasPages = null,
			premultipliedAlpha = false,
			scope = scope,
			report = { report -> error("a refused add reports no pack: $report") },
			rememberOptions = { _, _ -> },
		)

	/**
	 * A request for a file with no layers, which a refused add never reads.
	 *
	 * @return AddArtworkRequest The request.
	 */
	private fun requestOf(): AddArtworkRequest =
		AddArtworkRequest(
			object : SourceArt {
				override val layers: List<SourceLayer> = emptyList()
				override val widthPx: Int = 64
				override val heightPx: Int = 64
			},
			ArtSourceDescriptor("art.psd", "art.psd", "psd"),
			SourceArtImportOptions(),
		)
}