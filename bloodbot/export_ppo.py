"""Export the PPO brain as a Brain.java file the mod can load.

Brain.java stores: int count, the shape {channels, grid, conv1, conv2, conv3, scalars, hidden, outputs}, then every
parameter as a big-endian float in this order: conv1 weights+bias, conv2, conv3, dense1, dense2, outputs. PyTorch keeps
conv weights as [out][in][ky][kx] and dense weights as [out][in], the same order Brain.java reads them in. The value
output is training-only and is left out.

Usage: .venv/bin/python -m bloodbot.export_ppo [checkpoint.pt] [out.bin]
"""
import struct
import sys
from pathlib import Path

import torch

ROOT = Path(__file__).resolve().parent.parent
LAYERS = ["c1", "c2", "c3", "d1", "d2", "out"]


def brain_bytes(net_state: dict, dims: list) -> bytes:
    C, G, S, NB, T, FINE, ROT, ARGS = dims
    hidden = net_state["d1.weight"].shape[0]
    k1, k2, k3 = (net_state[f"c{i}.weight"].shape[0] for i in (1, 2, 3))
    outputs = net_state["out.weight"].shape[0]
    shape = [C, G, k1, k2, k3, S, hidden, outputs]
    params = torch.cat([net_state[f"{n}.{p}"].flatten().float() for n in LAYERS for p in ("weight", "bias")])
    return struct.pack(f">{1 + len(shape)}i", len(shape), *shape) + struct.pack(f">{params.numel()}f", *params.tolist())


def export(ckpt: Path, out: Path):
    data = torch.load(ckpt, weights_only=False)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(brain_bytes(data["net"], data["dims"]))
    return data


if __name__ == "__main__":
    ckpt = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "runs" / "ppo" / "checkpoint.pt"
    out = Path(sys.argv[2]) if len(sys.argv) > 2 else ROOT / "runs" / "ppo" / "brains" / "latest.bin"
    data = export(ckpt, out)
    print(f"{out} <- {ckpt} ({data['state']['games']} games, {data['state']['updates']} updates)")
