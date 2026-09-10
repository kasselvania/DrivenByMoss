#!/bin/sh
set -eu
# One complete affected-module build, followed by the real controller/receiver regression suites.
repository_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
java_home=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
cd "$repository_dir"
env JAVA_HOME="$java_home" PATH="$java_home/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin" \
    mvn -q package -Dbitwig.extension.directory=target
reference_jar="$repository_dir/target/DrivenByMoss-26.4.1.jar"
api_jar="$HOME/.m2/repository/com/bitwig/extension-api/21/extension-api-21.jar"
test_output=$(mktemp -d "$repository_dir/target/sampler-tests.XXXXXX")
classpath="$reference_jar:$api_jar"
controller=de/mossgrabers/controller/ableton/push
native=de/mossgrabers/bitwig/controller/ableton/push
"$java_home/bin/javac" -encoding UTF-8 -cp "$classpath" -d "$test_output" \
    "src/test/java/$controller/PushConfigurationIngressSettingTest.java" \
    "src/test/java/$controller/controller/PushwigRuntimeRendezvousTest.java" \
    "src/test/java/$controller/controller/ExternalRasterIngressLifecycleTest.java" \
    "src/test/java/$controller/controller/SamplerLensCompositionTest.java" \
    "src/test/java/$controller/mode/device/DeviceRemoteLedColorTest.java" \
    "src/test/java/$native/NativeSamplerLensTest.java"
for test_class in \
    de.mossgrabers.controller.ableton.push.PushConfigurationIngressSettingTest \
    de.mossgrabers.controller.ableton.push.controller.PushwigRuntimeRendezvousTest \
    de.mossgrabers.controller.ableton.push.controller.ExternalRasterIngressLifecycleTest \
    de.mossgrabers.controller.ableton.push.controller.SamplerLensCompositionTest \
    de.mossgrabers.controller.ableton.push.mode.device.DeviceRemoteLedColorTest \
    de.mossgrabers.bitwig.controller.ableton.push.NativeSamplerLensTest
do
    "$java_home/bin/java" -ea -cp "$test_output:$classpath" "$test_class"
done
shasum -a 256 "$reference_jar"
# Generated test classes stay under ignored target/, never in a scanned extension directory.
