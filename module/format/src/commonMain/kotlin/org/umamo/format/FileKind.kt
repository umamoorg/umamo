package org.umamo.format

/**
 * What the editor does with a file, as the file's kind declares it.
 *
 * The distinction is not about which codec reads the bytes - it is about which way in a file takes.  A
 * document REPLACES what is open (behind the unsaved-changes gate); artwork is ADDED to the document
 * that is already open, the way importing an object into a Blender scene adds to it.  A sidecar is
 * neither: it is found beside a file the editor opened, never handed over on its own.
 */
enum class FileRole {
	/** Opens as the whole document: the native `.uma`, and the CMO3 / MOC3 the editor imports. */
	Document,

	/** Ingested as source art into the open document: the layered formats and the flat rasters. */
	Artwork,

	/** Read beside a document, never opened as one: the MOC3 JSON family. */
	Sidecar,
}

/**
 * The file families Umamo understands, plus the art-source formats it ingests.
 *
 * Modelled as a `sealed interface` so a `when` over kinds is exhaustive (the compiler rejects an
 * unhandled case) - the same enforcement applied to [Cmo3Version]. Each kind declares its
 * capabilities ([readable]/[writable]/[role]), so dispatch and UI can reason about support without a
 * scattered list of booleans, and its [extension] (with any [extensionAliases]), so nothing else
 * spells the extension by hand.
 */
sealed interface FileKind {
	/** Conventional file extension without the dot, e.g. `"cmo3"`. */
	val extension: String

	/**
	 * Other extensions the same format is written under, without the dot.
	 *
	 * Detection is by magic bytes, so an alias only matters where the name is the only signal - the
	 * extension fallback in [FormatRegistry.kindForFileName] and the picker filters built from it.
	 */
	val extensionAliases: List<String> get() = emptyList()
	val readable: Boolean
	val writable: Boolean

	/** Which way into the editor a file of this kind takes. */
	val role: FileRole

	// --- Umamo UMA!~ ---
	data object Uma : FileKind {
		override val extension = "uma"
		override val readable = true
		override val writable = true
		override val role = FileRole.Document
	}

	// --- Live2D Cubism CMO3 Puppet Model ---
	data object Cmo3 : FileKind {
		override val extension = "cmo3"
		override val readable = true
		override val writable = true
		override val role = FileRole.Document
	}

	// --- Live2D Cubism MOC3 Puppet Model ---
	data object Moc3 : FileKind {
		override val extension = "moc3"
		override val readable = true
		override val writable = true
		override val role = FileRole.Document
	}

	// --- Live2D Cubism MOC3 Puppet Model Sidecar Files - model3/physics3/cdi3/pose3/exp3/motion3/userdata3. ---
	data object Json : FileKind {
		override val extension = "json"
		override val readable = true
		override val writable = true
		override val role = FileRole.Sidecar
	}

	// --- Adobe Photoshop PSD Layered Artwork ---
	data object Psd : FileKind {
		override val extension = "psd"
		override val readable = true
		override val writable = false
		override val role = FileRole.Artwork
	}

	// --- Clip Studio Paint CLIP Layered Artwork ---
	data object Clip : FileKind {
		override val extension = "clip"
		override val readable = true
		override val writable = false
		override val role = FileRole.Artwork
	}

	// --- Krita KRA Layered Artwork ---
	data object Kra : FileKind {
		override val extension = "kra"
		override val readable = true
		override val writable = false
		override val role = FileRole.Artwork
	}

	// --- PNG Raster Artwork ---
	data object Png : FileKind {
		override val extension = "png"
		override val readable = true
		override val writable = true
		override val role = FileRole.Artwork
	}

	// --- BMP Raster Artwork ---
	data object Bmp : FileKind {
		override val extension = "bmp"
		override val readable = true
		override val writable = true
		override val role = FileRole.Artwork
	}

	// --- JPEG Raster Artwork ---
	data object Jpeg : FileKind {
		override val extension = "jpg"
		override val extensionAliases = listOf("jpeg")
		override val readable = true
		override val writable = false
		override val role = FileRole.Artwork
	}

	// --- WEBP Raster Artwork ---
	data object WebP : FileKind {
		override val extension = "webp"
		override val readable = true
		override val writable = false
		override val role = FileRole.Artwork
	}

	// --- TIFF Raster Artwork ---
	data object Tiff : FileKind {
		override val extension = "tiff"
		override val extensionAliases = listOf("tif")
		override val readable = true
		override val writable = false
		override val role = FileRole.Artwork
	}
}