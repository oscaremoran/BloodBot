"""Frozen test: play normal games with brains that don't learn, to compare them fairly. Touches nothing in runs/ppo.

Usage (from the BloodBot folder, only while no training is running):
  .venv/bin/python -m bloodbot.ppo_eval runs/ppo/best.pt runs/ppo/checkpoint.pt --games 40 --servers 3

Each brain plays --games games at --difficulty on Ground Zero, turn about, so a slow spell hits them equally. No
practice saves, no updates, no replays. Actions are sampled, as in training. Results go to runs/eval/<time>.csv.
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
from pathlib import Path

import numpy as np
import torch

from bloodbot.ppo import Dims, Net, act, TARGETED
from bloodbot.ppo_train import M, ROOT, recv_exact, recv_float, recv_int, recv_utf, send_utf

OUT = ROOT / "runs" / "eval"
COLUMNS = ["brain", "outcome", "wave", "winWave", "coreHealth", "fitness", "gameSeconds", "blocksBuilt", "fedTurrets",
           "hitTurrets", "maceKills", "maceDamage"]


class Eval:
    def __init__(self, args, path):
        self.args, self.path = args, path
        self.lock = threading.Lock()
        self.dims = None
        self.nets = {}
        self.started = {b: 0 for b in args.brains}
        self.rows = []

    def setup(self, dims):
        self.dims = dims
        for b in self.args.brains:
            data = torch.load(b, map_location="cpu", weights_only=False)
            if data["dims"] != dims.as_list():
                raise SystemExit(f"{b} was trained with different game sizes")
            net = Net(dims)
            net.load_state_dict(data["net"])
            net.eval()
            self.nets[b] = net
            print(f"{b}: game {data['state']['games']}, update {data['state']['updates']}", flush=True)

    def next_game(self):
        """The brain with the fewest games started plays next; None when every brain has started its share."""
        brain = min(self.started, key=self.started.get)
        if self.started[brain] >= self.args.games:
            return None, {"quit": True}
        self.started[brain] += 1
        return brain, {"map": str(M / "groundZero.msav"), "winWave": 10, "difficulty": self.args.difficulty,
                       "bonuses": True, "allBlocks": False, "anyAmmo": True}

    def record(self, brain, result):
        row = [brain] + [result.get(c, "") for c in COLUMNS[1:]]
        self.rows.append(dict(zip(COLUMNS, row)))
        with open(self.path, "a", newline="") as f:
            csv.writer(f).writerow(row)
        print(f"{len(self.rows)}/{self.args.games * len(self.args.brains)} {brain}: {result['outcome']} "
              f"wave {result['wave']} core {result['coreHealth']:.2f}", flush=True)

    def serve(self, sock):
        d = self.dims
        grid_n, nb = d.C * d.G * d.G, d.NB
        with self.lock:
            brain, game = self.next_game()
        send_utf(sock, 2, json.dumps(game))
        while brain is not None:
            kind = recv_int(sock)
            recv_float(sock)  # reward: not used
            if kind == 2:  # game over
                result = json.loads(recv_utf(sock))
                with self.lock:
                    self.record(brain, result)
                    brain, game = self.next_game()
                send_utf(sock, 2, json.dumps(game))
                continue
            raw = recv_exact(sock, 4 * (grid_n + d.S) + d.T + nb + d.G * d.G * (1 + len(TARGETED)) + 2 * d.ARGS)
            floats = np.frombuffer(raw[:4 * (grid_n + d.S)], ">f4").astype(np.float32)
            b = np.frombuffer(raw[4 * (grid_n + d.S):], np.uint8).astype(bool)
            g = torch.from_numpy(floats[:grid_n].reshape(d.C, d.G, d.G))[None]
            sc = torch.from_numpy(floats[grid_n:])[None]
            masks = {"type": b[:d.T], "block": b[d.T:d.T + nb], "cell": b[d.T + nb:d.T + nb + d.G * d.G],
                     "mine": b[d.T + nb + d.G * d.G:d.T + nb + d.G * d.G + d.ARGS],
                     "deliver": b[d.T + nb + d.G * d.G + d.ARGS:d.T + nb + d.G * d.G + 2 * d.ARGS],
                     "cells": b[d.T + nb + d.G * d.G + 2 * d.ARGS:].reshape(len(TARGETED), d.G * d.G)}
            masks = {k: torch.from_numpy(v)[None] for k, v in masks.items()}
            with self.lock, torch.no_grad():
                action, _, _ = act(self.nets[brain], g, sc, masks)
            sock.sendall(struct.pack(">8i", 1, *action))

    def summary(self):
        for b in self.args.brains:
            r = [x for x in self.rows if x["brain"] == b]
            if not r:
                continue
            n = len(r)
            mean = lambda c: sum(float(x[c] or 0) for x in r) / n
            print(f"{b}: games {n}, wins {sum(x['outcome'] == 'win' for x in r)}, "
                  f"wave 9+ {sum(int(x['wave']) >= 9 for x in r)}, died by wave 3 {sum(int(x['wave']) <= 3 for x in r)}, "
                  f"avg wave {mean('wave'):.1f}, fed turrets {mean('fedTurrets'):.1f}, "
                  f"games with a mace kill {sum(float(x['maceKills'] or 0) > 0 for x in r)}, "
                  f"mace dmg {mean('maceDamage'):.0f}", flush=True)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("brains", nargs="+", help="checkpoint files to compare")
    p.add_argument("--games", type=int, default=40, help="games per brain")
    p.add_argument("--difficulty", type=float, default=0.8)
    p.add_argument("--servers", type=int, default=3, help="games at once (max 3)")
    p.add_argument("--speed", type=int, default=40, help="game speed cap, x real time (max 40)")
    p.add_argument("--nice", type=int, default=19)
    args = p.parse_args()
    args.servers = max(1, min(3, args.servers))
    os.nice(args.nice)
    torch.set_num_threads(2)

    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / f"{time.strftime('%Y-%m-%d-%H%M')}.csv"
    with open(path, "w", newline="") as f:
        csv.writer(f).writerow(COLUMNS)
    ev = Eval(args, path)

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
            recv_int(sock)  # worker number
            dims = Dims(*[recv_int(sock) for _ in range(8)])
            with ev.lock:
                if ev.dims is None:
                    ev.setup(dims)
            th = threading.Thread(target=ev.serve, args=(sock,), daemon=True)
            th.start()
            threads.append(th)
        for th in threads:
            th.join()
    finally:
        ev.summary()
        print(f"results: {path}", flush=True)
        for s in servers:
            try:
                s.wait(timeout=20)
            except subprocess.TimeoutExpired:
                s.terminate()


if __name__ == "__main__":
    main()
