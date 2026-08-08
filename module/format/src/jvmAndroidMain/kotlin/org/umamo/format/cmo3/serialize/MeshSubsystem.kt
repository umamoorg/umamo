package org.umamo.format.cmo3.serialize

import org.umamo.format.cmo3.serialize.gen.GeneratedDescriptors

/**
 * Registers the mesh/geometry subsystem (all descriptor-driven). These are frequent shared-pool
 * defs, so typing them is validated directly by the whole-file byte-identity gate.
 *
 * @param SerializerRegistry registry The registry to populate.
 */
internal fun registerMeshSubsystem(registry: SerializerRegistry) {
	registry.register(GeneratedDescriptors.coordType)
	registry.register(GeneratedDescriptors.pointInTriangle)
	registry.register(GeneratedDescriptors.pointOnCurve)
	registry.register(GeneratedDescriptors.meshPointRef)
}