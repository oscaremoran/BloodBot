"""PPO for BloodBot: the network (same shape as common/.../Brain.java, plus a value head) and the update step.

An action is 7 choices: type, block, map cell (row + column outputs), fine x, fine y, rotation, extra arg.
Only the choices that matter for the chosen type count towards its probability (e.g. "ship guard" ignores
the map cell), and every choice is limited to what is possible right now (the masks the game sends).
"""
import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

# Action types, in the game's order (Agent.TYPE_NAMES).
TYPE_NAMES = ["wait", "build", "remove", "rebuild", "supply items", "supply power", "supply liquid", "configure",
              "army move", "army attack", "ship guard", "ship mine", "ship fight", "ship deliver",
              "logic link", "logic write", "payload pick up", "payload drop"]
SHIP_MINE, SHIP_DELIVER = 11, 13
# Actions that need a target (Agent.TARGETED): the map cell is chosen after the action, among the cells where it can work.
TARGETED = [2, 4, 5, 6, 7, 13]
HEADS = ["block", "cell", "fx", "fy", "rot", "arg"]
_PLACE = {"cell", "fx", "fy"}
RELEVANT = [set(), set(HEADS), _PLACE, _PLACE, _PLACE, _PLACE, _PLACE, _PLACE | {"rot", "arg"},
            _PLACE | {"arg"}, _PLACE | {"arg"}, set(), {"arg"}, _PLACE, _PLACE | {"arg"},
            _PLACE, _PLACE | {"rot", "arg"}, _PLACE | {"arg"}, _PLACE | {"arg"}]


class Dims:
    def __init__(self, C, G, S, NB, T, FINE, ROT, ARGS):
        self.C, self.G, self.S, self.NB, self.T, self.FINE, self.ROT, self.ARGS = C, G, S, NB, T, FINE, ROT, ARGS
        self.out = T + NB + 2 * G + 2 * FINE + ROT + ARGS
        assert T == len(TYPE_NAMES), f"game has {T} action types, trainer knows {len(TYPE_NAMES)}"

    def as_list(self):
        return [self.C, self.G, self.S, self.NB, self.T, self.FINE, self.ROT, self.ARGS]


class Net(nn.Module):
    """Brain.java's layout (conv 3x3 stride 2 x3, tanh; dense x2, tanh; linear outputs) plus a value output."""

    def __init__(self, d: Dims, hidden=256):  # 64 until 2026-10-31
        super().__init__()
        self.d = d
        self.c1 = nn.Conv2d(d.C, 12, 3, 2, 1)
        self.c2 = nn.Conv2d(12, 16, 3, 2, 1)
        self.c3 = nn.Conv2d(16, 16, 3, 2, 1)
        flat = 16 * (d.G // 8) ** 2
        self.d1 = nn.Linear(flat + d.S, hidden)
        self.d2 = nn.Linear(hidden, hidden)
        self.out = nn.Linear(hidden, d.out)
        self.value = nn.Linear(hidden, 1)
        nn.init.orthogonal_(self.out.weight, 0.01)  # start near-uniform over choices
        nn.init.zeros_(self.out.bias)

    def forward(self, grid, scalars):
        x = torch.tanh(self.c1(grid))
        x = torch.tanh(self.c2(x))
        x = torch.tanh(self.c3(x)).flatten(1)
        h = torch.tanh(self.d1(torch.cat([x, scalars], 1)))
        h = torch.tanh(self.d2(h))
        return self.out(h), self.value(h).squeeze(1)


@torch.no_grad()
def widen(old: dict, new: Net) -> dict:
    """Copy a narrower brain's weights into the first neurons of a wider one. The extra neurons start with random
    inputs but zero outputs, so the wider brain plays exactly like the old one and grows into the new room."""
    sd = {k: v.clone() for k, v in new.state_dict().items()}
    h = old["d1.weight"].shape[0]
    for k in ("c1.weight", "c1.bias", "c2.weight", "c2.bias", "c3.weight", "c3.bias"):
        sd[k] = old[k].clone()
    sd["d1.weight"][:h] = old["d1.weight"]
    sd["d1.bias"][:h] = old["d1.bias"]
    sd["d2.weight"][:h, :h] = old["d2.weight"]
    sd["d2.weight"][:h, h:] = 0
    sd["d2.bias"][:h] = old["d2.bias"]
    for k in ("out", "value"):
        sd[f"{k}.weight"].zero_()
        sd[f"{k}.weight"][:, :h] = old[f"{k}.weight"]
        sd[f"{k}.bias"] = old[f"{k}.bias"].clone()
    return sd


@torch.no_grad()
def add_closeup(old: dict) -> dict:
    """A minimap-only brain -> the two-view brain (2026-10-31): the close-up layers get zero weights, so the brain
    plays exactly as before and learns to use them."""
    sd = {k: v.clone() for k, v in old.items()}
    w = old["c1.weight"]
    sd["c1.weight"] = torch.cat([w, torch.zeros_like(w)], 1)
    return sd


@torch.no_grad()
def add_route(old: dict) -> dict:
    """46 map layers -> 48 (2026-11-06): the enemy route goes last in each view, with zero weights, so the brain plays
    exactly as before and learns to use it."""
    sd = {k: v.clone() for k, v in old.items()}
    w = old["c1.weight"]
    half = w.shape[1] // 2
    pad = torch.zeros_like(w[:, :1])
    sd["c1.weight"] = torch.cat([w[:, :half], pad, w[:, half:], pad], 1)
    return sd


def split(d: Dims, logits, masks):
    """Masked logits per head. masks: dict of bool tensors type, block, cell, mine, deliver."""
    o = 0
    def take(n):
        nonlocal o
        part = logits[:, o:o + n]
        o += n
        return part
    t, b, lx, ly, fx, fy, rot, arg = (take(d.T), take(d.NB), take(d.G), take(d.G), take(d.FINE), take(d.FINE),
                                     take(d.ROT), take(d.ARGS))
    cell = (ly[:, :, None] + lx[:, None, :]).reshape(logits.shape[0], -1)  # cell index = row * G + col
    neg = -1e9

    def mask(x, m):
        m = m.clone()
        m[~m.any(1)] = True  # nothing possible: leave unmasked (the head is irrelevant then anyway)
        return x.masked_fill(~m, neg)
    return {"type": mask(t, masks["type"]), "block": mask(b, masks["block"]), "cell": mask(cell, masks["cell"]), "cell_raw": cell,
            "fx": fx, "fy": fy, "rot": rot, "arg_mine": mask(arg, masks["mine"]), "arg_deliver": mask(arg, masks["deliver"]),
            "arg": arg}


def arg_logits(heads, types):
    """The arg head's mask depends on the action type (ores to mine, items to deliver)."""
    a = heads["arg"].clone()
    mine, deliver = types == SHIP_MINE, types == SHIP_DELIVER
    a[mine] = heads["arg_mine"][mine]
    a[deliver] = heads["arg_deliver"][deliver]
    return a


def cell_logits(heads, masks, types):
    """The cell head's mask depends on the action type (2026-11-06). Replays saved before this have no "cells"."""
    if "cells" not in masks:
        return heads["cell"]
    m = masks["cell"].clone()
    for i, t in enumerate(TARGETED):
        rows = types == t
        m[rows] = masks["cells"][rows, i]
    m[~m.any(1)] = True
    return heads["cell_raw"].masked_fill(~m, -1e9)


def relevance(types):
    """[B, len(HEADS)] float: which heads count for each action's type."""
    table = torch.tensor([[1.0 if h in RELEVANT[t] else 0.0 for h in HEADS] for t in range(len(TYPE_NAMES))])
    return table[types]


def evaluate(net, grid, scalars, masks, actions):
    """Log-probability of stored actions, entropy, and value: what the PPO update needs."""
    logits, value = net(grid, scalars)
    heads = split(net.d, logits, masks)
    types = actions[:, 0]
    dists = {"block": heads["block"], "cell": cell_logits(heads, masks, types), "fx": heads["fx"], "fy": heads["fy"],
             "rot": heads["rot"], "arg": arg_logits(heads, types)}
    cols = {"block": 1, "cell": 2, "fx": 3, "fy": 4, "rot": 5, "arg": 6}
    rel = relevance(types)
    tl = F.log_softmax(heads["type"], 1)
    logp = tl.gather(1, types[:, None]).squeeze(1)
    ent = -(tl.exp() * tl).sum(1)
    for i, h in enumerate(HEADS):
        lp = F.log_softmax(dists[h], 1)
        logp = logp + rel[:, i] * lp.gather(1, actions[:, cols[h]][:, None]).squeeze(1)
        ent = ent + rel[:, i] * -(lp.exp() * lp).sum(1)
    return logp, ent, value


@torch.no_grad()
def act(net, grid, scalars, masks):
    """Sample one action for one observation: (7 ints, logp, value)."""
    logits, value = net(grid, scalars)
    heads = split(net.d, logits, masks)
    t = torch.distributions.Categorical(logits=heads["type"]).sample()
    heads["arg"] = arg_logits(heads, t)
    heads["cell"] = cell_logits(heads, masks, t)
    a = [t]
    for h in HEADS:
        a.append(torch.distributions.Categorical(logits=heads[h]).sample())
    actions = torch.stack(a, 1)
    logp, _, _ = evaluate(net, grid, scalars, masks, actions)
    return actions[0].tolist(), float(logp[0]), float(value[0])


def ppo_update(net, opt, batch, epochs=4, minibatch=256, clip=0.2, vf_coef=0.5, ent_coef=0.01, max_grad=0.5):
    """Standard clipped PPO on one batch of collected decisions."""
    n = batch["actions"].shape[0]
    adv = batch["adv"]
    adv = (adv - adv.mean()) / (adv.std() + 1e-8)
    stats = []
    for _ in range(epochs):
        for idx in torch.randperm(n).split(minibatch):
            masks = {k: batch["masks"][k][idx] for k in batch["masks"]}
            logp, ent, value = evaluate(net, batch["grid"][idx].float(), batch["scalars"][idx], masks, batch["actions"][idx])
            ratio = (logp - batch["logp"][idx]).exp()
            a = adv[idx]
            policy_loss = -torch.min(ratio * a, ratio.clamp(1 - clip, 1 + clip) * a).mean()
            value_loss = F.mse_loss(value, batch["returns"][idx])
            loss = policy_loss + vf_coef * value_loss - ent_coef * ent.mean()
            opt.zero_grad()
            loss.backward()
            nn.utils.clip_grad_norm_(net.parameters(), max_grad)
            opt.step()
            stats.append((policy_loss.item(), value_loss.item(), ent.mean().item()))
    s = np.array(stats).mean(0)
    return {"policy_loss": s[0], "value_loss": s[1], "entropy": s[2]}


def gae(rewards, values, dones, last_value, gamma=0.995, lam=0.95):
    """Advantages and returns for one worker's decisions, in order."""
    adv = np.zeros(len(rewards), np.float32)
    running = 0.0
    for i in reversed(range(len(rewards))):
        next_value = last_value if i == len(rewards) - 1 else values[i + 1]
        nonterminal = 0.0 if dones[i] else 1.0
        delta = rewards[i] + gamma * next_value * nonterminal - values[i]
        running = delta + gamma * lam * nonterminal * running
        adv[i] = running
    return adv, adv + np.array(values, np.float32)
