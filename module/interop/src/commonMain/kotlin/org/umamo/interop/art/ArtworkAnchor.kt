package org.umamo.interop.art

/**
 * Where a later artwork file's canvas is anchored on the document canvas when it is added: the nine
 * anchors the art programs' own Canvas Size dialogs offer, so a file whose canvas the artist resized
 * around some anchor lands back where the art was drawn.  A file the same size as the document canvas
 * lands identically under every anchor.
 *
 * @property String key        The stable setting and strip value.
 * @property Float  horizontal How far along the document's width the file's width is anchored (0 left, 0.5 center, 1 right).
 * @property Float  vertical   How far along the document's height the file's height is anchored (0 top, 0.5 middle, 1 bottom).
 */
enum class ArtworkAnchor(val key: String, val horizontal: Float, val vertical: Float) {
	TopLeft("topLeft", 0f, 0f),
	Top("top", 0.5f, 0f),
	TopRight("topRight", 1f, 0f),
	Left("left", 0f, 0.5f),
	Center("center", 0.5f, 0.5f),
	Right("right", 1f, 0.5f),
	BottomLeft("bottomLeft", 0f, 1f),
	Bottom("bottom", 0.5f, 1f),
	BottomRight("bottomRight", 1f, 1f),
	;

	companion object {
		/** The anchor a fresh install adds with: the art programs' own default when a canvas is resized. */
		val Default: ArtworkAnchor = Center

		/**
		 * The anchor a setting or strip value names, or [Default] for a value no anchor carries.
		 *
		 * @param String? key The value.
		 * @return ArtworkAnchor The anchor.
		 */
		fun fromKey(key: String?): ArtworkAnchor = entries.firstOrNull { anchor -> anchor.key == key } ?: Default
	}
}