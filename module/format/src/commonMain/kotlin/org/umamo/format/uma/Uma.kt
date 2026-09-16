package org.umamo.format.uma

import org.umamo.format.FileKind
import org.umamo.format.FormatCodec
import org.umamo.format.binary.ByteReader
import org.umamo.format.binary.ZipArchive
import org.umamo.format.binary.ZipEntry
import org.umamo.format.binary.ZipFormatException
import org.umamo.format.binary.ZipRecords
import org.umamo.format.binary.ZipWriter

/**
 * Umamo's native project format at the container level: identification, the manifest, the per-entry
 * compatibility classification, and preservation of everything this reader does not own.
 *
 * The domain entries are carried as JSON trees; their schemas are layered on per entry kind.
 *
 * @see <a href="https://docs.umamo.org/format/UMA.md">UMA.md §2 Identification, §3 Manifest</a>
 */
public object Uma : FormatCodec<UmaModel> {
	// UMA §2: a stored first entry named "mimetype" puts its content right after the 30-byte local header
	// and the 8-byte name.
	private const val PROBE_OFFSET = ZipRecords.LOCAL_HEADER_SIZE + 8

	private val MIMETYPE_NAME_BYTES = UmaContainer.MIMETYPE_PATH.encodeToByteArray()

	override val kind: FileKind = FileKind.Uma

	/**
	 * Whether [candidateBytes] start with the stored `mimetype` entry a UMA writer puts first.  Probes fixed
	 * offsets without parsing the archive.
	 *
	 * @param ByteArray candidateBytes Candidate file contents.
	 * @return Boolean True when the file announces itself as UMA.
	 */
	override fun matches(candidateBytes: ByteArray): Boolean {
		val mimetype = UmaContainer.MIMETYPE_BYTES
		if (candidateBytes.size < PROBE_OFFSET + mimetype.size) {
			return false
		}
		val reader = ByteReader(candidateBytes, littleEndian = true)
		// UMA §2 over ZIP's local file header (APPNOTE.TXT 4.3.7): @ +0x00 signature, @ +0x08 method,
		// @ +0x16 uncompressed size, @ +0x1A name length, @ +0x1C extra length, @ +0x1E the name.
		return reader.u32(0) == ZipRecords.LOCAL_HEADER_SIGNATURE.toLong() &&
			reader.u16(8) == ZipRecords.METHOD_STORED &&
			reader.u32(22) == mimetype.size.toLong() &&
			reader.u16(26) == MIMETYPE_NAME_BYTES.size &&
			reader.u16(28) == 0 &&
			regionEquals(candidateBytes, ZipRecords.LOCAL_HEADER_SIZE, MIMETYPE_NAME_BYTES) &&
			regionEquals(candidateBytes, PROBE_OFFSET, mimetype)
	}

	/**
	 * Reads a UMA archive: validates the mimetype and the manifest, classifies every listed entry, and keeps
	 * everything this reader does not understand byte for byte.
	 *
	 * @param ByteArray bytes The whole file.
	 * @return UmaModel The document, possibly read-only.
	 * @throws UmaFormatException When the file cannot be opened as UMA at all.
	 */
	override fun read(bytes: ByteArray): UmaModel {
		val archive =
			try {
				ZipArchive.read(bytes)
			} catch (failure: ZipFormatException) {
				throw UmaFormatException(UmaReadFailure.CorruptContainer(failure.message.orEmpty()), failure)
			}

		// UMA §2: the mimetype entry identifies the file wherever a re-zipping tool left it; the writer puts
		// it back first.
		val mimetypeEntry = archive.entry(UmaContainer.MIMETYPE_PATH) ?: throw UmaFormatException(UmaReadFailure.NotUma("the archive has no mimetype entry"))
		if (!contentsOf(archive, mimetypeEntry).contentEquals(UmaContainer.MIMETYPE_BYTES)) {
			throw UmaFormatException(UmaReadFailure.NotUma("the mimetype entry does not hold ${UmaContainer.MIMETYPE}"))
		}
		val manifestEntry =
			archive.entry(UmaContainer.MANIFEST_PATH)
				?: throw UmaFormatException(UmaReadFailure.MalformedManifest("the archive has no ${UmaContainer.MANIFEST_PATH}"))
		val manifest = parseUmaManifest(contentsOf(archive, manifestEntry))

		// UMA §3.3: classify every listed entry.
		val entries = ArrayList<UmaEntry>(manifest.records.size)
		val readOnlyReasons = ArrayList<UmaReadOnlyReason>()
		for (record in manifest.records) {
			val zipEntry = archive.entry(record.path) ?: throw UmaFormatException(UmaReadFailure.MissingEntry(record.path))
			val knownKind = UmaEntryKind.ofWireName(record.kind)
			val content =
				if (knownKind != null && knownKind.version >= record.minVersion) {
					val tree = parseJsonObject(contentsOf(archive, zipEntry)) { detail -> UmaReadFailure.MalformedEntry(record.path, detail) }
					UmaEntryContent.Live(knownKind, tree)
				} else {
					if (record.required) {
						val cause =
							if (knownKind == null) {
								UmaReadOnlyCause.UnknownKind
							} else {
								UmaReadOnlyCause.UnsupportedVersion(record.minVersion, knownKind.version)
							}
						readOnlyReasons += UmaReadOnlyReason(record.path, record.kind, cause)
					}
					UmaEntryContent.Preserved(UmaRawEntry(zipEntry, archive.rawPayload(zipEntry)))
				}
			entries += UmaEntry(record.path, record.kind, record.version, record.minVersion, record.required, record.record, content)
		}

		// UMA §3.3: every other entry is a payload, kept exactly; nothing is garbage-collected.
		val listedPaths = manifest.records.mapTo(HashSet()) { record -> record.path }
		val archiveOrder = ArrayList<String>(archive.entries.size)
		val payloads = ArrayList<UmaPayload>()
		for (zipEntry in archive.entries) {
			if (UmaContainer.isReservedPath(zipEntry.name)) {
				continue
			}
			archiveOrder += zipEntry.name
			if (zipEntry.name !in listedPaths) {
				payloads += UmaPayload(zipEntry.name, UmaRawEntry(zipEntry, archive.rawPayload(zipEntry)))
			}
		}
		val model = UmaModel(manifest.writer, entries, payloads, readOnlyReasons, manifest.tree, archiveOrder)
		// UMA §4.8: decoding the puppet entry here makes a malformed one fail the read, not a later access.
		model.puppet
		return model
	}

	/**
	 * Writes [model] as a UMA archive: the mimetype first and stored, the manifest second, then every other
	 * entry in the order the document holds them, all stamped with the fixed epoch.
	 *
	 * @param UmaModel model The document.
	 * @return ByteArray The archive.
	 * @throws IllegalStateException When the document is read-only.
	 */
	override fun write(model: UmaModel): ByteArray {
		check(!model.isReadOnly) {
			"a read-only UMA document cannot be written: " +
				model.readOnlyReasons.joinToString { reason -> "'${reason.path}' (${reason.kind}): ${reason.cause}" }
		}
		// UMA §3.5: one fixed stamp on every entry, so the same document always writes the same bytes.
		val writer = ZipWriter(ZipRecords.DOS_EPOCH_DATE_TIME)
		writer.addStored(UmaContainer.MIMETYPE_PATH, UmaContainer.MIMETYPE_BYTES)
		writer.addDeflated(UmaContainer.MANIFEST_PATH, emitUmaManifest(model))

		val entryByPath = model.entries.associateBy { entry -> entry.path }
		val payloadByPath = model.payloads.associateBy { payload -> payload.path }
		val writtenPaths = HashSet<String>()

		/**
		 * Writes the entry or payload at [path] once.
		 *
		 * @param String path The entry path.
		 */
		fun writePath(path: String) {
			if (!writtenPaths.add(path)) {
				return
			}
			val entry = entryByPath[path]
			if (entry != null) {
				when (val content = entry.content) {
					is UmaEntryContent.Live -> writer.addDeflated(path, encodeUmaJson(content.tree))
					is UmaEntryContent.Preserved -> writer.addRaw(content.raw.zipEntry, content.raw.rawPayload)
				}
				return
			}
			payloadByPath[path]?.let { payload -> writer.addRaw(payload.raw.zipEntry, payload.raw.rawPayload) }
		}

		// UMA §3.5: the archive's own order first, then entries new to this document in manifest order, then
		// any payloads not yet written.
		for (path in model.archiveOrder) {
			writePath(path)
		}
		for (entry in model.entries) {
			writePath(entry.path)
		}
		for (payload in model.payloads) {
			writePath(payload.path)
		}
		return writer.finish()
	}

	/**
	 * An entry's contents, with a ZIP-level failure reported as a corrupt container.
	 *
	 * @param ZipArchive archive The archive.
	 * @param ZipEntry   entry   The entry.
	 * @return ByteArray The entry's uncompressed bytes.
	 */
	private fun contentsOf(archive: ZipArchive, entry: ZipEntry): ByteArray =
		try {
			archive.contents(entry)
		} catch (failure: ZipFormatException) {
			throw UmaFormatException(UmaReadFailure.CorruptContainer(failure.message.orEmpty()), failure)
		}

	/**
	 * Whether [expected] occurs in [bytes] at [offset].
	 *
	 * @param ByteArray bytes    The buffer.
	 * @param Int       offset   Where the region starts.
	 * @param ByteArray expected The bytes to find there.
	 * @return Boolean True when every byte matches.
	 */
	private fun regionEquals(bytes: ByteArray, offset: Int, expected: ByteArray): Boolean {
		if (offset + expected.size > bytes.size) {
			return false
		}
		for (byteIndex in expected.indices) {
			if (bytes[offset + byteIndex] != expected[byteIndex]) {
				return false
			}
		}
		return true
	}
}