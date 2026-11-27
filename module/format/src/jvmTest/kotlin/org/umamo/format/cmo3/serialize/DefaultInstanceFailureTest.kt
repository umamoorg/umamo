package org.umamo.format.cmo3.serialize

import org.umamo.format.cmo3.serialize.annotations.DontSerializeIfDefault
import org.umamo.format.cmo3.serialize.annotations.SerialTag
import org.umamo.format.cmo3.xml.XmlCodec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Whether [OutOfMemoryOnConstruct]'s constructor runs out of memory. */
private var constructorRunsOutOfMemory = false

/** Whether [RefusesToConstruct]'s constructor refuses. */
private var constructorRefuses = false

@SerialTag("OutOfMemoryOnConstruct")
@DontSerializeIfDefault
class OutOfMemoryOnConstruct {
	var count: Int = 0

	init {
		if (constructorRunsOutOfMemory) {
			throw OutOfMemoryError("simulated")
		}
	}
}

@SerialTag("RefusesToConstruct")
@DontSerializeIfDefault
class RefusesToConstruct {
	var count: Int = 0

	init {
		if (constructorRefuses) {
			throw IllegalStateException("simulated")
		}
	}
}

/**
 * Holds the serializer's default-instance probe to the difference between a class that will not instantiate
 * and a JVM that ran out of memory.  Reflection wraps both in an InvocationTargetException; the first means
 * "no default, write every field", the second must fail the write rather than finish a file that silently
 * carries every default-valued field.
 */
class DefaultInstanceFailureTest {
	/**
	 * Leaves both constructors working for the next test.
	 */
	@AfterTest
	fun restoreConstructors() {
		constructorRunsOutOfMemory = false
		constructorRefuses = false
	}

	@Test
	fun runningOutOfMemoryForTheDefaultFailsTheWrite() {
		val engine = SerializeEngine.of(listOf(OutOfMemoryOnConstruct::class))
		val value = OutOfMemoryOnConstruct()
		constructorRunsOutOfMemory = true

		assertFailsWith<OutOfMemoryError> { engine.writeRoot(value) }
	}

	@Test
	fun runningOutOfMemoryWhileReadingIsRethrownUnwrapped() {
		val engine = SerializeEngine.of(listOf(OutOfMemoryOnConstruct::class))
		val xml = XmlCodec.write(engine.writeRoot(OutOfMemoryOnConstruct().apply { count = 3 }))
		constructorRunsOutOfMemory = true

		assertFailsWith<OutOfMemoryError> { engine.readRoot(XmlCodec.parse(xml)) }
	}

	@Test
	fun aClassThatWillNotInstantiateWritesEveryField() {
		val engine = SerializeEngine.of(listOf(RefusesToConstruct::class))
		val value = RefusesToConstruct()
		constructorRefuses = true

		val xml = XmlCodec.write(engine.writeRoot(value)).decodeToString()

		assertTrue("xs.n=\"count\"" in xml, "with no default to compare against, the default-valued field is written: $xml")
	}
}