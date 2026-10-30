"""Choose which saved training brains become the mod's difficulty levels.

Training saves the brain every 10 generations (runs/train/brains/gen_*.bin) plus the newest (latest.bin).
Easy/Normal/Hard come from snapshots a third, two thirds and most of the way through training; Insane is the
newest brain. Each is copied into mod/brains/ for mod/build.sh to bundle.

Usage: python3 -m bloodbot.pick_brains [runs/train]
"""
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def main(run_dir):
    brains = Path(run_dir) / "brains"
    snapshots = sorted(brains.glob("gen_*.bin"))
    latest = brains / "latest.bin"
    if not latest.exists():
        sys.exit(f"no brains in {brains} yet; training saves one after its first generation")
    pool = snapshots + [latest]

    def at(fraction):
        return pool[min(len(pool) - 1, round(fraction * (len(pool) - 1)))]

    out = ROOT / "mod" / "brains"
    out.mkdir(parents=True, exist_ok=True)
    for name, src in {"easy": at(1 / 3), "normal": at(2 / 3), "hard": at(0.9), "insane": latest}.items():
        shutil.copy(src, out / f"{name}.bin")
        print(f"{name:7s} <- {src.name}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else ROOT / "runs" / "train")
