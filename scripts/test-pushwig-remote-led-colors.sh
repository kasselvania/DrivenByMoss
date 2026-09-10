#!/bin/sh
set -eu
# Compile the changed production mode plus its test against an existing matching base/package.
# This runner does not build or install an extension.
repository_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
java_home=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
reference_jar=${1:-"$repository_dir/target/DrivenByMoss-26.4.1.jar"}
test_output=$(mktemp -d /tmp/pushwig-remote-led-tests.XXXXXX)
trap 'rm -rf "$test_output"' EXIT HUP INT TERM
package_path=de/mossgrabers/controller/ableton/push/mode/device
"$java_home/bin/javac" -encoding UTF-8 -cp "$reference_jar" -d "$test_output" \
    "$repository_dir/src/main/java/de/mossgrabers/framework/controller/display/AbstractGraphicDisplay.java" \
    "$repository_dir/src/main/java/de/mossgrabers/controller/ableton/push/controller/Push2Display.java" \
    "$repository_dir/src/main/java/de/mossgrabers/controller/ableton/push/controller/SamplerLensContextPublisher.java" \
    "$repository_dir/src/main/java/de/mossgrabers/controller/ableton/push/controller/ExternalRasterPushFramePipeline.java" \
    "$repository_dir/src/main/java/de/mossgrabers/controller/ableton/push/controller/LatestExternalRasterFrameStore.java" \
    "$repository_dir/src/main/java/de/mossgrabers/controller/ableton/push/controller/ExternalRasterReceiver.java" \
    "$repository_dir/src/main/java/de/mossgrabers/controller/ableton/push/controller/SamplerLensPresentation.java" \
    "$repository_dir/src/main/java/$package_path/DeviceParamsMode.java" \
    "$repository_dir/src/test/java/$package_path/DeviceRemoteLedColorTest.java"
"$java_home/bin/java" -ea -cp "$test_output:$reference_jar" \
    de.mossgrabers.controller.ableton.push.mode.device.DeviceRemoteLedColorTest
