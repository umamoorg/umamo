package org.umamo.runtime.model

/**
 * Typed identifiers for model entities.
 *
 * Each is a `@JvmInline value class` - a zero-cost wrapper the compiler erases to the bare
 * underlying value where it can, while keeping the types distinct at compile time. This replaces
 * the "stringly-typed" anti-pattern (`Map<String, …>` where any String fits any slot): you can't
 * pass a [DrawableId] where a [PartId] is wanted. Cheap safety, no allocation.
 *
 * モデル要素の型付き ID。value class でゼロコストかつ型安全（取り違えをコンパイルエラーにする）。
 */
@JvmInline
value class PartId(val raw: String)

@JvmInline
value class DrawableId(val raw: String)

@JvmInline
value class DeformerId(val raw: String)

@JvmInline
value class ParameterId(val raw: String)

@JvmInline
value class ParameterGroupId(val raw: String)

@JvmInline
value class KeyformId(val raw: String)
