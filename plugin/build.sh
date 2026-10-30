#!/bin/sh
# Compiles the plugin against the pinned server jar and installs it into the server's mods folder.
set -e
cd "$(dirname "$0")"
JAR=../server/server-release.jar
rm -rf build && mkdir -p build/classes
# The bot itself lives in ../common (shared with the mod); this adds the training-only code.
javac --release 17 -nowarn -cp "$JAR" -d build/classes $(find src/main/java ../common/src/main/java -name '*.java')
cp src/main/resources/plugin.json build/classes/
jar cf build/bloodbot-plugin.jar -C build/classes .
mkdir -p ../server/config/mods
cp build/bloodbot-plugin.jar ../server/config/mods/
echo "built and installed build/bloodbot-plugin.jar"
