#!/bin/sh
# Self-learning training on the map ladder. Safe to stop any time (Ctrl+C or `pkill -f server-release.jar`);
# running it again resumes from runs/train/state.bin.
#
#   ./train.sh [speed] [servers]   speed = game speed cap, x real time (default 20, max 40)
#                                  servers = games played at once (default 1, max 3; more = more CPU)
#
# Ladder: Ground Zero (10 waves) -> Frozen Forest (15) -> Cratered Battleground (20) -> Fungal Pass -> USS Iowa.
# A map's enemy strength goes 30% -> 55% -> 80% -> 100% each time the brain wins twice in a row; winning twice
# at 100% moves on to the next map. Bloodshed Bastion is intentionally not on the ladder yet.
cd "$(dirname "$0")/server"
M=../maps/campaign
LADDER="bbtrain ../runs/train $M/groundZero.msav:10 $M/frozenForest.msav:15 $M/crateredBattleground.msav:20 $M/fungalPass.msav:0 $M/ussIowa.msav:0"
SERVERS="${2:-1}"
export BLOODBOT_MAX_SERVERS="$SERVERS" BLOODBOT_WORKERS="$SERVERS"
i=1
while [ "$i" -lt "$SERVERS" ]; do
    BLOODBOT_WORKER=$i ./run_server.sh "$LADDER" "${1:-20}" > "../runs/train/server-$i.log" 2>&1 &
    i=$((i + 1))
done
BLOODBOT_WORKER=0 ./run_server.sh "$LADDER" "${1:-20}"
wait
