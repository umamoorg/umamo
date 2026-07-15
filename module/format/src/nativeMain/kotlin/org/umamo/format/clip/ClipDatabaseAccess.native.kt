package org.umamo.format.clip

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import org.umamo.format.clip.db.ClipDatabase
import kotlin.random.Random

/**
 * Kotlin/Native actual: write the database bytes to a temp file and open SQLDelight's
 * [NativeSqliteDriver] (SQLiter) over it.
 *
 * This is the iPadOS path, and it is the one native actual worth having: Clip Studio Paint runs on
 * iPad, so ingesting a `.clip` is exactly what a tablet build is for.
 *
 * Two details are load-bearing:
 *  - SQLiter opens a database by NAME within a directory, not by arbitrary path, so the blob has to
 *    be written somewhere and the configuration pointed at that directory.
 *  - Schema creation must be defeated. The convenience `NativeSqliteDriver(schema, name)` constructor
 *    runs Schema.create when user_version is 0 — and an embedded .clip database carries user_version 0
 *    while already having every table, so it would collide. The Android actual documents the same
 *    trap. Hence the DatabaseConfiguration overload with no-op create/upgrade.
 *
 * @param ByteArray databaseBytes The raw "SQLite format 3" bytes.
 * @param Function1 block         Runs against the open database.
 * @return T The block's result.
 */
internal actual fun <T> useClipDatabase(databaseBytes: ByteArray, block: (ClipDatabase) -> T): T {
	val fileSystem = FileSystem.SYSTEM
	val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY
	fileSystem.createDirectories(directory)
	val databasePath = createTemporaryDatabase(fileSystem, directory, databaseBytes)
	try {
		val configuration =
			DatabaseConfiguration(
				// SQLiter addresses the file as (basePath, name), so the two are split rather than joined.
				name = databasePath.name,
				version = ClipDatabase.Schema.version.toInt(),
				// Both no-ops, deliberately: the file already IS a complete CLIP database, so creating
				// or migrating it would collide with the tables it arrived with.
				create = {},
				upgrade = { _, _, _ -> },
				extendedConfig = DatabaseConfiguration.Extended(basePath = directory.toString()),
			)
		val driver = NativeSqliteDriver(configuration)
		try {
			return block(ClipDatabase(driver))
		} finally {
			driver.close()
		}
	} finally {
		fileSystem.delete(databasePath, mustExist = false)
		// SQLite may leave journal/WAL sidecars beside the database; take them with it.
		for (suffix in listOf("-wal", "-shm", "-journal")) {
			fileSystem.delete("$databasePath$suffix".toPath(), mustExist = false)
		}
	}
}

/**
 * Writes [databaseBytes] to a freshly created file in [directory] and returns its path.
 *
 * The name is random per call rather than derived from the content, which is what the sibling actuals
 * guarantee too (JVM `Files.createTempFile`, Android `System.nanoTime()`).  A content-derived name
 * would not: two concurrent reads of the same `.clip` would resolve to one path, so each would
 * truncate the database the other had open and then delete it mid-query.
 *
 * `mustCreate` claims the name atomically (O_EXCL), so the retry closes that race rather than merely
 * narrowing it.
 *
 * @param FileSystem fileSystem   The filesystem to create on.
 * @param Path directory          The directory to create in.
 * @param ByteArray databaseBytes The bytes to write.
 * @return Path The created file.
 */
private fun createTemporaryDatabase(fileSystem: FileSystem, directory: Path, databaseBytes: ByteArray): Path {
	while (true) {
		val candidate = directory / "umamo-clip-${Random.nextLong().toULong().toString(16)}.sqlite3"
		// Only the claim retries. A failure to WRITE is a real error (a full or read-only temp dir)
		// and propagates — looping on it would spin forever laying down files.
		val handle =
			try {
				fileSystem.openReadWrite(candidate, mustCreate = true)
			} catch (_: IOException) {
				// The name is taken — vanishingly unlikely across 64 random bits, but draw again
				// rather than clobber a file another reader is holding open.
				continue
			}
		try {
			handle.write(0L, databaseBytes, 0, databaseBytes.size)
		} finally {
			handle.close()
		}
		return candidate
	}
}
