package org.umamo.interop.cmo3.export

import org.umamo.format.cmo3.model.identity.Guid
import org.umamo.format.cmo3.model.identity.Id
import java.util.UUID

/*
 * The identity tokens an export mints for what it creates.  One function draws the random uuid, so the
 * fresh-graph synthesis and every lowering write guids of the same shape.
 */

/**
 * Mints a fresh guid of the given kind.
 *
 * @param String kind The typed guid tag (e.g. "CPartGuid").
 * @return Guid The fresh guid.
 */
internal fun freshGuid(kind: String): Guid = freshGuidLike(template = null, fallbackKind = kind)

/**
 * A fresh guid of the template's kind, or of a fallback kind when there is no template.
 *
 * @param Guid? template The guid whose kind and note to copy, or null.
 * @param String fallbackKind The kind to use without a template.
 * @return Guid The new guid.
 */
internal fun freshGuidLike(template: Guid?, fallbackKind: String): Guid =
	Guid(template?.kind ?: fallbackKind).apply {
		uuid = UUID.randomUUID().toString()
		note = template?.note ?: "(no debug info)"
	}

/**
 * A fresh id of the template's kind, carrying a given id string.
 *
 * @param Id? template The id whose kind to copy, or null.
 * @param String fallbackKind The kind to use without a template.
 * @param String idStr The id string.
 * @return Id The new id.
 */
internal fun freshIdLike(template: Id?, fallbackKind: String, idStr: String): Id =
	Id(template?.kind ?: fallbackKind).apply { idstr = idStr }