package org.umamo.format

/**
 * The file families Umamo understands, plus the art-source formats it ingests.
 *
 * Modelled as a `sealed interface` so a `when` over kinds is exhaustive (the compiler rejects an
 * unhandled case) - the same enforcement applied to [Cmo3Version]. Each kind declares its
 * capabilities ([readable]/[writable]) and its priority [tier], so dispatch and UI can reason
 * about support without a scattered list of booleans.
 */
sealed interface FileKind {
	/** Conventional file extension without the dot, e.g. `"cmo3"`. */
	val extension: String

	/** Whether Umamo can parse this format today. */
	val readable: Boolean

	/** Whether Umamo can emit this format today. */
	val writable: Boolean

	// --- Editor source: Read AND write. ---
	data object Uma : FileKind {
		override val extension = "uma"
		override val readable = true
		override val writable = true
	}

	// --- Editor source: the adoption wedge. Read AND write. ---
	data object Cmo3 : FileKind {
		override val extension = "cmo3"
		override val readable = true
		override val writable = true
	}

	// --- Layered art ingestion. ---
	data object Psd : FileKind {
		override val extension = "psd"
		override val readable = true
		override val writable = false
	}

	// --- Art ingestion with stable layer IDs. ---
	data object Clip : FileKind {
		override val extension = "clip"
		override val readable = true
		override val writable = false
	}

	// --- Krita source. ---
	data object Kra : FileKind {
		override val extension = "kra"
		override val readable = true
		override val writable = false
	}

	// --- Runtime model, serialized C structs. ---
	data object Moc3 : FileKind {
		override val extension = "moc3"
		override val readable = true
		override val writable = true
	}

	// --- Runtime JSON family - model3/physics3/cdi3/pose3/exp3/motion3/userdata3. ---
	data object Json : FileKind {
		override val extension = "json"
		override val readable = true
		override val writable = true
	}
}
