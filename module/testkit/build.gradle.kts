// :testkit — what the modules' JVM tests share: the helpers that end a test as SKIPPED when its corpus
// sample, or anything else it cannot run without, is absent.
// JVM-only on purpose: every gated test is a jvmTest (the corpus is files on disk, read through java.io),
// and JUnit, whose assumption is what a skip is, is a JVM library.  A module's test source set depends on
// it; nothing that ships does.
plugins {
	alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
	jvmToolchain(21)
	jvm()

	sourceSets {
		jvmMain {
			dependencies {
				// The JUnit 4 the Kotlin plugin gives every jvmTest, by the same coordinate, so the exception a
				// skip throws is the class the tests' runner catches.
				implementation(kotlin("test-junit"))
			}
		}
		jvmTest {
			dependencies {
				implementation(kotlin("test"))
			}
		}
	}
}