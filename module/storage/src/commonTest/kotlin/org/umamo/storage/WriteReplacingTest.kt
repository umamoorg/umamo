package org.umamo.storage

import okio.Buffer
import okio.ForwardingFileSystem
import okio.ForwardingSink
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * Pins the one promise a save makes about the file on disk: it is either the old file or the new one,
 * never something in between, and no temporary is left behind either way.
 */
class WriteReplacingTest {
	private val directory = "/documents".toPath()

	/**
	 * A file system whose [directory] exists.
	 *
	 * @return FakeFileSystem The file system.
	 */
	private fun fileSystem(): FakeFileSystem = FakeFileSystem().also { fileSystem -> fileSystem.createDirectories(directory) }

	@Test
	fun replacesAnExistingFileAndLeavesNoTemporary() {
		val fileSystem = fileSystem()
		val target = directory / "rig.uma"
		fileSystem.write(target) { write(byteArrayOf(1, 2, 3)) }

		writeReplacing(fileSystem, target, byteArrayOf(9, 8, 7, 6))

		assertContentEquals(byteArrayOf(9, 8, 7, 6), fileSystem.read(target) { readByteArray() })
		assertEquals(listOf(target), fileSystem.list(directory), "nothing but the target remains")
	}

	@Test
	fun createsAFileThatDidNotExist() {
		val fileSystem = fileSystem()
		val target = directory / "new.uma"

		writeReplacing(fileSystem, target, byteArrayOf(4, 2))

		assertContentEquals(byteArrayOf(4, 2), fileSystem.read(target) { readByteArray() })
		assertEquals(listOf(target), fileSystem.list(directory))
	}

	/**
	 * A process killed mid-save leaves its temporary behind; the next save of that file sweeps it, and
	 * touches nothing that is not its own.
	 */
	@Test
	fun aLeftoverTemporaryIsSweptByTheNextSaveOfThatFile() {
		val fileSystem = fileSystem()
		val target = directory / "rig.uma"
		val leftover = directory / ".rig.uma.tmp-deadbeef"
		val anothersTemporary = directory / ".other.uma.tmp-cafe"
		val lookAlike = directory / "rig.uma.tmp-notes"
		for (path in listOf(leftover, anothersTemporary, lookAlike)) {
			fileSystem.write(path) { write(byteArrayOf(0)) }
		}

		writeReplacing(fileSystem, target, byteArrayOf(5, 5))

		assertEquals(listOf(anothersTemporary, target, lookAlike).sortedBy { path -> path.name }, fileSystem.list(directory).sortedBy { path -> path.name }, "only this file's own leftover went")
		assertContentEquals(byteArrayOf(5, 5), fileSystem.read(target) { readByteArray() })
	}

	@Test
	fun aFailedWriteLeavesTheOldFileAndNoTemporary() {
		val disk = fileSystem()
		val target = directory / "rig.uma"
		disk.write(target) { write(byteArrayOf(1, 2, 3)) }
		// A disk that fails every write mid-stream: the temporary is opened, the bytes never land.
		val failing =
			object : ForwardingFileSystem(disk) {
				override fun sink(file: Path, mustCreate: Boolean): Sink {
					val sink = super.sink(file, mustCreate)
					return object : ForwardingSink(sink) {
						override fun write(source: Buffer, byteCount: Long): Unit = throw IOException("no space left on device")
					}
				}
			}

		assertFailsWith<IOException> { writeReplacing(failing, target, byteArrayOf(9, 9, 9)) }

		assertContentEquals(byteArrayOf(1, 2, 3), disk.read(target) { readByteArray() }, "the old file is intact")
		assertFalse(disk.list(directory).any { path -> path.name.contains(".tmp-") }, "no temporary is left behind")
	}
}