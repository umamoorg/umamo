package org.umamo.format.cmo3.serialize

import org.umamo.format.cmo3.serialize.descriptors.ClassDescriptor
import org.umamo.format.cmo3.tools.DescriptorReflection
import kotlin.reflect.KClass

/**
 * Builds a registry for synthetic test classes by deriving their descriptors reflectively, so
 * fixture classes need no generated descriptors.  One derivation cache spans the whole build,
 * keeping a listed subclass's super chain identical to the listed super's own descriptor.
 *
 * @param Collection classes The model classes and enums to register.
 * @return SerializerRegistry The populated registry.
 */
internal fun reflectiveDescriptorRegistry(classes: Collection<KClass<*>>): SerializerRegistry {
	val registry = SerializerRegistry()
	val derivationCache = HashMap<KClass<*>, ClassDescriptor>()
	for (kClass in classes) {
		if (kClass.java.isEnum) {
			registry.register(DescriptorReflection.enumDescriptorFor(kClass))
		} else {
			registry.register(DescriptorReflection.classDescriptorFor(kClass, derivationCache))
		}
	}
	return registry
}

/**
 * Builds an engine over [reflectiveDescriptorRegistry], the test-fixture stand-in for the deleted
 * reflective production path.
 *
 * @param Collection           classes     The model classes and enums to register.
 * @param SerializeDiagnostics diagnostics Sink for unmodeled-tag reports (default no-op).
 * @return SerializeEngine A ready engine.
 */
internal fun reflectiveEngineOf(
	classes: Collection<KClass<*>>,
	diagnostics: SerializeDiagnostics = SerializeDiagnostics.None,
): SerializeEngine = SerializeEngine(reflectiveDescriptorRegistry(classes), diagnostics)