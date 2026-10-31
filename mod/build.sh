#!/bin/sh
# Builds the BloodBot mod: build/BloodBot.jar. Copy it into Mindustry's mods folder to play
# (macOS: ~/Library/Application Support/Mindustry/mods/). Desktop only for now (Android needs extra dexing).
set -e
cd "$(dirname "$0")"
# Compile against the Steam game itself when present (same build as the training server: 160.7).
STEAM_JAR="$HOME/Library/Application Support/Steam/steamapps/common/Mindustry/Mindustry.app/Contents/Resources/desktop.jar"
JAR=../server/server-release.jar
[ -f "$STEAM_JAR" ] && JAR="$STEAM_JAR"
rm -rf build && mkdir -p build/classes/brains
javac --release 17 -nowarn -cp "$JAR" -d build/classes $(find src/main/java ../common/src/main/java -name '*.java')
cp ../mod.hjson build/classes/  # kept at the repo root so the in-game mod browser can find it
cp brains/*.bin build/classes/brains/ 2>/dev/null || echo "warning: no brains in mod/brains/ yet (run: python3 -m bloodbot.pick_brains)"
rm -f build/BloodBot.zip
(cd build/classes && zip -qr ../BloodBot.zip .)
cp build/BloodBot.zip build/BloodBot.jar  # the mod browser installs Java mods from a release's .jar
echo "built build/BloodBot.zip and build/BloodBot.jar"
