// :cli (app/cli) — the headless diagnostic tool: dump / convert / diff over cmo3 and moc3 files.
// JVM-only on purpose: it is an operator's tool, not a shipping app target.  It deliberately avoids
// :ui (whose document layer drags in Compose via LiveParams) and :edit — everything it needs is the
// :interop conversion surface plus :render's space/texture helpers.
plugins {
	alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
	jvmToolchain(21)
	jvm()

	sourceSets {
		jvmMain {
			dependencies {
				// :interop api-exposes :format and :runtime, so those come transitively.
				implementation(project(":interop"))
				// restMeshesToCanvasSpace / canvasToParentSpaceFor and the atlas texture helpers.
				implementation(project(":render"))
			}
		}
	}
}

// A hand-registered JavaExec rather than the application plugin: the compose.desktop application
// block is the repo's only other runnable and is not wanted here.  Only file collections are
// captured (never the Project), keeping the task configuration-cache safe; `--args` works through
// JavaExec's built-in @Option.
val cliMainCompilation = kotlin.jvm().compilations.getByName("main")
val repositoryRoot = rootDir

tasks.register<JavaExec>("run") {
	group = "application"
	description = "Runs the Umamo diagnostic CLI; pass arguments with --args=\"dump model.moc3\"."
	mainClass.set("org.umamo.cli.MainKt")
	classpath(cliMainCompilation.output.allOutputs, cliMainCompilation.runtimeDependencyFiles)
	// Relative file arguments should resolve against the repo root the operator invoked from, not
	// this subproject's directory (JavaExec's default).
	workingDir = repositoryRoot
	// The atlas packer holds every source layer's pixels plus the pages it composes, and a real
	// document is hundreds of megabytes of raster before a single page is allocated.  The JVM
	// default (a quarter of RAM) is not something to leave to the operator's machine.
	maxHeapSize = "6g"
}