"""Rank surveyed maps by difficulty to build a training ladder.

Usage: python -m bloodbot.survey_report runs/survey/library.jsonl runs/survey/builtin.jsonl
"""
import json
import math
import sys


def load(paths):
    rows = []
    for path in paths:
        source = path.rsplit("/", 1)[-1].removesuffix(".jsonl")
        with open(path) as f:
            for line in f:
                if line.strip():
                    row = json.loads(line)
                    row["source"] = source
                    rows.append(row)
    return rows


def trainable(row):
    """The bot's goal is destroying enemy cores, so a map needs one core on each side."""
    return "error" not in row and row["playerCores"] > 0 and row["enemyAtStart"]["cores"] > 0


def difficulty(row):
    """Heuristic score: how much there is to destroy and how hard the map hits a bot that does nothing."""
    enemy = row["enemyAtStart"]
    score = math.log10(1 + enemy["buildingHealth"])          # size of the enemy base
    score += 0.5 * enemy["cores"]
    score += 1.5 if enemy["ai"] else 0                           # enemy builds/attacks on its own
    score += 1.0 if row["waves"] and row["waveTimer"] else 0     # waves arrive on a timer
    if row["outcome"] == "loss":                                 # dying fast to an idle bot = hard
        score += 3.0 * (1 - min(row["gameSeconds"], 900) / 900)
    score += math.log2(row["width"] * row["height"] / 40000)    # bigger maps = longer games
    return score


def main(paths):
    rows = load(paths)
    good = sorted((r for r in rows if trainable(r)), key=difficulty)
    skipped = [r for r in rows if not trainable(r)]

    print(f"{'#':>2}  {'score':>5}  {'map':<34} {'size':>8}  {'enemy cores':>11} {'enemy HP':>9} {'ai':<9} {'waves':<5} {'idle bot result':<22}")
    for i, r in enumerate(good, 1):
        e = r["enemyAtStart"]
        result = f"{r['outcome']} @ {r['gameSeconds'] // 60}m{r['gameSeconds'] % 60:02d}s"
        print(f"{i:>2}  {difficulty(r):5.1f}  {r['name'][:34]:<34} {r['width']:>3}x{r['height']:<4}  {e['cores']:>11} {e['buildingHealth']:>9} "
              f"{e['ai'] or '-':<9} {'yes' if r['waves'] else 'no':<5} {result:<22}")

    print(f"\nNot trainable for 'destroy the enemy cores' ({len(skipped)}):")
    for r in skipped:
        why = r.get("error") or ("no player core" if r["playerCores"] == 0 else "no enemy core")
        print(f"  - {r.get('name', r['file'])}: {why}")


if __name__ == "__main__":
    main(sys.argv[1:])
