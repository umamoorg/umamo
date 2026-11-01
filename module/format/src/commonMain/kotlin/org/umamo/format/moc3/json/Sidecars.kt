package org.umamo.format.moc3.json

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * The `Json` configuration that reproduces the Cubism editor's sidecar output.
 *
 * Numeric leaves the editor prints without a trailing `.0` (e.g. `"Y": -1`, `"Weight": 60`) are
 * modeled as [kotlinx.serialization.json.JsonPrimitive] so the original token round-trips exactly.
 * Decoding tolerates unknown keys because newer Cubism exports add manifest fields the schema does
 * not model yet (e.g. Cubism 5's FileReferences.MotionSync); decode-then-encode therefore DROPS
 * unmodeled keys, so a sidecar re-emit path must carry them through explicitly (raw JsonObject
 * passthrough) rather than relying on the serializer.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §JSON sidecars</a>
 */
@OptIn(ExperimentalSerializationApi::class)
internal val SidecarJson: Json =
	Json {
		prettyPrint = true
		prettyPrintIndent = "\t"
		explicitNulls = false
		encodeDefaults = true
		ignoreUnknownKeys = true
	}