package org.umamo.interop.uma

import org.umamo.format.uma.UmaWriteException

/*
 * The checks every domain entry's export shares for values written inline as JSON (docs/format/UMA.md §4.1).
 */

/**
 * [value], refusing a NaN or an infinity, which JSON cannot hold.
 *
 * @param Float  value     The value.
 * @param String entryPath The entry the value is written to, for the failure.
 * @param String path      Where the value sits in the entry, for the failure.
 * @return Float The value.
 * @throws UmaWriteException When the value is not finite.
 */
internal fun finiteInline(value: Float, entryPath: String, path: String): Float {
	if (!value.isFinite()) {
		// UMA §4.1: JSON has no NaN or infinity.
		throw UmaWriteException("$entryPath: $path", "$value cannot be written as JSON")
	}
	return value
}