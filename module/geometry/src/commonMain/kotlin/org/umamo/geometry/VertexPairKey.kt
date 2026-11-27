package org.umamo.geometry

/*
 * Two vertex indices packed into one Long, the key every edge set and edge map in the module and its
 * consumers hashes on.  One packing, so a directed key built in one place matches a lookup in another.
 */

/**
 * Packs two vertex indices into one Long, the first in the high half.
 *
 * @param Int first  The first vertex.
 * @param Int second The second vertex.
 * @return Long The key.
 */
public fun packVertexPair(first: Int, second: Int): Long = (first.toLong() shl 32) or (second.toLong() and 0xFFFFFFFFL)

/**
 * The first vertex of a packed pair.
 *
 * @param Long pair The packed pair.
 * @return Int The first vertex.
 */
public fun pairFirst(pair: Long): Int = (pair ushr 32).toInt()

/**
 * The second vertex of a packed pair.
 *
 * @param Long pair The packed pair.
 * @return Int The second vertex.
 */
public fun pairSecond(pair: Long): Int = pair.toInt()

/**
 * Packs an undirected pair, the lower vertex first, so either order names the same edge.
 *
 * @param Int first  One vertex.
 * @param Int second The other.
 * @return Long The key.
 */
public fun packUndirectedPair(first: Int, second: Int): Long = packVertexPair(minOf(first, second), maxOf(first, second))