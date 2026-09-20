package org.umamo.interop.uma

import org.umamo.format.uma.sources.UMA_SHA256_PREFIX

/*
 * The hash form the sources entry writes (docs/format/UMA.md §6.4): the model keeps a SHA-256 digest as bare
 * lowercase hex, and the file names the algorithm in front of it.
 */

/**
 * The file's form of a model hash.
 *
 * @param String? digest The digest as bare lowercase hex, or null.
 * @return String? The prefixed hash, or null.
 */
internal fun prefixedHashOf(digest: String?): String? = digest?.let { hex -> "$UMA_SHA256_PREFIX$hex" }

/**
 * The model's form of a file hash.  The format layer has already refused any other algorithm, so the prefix is
 * always there to strip.
 *
 * @param String? hash The prefixed hash, or null.
 * @return String? The bare digest, or null.
 */
internal fun digestOf(hash: String?): String? = hash?.removePrefix(UMA_SHA256_PREFIX)