"""Choose which saved training brains become the mod's difficulty levels.

PPO training (runs/ppo) saves the brain every 25 updates (brains/update_*.bin) plus the newest (brains/latest.bin);
python3 -m bloodbot.export_ppo makes latest.bin from the checkpoint at any time. The old evolution run (runs/train)
saved brains/gen_*.bin. Easy/Normal/Hard come from snapshots a third, two thirds and most of the way through training;
Insane is the newest brain. With fewer snapshots than levels, levels share a brain and differ only by randomness
(Difficulty.temperature). Each is copied into mod/brains/ for mod/build.sh to bundle.

Usage: python3 -m bloodbot.pick_brains [runs/ppo]
"""
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def upgrade_bin(raw: bytes) -> bytes:
    """Old brain files -> the current layout: enemy route (46 -> 48 map layers, 2026-11-06), then the "build line"
    action type (69 -> 70 scalars, one more output, 2026-11-08). New parts get zero weights (see ppo.add_type)."""
    return add_type_bin(add_route_bin(raw))


OLD_SCALARS, OLD_TYPES = 69, 18


def add_type_bin(raw: bytes) -> bytes:
    (n,) = struct.unpack(">i", raw[:4])
    shape = list(struct.unpack(f">{n}i", raw[4:4 + 4 * n]))
    if shape[5] != OLD_SCALARS:
        return raw
    c, g, k1, k2, k3, sc, h, out = shape
    off = 4 + 4 * n
    p = list(struct.unpack(f">{(len(raw) - off) // 4}f", raw[off:]))
    i = k1 * c * 9 + k1 + k2 * k1 * 9 + k2 + k3 * k2 * 9 + k3
    inp = k3 * (g // 8) ** 2 + sc
    d1 = [p[i + r * inp:i + (r + 1) * inp] for r in range(h)]
    head = p[:i]
    d1 = [row[:inp - 1] + [0.0] + row[inp - 1:] for row in d1]
    i += h * inp
    rest = p[i:i + h + h * h + h]  # d1 bias, d2 weight, d2 bias
    i += h + h * h + h
    ow = [p[i + r * h:i + (r + 1) * h] for r in range(out)]
    i += out * h
    ob = p[i:i + out]
    ow = ow[:OLD_TYPES] + [[0.0] * h] + ow[OLD_TYPES:]
    ob = ob[:OLD_TYPES] + [sum(ob[:OLD_TYPES]) / OLD_TYPES] + ob[OLD_TYPES:]
    shape[5], shape[7] = sc + 1, out + 1
    params = head + [x for row in d1 for x in row] + rest + [x for row in ow for x in row] + ob
    return struct.pack(f">{1 + n}i", n, *shape) + struct.pack(f">{len(params)}f", *params)


def add_route_bin(raw: bytes) -> bytes:
    (n,) = struct.unpack(">i", raw[:4])
    shape = list(struct.unpack(f">{n}i", raw[4:4 + 4 * n]))
    if shape[0] != 46:
        return raw
    c, k1, off, cell = shape[0], shape[2], 4 + 4 * n, 9 * 4  # 3x3 floats per input layer
    parts, pad = [], bytes(cell)
    for o in range(k1):
        row = raw[off + o * c * cell:off + (o + 1) * c * cell]
        parts += [row[:c // 2 * cell], pad, row[c // 2 * cell:], pad]
    shape[0] = c + 2
    return struct.pack(f">{1 + n}i", n, *shape) + b"".join(parts) + raw[off + k1 * c * cell:]


def main(run_dir):
    brains = Path(run_dir) / "brains"
    snapshots = sorted(brains.glob("update_*.bin")) + sorted(brains.glob("gen_*.bin"))
    latest = brains / "latest.bin"
    if not latest.exists():
        sys.exit(f"no brains in {brains} yet (PPO: .venv/bin/python -m bloodbot.export_ppo)")
    pool = snapshots + [latest]

    def at(fraction):
        return pool[min(len(pool) - 1, round(fraction * (len(pool) - 1)))]

    out = ROOT / "mod" / "brains"
    out.mkdir(parents=True, exist_ok=True)
    for name, src in {"easy": at(1 / 3), "normal": at(2 / 3), "hard": at(0.9), "insane": latest}.items():
        (out / f"{name}.bin").write_bytes(upgrade_bin(src.read_bytes()))
        print(f"{name:7s} <- {src.parent.parent.name}/{src.name}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else ROOT / "runs" / "ppo")
