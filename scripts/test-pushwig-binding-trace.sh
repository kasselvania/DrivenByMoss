#!/bin/sh
set -eu
# Targeted compile only. Supply an existing matching DBM JAR/extension; no build loop.
repository_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
java_home=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
reference_jar=${1:-"$repository_dir/target/DrivenByMoss-26.4.1.jar"}
api_jar="$HOME/.m2/repository/com/bitwig/extension-api/21/extension-api-21.jar"
test_output=$(mktemp -d /tmp/pushwig-binding-tests.XXXXXX)
trap 'rm -rf "$test_output"' EXIT HUP INT TERM
package_path=de/mossgrabers/bitwig/controller/ableton/push
"$java_home/bin/javac" -encoding UTF-8 -cp "$reference_jar:$api_jar" -d "$test_output" \
    "$repository_dir/src/main/java/$package_path/PushBindingTrace.java" \
    "$repository_dir/src/main/java/$package_path/Push3ControllerExtensionDefinition.java" \
    "$repository_dir/src/test/java/$package_path/PushBindingTraceTest.java"
"$java_home/bin/java" -ea -cp "$test_output:$reference_jar:$api_jar" \
    de.mossgrabers.bitwig.controller.ableton.push.PushBindingTraceTest
