package org.umamo.format.clip

import org.umamo.format.clip.db.ClipDatabase

/**
 * Opens a SQLDelight driver over the extracted .clip SQLite [databaseBytes], runs [block] against the
 * resulting [ClipDatabase], and tears the driver (and any temp file) down afterward.
 *
 * The driver is the only platform-specific piece - JVM uses JdbcSqliteDriver, Android uses
 * AndroidSqliteDriver - so it lives behind this expect/actual seam while the rest of the reader stays
 * platform-neutral.  The embedded database already has its tables, so the actuals must NOT run
 * Schema.create.
 *
 * @param ByteArray databaseBytes The raw "SQLite format 3" bytes (the CHNKSQLi payload).
 * @param Function1 block         Runs against the open database and returns the parsed result.
 * @return T The block's result.
 */
internal expect fun <T> useClipDatabase(databaseBytes: ByteArray, block: (ClipDatabase) -> T): T
