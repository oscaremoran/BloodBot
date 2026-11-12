"""Self-imitation (2026-11-08, user's OK): keep the bot's own best games and keep learning from their moves.

PPO uses each game for one update and forgets it, so a rare win (Frozen Forest game 6484) barely moved the brain and
the following losses washed it out. This keeps a small library of the bot's own best normal games at the current
level (wins first, then furthest wave) and, after every PPO update, takes a few extra steps on moves from them.
Only moves whose real outcome (discounted return) beat the brain's current expectation (value) count, and only by
that margin (Oh et al. 2018, "Self-Imitation Learning"); once the brain expects the good result, a stored game stops
pulling. The library only ever holds the bot's own games, never the user's or a script's.

Storage: the vision grid is ~97% zeros, so each decision keeps only its non-zero cells (uint16 index + float16
value); masks are bit-packed. One 2,000-decision game is ~10 MB in memory and is also saved to runs/<run>/sil/
so the library survives restarts.
"""
import json
from pathlib import Path

import numpy as np
import torch

from bloodbot.ppo import evaluate

MASK_KEYS = ("type", "block", "cell", "mine", "deliver", "cells")


class Episode:
    """One game's decisions in compact form, built step by step while the game runs."""

    def __init__(self):
        self.idx, self.val, self.off = [], [], [0]
        self.scalars, self.masks, self.actions, self.rewards = [], [], [], []

    def add(self, obs, action):
        flat = obs["grid"].ravel()
        nz = np.flatnonzero(flat)
        self.idx.append(nz.astype(np.uint16))
        self.val.append(flat[nz].astype(np.float16))
        self.off.append(self.off[-1] + len(nz))
        self.scalars.append(obs["scalars"].astype(np.float32))
        self.masks.append(np.packbits(np.concatenate([obs[k].ravel() for k in MASK_KEYS])))
        self.actions.append(np.array(action, np.int16))
        self.rewards.append(0.0)

    def set_reward(self, r):
        if self.rewards:
            self.rewards[-1] = r

    def __len__(self):
        return len(self.actions)

    def finish(self, gamma):
        """Discounted return from every decision to the end of the game, then pack into arrays."""
        ret, running = np.zeros(len(self.rewards), np.float32), 0.0
        for t in range(len(self.rewards) - 1, -1, -1):
            running = self.rewards[t] + gamma * running
            ret[t] = running
        return {"idx": np.concatenate(self.idx) if self.idx else np.zeros(0, np.uint16),
                "val": np.concatenate(self.val) if self.val else np.zeros(0, np.float16),
                "off": np.array(self.off, np.int64), "scalars": np.stack(self.scalars), "masks": np.stack(self.masks),
                "actions": np.stack(self.actions), "returns": ret}


def rank_score(won, hit_turrets, wave_frac):
    """Library ranking (user's call, 2026-11-11): wins first, then bot-built turrets that hit an enemy, then wave
    reached (wave_frac = wave / winWave, at most 1). Before, it kept the furthest waves, which were all ship-only games
    with 0 fed turrets, so it taught the plateau."""
    return 100.0 * won + hit_turrets + wave_frac


class Library:
    """The best normal games per level, kept in memory and in runs/<run>/sil/."""

    def __init__(self, folder: Path, size: int, gamma: float = 0.995):
        self.folder, self.size = folder, size
        self.games = []  # dicts: meta + arrays
        folder.mkdir(parents=True, exist_ok=True)
        for f in sorted(folder.glob("game_*.npz")):
            try:
                with np.load(f) as z:
                    g = {k: z[k] for k in z.files if k != "meta"}
                    g["meta"] = json.loads(str(z["meta"]))
                old = g["meta"].get("gamma", 0.995)
                if abs(old - gamma) > 1e-9:
                    # Returns were discounted with another gamma: recover the rewards exactly (r_t = R_t - old * R_t+1)
                    # and discount them again, so stored games match the trainer's horizon.
                    R = g["returns"].astype(np.float64)
                    r = R - old * np.append(R[1:], 0.0)
                    out, run = np.zeros_like(R), 0.0
                    for t in range(len(r) - 1, -1, -1):
                        run = r[t] + gamma * run
                        out[t] = run
                    g["returns"] = out.astype(np.float32)
                    g["meta"]["gamma"] = gamma
                m = g["meta"]
                if "hitTurrets" not in m:  # stored before 2026-11-11: re-rank as wins, then turret hits (unknown = 0), then wave
                    m["hitTurrets"] = 0
                    m["score"] = rank_score(m["won"], 0, m["score"] - (1.0 if m["won"] else 0.0))
                g["file"] = f
                self.games.append(g)
            except Exception as e:  # a half-written file from a killed run
                print(f"self-imitation: skipped {f.name} ({e})", flush=True)

    def level_games(self, level):
        return [g for g in self.games if g["meta"]["level"] == level]

    def offer(self, packed, meta):
        """Keep the game if it is among the best `size` at its level. Returns True if kept."""
        same = sorted(self.level_games(meta["level"]), key=lambda g: (g["meta"]["score"], g["meta"]["game"]))
        if len(same) >= self.size and meta["score"] <= same[0]["meta"]["score"]:
            return False
        if len(same) >= self.size:
            drop = same[0]
            self.games = [g for g in self.games if g is not drop]  # by identity: dicts hold arrays, == breaks
            drop["file"].unlink(missing_ok=True)
        f = self.folder / f"game_{meta['game']:05d}.npz"
        np.savez_compressed(f, meta=json.dumps(meta), **packed)  # ~30x smaller (2026-11-10)
        g = dict(packed)
        g["meta"], g["file"] = meta, f
        self.games.append(g)
        return True

    def sample(self, level, n, rng):
        """n random decisions from this level's games, decoded to tensors (grid, scalars, masks, actions, returns)."""
        games = self.level_games(level)
        sizes = np.array([len(g["returns"]) for g in games])
        if not games or sizes.sum() == 0:
            return None
        which = rng.choice(len(games), size=n, p=sizes / sizes.sum())
        return games, which


def decode(games, which, rng, dims, mask_sizes):
    C, G = dims.C, dims.G
    grid = np.zeros((len(which), C * G * G), np.float32)
    scalars, masks, actions, returns = [], [], [], []
    for row, gi in enumerate(which):
        g = games[gi]
        t = rng.integers(len(g["returns"]))
        a, b = g["off"][t], g["off"][t + 1]
        grid[row, g["idx"][a:b].astype(np.int64)] = g["val"][a:b]
        scalars.append(g["scalars"][t])
        bits = np.unpackbits(g["masks"][t])[:sum(mask_sizes.values())].astype(bool)
        m, o = {}, 0
        for k in MASK_KEYS:
            m[k] = bits[o:o + mask_sizes[k]]
            o += mask_sizes[k]
        masks.append(m)
        actions.append(g["actions"][t])
        returns.append(g["returns"][t])
    out_masks = {k: torch.from_numpy(np.stack([m[k] for m in masks])) for k in MASK_KEYS}
    out_masks["cells"] = out_masks["cells"].reshape(len(which), -1, G * G)
    return (torch.from_numpy(grid.reshape(-1, C, G, G)), torch.from_numpy(np.stack(scalars)), out_masks,
            torch.from_numpy(np.stack(actions).astype(np.int64)), torch.from_numpy(np.array(returns, np.float32)))


def sil_update(net, opt, library, level, dims, mask_sizes, ret_scale, rng, batches=4, minibatch=256, coef=0.5,
               value_coef=0.01, max_grad=0.5, target_kl=0.02, ret_div=1.0, sgd_lr=0.01, min_adv=0.01):
    """A few extra steps on stored good moves. Advantage = max(return - value, 0) / ret_scale, capped at 1.
    The policy change is measured as the total KL over all steps on one fixed probe sample of stored moves
    (2026-11-10: the per-step figure understated it); stops early once that total passes 1.5x target_kl."""
    picked = library.sample(level, minibatch, rng)
    if picked is None:
        return None
    games, probe_which = picked
    probe = decode(games, probe_which, rng, dims, mask_sizes)
    with torch.no_grad():
        probe_old, _, _ = evaluate(net, probe[0], probe[1], probe[2], probe[3])
    stats = {"steps": 0, "skipped": 0, "pos": [], "adv": [], "kl": 0.0, "games": len(games)}
    for _ in range(batches):
        _, which = library.sample(level, minibatch, rng)
        grid, sc, masks, actions, returns = decode(games, which, rng, dims, mask_sizes)
        logp, _, value = evaluate(net, grid, sc, masks, actions)
        gap = (returns / ret_div - value).clamp(min=0) / ret_scale  # stored returns are raw; the value net is scaled
        adv = gap.detach().clamp(max=1.0)
        stats["pos"].append(float((adv > 0).float().mean()))
        stats["adv"].append(float(adv.mean()))
        if float(adv.mean()) < min_adv:  # almost nothing beat expectation: no step (2026-11-10)
            stats["skipped"] += 1
            continue
        loss = coef * (-(logp * adv).mean() + value_coef * 0.5 * (gap ** 2).mean())
        net.zero_grad()
        loss.backward()
        torch.nn.utils.clip_grad_norm_(net.parameters(), max_grad)
        # Plain gradient step, not Adam (2026-11-10): Adam makes every step about lr in size however weak the
        # signal, so self-imitation moved the policy as much as PPO on almost no signal. Here the step shrinks
        # with the advantage.
        with torch.no_grad():
            for prm in net.parameters():
                if prm.grad is not None:
                    prm.add_(prm.grad, alpha=-sgd_lr)
        net.zero_grad()
        with torch.no_grad():
            new_logp, _, _ = evaluate(net, probe[0], probe[1], probe[2], probe[3])
            r = new_logp - probe_old
            kl = float(((r.exp() - 1) - r).mean())
        stats["steps"] += 1
        stats["kl"] = kl
        if target_kl and kl > 1.5 * target_kl:
            break
    stats["pos"] = float(np.mean(stats["pos"]))
    stats["adv"] = float(np.mean(stats["adv"]))
    return stats
