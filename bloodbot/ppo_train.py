"""Train BloodBot with PPO: starts the game servers, plays games through them, learns from every decision.

Usage (from the BloodBot folder):
  .venv/bin/python -m bloodbot.ppo_train [--servers 1] [--speed 40] [--games N] [--minutes M]

Resumes from runs/ppo/ if a checkpoint exists. Safe to stop any time (Ctrl+C); at most the games in
progress and the decisions since the last update are lost. Uses the same map ladder and difficulty steps
as the evolution trainer, and writes the same log/recordings, so the scoreboard and viewer just work.
"""
import argparse
import csv
import json
import os
import signal
import socket
import struct
import subprocess
import threading
import time
from collections import deque
from pathlib import Path

import numpy as np
import torch

from bloodbot import gallery
from bloodbot.ppo import Dims, Net, act, gae, ppo_update

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "runs" / "ppo"
M = ROOT / "maps" / "campaign"
LADDER = [("groundZero", 10), ("frozenForest", 15), ("crateredBattleground", 20), ("fungalPass", 0), ("ussIowa", 0)]
DIFFICULTY = [0.3, 0.55, 0.8, 1.0]
LOG_COLUMNS = ["game", "generation", "member", "map", "difficulty", "outcome", "wave", "winWave", "coreHealth",
               "fitness", "gameSeconds", "realSeconds", "blocksBuilt", "actions"]


def scheduled(game):
    if game == 1:
        return True
    scale = 1
    while scale <= game:
        if game in (5 * scale, 10 * scale, 25 * scale):
            return True
        scale *= 10
    return False


# ---------------------------------------------------------------- wire format (big-endian, see PpoClient.java)

def recv_exact(sock, n):
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("game server disconnected")
        buf.extend(chunk)
    return bytes(buf)


def recv_int(sock):
    return struct.unpack(">i", recv_exact(sock, 4))[0]


def recv_float(sock):
    return struct.unpack(">f", recv_exact(sock, 4))[0]


def recv_utf(sock):
    n = struct.unpack(">H", recv_exact(sock, 2))[0]
    return recv_exact(sock, n).decode("utf-8")


def send_utf(sock, kind, text):
    data = text.encode("utf-8")
    sock.sendall(struct.pack(">iH", kind, len(data)) + data)


# ---------------------------------------------------------------- training state

class Trainer:
    def __init__(self, args):
        self.args = args
        OUT.mkdir(parents=True, exist_ok=True)
        self.lock = threading.Lock()
        self.update_ready = threading.Condition(self.lock)
        self.state = {"rung": 0, "level": 0, "games": 0, "updates": 0, "recent": [], "firstWin": []}
        self.net = None
        self.opt = None
        self.dims = None
        self.buffers = {}      # worker -> list of decisions since the last update
        self.pending = {}      # worker -> observation waiting for an action (for bootstrapping)
        self.waiting = set()   # workers paused for an update
        self.steps = 0
        self.stop = False
        self.games_this_run = 0
        self.started = time.time()
        if not (OUT / "log.csv").exists():
            with open(OUT / "log.csv", "w", newline="") as f:
                csv.writer(f).writerow(LOG_COLUMNS)
        # Best wave reached so far on each map (from this run's log), so new records get recorded.
        # Also: once any bot has beaten Ground Zero at 30%, turrets earn their +50 for any ammo, not just copper.
        self.best_wave = {}
        self.any_ammo = False
        with open(OUT / "log.csv") as f:
            for row in csv.DictReader(f):
                self.best_wave[row["map"]] = max(self.best_wave.get(row["map"], 0), int(row["wave"]))
                if row["map"] == "groundZero" and row["outcome"] == "win" and abs(float(row["difficulty"]) - 0.3) < 1e-6:
                    self.any_ammo = True

    def setup(self, dims: Dims):
        self.dims = dims
        self.net = Net(dims)
        self.opt = torch.optim.Adam(self.net.parameters(), lr=self.args.lr)
        ckpt = OUT / "checkpoint.pt"
        if ckpt.exists():
            data = torch.load(ckpt)
            if data["dims"] != dims.as_list():
                raise SystemExit("checkpoint was trained with different game sizes; start a new runs/ppo")
            self.net.load_state_dict(data["net"])
            self.opt.load_state_dict(data["opt"])
            self.state = data["state"]
            print(f"resumed: {self.state['games']} games, {self.state['updates']} updates, "
                  f"map {LADDER[self.state['rung']][0]} at {int(DIFFICULTY[self.state['level']] * 100)}%")
        else:
            print(f"new run: {sum(p.numel() for p in self.net.parameters())} parameters")

    def save(self):
        tmp = OUT / "checkpoint.pt.tmp"
        torch.save({"net": self.net.state_dict(), "opt": self.opt.state_dict(), "state": self.state,
                    "dims": self.dims.as_list()}, tmp)
        os.replace(tmp, OUT / "checkpoint.pt")

    # ---- curriculum ----

    def next_game(self):
        name, wave = LADDER[self.state["rung"]]
        return {"map": str(M / f"{name}.msav"), "winWave": wave, "difficulty": DIFFICULTY[self.state["level"]],
                "bonuses": self.args.bonuses, "allBlocks": False, "anyAmmo": self.any_ammo}

    def record_result(self, result):
        """Log a finished game; decide whether to keep its replay; climb the ladder. Returns the save dir or None."""
        s = self.state
        s["games"] += 1
        game = s["games"]
        won = result["outcome"] == "win"
        beaten = won and (result["winWave"] <= 0 or result["coreHealth"] >= 0.5)
        with open(OUT / "log.csv", "a", newline="") as f:
            csv.writer(f).writerow([game, s["updates"], result["worker"] + 1, result["map"], result["difficulty"],
                                    result["outcome"], result["wave"], result["winWave"], result["coreHealth"],
                                    result["fitness"], result["gameSeconds"], result["realSeconds"],
                                    result["blocksBuilt"], result["actions"]])
        save = None
        first_win = won and result["map"] not in s["firstWin"]
        if won and result["map"] == "groundZero" and abs(result["difficulty"] - 0.3) < 1e-6 and not self.any_ammo:
            self.any_ammo = True
            print("Ground Zero beaten at 30%: turrets now earn +50 for any ammo", flush=True)
        new_best = result["wave"] > self.best_wave.get(result["map"], 0)
        if new_best:
            self.best_wave[result["map"]] = result["wave"]
            print(f"new best wave on {result['map']}: {result['wave']} (game {game}, recorded)", flush=True)
        # The user's rule (2026-10-30): also record the first game finished in each round (learning update).
        first_of_round = s.get("roundRecorded", -1) != s["updates"]
        if first_of_round:
            s["roundRecorded"] = s["updates"]
        if scheduled(game) or first_win or new_best or first_of_round or self.args.record_all:
            save = str(OUT / result["map"] / f"game_{game:05d}")
            if first_win:
                s["firstWin"].append(result["map"])
        # Same climb as before: half of the last 10 games at this level beaten -> harder; at 100% -> next map.
        if result["map"] == LADDER[s["rung"]][0] and abs(result["difficulty"] - DIFFICULTY[s["level"]]) < 1e-6:
            s["recent"] = (s["recent"] + [beaten])[-10:]
            if len(s["recent"]) == 10 and sum(s["recent"]) >= 5:
                s["recent"] = []
                if s["level"] + 1 < len(DIFFICULTY):
                    s["level"] += 1
                elif s["rung"] + 1 < len(LADDER):
                    s["rung"] += 1
                    s["level"] = 0
        self.games_this_run += 1
        print(f"game {game}: {result['map']} {int(result['difficulty'] * 100)}% wave {result['wave']} "
              f"blocks {result['blocksBuilt']} ({result['realSeconds']:.0f}s)"
              + (f" | chain drill>conv {'Y' if result['drillToConveyor'] else 'n'} conv>core {'Y' if result['conveyorToCore'] else 'n'}"
                 f" conv>turret {'Y' if result['conveyorToTurret'] else 'n'} | fed turrets {result['fedTurrets']} unused {result['unused']}"
                 if 'drillToConveyor' in result else ""), flush=True)
        if self.args.games and self.games_this_run >= self.args.games:
            self.stop = True
        if self.args.minutes and time.time() - self.started > self.args.minutes * 60:
            self.stop = True
        return save

    # ---- one game server ----

    def serve(self, sock, worker):
        d = self.dims
        grid_n, nb = d.C * d.G * d.G, d.NB
        with self.lock:
            game = self.next_game()
        send_utf(sock, 2, json.dumps(game))
        last = None  # the previous decision, waiting for its reward
        while True:
            kind = recv_int(sock)
            reward = recv_float(sock)
            if last is not None:
                last["reward"] = reward
            if kind == 2:  # game over
                result = json.loads(recv_utf(sock))
                if last is not None:
                    last["done"] = True
                last = None
                with self.lock:
                    save = self.record_result(result)
                    stop = self.stop
                    nxt = {} if stop else self.next_game()
                if save:
                    nxt["saveDir"] = save
                if stop:
                    nxt["quit"] = True
                send_utf(sock, 2, json.dumps(nxt))
                if stop:
                    return
                continue

            raw = recv_exact(sock, 4 * (grid_n + d.S) + d.T + nb + d.G * d.G + 2 * d.ARGS)
            floats = np.frombuffer(raw[:4 * (grid_n + d.S)], ">f4").astype(np.float32)
            b = np.frombuffer(raw[4 * (grid_n + d.S):], np.uint8).astype(bool)
            obs = {"grid": floats[:grid_n].reshape(d.C, d.G, d.G), "scalars": floats[grid_n:],
                   "type": b[:d.T], "block": b[d.T:d.T + nb], "cell": b[d.T + nb:d.T + nb + d.G * d.G],
                   "mine": b[d.T + nb + d.G * d.G:d.T + nb + d.G * d.G + d.ARGS], "deliver": b[-d.ARGS:]}

            with self.lock:
                # Enough decisions collected: pause here until the learning update is done.
                if self.steps >= self.args.steps:
                    self.pending[worker] = obs
                    self.waiting.add(worker)
                    self.update_ready.notify_all()
                    while worker in self.waiting:
                        self.update_ready.wait()
                g = torch.from_numpy(obs["grid"])[None]
                sc = torch.from_numpy(obs["scalars"])[None]
                masks = {k: torch.from_numpy(obs[k])[None] for k in ("type", "block", "cell", "mine", "deliver")}
                action, logp, value = act(self.net, g, sc, masks)
                last = {"obs": obs, "action": action, "logp": logp, "value": value, "reward": 0.0, "done": False}
                self.buffers.setdefault(worker, []).append(last)
                self.steps += 1
            sock.sendall(struct.pack(">8i", 1, *action))

    # ---- learning ----

    def learn_loop(self, workers):
        while True:
            with self.lock:
                while len(self.waiting) < len([w for w in workers if w.is_alive()]) and not self.all_done(workers):
                    self.update_ready.wait(timeout=1.0)
                if self.all_done(workers):
                    return
                self.update()
                self.waiting.clear()
                self.update_ready.notify_all()

    @staticmethod
    def all_done(workers):
        return not any(w.is_alive() for w in workers)

    def update(self):
        grids, scalars, actions, logps, advs, rets = [], [], [], [], [], []
        masks = {k: [] for k in ("type", "block", "cell", "mine", "deliver")}
        for worker, buf in self.buffers.items():
            if not buf:
                continue
            last_value = 0.0
            if worker in self.pending and not buf[-1]["done"]:
                o = self.pending[worker]
                with torch.no_grad():
                    _, v = self.net(torch.from_numpy(o["grid"])[None], torch.from_numpy(o["scalars"])[None])
                last_value = float(v[0])
            adv, ret = gae([x["reward"] for x in buf], [x["value"] for x in buf], [x["done"] for x in buf], last_value,
                           self.args.gamma, self.args.lam)
            for x, a, r in zip(buf, adv, ret):
                grids.append(x["obs"]["grid"].astype(np.float16))
                scalars.append(x["obs"]["scalars"])
                for k in masks:
                    masks[k].append(x["obs"][k])
                actions.append(x["action"])
                logps.append(x["logp"])
                advs.append(a)
                rets.append(r)
        batch = {"grid": torch.from_numpy(np.stack(grids)), "scalars": torch.from_numpy(np.stack(scalars)),
                 "masks": {k: torch.from_numpy(np.stack(v)) for k, v in masks.items()},
                 "actions": torch.tensor(actions), "logp": torch.tensor(logps),
                 "adv": torch.tensor(np.array(advs)), "returns": torch.tensor(np.array(rets))}
        t = time.time()
        stats = ppo_update(self.net, self.opt, batch, epochs=self.args.epochs, ent_coef=self.args.ent_coef)
        self.state["updates"] += 1
        self.buffers = {}
        self.pending = {}
        self.steps = 0
        self.save()
        print(f"update {self.state['updates']}: {len(actions)} decisions, policy {stats['policy_loss']:.3f} "
              f"value {stats['value_loss']:.3f} entropy {stats['entropy']:.2f} ({time.time() - t:.1f}s)", flush=True)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--servers", type=int, default=1, help="games at once (max 3)")
    p.add_argument("--speed", type=int, default=40, help="game speed cap, x real time (max 40)")
    p.add_argument("--games", type=int, default=0, help="stop after this many games (0 = no limit)")
    p.add_argument("--minutes", type=float, default=0, help="stop after this many minutes (0 = no limit)")
    p.add_argument("--steps", type=int, default=2048, help="decisions per learning update")
    p.add_argument("--epochs", type=int, default=4)
    p.add_argument("--lr", type=float, default=3e-4)
    p.add_argument("--ent-coef", type=float, default=0.01, help="exploration bonus (higher = stays random longer)")
    p.add_argument("--gamma", type=float, default=0.995)
    p.add_argument("--lam", type=float, default=0.95)
    p.add_argument("--bonuses", action=argparse.BooleanOptionalAction, default=True,
                   help="one-time rewards for first block built and first copper (the user's rule)")
    p.add_argument("--record-all", action="store_true")
    p.add_argument("--nice", type=int, default=int(os.environ.get("BLOODBOT_NICE", 19)))
    args = p.parse_args()
    args.servers = max(1, min(3, args.servers))
    os.nice(args.nice)
    torch.set_num_threads(2)

    trainer = Trainer(args)
    listener = socket.socket()
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind(("127.0.0.1", 0))
    listener.listen(args.servers)
    port = listener.getsockname()[1]

    env = dict(os.environ, BLOODBOT_MAX_SERVERS=str(args.servers), BLOODBOT_WORKERS=str(args.servers),
               BLOODBOT_NICE=str(args.nice))
    servers = []
    for i in range(args.servers):
        log = open(OUT / f"server-{i}.log", "a")
        servers.append(subprocess.Popen([str(ROOT / "server" / "run_server.sh"), f"bbppo {port} {i}", str(args.speed)],
                                        env=dict(env, BLOODBOT_WORKER=str(i)), stdout=log, stderr=subprocess.STDOUT))
        time.sleep(2)

    def shutdown(*_):
        for s in servers:
            s.terminate()
        raise SystemExit(0)
    signal.signal(signal.SIGINT, shutdown)
    signal.signal(signal.SIGTERM, shutdown)

    threads = []
    try:
        for _ in range(args.servers):
            listener.settimeout(180)
            sock, _ = listener.accept()
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            worker = recv_int(sock)
            dims = Dims(*[recv_int(sock) for _ in range(8)])
            with trainer.lock:
                if trainer.net is None:
                    trainer.setup(dims)
            th = threading.Thread(target=trainer.serve, args=(sock, worker), daemon=True)
            th.start()
            threads.append(th)
        trainer.learn_loop(threads)
    finally:
        with trainer.lock:
            if trainer.net is not None:
                trainer.save()
        for s in servers:
            try:
                s.wait(timeout=20)
            except subprocess.TimeoutExpired:
                s.terminate()
        gallery.main()


if __name__ == "__main__":
    main()
