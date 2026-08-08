package org.umamo.format.clip

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.umamo.format.art.SourceLayerKind
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reads a fully synthetic .clip: a SQLite database generated through the SQLDelight schema, wrapped
 * in a hand-assembled CSFCHUNK container.  This is the corpus-free ClipReader coverage - the layer
 * tree, canvas, folder metadata, and placeholder-raster fallback all assert without a real Clip
 * Studio file.  Raster tiles are deliberately absent (synthesizing valid tiled pixel data is a job
 * of its own); layers fall back to the 1x1 transparent placeholder, which is the documented behavior
 * for a layer with no decodable content.
 */
class ClipSyntheticReaderTest {
	/**
	 * Generates a SQLite database with the ClipDatabase schema and the given rows.
	 *
	 * The real reader never runs Schema.create (an embedded database already has its tables); the
	 * FIXTURE runs it precisely because it is building that embedded database from nothing.
	 *
	 * @param List insertStatements Raw INSERT statements to run against the fresh schema.
	 * @return ByteArray The database file's bytes.
	 */
	private fun syntheticDatabaseBytes(insertStatements: List<String>): ByteArray {
		val temporaryDatabasePath = Files.createTempFile("umamo-clip-synthetic", ".sqlite3")
		try {
			val driver = JdbcSqliteDriver("jdbc:sqlite:$temporaryDatabasePath")
			try {
				org.umamo.format.clip.db.ClipDatabase.Schema.create(driver)
				for (statement in insertStatements) {
					driver.execute(null, statement, 0)
				}
			} finally {
				driver.close()
			}
			return Files.readAllBytes(temporaryDatabasePath)
		} finally {
			Files.deleteIfExists(temporaryDatabasePath)
		}
	}

	/**
	 * One Layer INSERT.  Tree links per docs/format/CLIP.md: LayerFirstChildIndex points at the first
	 * (bottom-most) child, LayerNextIndex at the next sibling, 0 terminates.  LayerFolder bit 0x10
	 * marks a folder.
	 *
	 * @param Long    mainId     The layer's MainId.
	 * @param String  name       The layer name.
	 * @param Boolean isFolder   Whether the folder bit is set.
	 * @param Long    visibility LayerVisibility (bit 0 = shown).
	 * @param Long    opacity    LayerOpacity (0..256).
	 * @param Long    composite  LayerComposite blend code (30 = folder Through).
	 * @param Long    offsetX    LayerOffsetX.
	 * @param Long    offsetY    LayerOffsetY.
	 * @param Long    firstChild LayerFirstChildIndex.
	 * @param Long    next       LayerNextIndex.
	 * @param String? uuid       LayerUuid, the stable re-import key.
	 * @return String The INSERT statement.
	 */
	private fun layerInsert(
		mainId: Long,
		name: String,
		isFolder: Boolean = false,
		visibility: Long = 1,
		opacity: Long = 256,
		composite: Long = 0,
		offsetX: Long = 0,
		offsetY: Long = 0,
		firstChild: Long = 0,
		next: Long = 0,
		uuid: String? = null,
	): String {
		val folderBits = if (isFolder) 0x10L else 0L
		val layerType = if (isFolder) 0L else 1L
		val uuidLiteral = if (uuid != null) "'$uuid'" else "NULL"
		return "INSERT INTO Layer (MainId, LayerName, LayerFolder, LayerType, LayerVisibility, LayerOpacity, " +
			"LayerComposite, LayerClip, LayerOffsetX, LayerOffsetY, LayerFirstChildIndex, LayerNextIndex, " +
			"LayerUuid, LayerRenderMipmap, LayerRenderOffscrOffsetX, LayerRenderOffscrOffsetY, " +
			"LayerLayerMaskMipmap, TextLayerType) " +
			"VALUES ($mainId, '$name', $folderBits, $layerType, $visibility, $opacity, $composite, 0, " +
			"$offsetX, $offsetY, $firstChild, $next, $uuidLiteral, NULL, 0, 0, NULL, NULL)"
	}

	/** A canvas with a root folder holding a bottom Paper layer and a Through folder of two layers. */
	private fun syntheticClipBytes(): ByteArray {
		val databaseBytes =
			syntheticDatabaseBytes(
				listOf(
					"INSERT INTO Canvas (CanvasWidth, CanvasHeight, CanvasRootFolder) VALUES (640.0, 480.0, 1)",
					layerInsert(mainId = 1, name = "Root", isFolder = true, firstChild = 2),
					layerInsert(mainId = 2, name = "Paper", next = 3, uuid = "uuid-paper"),
					layerInsert(mainId = 3, name = "Folder1", isFolder = true, composite = 30, firstChild = 4),
					layerInsert(mainId = 4, name = "LayerA", opacity = 128, offsetX = 5, offsetY = 7, next = 5, uuid = "uuid-a"),
					layerInsert(mainId = 5, name = "LayerB", visibility = 2, uuid = "uuid-b"),
				),
			)
		return syntheticClipContainer(
			listOf(
				"CHNKHead" to byteArrayOf(0, 0, 0, 0),
				"CHNKSQLi" to databaseBytes,
				"CHNKFoot" to byteArrayOf(),
			),
		)
	}

	/** The reader assembles canvas, bottom-to-top layer order, folder metadata, and stable ids. */
	@Test
	fun readsTheSyntheticCanvasAndLayerTree() {
		val art = ClipReader.read(syntheticClipBytes())
		assertEquals(640, art.widthPx)
		assertEquals(480, art.heightPx)

		assertEquals(listOf("Paper", "LayerA", "LayerB"), art.layers.map { layer -> layer.name }, "bottom-to-top document order")
		assertEquals(listOf("uuid-paper", "uuid-a", "uuid-b"), art.layers.map { layer -> layer.id.raw }, "LayerUuid is the stable id")
		assertEquals(listOf(2, 1, 0), art.layers.map { layer -> layer.order }, "order counts from the topmost layer")
		assertEquals(listOf("", "Folder1", "Folder1"), art.layers.map { layer -> layer.groupPath })
		assertTrue(art.layers.all { layer -> layer.kind == SourceLayerKind.Raster })

		val layerA = art.layers.single { layer -> layer.name == "LayerA" }
		assertEquals(0.5f, layerA.opacity, "opacity is the 0..256 CLIP scale normalized")
		assertEquals(5, layerA.bounds.left, "a rasterless layer anchors its placeholder at the layer offset")
		assertEquals(7, layerA.bounds.top)
		assertEquals(1, layerA.bounds.width, "no decodable raster falls back to the 1x1 placeholder")
		assertFalse(art.layers.single { layer -> layer.name == "LayerB" }.visible, "visibility bit 0 clear means hidden")

		val folder = art.groups.single { group -> group.path == "Folder1" }
		assertTrue(folder.passThrough, "composite 30 is the folder Through mode")
		assertTrue(folder.visible)
	}

	/** A canvas whose root folder has no rows yields an empty tree rather than failing. */
	@Test
	fun readsACanvasWithNoLayers() {
		val databaseBytes =
			syntheticDatabaseBytes(
				listOf("INSERT INTO Canvas (CanvasWidth, CanvasHeight, CanvasRootFolder) VALUES (320.0, 200.0, 1)"),
			)
		val container =
			syntheticClipContainer(
				listOf(
					"CHNKSQLi" to databaseBytes,
					"CHNKFoot" to byteArrayOf(),
				),
			)
		val art = ClipReader.read(container)
		assertEquals(320, art.widthPx)
		assertEquals(200, art.heightPx)
		assertTrue(art.layers.isEmpty())
		assertTrue(art.groups.isEmpty())
	}
}
