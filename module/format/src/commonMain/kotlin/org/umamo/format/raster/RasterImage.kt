package org.umamo.format.raster

/**
 * A whole decoded flat image: RGBA8888, straight (non-premultiplied) alpha, row-major from the top.
 *
 * The in-memory model of the flat-raster codec family (PNG, BMP, JPEG, WebP, TIFF), the neutral
 * pixel form fixed by the art-sourcing roadmap invariant #6.  It is field-compatible with the
 * layered readers' [org.umamo.format.art.LayerRaster] and the renderer's DecodedImage, so the
 * adapters between them are trivial copies (see [rasterToSourceArt]).
 *
 * A plain class, not a `data class`: it wraps a large [rgba] buffer for which a generated structural
 * equals/hashCode would be a footgun (a deep array compare on every call).  Identity equality is the
 * right default - the same rationale as [org.umamo.format.art.LayerRaster].
 */
public class RasterImage(
	public val width: Int,
	public val height: Int,
	public val rgba: ByteArray,
)
