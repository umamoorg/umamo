package org.umamo.format.clip

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import org.umamo.format.clip.db.ClipDatabase
import java.io.File

/**
 * Android app hook for the CLIP reader.  Set [applicationContext] once at startup (from your
 * Application or Activity, the same way the app calls FileKit.init): the Android SQLite driver needs
 * a Context to locate a database file, but the format-neutral ArtReader.read(bytes) has none, so it
 * is supplied here.  Prefer the application context to avoid leaking an Activity.
 *
 * Android 用フック。起動時に applicationContext を一度設定する（Context が必要なため）。
 */
object ClipAndroid {
	// TODO(android-clip): currently assigned nowhere - the Android app has no file-open flow yet, so
	// CLIP is unreachable there and this hook is inert. When Android gains an open path, set
	// `ClipAndroid.applicationContext = applicationContext` in MainActivity.onCreate (where a future
	// FileKit.init(this) will also go), or reading a .clip will fail the error() in useClipDatabase.
	@Volatile
	var applicationContext: Context? = null
}

/**
 * Android actual: write the database bytes into the app's databases dir and open the SQLDelight
 * AndroidSqliteDriver over it.  A no-op callback skips schema creation/migration - the embedded .clip
 * database already has its tables (and carries user_version 0, which would otherwise trigger
 * onCreate -> Schema.create and collide with the existing tables).
 *
 * @param ByteArray databaseBytes The raw "SQLite format 3" bytes.
 * @param Function1 block         Runs against the open database.
 * @return T The block's result.
 */
internal actual fun <T> useClipDatabase(databaseBytes: ByteArray, block: (ClipDatabase) -> T): T {
	val context =
		ClipAndroid.applicationContext
			?: error("ClipReader on Android requires ClipAndroid.applicationContext to be set at startup")

	// A short-lived DB under the app's databases dir; AndroidSqliteDriver(name) opens the same path.
	val name = "umamo-clip-${System.nanoTime()}.sqlite3"
	val databaseFile = context.getDatabasePath(name)
	databaseFile.parentFile?.mkdirs()
	databaseFile.writeBytes(databaseBytes)
	try {
		// Skip Schema.create/migrate: the file already is a complete CLIP database.
		val callback =
			object : AndroidSqliteDriver.Callback(ClipDatabase.Schema) {
				override fun onCreate(db: SupportSQLiteDatabase) = Unit

				override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
			}
		val driver = AndroidSqliteDriver(schema = ClipDatabase.Schema, context = context, name = name, callback = callback)
		try {
			return block(ClipDatabase(driver))
		} finally {
			driver.close()
		}
	} finally {
		databaseFile.delete()
		// SQLite may create sidecar journal/WAL files next to the database; remove them too.
		File("${databaseFile.path}-wal").delete()
		File("${databaseFile.path}-shm").delete()
		File("${databaseFile.path}-journal").delete()
	}
}
