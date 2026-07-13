package org.umamo.format.clip

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.umamo.format.clip.db.ClipDatabase
import java.nio.file.Files

/**
 * Desktop actual: write the database bytes to a temp file and open the JVM JdbcSqliteDriver over it.
 * The driver and temp file are released in finally blocks.  The embedded DB already has its tables, so
 * we instantiate ClipDatabase(driver) and query WITHOUT calling Schema.create.
 *
 * @param ByteArray databaseBytes The raw "SQLite format 3" bytes.
 * @param Function1 block         Runs against the open database.
 * @return T The block's result.
 */
internal actual fun <T> useClipDatabase(databaseBytes: ByteArray, block: (ClipDatabase) -> T): T {
	val tempDatabasePath = Files.createTempFile("umamo-clip", ".sqlite3")
	try {
		Files.write(tempDatabasePath, databaseBytes)
		val driver = JdbcSqliteDriver("jdbc:sqlite:$tempDatabasePath")
		try {
			return block(ClipDatabase(driver))
		} finally {
			driver.close()
		}
	} finally {
		Files.deleteIfExists(tempDatabasePath)
	}
}
