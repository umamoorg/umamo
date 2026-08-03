// Convention plugin: wire a module's iosArm64 compiles into `check`, main AND test.
//
// Neither task arrives on its own. `check` reaches a target's compile only by way of a runnable test
// task, and iosArm64 is a DEVICE target with none — so the commonMain-purity guarantee a module
// carries an Apple target FOR only fired when someone typed the task name, and `./gradlew check`
// passed straight over a commonMain `java.*` leak exactly as if the target did not exist.
//
// The test source set matters as much as main, and is the half that actually broke: KraReader sat in
// commonMain importing java.util.zip while a commonTest beside it referenced a symbol only it
// declared. compileKotlinIosArm64 was green throughout — main compiled fine — and CI failed on
// compileTestKotlinIosArm64, a task no local run touched. commonTest has to build for every target
// commonMain does, so it gets the same gate.
//
// Apply alongside an `iosArm64()` target; there is nothing to wire without one.
// 規約プラグイン：iosArm64 のコンパイル（main と test の両方）を `check` に接続する。

// `withId` so the module's `plugins {}` block order does not matter — `check` comes from the base
// plugin that Kotlin Multiplatform brings with it, so it exists by the time this fires.
plugins.withId("org.jetbrains.kotlin.multiplatform") {
	tasks.named("check") {
		dependsOn("compileKotlinIosArm64", "compileTestKotlinIosArm64")
	}
}
