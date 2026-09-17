package org.umamo.interop.uma

/*
 * The hash form the sources entry writes (docs/format/UMA.md §6.4, D16): the model keeps a SHA-256 digest as bare
 * lowercase hex, and the file names the algorithm in front of it.
 */

// UMA §6.4: the algorithm prefix a recorded hash carries.
private const val SHA256_PREFIX = "sha256:"

/**
 * The file's form of a model hash.
 *
 * @param String? digest The digest as bare lowercase hex, or null.
 * @return String? The prefixed hash, or null.
 */
internal fun prefixedHashOf(digest: String?): String? = digest?.let { hex -> "$SHA256_PREFIX$hex" }

/**
 * The model's form of a file hash.  The format layer has already refused any other algorithm, so the prefix is
 * always there to strip.
 *
 * @param String? hash The prefixed hash, or null.
 * @return String? The bare digest, or null.
 */
internal fun digestOf(hash: String?): String? = hash?.removePrefix(SHA256_PREFIX)