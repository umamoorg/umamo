package org.umamo.format.cmo3.type

/**
 * A list type that serializes as `<carray_list>` (vs `<array_list>` for a plain ArrayList).
 *
 * EN: The editor uses a distinct list type for most model collections, and the tag
 *     distinguishes it on disk, so model fields that are carray_list must use this type to round-trip.
 * JA: `carray_list` タグで直列化されるリスト型。
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Primitive & collection tags</a>
 */
public class CArrayList<E> : ArrayList<E> {
	public constructor() : super()
	public constructor(initialCapacity: Int) : super(initialCapacity)
	public constructor(elements: Collection<E>) : super(elements)
}
