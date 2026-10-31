"""Build runs/index.html (recorded games) and runs/scoreboard.html (every game, and the best per map).

Usage: python -m bloodbot.gallery
"""
import csv
import html
import math
import json
from pathlib import Path

RUNS = Path(__file__).resolve().parent.parent / "runs"

STYLE = """
 body { background:#0d1117; color:#e6edf3; font-family:-apple-system,sans-serif; margin:24px; }
 a { color:#58a6ff; }
 .muted { color:#8b949e; }
 .grid { display:flex; flex-wrap:wrap; gap:16px; }
 .card { background:#161b22; border:1px solid #30363d; border-radius:8px; padding:12px; color:inherit; text-decoration:none; width:280px; line-height:1.6; }
 a.card:hover { border-color:#58a6ff; }
 .card img { width:100%; image-rendering:pixelated; border-radius:4px; background:#1a1712; }
 table { border-collapse:collapse; width:100%; font-size:13px; }
 th, td { padding:5px 8px; border-bottom:1px solid #21262d; text-align:left; white-space:nowrap; }
 th { cursor:pointer; color:#8b949e; position:sticky; top:0; background:#0d1117; }
 th:hover { color:#e6edf3; }
 tr:hover td { background:#161b22; }
 .win { color:#3fb950; font-weight:600; } .loss { color:#f85149; } .timeout { color:#d29922; }
 select { background:#21262d; color:#e6edf3; border:1px solid #30363d; border-radius:6px; padding:4px 8px; }
 nav { margin-bottom:16px; }
"""


def nice_map(name):
    return {"groundZero": "Ground Zero", "frozenForest": "Frozen Forest", "crateredBattleground": "Cratered Battleground",
            "fungalPass": "Fungal Pass", "ussIowa": "USS Iowa", "bloodshed_bastion": "Bloodshed Bastion"}.get(name, name)


def load_games():
    """Every game played: learning-bot games from each run's log.csv, plus scripted-benchmark games."""
    games = []
    for log in sorted(RUNS.glob("*/log.csv")):
        run = log.parent.name
        with open(log) as f:
            for row in csv.DictReader(f):
                replay = log.parent / row["map"] / f"game_{int(row['game']):05d}"
                games.append({
                    "run": run, "bot": "learning", "game": int(row["game"]), "generation": int(row["generation"]),
                    "who": "brain" if row["member"] == "brain" else f"variant {row['member']}",
                    "map": row["map"], "difficulty": float(row.get("difficulty") or 1), "outcome": row["outcome"],
                    "wave": int(row["wave"]), "winWave": int(row["winWave"]), "core": float(row["coreHealth"]),
                    "fitness": float(row["fitness"]), "minutes": int(row["gameSeconds"]) / 60,
                    "blocks": int(row.get("blocksBuilt") or 0),
                    "replay": replay.relative_to(RUNS).as_posix() if (replay / "replay.js").exists() else None,
                })
    for result in sorted(RUNS.glob("campaign/*/game_*/result.json")):
        r = json.loads(result.read_text())
        games.append({
            "run": "campaign", "bot": "scripted", "game": int(result.parent.name.split("_")[1]), "generation": None,
            "who": "scripted benchmark", "map": r["map"], "difficulty": 1.0, "outcome": r["outcome"],
            "wave": r["wave"], "winWave": r["winWave"], "core": r.get("coreHealth", 0), "fitness": None,
            "minutes": r["gameSeconds"] / 60, "blocks": r.get("blocksBuilt", 0),
            "replay": result.parent.relative_to(RUNS).as_posix() if (result.parent / "replay.js").exists() else None,
        })
    return games


# ---- Evolution: a progress scale from 1 (random start) to 1,000,000 (beats Bloodshed Bastion), in powers of ten ----
# Milestones (log10): 1 builds on purpose, 2 fed turrets, 3 beats GZ@30%, 3.48 GZ@100%, 4 FF, 4.48 Craters,
# 5 Fungal Pass, 5.48 USS Iowa, 6 Bloodshed Bastion. Each map's span is split across its 4 enemy-strength levels.
EVO_LADDER = ["groundZero", "frozenForest", "crateredBattleground", "fungalPass", "ussIowa", "bloodshed_bastion"]
EVO_END = [3.477, 4.0, 4.477, 5.0, 5.477, 6.0]
EVO_LEVELS = [0.3, 0.55, 0.8, 1.0]


def evo_win(m, level):
    """log10 evolution for winning map m at enemy-strength level index (0..3)."""
    if m == 0:  # Ground Zero: the first win (30%) is the 1,000 milestone, 100% is 3,000
        return 3.0 + (EVO_END[0] - 3.0) * level / 3
    start = EVO_END[m - 1]
    return start + (EVO_END[m] - start) * (level + 1) / 4


def evolution(g):
    """Evolution shown by one game."""
    if g["map"] not in EVO_LADDER:
        return None
    m = EVO_LADDER.index(g["map"])
    level = min(range(4), key=lambda i: abs(EVO_LEVELS[i] - g["difficulty"]))
    if g["outcome"] == "win":
        return 10 ** evo_win(m, level)
    progress = min(1.0, g["wave"] / g["winWave"]) if g["winWave"] else 0.5
    if m == 0 and level == 0:  # before any win: 1 for building at all, up to 2 for surviving
        return 10 ** ((1.0 if g["blocks"] > 0 else 0.0) + progress)
    below = evo_win(m, level - 1) if level > 0 else (EVO_END[m - 1] if m > 0 else 2.0)
    return 10 ** (below + (evo_win(m, level) - below) * 0.9 * progress)


def fmt_evo(e):
    return "" if e is None else f"{e:,.0f}" if e >= 10 else f"{e:.1f}"


def current_evolution(games, run):
    """The bot's level now: a typical recent game (75th percentile of the last 30), not its single luckiest one."""
    recent = [evolution(g) for g in games if g["run"] == run][-30:]
    recent = sorted(e for e in recent if e is not None)
    return recent[int(0.75 * (len(recent) - 1))] if recent else None


EVO_MARKS = [(0, "Random"), (3.0, "Ground Zero"), (EVO_END[0], "Frozen Forest"), (EVO_END[1], "Craters"),
             (EVO_END[2], "Fungal Pass"), (EVO_END[3], "USS Iowa"), (EVO_END[4], "Bloodshed Bastion")]
# Human skill levels on the same scale (log10), roughly what a human player at that level can beat.
# Nobody has ever beaten Bloodshed Bastion, so the top of the scale is beyond every human; Oscar is past USS Iowa,
# halfway to it.
HUMAN_LEVELS = [(3.0, "Noob"), (EVO_END[0], "Bad"), (4.0, "Fine"), (EVO_END[2], "Good"), (5.0, "Veteran"),
                ((EVO_END[4] + EVO_END[5]) / 2, "Oscar"), (6.0, "Nobody yet")]


def evo_num(v):
    """A log10 position as its real evolution number: 1,000 / 14,678 / 1,000,000."""
    return f"{10 ** v:,.0f}"


def _bar(lo, hi, lg, ticks, height):
    """One bar from log10 lo to hi, filled up to lg. ticks: (v, label above or None, label below or None, strong)."""
    pos = lambda v: f"{(v - lo) / (hi - lo) * 100:.2f}%"
    shift = lambda v: "0" if v <= lo else "-100%" if v >= hi else "-50%"
    fill = max(0.0, min(1.0, (lg - lo) / (hi - lo))) * 100
    parts = []
    for v, above, below, strong in ticks:
        parts.append(f'<div style="position:absolute;left:{pos(v)};top:0;height:{height}px;'
                     f'border-left:{2 if strong else 1}px solid {"#fffa" if strong else "#fff5"}"></div>')
        if above:
            parts.append(f'<div style="position:absolute;left:{pos(v)};bottom:{height + 6}px;transform:translateX({shift(v)});'
                         f'text-align:center;white-space:nowrap;line-height:15px">{above}</div>')
        if below:
            parts.append(f'<div style="position:absolute;left:{pos(v)};top:{height + 6}px;transform:translateX({shift(v)});'
                         f'text-align:center;white-space:nowrap;line-height:15px">{below}</div>')
    return (f'<div style="position:relative;height:{height}px;border-radius:{height // 2}px;background:#8884">'
            f'<div style="height:100%;width:{fill:.2f}%;border-radius:{height // 2}px;'
            f'background:linear-gradient(90deg,#c0392b,#ffd37f)"></div>{"".join(parts)}'
            f'<div style="position:absolute;left:{fill:.2f}%;top:-6px;height:{height + 12}px;border-left:3px solid #fff"></div></div>')


def evo_bar(e):
    """Two log-scale bars: the whole ladder (1 -> 1,000,000) with human skill levels and maps, then a zoom on the
    bot's current map with each enemy-strength level and its evolution number."""
    lg = math.log10(max(e, 1))
    gold = lambda t: f'<b style="color:#ffd37f;font-size:14px">{t}</b>'
    small = lambda t: f'<span class="muted" style="font-size:12px">{t}</span>'
    human = {v: name for v, name in HUMAN_LEVELS}
    maps = {v: name for v, name in EVO_MARKS}
    ticks = [(v, gold(human[v]) if v in human else None,
              f'{maps[v]}<br>{small(evo_num(v))}' if v in maps else small(evo_num(v)) if v in (1, 2) else None, v in maps)
             for v in sorted(set(human) | set(maps) | {1, 2})]
    whole = _bar(0, 6, lg, ticks, 56)
    # Zoom on the current map: from where it starts to where it ends, one tick per enemy-strength level.
    m = next((k for k in range(len(EVO_LADDER)) if lg < EVO_END[k] - 1e-9), len(EVO_LADDER) - 1)
    lo = (3.0 if m == 0 else EVO_END[m - 1])
    if lg < lo:
        lo = 2.0
    hi = EVO_END[m]
    zticks = [(lo, None, small(evo_num(lo)), True)] + [
        (evo_win(m, lv), f'{int(EVO_LEVELS[lv] * 100)}% enemy strength', small(evo_num(evo_win(m, lv))), True)
        for lv in range(len(EVO_LEVELS)) if evo_win(m, lv) > lo]
    zoom = _bar(lo, hi, lg, zticks, 30)
    name = dict(EVO_MARKS[1:])[3.0 if m == 0 else EVO_END[m - 1]]
    return (f'<div style="margin:44px 8px 64px">{whole}</div>'
            f'<div class="muted" style="font-size:13px;margin:0 0 30px">Zoom: {name}</div>'
            f'<div style="margin:0 8px 40px">{zoom}</div>')


def rank(g):
    """Best = won, then at higher enemy strength, then higher score (or further wave for the scripted bot)."""
    return (g["outcome"] == "win", g["difficulty"], g["fitness"] if g["fitness"] is not None else -1, g["wave"], g["core"])


def wave_text(g):
    return f"{g['wave']}/{g['winWave']}" if g["winWave"] else str(g["wave"])


def write_scoreboard(games):
    maps = sorted({g["map"] for g in games}, key=lambda m: ["groundZero", "frozenForest", "crateredBattleground",
                                                             "fungalPass", "ussIowa", "bloodshed_bastion", m].index(m))
    best_cards = []
    for m in maps:
        lines = []
        for bot in ("learning", "scripted"):
            pool = [g for g in games if g["map"] == m and g["bot"] == bot]
            if not pool:
                continue
            b = max(pool, key=rank)
            link = f' · <a href="viewer.html?game={b["replay"]}">watch</a>' if b["replay"] else ""
            score = f' · score {b["fitness"]:.1f}' if b["fitness"] is not None else ""
            lines.append(f"""<div><b>{'Learning bot' if bot == 'learning' else 'Scripted benchmark'}</b>
              <span class="muted">({len(pool)} games)</span><br>
              <span class="{b['outcome']}">{b['outcome'].upper()}</span> · wave {wave_text(b)} · {int(b['difficulty'] * 100)}% enemy strength{score}<br>
              <span class="muted">game {b['game']} ({b['who']}) · {b['minutes']:.0f} min · core {b['core']:.0%}</span>{link}</div>""")
        best_cards.append(f'<div class="card"><h3 style="margin:0 0 8px">{nice_map(m)}</h3>{"<br>".join(lines)}</div>')

    rows = []
    for g in sorted(games, key=lambda g: (g["run"], g["game"])):
        link = f'<a href="viewer.html?game={g["replay"]}">watch</a>' if g["replay"] else ""
        rows.append(f"""<tr data-map="{g['map']}" data-run="{html.escape(g['run'])}" data-gen="{'' if g['generation'] is None else g['generation']}">
          <td>{html.escape(g['run'])}</td><td>{g['game']}</td><td>{g['generation'] if g['generation'] is not None else ''}</td>
          <td>{html.escape(g['who'])}</td><td>{nice_map(g['map'])}</td><td>{int(g['difficulty'] * 100)}%</td>
          <td class="{g['outcome']}">{g['outcome']}</td><td data-v="{g['wave']}">{wave_text(g)}</td>
          <td>{g['core']:.0%}</td><td>{'' if g['fitness'] is None else f"{g['fitness']:.1f}"}</td>
          <td data-v="{evolution(g) or 0}">{fmt_evo(evolution(g))}</td><td>{g['minutes']:.1f}</td><td>{g['blocks']}</td><td>{link}</td></tr>""")

    runs = sorted({g["run"] for g in games if g["bot"] == "learning"}, key=lambda r: max(x["game"] for x in games if x["run"] == r))
    latest = max(runs, key=lambda r: (RUNS / r / "log.csv").stat().st_mtime) if runs else None
    cur = current_evolution(games, latest) if latest else None
    evo_banner = (f'<div style="margin:-24px -24px 24px;padding:16px 24px 6px;background:#161b22;border-bottom:1px solid #30363d">'
                  f'<div style="display:flex;justify-content:space-between;align-items:baseline;flex-wrap:wrap;gap:8px">'
                  f'<span><span class="muted">Current evolution ({latest})</span> '
                  f'<span style="font-size:28px;font-weight:700;margin-left:8px">{fmt_evo(cur)}</span>'
                  f'<span style="font-size:28px;font-weight:700;margin-left:16px;color:#ffd37f">'
                  f'{max(0.0, min(1.0, math.log10(max(cur, 1)) / 6)) * 100:.1f}%</span></span>'
                  f'<span class="muted">Logarithmic scale (each step = ×10) · 1 = random start · 1,000 = beats Ground Zero at 30% · 1,000,000 = beats Bloodshed Bastion</span></div>'
                  f'{evo_bar(cur)}</div>'
                  if cur is not None else "")
    options = "".join(f'<option value="{m}">{nice_map(m)}</option>' for m in maps)
    run_options = "".join(f'<option value="{html.escape(r)}"{" selected" if r == latest else ""}>{html.escape(r)}</option>'
                          for r in sorted({g["run"] for g in games}))
    (RUNS / "scoreboard.html").write_text(f"""<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>BloodBot scoreboard</title><style>{STYLE}</style></head><body>
{evo_banner}
<nav><a href="index.html">Recordings</a> · <b>Scoreboard</b></nav>
<h1>Scoreboard</h1>
<h2>Best on each map</h2>
<p class="muted">Best = a win beats a loss, then higher enemy strength, then higher score.</p>
<div class="grid">{''.join(best_cards) or '<p class="muted">No games yet.</p>'}</div>
<h2>All games <span class="muted" style="font-size:14px">({len(games)})</span></h2>
<p>Map: <select id="filter"><option value="">all maps</option>{options}</select>
Run: <select id="runFilter"><option value="">all runs</option>{run_options}</select>
Generation: <input id="genFilter" size="8" placeholder="e.g. 40 or 30-40" title="a number, or a range like 30-40; blank = all">
<span class="muted">Click a column to sort.</span></p>
<table id="games"><thead><tr>
<th>run</th><th>game</th><th>generation</th><th>player</th><th>map</th><th>enemy strength</th><th>result</th><th>wave</th>
<th>core left</th><th>score</th><th>evolution</th><th>minutes</th><th>blocks built</th><th>replay</th>
</tr></thead><tbody>{''.join(rows)}</tbody></table>
<script>
const table = document.getElementById("games"), body = table.tBodies[0];
const mapSel = document.getElementById("filter"), runSel = document.getElementById("runFilter"), genBox = document.getElementById("genFilter");
const applyFilters = () => {{
  // Generation box: blank = all, "40" = just that one, "30-40" = a range.
  const m = genBox.value.trim().match(/^(\\d+)\\s*(?:-\\s*(\\d+))?$/);
  const lo = m ? +m[1] : null, hi = m ? +(m[2] ?? m[1]) : null;
  for (const r of body.rows) {{
    const gen = r.dataset.gen === "" ? null : +r.dataset.gen;
    const genOk = lo === null || (gen !== null && gen >= lo && gen <= hi);
    r.style.display = (!mapSel.value || r.dataset.map === mapSel.value) && (!runSel.value || r.dataset.run === runSel.value) && genOk ? "" : "none";
  }}
}};
mapSel.onchange = runSel.onchange = applyFilters;
genBox.oninput = applyFilters;
applyFilters();
let sortCol = -1, asc = true;
table.tHead.rows[0].querySelectorAll("th").forEach((th, i) => th.onclick = () => {{
  asc = sortCol === i ? !asc : false; sortCol = i;
  const val = r => {{ const c = r.cells[i], v = c.dataset.v ?? c.textContent.replace("%", ""); return isNaN(parseFloat(v)) ? v : parseFloat(v); }};
  [...body.rows].sort((a, b) => (val(a) > val(b) ? 1 : val(a) < val(b) ? -1 : 0) * (asc ? 1 : -1)).forEach(r => body.appendChild(r));
}});
</script>
</body></html>
""")


def write_index():
    sections = {}
    for replay in sorted(RUNS.glob("*/*/game_*/replay.js")):
        game_dir = replay.parent
        rel = game_dir.relative_to(RUNS).as_posix()
        result = json.loads((game_dir / "result.json").read_text())
        colour = {"win": "#3fb950", "loss": "#f85149"}.get(result["outcome"], "#d29922")
        wave = f"{result['wave']}/{result['winWave']}" if result.get("winWave") else str(result["wave"])
        extra = f" · {int(result['difficulty'] * 100)}% strength" if "difficulty" in result else ""
        card = f"""
  <a class="card" href="viewer.html?game={rel}">
    <img src="{rel}/final.png">
    <div><b>{nice_map(game_dir.parent.name)}</b> · {game_dir.name.replace('_', ' ')}</div>
    <div><b style="color:{colour}">{result['outcome'].upper()}</b> · wave {wave} · {result['gameSeconds'] // 60} min{extra}</div>
  </a>"""
        sections.setdefault(game_dir.parent.parent.name, []).append(card)

    body = "".join(f"<h2>{html.escape(name)}</h2><div class='grid'>{''.join(cards)}</div>" for name, cards in sections.items())
    (RUNS / "index.html").write_text(f"""<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>BloodBot recordings</title><style>{STYLE}</style></head><body>
<nav><b>Recordings</b> · <a href="scoreboard.html">Scoreboard</a></nav>
<h1>BloodBot recordings</h1>
<p class="muted">Click a game to open the replay viewer: zoom, pan, scrub the timeline, hover any tile to see its block.
Yellow = the bot, red = enemy.</p>
{body or '<p class="muted">No recordings yet.</p>'}
</body></html>
""")


def main():
    write_index()
    write_scoreboard(load_games())
    print(f"recordings: {RUNS / 'index.html'}\nscoreboard: {RUNS / 'scoreboard.html'}")


if __name__ == "__main__":
    main()
