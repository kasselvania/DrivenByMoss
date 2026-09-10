#!/bin/sh
set -eu
# Targeted production-path proof against an existing package; no installation or package rebuild.
repository_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
java_home=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
reference_jar=${1:-"$repository_dir/target/DrivenByMoss-26.4.1.jar"}
test_output=$(mktemp -d /tmp/pushwig-sampler-tests.XXXXXX)
trap 'rm -rf "$test_output"' EXIT HUP INT TERM
package_path=de/mossgrabers/controller/ableton/push/controller
"$java_home/bin/javac" -encoding UTF-8 -cp "$reference_jar" -d "$test_output" \
    "$repository_dir/src/main/java/$package_path/LatestExternalRasterFrameStore.java" \
    "$repository_dir/src/main/java/$package_path/ExternalRasterReceiver.java" \
    "$repository_dir/src/main/java/$package_path/ExternalRasterPushFramePipeline.java" \
    "$repository_dir/src/main/java/$package_path/SamplerLensPresentation.java" \
    "$repository_dir/src/test/java/$package_path/SamplerLensCompositionTest.java"
"$java_home/bin/java" -ea -cp "$test_output:$reference_jar" \
    de.mossgrabers.controller.ableton.push.controller.SamplerLensCompositionTest
