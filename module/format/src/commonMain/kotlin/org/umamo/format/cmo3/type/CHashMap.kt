package org.umamo.format.cmo3.type

/**
 * A map that serializes as `<hash_map>` but preserves insertion/read order. The editor writes
 * hash_map entries in JVM-hash order; reading into a plain HashMap and re-emitting would reorder
 * them (Enum/object keys hash by identity), breaking byte-identity. Reading into this order-keeping
 * type re-emits entries in their original document order.
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Primitive & collection tags</a>
 */
public class CHashMap<K, V> : LinkedHashMap<K, V>()
