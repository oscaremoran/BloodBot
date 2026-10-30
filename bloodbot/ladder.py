"""Play the training ladder: the first three Serpulo campaign maps, then Fungal Pass once they're easy.

Usage: python -m bloodbot.ladder [--speed 20]
Recordings land in runs/campaign/; open runs/index.html to watch.
"""
import argparse
import json
import subprocess
from pathlib import Path

from bloodbot import gallery

ROOT = Path(__file__).resolve().parent.parent
RUNS = ROOT / "runs" / "campaign"

# (map file, waves to survive; 0 = destroy the enemy cores)
FIRST_THREE = [("groundZero", 10), ("frozenForest", 15), ("crateredBattleground", 20)]
UNLOCKS = [("fungalPass", 0)]

CAP_SECONDS = 60 * 60     # stop a game after 60 game minutes
EASY_CORE_HEALTH = 0.5    # "easily beat" = win with the core above half health


def play(maps, speed):
    specs = " ".join(f"../maps/campaign/{name}.msav:{wave}" for name, wave in maps)
    cmd = [str(ROOT / "server" / "run_server.sh"), f"bbepisodes {CAP_SECONDS} {RUNS} {specs}", str(speed)]
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)


def latest_result(name):
    games = sorted((RUNS / name).glob("game_*"))
    if not games:
        return None
    return json.loads((games[-1] / "result.json").read_text())


def beaten_easily(result):
    return result and result["outcome"] == "win" and result["coreHealth"] >= EASY_CORE_HEALTH


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--speed", type=int, default=20, help="game speed cap, x real time (max 40)")
    args = parser.parse_args()

    play(FIRST_THREE, args.speed)
    results = {name: latest_result(name) for name, _ in FIRST_THREE}
    for name, r in results.items():
        print(f"{name}: {r['outcome']} at wave {r['wave']}/{r['winWave']}, core {r['coreHealth']:.0%}")

    if all(beaten_easily(r) for r in results.values()):
        print("First three maps beaten easily -> unlocking Fungal Pass")
        play(UNLOCKS, args.speed)
        r = latest_result("fungalPass")
        print(f"fungalPass: {r['outcome']} after {r['gameSeconds'] // 60} min")
    else:
        print("Fungal Pass stays locked until all three are won with the core above "
              f"{EASY_CORE_HEALTH:.0%} health.")

    gallery.main()


if __name__ == "__main__":
    main()
