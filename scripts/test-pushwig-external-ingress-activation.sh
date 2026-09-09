#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repository_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)
java_home=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}
test_output=$(mktemp -d "${TMPDIR:-/tmp}/pushwig-v5a-tests.XXXXXX")

cleanup ()
{
    rm -rf "$test_output"
}
trap cleanup EXIT HUP INT TERM

cd "$repository_dir"
env JAVA_HOME="$java_home" \
    PATH="$java_home/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin" \
    mvn -q clean package -Dbitwig.extension.directory=target

extension_jar="$repository_dir/target/DrivenByMoss-26.4.1.jar"

"$java_home/bin/javac" -encoding UTF-8 -cp "$extension_jar" -d "$test_output" \
    "$repository_dir/src/test/java/de/mossgrabers/controller/ableton/push/PushConfigurationIngressSettingTest.java" \
    "$repository_dir/src/test/java/de/mossgrabers/controller/ableton/push/controller/PushwigRuntimeRendezvousTest.java" \
    "$repository_dir/src/test/java/de/mossgrabers/controller/ableton/push/controller/ExternalRasterIngressLifecycleTest.java"

for test_class in \
    de.mossgrabers.controller.ableton.push.PushConfigurationIngressSettingTest \
    de.mossgrabers.controller.ableton.push.controller.PushwigRuntimeRendezvousTest \
    de.mossgrabers.controller.ableton.push.controller.ExternalRasterIngressLifecycleTest
do
    "$java_home/bin/java" -ea -cp "$test_output:$extension_jar" "$test_class"
done
