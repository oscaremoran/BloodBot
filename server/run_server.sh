#!/bin/sh
# The ONLY supported way to start a Mindustry server for BloodBot.
# Keeps the Mac usable: one server at a time, lowest CPU priority, 2 cores, capped heap and game speed.
#
#   ./run_server.sh "bbplay ../maps/campaign/groundZero.msav ..." [maxSpeed]
#
# maxSpeed = cap on game speed as a multiple of real time (default 20, hard ceiling 40).
# BLOODBOT_NICE = CPU priority, 19 = lowest (default) .. 0 = normal. Only raise it when the user asks.
set -e
cd "$(dirname "$0")"

COMMANDS="$1"
SPEED="${2:-20}"
if [ "$SPEED" -gt 40 ]; then
    echo "refusing speed ${SPEED}x: hard ceiling is 40x (see run_server.sh)" >&2
    exit 1
fi

# One server at a time unless the user has OK'd more: BLOODBOT_MAX_SERVERS (max 3), each with its own BLOODBOT_WORKER id.
MAX="${BLOODBOT_MAX_SERVERS:-1}"
WORKER="${BLOODBOT_WORKER:-0}"
WORKERS="${BLOODBOT_WORKERS:-1}"
if [ "$MAX" -gt 3 ]; then
    echo "refusing more than 3 servers" >&2
    exit 1
fi
LOCK=/tmp/bloodbot-server.lock.$WORKER
if ! mkdir "$LOCK" 2>/dev/null; then
    echo "a BloodBot server for worker $WORKER is already running (lock $LOCK)." >&2
    exit 1
fi
trap 'rmdir "$LOCK"' EXIT INT TERM
if [ "$(pgrep -f "java .*server-release.jar" | wc -l)" -ge "$MAX" ]; then
    echo "already $MAX Mindustry server(s) running; that's the limit (stop with: pkill -f server-release.jar)." >&2
    exit 1
fi

NICE="${BLOODBOT_NICE:-19}"
if [ "$NICE" -lt 0 ] || [ "$NICE" -gt 19 ]; then
    echo "BLOODBOT_NICE must be 0..19 (0 = normal priority)" >&2
    exit 1
fi

nice -n "$NICE" java \
    -XX:ActiveProcessorCount=2 -XX:CICompilerCount=2 -XX:ParallelGCThreads=1 \
    -Xms256m -Xmx1g -Djava.awt.headless=true \
    -Dbloodbot.maxSpeed="$SPEED" -Dbloodbot.exitWhenDone=true -Dbloodbot.worker="$WORKER" -Dbloodbot.workers="$WORKERS" -Dbloodbot.debug="${BLOODBOT_DEBUG:-false}" -Dbloodbot.shipGun="${BLOODBOT_SHIP_GUN:-false}" -Dbloodbot.shipUntouchable="${BLOODBOT_SHIP_UNTOUCHABLE:-true}" \
    "-Dbloodbot.run=$COMMANDS" \
    -jar server-release.jar
