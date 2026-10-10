package org.umamo.cli

import org.umamo.format.art.LayerRaster
import org.umamo.format.raster.RasterImage
import org.umamo.geometry.mesh.PlanarTriangleMesh
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/*
 * A mesh drawn over the art it was generated from, for a human to judge: the layer on a checkerboard,
 * interior edges in blue, boundary edges (the outline) in magenta, vertices as yellow dots, pins red.
 * The canvas grows past the raster wherever the mesh does, since an outline runs outside the art.
 */

/** Checkerboard square side, in canvas pixels before scaling. */
private const val CHECKER_SIDE = 8

/** Canvas pixels kept around the mesh and raster. */
private const val CANVAS_MARGIN = 2

/**
 * Renders a mesh over its layer raster.
 *
 * @param LayerRaster        raster The layer's pixels.
 * @param PlanarTriangleMesh mesh   The mesh, in the raster's pixels.
 * @param Int                scale  Whole-number magnification (at least 1).
 * @return RasterImage? The picture, or null when it would exceed the preview cap.
 */
internal fun renderWireframe(raster: LayerRaster, mesh: PlanarTriangleMesh, scale: Int): RasterImage? {
	var minimumX = 0.0
	var minimumY = 0.0
	var maximumX = raster.width.toDouble()
	var maximumY = raster.height.toDouble()

	for (vertex in 0 until mesh.vertexCount) {
		minimumX = minOf(minimumX, mesh.positions[2 * vertex].toDouble())
		minimumY = minOf(minimumY, mesh.positions[2 * vertex + 1].toDouble())
		maximumX = maxOf(maximumX, mesh.positions[2 * vertex].toDouble())
		maximumY = maxOf(maximumY, mesh.positions[2 * vertex + 1].toDouble())
	}

	val originX = floor(minimumX).toInt() - CANVAS_MARGIN
	val originY = floor(minimumY).toInt() - CANVAS_MARGIN
	val width = (ceil(maximumX).toInt() + CANVAS_MARGIN - originX) * scale
	val height = (ceil(maximumY).toInt() + CANVAS_MARGIN - originY) * scale

	if (width.toLong() * height.toLong() > PREVIEW_PIXEL_CAP) {
		return null
	}

	val canvas = Canvas(width, height)
	canvas.paintBackground(raster, originX, originY, scale)
	val directed = HashSet<Long>()

	for (entry in mesh.triangles.indices) {
		directed.add(edgeKey(mesh.triangles[entry], mesh.triangles[nextCorner(entry)]))
	}

	for (entry in mesh.triangles.indices) {
		val start = mesh.triangles[entry]
		val end = mesh.triangles[nextCorner(entry)]
		val isBoundary = edgeKey(end, start) !in directed

		// Each interior edge appears twice; draw it once.
		if (!isBoundary && start > end) {
			continue
		}

		val color = if (isBoundary) BOUNDARY_COLOR else INTERIOR_COLOR
		canvas.line(
			toCanvas(mesh.positions[2 * start], originX, scale),
			toCanvas(mesh.positions[2 * start + 1], originY, scale),
			toCanvas(mesh.positions[2 * end], originX, scale),
			toCanvas(mesh.positions[2 * end + 1], originY, scale),
			color,
		)
	}

	for (vertex in 0 until mesh.vertexCount) {
		canvas.dot(toCanvas(mesh.positions[2 * vertex], originX, scale), toCanvas(mesh.positions[2 * vertex + 1], originY, scale), 1, VERTEX_COLOR)
	}

	for (vertex in mesh.pinVertices) {
		canvas.dot(toCanvas(mesh.positions[2 * vertex], originX, scale), toCanvas(mesh.positions[2 * vertex + 1], originY, scale), 2, PIN_COLOR)
	}

	return RasterImage(width, height, canvas.rgba)
}

/**
 * Maps a raster coordinate to a canvas pixel.
 *
 * @param Float value  The coordinate.
 * @param Int   origin The canvas origin in raster pixels.
 * @param Int   scale  The magnification.
 * @return Int The canvas pixel.
 */
private fun toCanvas(value: Float, origin: Int, scale: Int): Int = ((value - origin) * scale).roundToInt()

/**
 * The next corner of the same triangle.
 *
 * @param Int entry An index into a triangle array.
 * @return Int The next corner's index.
 */
private fun nextCorner(entry: Int): Int = if (entry % 3 == 2) entry - 2 else entry + 1

/**
 * Packs a directed edge into one Long.
 *
 * @param Int start The start vertex.
 * @param Int end   The end vertex.
 * @return Long The key.
 */
private fun edgeKey(start: Int, end: Int): Long = (start.toLong() shl 32) or (end.toLong() and 0xFFFFFFFFL)

/** Interior edges. */
private val INTERIOR_COLOR = intArrayOf(40, 150, 255)

/** Boundary edges: the outline. */
private val BOUNDARY_COLOR = intArrayOf(255, 0, 170)

/** Vertices. */
private val VERTEX_COLOR = intArrayOf(255, 220, 0)

/** Pins. */
private val PIN_COLOR = intArrayOf(255, 40, 40)

/**
 * An opaque RGBA canvas with the few drawing operations a preview needs.
 *
 * @property Int width  The width in pixels.
 * @property Int height The height in pixels.
 */
private class Canvas(val width: Int, val height: Int) {
	/** RGBA8888, opaque throughout. */
	val rgba: ByteArray = ByteArray(width * height * 4)

	/**
	 * Fills the canvas with a checkerboard and composites the raster over it, source-over.
	 *
	 * @param LayerRaster raster  The layer's pixels.
	 * @param Int         originX The canvas origin's raster x.
	 * @param Int         originY The canvas origin's raster y.
	 * @param Int         scale   The magnification.
	 */
	fun paintBackground(raster: LayerRaster, originX: Int, originY: Int, scale: Int) {
		for (canvasY in 0 until height) {
			val rasterY = floor(canvasY.toDouble() / scale).toInt() + originY

			for (canvasX in 0 until width) {
				val rasterX = floor(canvasX.toDouble() / scale).toInt() + originX
				val light = ((canvasX / (CHECKER_SIDE * scale)) + (canvasY / (CHECKER_SIDE * scale))) % 2 == 0
				val background = if (light) 204 else 153
				var red = background.toDouble()
				var green = background.toDouble()
				var blue = background.toDouble()

				if (rasterX in 0 until raster.width && rasterY in 0 until raster.height) {
					val source = (rasterY * raster.width + rasterX) * 4
					val alpha = (raster.rgba[source + 3].toInt() and 0xFF) / 255.0
					red = (raster.rgba[source].toInt() and 0xFF) * alpha + red * (1.0 - alpha)
					green = (raster.rgba[source + 1].toInt() and 0xFF) * alpha + green * (1.0 - alpha)
					blue = (raster.rgba[source + 2].toInt() and 0xFF) * alpha + blue * (1.0 - alpha)
				}

				set(canvasX, canvasY, red.roundToInt(), green.roundToInt(), blue.roundToInt())
			}
		}
	}

	/**
	 * Draws a one-pixel line (Bresenham).
	 *
	 * @param Int      startX The start's x.
	 * @param Int      startY The start's y.
	 * @param Int      endX   The end's x.
	 * @param Int      endY   The end's y.
	 * @param IntArray color  The RGB color.
	 */
	fun line(startX: Int, startY: Int, endX: Int, endY: Int, color: IntArray) {
		val deltaX = abs(endX - startX)
		val deltaY = -abs(endY - startY)
		val stepX = if (startX < endX) 1 else -1
		val stepY = if (startY < endY) 1 else -1
		var error = deltaX + deltaY
		var x = startX
		var y = startY

		while (true) {
			set(x, y, color[0], color[1], color[2])

			if (x == endX && y == endY) {
				break
			}

			val doubled = 2 * error

			if (doubled >= deltaY) {
				error += deltaY
				x += stepX
			}

			if (doubled <= deltaX) {
				error += deltaX
				y += stepY
			}
		}
	}

	/**
	 * Draws a filled square dot.
	 *
	 * @param Int      centerX The center's x.
	 * @param Int      centerY The center's y.
	 * @param Int      radius  Pixels on each side of the center.
	 * @param IntArray color   The RGB color.
	 */
	fun dot(centerX: Int, centerY: Int, radius: Int, color: IntArray) {
		for (y in centerY - radius..centerY + radius) {
			for (x in centerX - radius..centerX + radius) {
				set(x, y, color[0], color[1], color[2])
			}
		}
	}

	/**
	 * Writes one opaque pixel, ignoring anything off the canvas.
	 *
	 * @param Int x     The pixel's x.
	 * @param Int y     The pixel's y.
	 * @param Int red   The red channel.
	 * @param Int green The green channel.
	 * @param Int blue  The blue channel.
	 */
	private fun set(x: Int, y: Int, red: Int, green: Int, blue: Int) {
		if (x < 0 || y < 0 || x >= width || y >= height) {
			return
		}

		val offset = (y * width + x) * 4
		rgba[offset] = red.coerceIn(0, 255).toByte()
		rgba[offset + 1] = green.coerceIn(0, 255).toByte()
		rgba[offset + 2] = blue.coerceIn(0, 255).toByte()
		rgba[offset + 3] = 0xFF.toByte()
	}
}