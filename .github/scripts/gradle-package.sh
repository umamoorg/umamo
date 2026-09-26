#!/usr/bin/env bash
# Runs the release workflow's packaging Gradle tasks with the flags every packaging step shares, so the app image,
# the upgrade-test installers, and the published installers are all built the same way.
#
#   * -Pumamo.target is passed explicitly rather than left to host detection: the uber jar's name embeds it, so
#     artifact names follow the matrix rather than what the runner reported, and with -Pumamo.requireTargetIsHost an
#     unexpected os.arch fails the build instead of producing a jar full of the wrong natives.
#   * The heap overrides are not cosmetic: gradle.properties asks for a 4 GB daemon and the Kotlin daemon mirrors it,
#     ~8 GB against the arm64 macOS runner's ~7 GB; 2 GB each fits every runner and is ample for :desktop alone.
#   * UMAMO_PACKAGING_JAVA_HOME, when set, is the packaging JDK the leg names (docs/plan/distribution.md D14).
#
# Usage: gradle-package.sh [Gradle arguments...]   (UMAMO_TARGET and UMAMO_PACKAGING_JAVA_HOME from the environment)
#
# Written for the bash 3.2 macOS ships: no empty-array expansion under set -u.

set -euo pipefail

packaging_flag=""
if [ -n "${UMAMO_PACKAGING_JAVA_HOME:-}" ]; then
	packaging_flag="-Pumamo.packagingJavaHome=${UMAMO_PACKAGING_JAVA_HOME}"
fi

./gradlew --stacktrace \
	-Dorg.gradle.jvmargs="-Xmx2g -Dfile.encoding=UTF-8" \
	-Pkotlin.daemon.jvmargs=-Xmx2g \
	-Pumamo.target="${UMAMO_TARGET}" \
	-Pumamo.requireTargetIsHost=true \
	${packaging_flag:+"${packaging_flag}"} \
	"$@"