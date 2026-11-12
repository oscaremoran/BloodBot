"""Bot highlights reel: the strangest PPO games as a "top 10" page (runs/highlights.html).

Reads runs/ppo/log.csv (outcome, actions, times) and the per-game stat lines in runs/ppo/trainer.log
(ship kills, unused blocks, turrets...). Normal games only (no practice or broke starts). Each category
lists its top 3 and links a replay when one was saved. Light: a few seconds, no game server.
Usage: python3 -m bloodbot.highlights
"""
import csv
import html
import os
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RUNS = ROOT / "runs"
PPO = RUNS / "ppo"

STAT_RE = {
    "shipKills": r"ship kills (\d+)", "unused": r"unused (\d+)", "fedTurrets": r"fed turrets (\d+)",
    "hitting": r"hitting (\d+)", "mined": r"mined (\d+)", "maceKills": r"maces killed (\d+)",
    "wasted": r"wasted (\d+)",
}
MAP_NAMES = {"groundZero": "Ground Zero", "frozenForest": "Frozen Forest", "crateredBattleground": "Cratered Battleground"}


def load():
    stats = {}
    with open(PPO / "trainer.log", errors="replace") as f:
        for line in f:
            if not line.startswith("game ") or "PRACTICE" in line or "BROKE" in line:
                continue
            m = re.match(r"game (\d+):", line)
            if not m:
                continue
            d = {}
            for k, rx in STAT_RE.items():
                s = re.search(rx, line)
                if s:
                    d[k] = int(s.group(1))
            s = re.search(r"lines (\d+)/(\d+)", line)
            if s:
                d["lines"], d["linePlans"] = int(s.group(1)), int(s.group(2))
            stats[int(m.group(1))] = d
    games = []
    with open(PPO / "log.csv") as f:
        for r in csv.DictReader(f):
            if r.get("start"):
                continue
            g = int(r["game"])
            acts = dict((a.split("=")[0], int(a.split("=")[1])) for a in r["actions"].split(";") if "=" in a)
            d = {"game": g, "map": r["map"], "diff": float(r["difficulty"]), "outcome": r["outcome"],
                 "wave": int(r["wave"]), "winWave": int(r["winWave"]), "core": float(r["coreHealth"]),
                 "secs": int(r["gameSeconds"]), "blocks": int(r["blocksBuilt"]), "acts": acts,
                 "decisions": sum(acts.values())}
            d.update(stats.get(g, {}))
            games.append(d)
    return games


def replay(g):
    folder = PPO / g["map"] / f"game_{g['game']:05d}"
    return f"viewer.html?game=ppo/{g['map']}/game_{g['game']:05d}" if (folder / "replay.js").exists() else None


def where(g):
    return f"{MAP_NAMES.get(g['map'], g['map'])} {round(g['diff'] * 100)}%"


def mmss(s):
    return f"{s // 60}:{s % 60:02d}"


# (title, emoji, key to sort by (bigger = stranger), filter, caption)
CATEGORIES = [
    ("The one that actually won", "🏆", lambda g: (g["map"] != "groundZero", g["core"]), lambda g: g["outcome"] == "win",
     lambda g: f"Won at wave {g['wave']} with the core at {g['core']:.0%}. {g.get('fedTurrets', '?')} fed turrets, "
               f"{g.get('hitting', '?')} of them actually hitting things."),
    ("Construction frenzy", "🏗️", lambda g: g["blocks"], lambda g: True,
     lambda g: f"Built {g['blocks']} blocks and still died at wave {g['wave']}. {g.get('unused', '?')} of them never did anything."),
    ("One-ship army", "🚀", lambda g: g.get("shipKills", -1), lambda g: "shipKills" in g,
     lambda g: f"The ship finished off {g['shipKills']} enemies by itself. Turrets hitting: {g.get('hitting', 0)}. Reached wave {g['wave']}."),
    ("Minimalist", "🧘", lambda g: (g["wave"], -g["blocks"]), lambda g: g["blocks"] <= 30 and g["wave"] >= 5,
     lambda g: f"Reached wave {g['wave']} having built only {g['blocks']} blocks."),
    ("Hoarder of useless things", "📦", lambda g: g.get("unused", -1), lambda g: "unused" in g,
     lambda g: f"{g['unused']} blocks that never carried an item or fired a shot."),
    ("Fiddler", "🔧", lambda g: g["acts"].get("configure", 0), lambda g: True,
     lambda g: f"Configured blocks {g['acts'].get('configure', 0)} times in one game "
               f"({g['acts'].get('configure', 0) / max(1, g['decisions']):.0%} of all its decisions)."),
    ("Deep thinker", "💭", lambda g: g["acts"].get("wait", 0), lambda g: True,
     lambda g: f"Chose to wait {g['acts'].get('wait', 0)} times. Wave {g['wave']}, core {g['core']:.0%}."),
    ("Speedrun to defeat", "💀", lambda g: -g["secs"], lambda g: g["outcome"] == "loss",
     lambda g: f"Core gone after {mmss(g['secs'])} of game time, at wave {g['wave']}."),
    ("Line artist", "📏", lambda g: g.get("linePlans", -1), lambda g: "linePlans" in g,
     lambda g: f"{g.get('lines', 0)} build lines planning {g['linePlans']} blocks. Wave {g['wave']}."),
    ("Wrecking ball", "🔨", lambda g: g["acts"].get("remove", 0), lambda g: True,
     lambda g: f"Removed {g['acts'].get('remove', 0)} of its own blocks."),
    ("Miner 49er", "⛏️", lambda g: g.get("mined", -1), lambda g: "mined" in g,
     lambda g: f"The ship mined {g['mined']} items itself. Wave {g['wave']}."),
]


def page(games):
    out = []
    for title, emoji, key, keep, caption in CATEGORIES:
        top = sorted((g for g in games if keep(g)), key=key, reverse=True)[:3]
        if not top:
            continue
        rows = []
        for i, g in enumerate(top):
            link = replay(g)
            watch = f'<a href="{link}">watch replay</a>' if link else '<span class="dim">no replay saved</span>'
            rows.append(f'<li class="{"first" if i == 0 else ""}"><b>Game {g["game"]}</b> · {where(g)} · '
                        f'{html.escape(caption(g))} · {watch}</li>')
        out.append(f'<section><h2>{emoji} {html.escape(title)}</h2><ol>{"".join(rows)}</ol></section>')
    n = len(games)
    return f"""<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><title>BloodBot highlights</title>
<style>
body {{ font: 15px/1.5 system-ui, sans-serif; background: #15161a; color: #e6e6e6; max-width: 900px; margin: 2em auto; padding: 0 1em; }}
h1 {{ margin-bottom: 0.2em; }} .sub {{ color: #9a9aa5; margin-top: 0; }}
section {{ background: #1e2026; border-radius: 10px; padding: 0.6em 1.2em; margin: 1em 0; }}
h2 {{ font-size: 1.15em; margin: 0.4em 0; }} ol {{ margin: 0.3em 0 0.6em; padding-left: 1.4em; }}
li {{ margin: 0.35em 0; color: #c4c4cc; }} li.first {{ color: #fff; }}
a {{ color: #ffb347; }} .dim {{ color: #6c6c78; }}
</style></head><body>
<h1>BloodBot highlights reel</h1>
<p class="sub">The strangest of {n:,} normal training games (practice starts left out). Top 3 per category.
<a href="index.html">All recordings</a> · <a href="scoreboard.html">Scoreboard</a></p>
{"".join(out)}
</body></html>
"""


def main():
    try:
        os.nice(19)
    except OSError:
        pass
    games = load()
    dest = RUNS / "highlights.html"
    dest.write_text(page(games))
    print(f"highlights: {dest} ({len(games)} games)")


if __name__ == "__main__":
    main()
