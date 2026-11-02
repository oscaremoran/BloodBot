"""Roll the PPO policy back to a saved brain snapshot (runs/ppo/brains/update_NNNNN.bin).

Snapshots hold only the policy (convs, dense layers, outputs), so the current value head is kept and the optimizer is
reset. Game/update counters and the ladder state are kept, so numbering continues. Backs up checkpoint.pt first.
Never run while training is running (the trainer would overwrite the checkpoint).

Usage: .venv/bin/python -m bloodbot.rollback_ppo runs/ppo/brains/update_00550.bin
"""
import shutil
import struct
import sys
from pathlib import Path

import torch

from bloodbot.export_ppo import LAYERS

ROOT = Path(__file__).resolve().parent.parent
CKPT = ROOT / "runs" / "ppo" / "checkpoint.pt"


def main(snapshot: Path):
    data = torch.load(CKPT, weights_only=False)
    raw = snapshot.read_bytes()
    (n,) = struct.unpack(">i", raw[:4])
    off = 4 + 4 * n
    floats = torch.tensor(struct.unpack(f">{(len(raw) - off) // 4}f", raw[off:]))
    net = data["net"]
    i = 0
    for name in LAYERS:
        for p in ("weight", "bias"):
            t = net[f"{name}.{p}"]
            net[f"{name}.{p}"] = floats[i:i + t.numel()].reshape(t.shape).to(t.dtype)
            i += t.numel()
    if i != floats.numel():
        raise SystemExit(f"snapshot size mismatch: {floats.numel()} floats, checkpoint policy needs {i}")
    backup = CKPT.with_name(f"checkpoint.before-rollback-{data['state']['updates']}.pt")
    shutil.copy2(CKPT, backup)
    data.pop("opt", None)
    torch.save(data, CKPT)
    print(f"policy <- {snapshot.name}; value head kept; optimizer reset; backup {backup.name}")


if __name__ == "__main__":
    main(Path(sys.argv[1]))
