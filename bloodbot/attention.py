"""Attention heatmap: which parts of what the bot sees drove each decision it made.

For every saved frame of a recorded game (frames.npz, written by ppo_train), take the gradient of the chosen action's
log-probability with respect to the input map layers, times the input (|grad x input|): cells that were there AND
mattered light up. Shown on both views (minimap and close-up), frame by frame, plus which map layers mattered most.

Usage: nice -n 19 .venv/bin/python -m bloodbot.attention [game number ...]   ->  runs/attention.html
       (default: the newest recorded games that have frames.npz, up to 3)
"""
import base64
import json
import sys
from pathlib import Path

import numpy as np
import torch

from bloodbot.brain_mri import layer_names
from bloodbot.ppo import TYPE_NAMES, Dims, Net, evaluate

ROOT = Path(__file__).resolve().parent.parent
RUN = ROOT / "runs" / "ppo"
OUT = ROOT / "runs" / "attention.html"
MASKS = ("type", "block", "cell", "mine", "deliver")


def load_net():
    data = torch.load(RUN / "checkpoint.pt", weights_only=False)
    net = Net(Dims(*data["dims"]))
    net.load_state_dict(data["net"])
    net.eval()
    return net, data["state"]["updates"]


def analyse(net, frames):
    grid = torch.from_numpy(frames["grid"].astype(np.float32)).requires_grad_(True)
    scalars = torch.from_numpy(frames["scalars"].astype(np.float32))
    masks = {k: torch.from_numpy(frames[k]) for k in MASKS}
    actions = torch.from_numpy(frames["action"].astype(np.int64))
    logp, _, _ = evaluate(net, grid, scalars, masks, actions)
    logp.sum().backward()
    attr = (grid.grad * grid).abs().detach().numpy()  # [N, 2C, G, G]
    C = attr.shape[1] // 2
    return attr[:, :C].sum(1), attr[:, C:].sum(1), attr.sum((2, 3)), logp.exp().detach().numpy()


def b64(a: np.ndarray) -> str:
    return base64.b64encode(np.ascontiguousarray(a).tobytes()).decode()


def game_data(net, game_dir: Path):
    f = dict(np.load(game_dir / "frames.npz"))
    mini, close, per_layer, prob = analyse(net, f)
    N, C2, G, _ = f["grid"].shape
    C = C2 // 2
    # Normalise heat per frame (relative: what mattered most in this decision), as bytes.
    def q(h):
        m = h.reshape(N, -1).max(1)[:, None, None] + 1e-9
        return b64(np.round(h / m * 255).astype(np.uint8))
    # Which drawn layer to show in each cell (simple picture of what the bot saw): highest-priority present layer.
    def picture(view):
        g = f["grid"][:, view * C:(view + 1) * C].astype(np.float32)
        return b64(np.where(g.max(1) > 0.05, g.argmax(1) + 1, 0).astype(np.uint8))
    result = json.loads((game_dir / "result.json").read_text()) if (game_dir / "result.json").exists() else {}
    layer_share = per_layer.sum(0)
    return {"game": int(game_dir.name.split("_")[1]), "n": int(N), "G": int(G),
            "outcome": result.get("outcome", "?"), "wave": result.get("wave", "?"),
            "types": [TYPE_NAMES[int(t)] for t in f["action"][:, 0]],
            "cells": [int(c) for c in f["action"][:, 2]], "prob": [round(float(p), 4) for p in prob],
            "mini": q(mini), "close": q(close), "picMini": picture(0), "picClose": picture(1),
            "avgMini": [round(float(v), 4) for v in (mini / (mini.reshape(N, -1).max(1)[:, None, None] + 1e-9)).mean(0).ravel()],
            "layerMini": [round(float(v), 3) for v in layer_share[:C] / layer_share.sum()],
            "layerClose": [round(float(v), 3) for v in layer_share[C:] / layer_share.sum()]}


def main():
    dirs = sorted(RUN.glob("*/game_*/frames.npz"), key=lambda p: p.parent.name)
    if len(sys.argv) > 1:
        want = {int(a) for a in sys.argv[1:]}
        dirs = [p for p in dirs if int(p.parent.name.split("_")[1]) in want]
    else:
        dirs = dirs[-3:]
    if not dirs:
        raise SystemExit("no recorded games with frames.npz yet (they're saved by training runs from 2026-11-01 on)")
    net, updates = load_net()
    games = [game_data(net, p.parent) for p in dirs]
    page = Path(__file__).with_name("attention.html").read_text().replace("__DATA__", json.dumps({"names": layer_names(), "games": games, "updates": updates}))
    OUT.write_text(page)
    print(f"{OUT} ({', '.join(str(g['game']) for g in games)})")


if __name__ == "__main__":
    main()
