"""Log a human's game so it gets an evolution score on the same scale as the bot (runs/human/log.csv).

A human's evolution is their best game (the hardest thing they've beaten), shown on the scoreboard next to the bot's.

Usage:
  python3 -m bloodbot.human NAME MAP OUTCOME [--wave N] [--strength 80] [--core 60] [--minutes 12] [--blocks 300]
  python3 -m bloodbot.human Oscar groundZero win --strength 80 --core 60 --minutes 12
  python3 -m bloodbot.human Oscar frozenForest loss --wave 11
  python3 -m bloodbot.human --list
MAP: groundZero, frozenForest, crateredBattleground, fungalPass, ussIowa, bloodshed_bastion (others are logged, not scored).
"""
import argparse
import csv
from pathlib import Path

from bloodbot.gallery import EVO_LADDER, evolution, fmt_evo

ROOT = Path(__file__).resolve().parent.parent
LOG = ROOT / "runs" / "human" / "log.csv"
COLUMNS = ["game", "generation", "member", "map", "difficulty", "outcome", "wave", "winWave", "coreHealth",
           "fitness", "gameSeconds", "realSeconds", "blocksBuilt", "actions"]
WIN_WAVE = {"groundZero": 10, "frozenForest": 15, "crateredBattleground": 20}


def rows():
    if not LOG.exists():
        return []
    with open(LOG, newline="") as f:
        return list(csv.DictReader(f))


def as_game(r):
    return {"bot": "human", "map": r["map"], "difficulty": float(r["difficulty"]), "outcome": r["outcome"], "wave": int(r["wave"]),
            "winWave": int(r["winWave"]), "blocks": int(r["blocksBuilt"] or 0)}


def show():
    best = {}
    for r in rows():
        e = evolution(as_game(r)) or 0
        print(f"game {r['game']}: {r['member']} {r['map']} {int(float(r['difficulty']) * 100)}% {r['outcome']} "
              f"wave {r['wave']} -> evolution {fmt_evo(e)}")
        best[r["member"]] = max(best.get(r["member"], 0), e)
    for name, e in best.items():
        print(f"{name}: best evolution {fmt_evo(e)}")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("name", nargs="?")
    p.add_argument("map", nargs="?")
    p.add_argument("outcome", nargs="?", choices=["win", "loss"])
    p.add_argument("--wave", type=int, help="last wave reached (default: the win wave for a win)")
    p.add_argument("--strength", type=int, default=100, help="enemy strength %% (30, 55, 80 or 100; default 100)")
    p.add_argument("--core", type=float, default=0, help="core health left, %%")
    p.add_argument("--minutes", type=float, default=0, help="game time in minutes")
    p.add_argument("--blocks", type=int, default=1, help="blocks built (default 1)")
    p.add_argument("--list", action="store_true", help="show logged human games and best evolution per person")
    a = p.parse_args()
    if a.list or not a.outcome:
        if not a.list:
            p.error("give NAME MAP OUTCOME, or --list")
        return show()
    if a.map not in EVO_LADDER:
        print(f"note: {a.map} isn't on the evolution ladder ({', '.join(EVO_LADDER)}); logged without a score")
    win_wave = WIN_WAVE.get(a.map, 0)
    wave = a.wave if a.wave is not None else win_wave
    existing = rows()
    game = len(existing) + 1
    row = [game, 0, a.name, a.map, a.strength / 100, a.outcome, wave, win_wave, a.core / 100, 0,
           int(a.minutes * 60), 0, a.blocks, ""]
    LOG.parent.mkdir(parents=True, exist_ok=True)
    new = not LOG.exists()
    with open(LOG, "a", newline="") as f:
        w = csv.writer(f)
        if new:
            w.writerow(COLUMNS)
        w.writerow(row)
    e = evolution(as_game(dict(zip(COLUMNS, map(str, row)))))
    best = max([evolution(as_game(r)) or 0 for r in existing if r["member"] == a.name] + [e or 0])
    print(f"logged human game {game}: {a.name} {a.map} {a.strength}% {a.outcome} wave {wave} -> evolution {fmt_evo(e)}"
          f" ({a.name}'s best: {fmt_evo(best)}). Rebuild pages: python3 -m bloodbot.gallery")


if __name__ == "__main__":
    main()
