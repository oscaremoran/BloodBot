"""Restore a full PPO brain (policy AND value head) from a saved .pt (e.g. runs/ppo/best_frozenForest_30.pt) into
runs/ppo/checkpoint.pt, unlike rollback_ppo, which only swaps the policy. Game/update counters and the ladder state of
the current checkpoint are kept, so numbering continues. Optimizer reset. Backs up checkpoint.pt first.
Never run while training is running (2026-11-08: rollbacks kept a mismatched value head and slid within a few updates).

Usage: .venv/bin/python -m bloodbot.restore_ppo runs/ppo/best_frozenForest_30.pt
"""
import shutil
import sys
from pathlib import Path

import torch

from bloodbot.ppo import upgrade_dims


ROOT = Path(__file__).resolve().parent.parent
CKPT = ROOT / "runs" / "ppo" / "checkpoint.pt"


def main(src: Path):
    data = torch.load(CKPT, weights_only=False)
    other = torch.load(src, weights_only=False)
    net = dict(other["net"])
    if other.get("dims") and other["dims"] != data["dims"]:
        up = upgrade_dims(net, other["dims"], data["dims"])
        if up is None:
            raise SystemExit(f"can't upgrade {other['dims']} -> {data['dims']}")
        net = up[0]
    missing = set(data["net"]) ^ set(net)
    bad = [k for k in data["net"] if k in net and data["net"][k].shape != net[k].shape]
    if missing or bad:
        raise SystemExit(f"layout mismatch: keys {sorted(missing)[:5]} shapes {bad[:5]}")
    backup = CKPT.with_name(f"checkpoint.before-restore-{data['state']['updates']}.pt")
    shutil.copy2(CKPT, backup)
    data["net"] = net
    data.pop("opt", None)
    torch.save(data, CKPT)
    print(f"policy + value head <- {src.name} (update {other['state'].get('updates')}); optimizer reset; backup {backup.name}")


if __name__ == "__main__":
    main(Path(sys.argv[1]))
