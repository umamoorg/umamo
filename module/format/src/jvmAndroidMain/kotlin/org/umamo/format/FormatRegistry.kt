package org.umamo.format

import org.umamo.format.bmp.BmpCodec
import org.umamo.format.clip.ClipReader
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.jpeg.JpegReader
import org.umamo.format.kra.KraReader
import org.umamo.format.moc3.Moc3
import org.umamo.format.png.PngCodec
import org.umamo.format.psd.PsdReader
import org.umamo.format.tiff.TiffReader
import org.umamo.format.uma.Uma
import org.umamo.format.webp.WebPReader

/**
 * The registry of binary container codecs Umamo can read/write, and the entry point for dispatching
 * an unknown file to the right one.
 *
 * Lives in `jvmAndroidMain` because it references [Cmo3], whose JDOM/reflection serializer is
 * JVM-only (it still sees the commonMain [Moc3]). Holds heterogeneous `FormatCodec<*>` since the
 * models share no supertype; a caller does `detect(bytes)?.read(bytes)` and then branches on the
 * returned model type or the codec's [FormatCodec.kind]. Text sidecars (`model3.json` etc.) are
 * not registered here - they are `String`-shaped and live as helpers on [Moc3].
 */
public object FormatRegistry {
	/**
	 * Every registered codec, in priority order (first magic match wins in [detect]): the native UMA
	 * container, the model codecs (CMO3, MOC3), the layered art readers (CLIP, KRA, PSD), and the flat raster
	 * codecs (PNG, BMP, JPEG, WebP, TIFF).  The registry sits in jvmAndroidMain because the CMO3 codec does.
	 * The magics do not collide: UMA and KRA are both ZIPs announcing themselves through a mimetype entry,
	 * but each probe matches only its own mimetype string.  The raster codecs sit last, so BMP's short
	 * 2-byte "BM" magic is checked after the longer signatures.
	 */
	private val codecs: List<FormatCodec<*>> =
		listOf(Uma, Cmo3, Moc3, ClipReader, KraReader, PsdReader, PngCodec, BmpCodec, JpegReader, WebPReader, TiffReader)

	/**
	 * Identifies the codec for [bytes], preferring a reliable magic-byte match and falling back to the
	 * file extension when no codec recognises the leading bytes.
	 *
	 * Magic comes first because it is content-truthful - a mislabelled file still routes correctly.
	 * The extension fallback exists for container formats whose magic is not self-identifying (e.g. a
	 * ZIP-based .kra whose mimetype marker a third-party tool stripped), where the name is the only
	 * remaining signal. [fileName] may be a bare name or a full path; only its extension is read.
	 *
	 * @param ByteArray bytes The file contents to identify.
	 * @param String? fileName The file name or path, used only for the extension fallback.
	 * @return FormatCodec<*>? The matching codec, or null when unrecognised.
	 */
	public fun detect(bytes: ByteArray, fileName: String? = null): FormatCodec<*>? {
		codecs.firstOrNull { codec -> codec.matches(bytes) }?.let { return it }
		val kind = fileName?.let(::kindForFileName) ?: return null
		return forKind(kind)
	}

	/**
	 * Identifies a file kind from its name alone - the extension or one of its aliases, case-insensitively.
	 *
	 * Name-only, so it says nothing about whether the bytes are what the name claims: this answers "which
	 * way in does this path take" for a caller holding a path and no contents (a command-line argument, a
	 * file the OS hands over, a dropped file), and it is the extension fallback [detect] uses once the
	 * magic has not matched.  The chosen route then reads the file and detects its content for real.
	 *
	 * @param String fileName The file name or path; only its extension is read.
	 * @return FileKind? The kind that claims that extension, or null when none does.
	 */
	public fun kindForFileName(fileName: String): FileKind? {
		val extension = fileName.substringAfterLast('.', "").lowercase()
		if (extension.isEmpty()) {
			return null
		}
		return codecs.map { codec -> codec.kind }
			.firstOrNull { kind -> kind.extension == extension || extension in kind.extensionAliases }
	}

	/**
	 * Returns the registered codec for [kind], or null when no codec is wired for it yet (e.g. the
	 * JSON kind, which has no registered codec).
	 *
	 * @param FileKind kind The file kind to look up.
	 * @return FormatCodec<*>? The codec for that kind, or null.
	 */
	public fun forKind(kind: FileKind): FormatCodec<*>? = codecs.firstOrNull { codec -> codec.kind == kind }

	/**
	 * The file extensions Umamo can read, derived from the registered codecs - the single source of
	 * truth for an open-file picker's filter list (so it tracks the registry instead of a frozen list).
	 *
	 * @return List the distinct readable extensions, without the leading dot.
	 */
	public fun readableExtensions(): List<String> =
		codecs.filter { codec -> codec.kind.readable }.map { codec -> codec.kind.extension }.distinct()

	/**
	 * The file extensions of every registered kind that takes [role], aliases included - the single source
	 * of truth for a picker filter over one way in (e.g. Import Artwork's filter, which is every art format
	 * the registry reads and nothing else).
	 *
	 * @param FileRole role The way in to filter by.
	 * @return List the distinct extensions, without the leading dot, in registry order.
	 */
	public fun extensionsFor(role: FileRole): List<String> =
		codecs.map { codec -> codec.kind }
			.filter { kind -> kind.role == role }
			.flatMap { kind -> listOf(kind.extension) + kind.extensionAliases }
			.distinct()
}