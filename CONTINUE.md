Continue the BloodBot project (a self-learning Mindustry bot plus a mod). Read your memory files first: they hold the hard rules (CPU/lag limits, ask before background work, no opening apps, wait 10s after Steam sync, I'm a Mindustry veteran so keep it terse, self-learning only, spoiler-free progress unless I ask).

Where we are (end of 2026-10-30):
- **Learner: PPO** (Python + PyTorch in `.venv`), replacing the evolution trainer, which plateaued (old run kept in `runs/train`). The game plugin (`plugin/PpoClient.java`) connects to `bloodbot/ppo_train.py`; the network matches `common/Brain.java`.
- **Current run: `runs/ppo`** (checkpoint.pt, log.csv, trainer.log). The first PPO run is kept as `runs/ppo-run1`. Ground Zero cleared at 30% (game 990) and 55% (game 1259); now on **Ground Zero at 80%**: 3 wins in 118 games (1286, 1338, 1363), stopped at game 1379, update 419. It builds 300–800 blocks a game (drills, conveyors, routers, lots of duos) and hand-feeds turrets. Why it loses at 80% (replay analysis of games 1357 vs 1338): wave 9 brings maces that copper duos barely hurt (no coal on GZ, so no graphite); losing games bleed core from wave 3 on because copper runs out by wave 5 and the duos ring the core instead of covering the enemy path.
- **Reward rules (mine)**, in score points (reward = points/100): −5 per remove; −2.5 per wait (max −500/game; the old +5-after-a-wait bonus was removed 2026-10-30); −5 per built block that never carried items/ammo (was −2.5; −10 made the bot stop building), counted at game end (conveyors, routers, junctions, bridges, drills, item turrets, crafters; walls, power and sorters exempt); +50 once per game each for the first drill→conveyor copper, conveyor→core copper, conveyor→turret copper and router→turret copper (bot-built blocks only); the bot's alpha has NO GUN in training (server/run_server.sh passes -Dbloodbot.shipGun=false; set BLOODBOT_SHIP_GUN=true to restore; the mod keeps the gun); +50 per bot-built turret's first HIT on an enemy (moved from its first ammo, 2026-10-30, so badly placed turrets earn nothing); +5 per turret hit; one-time +100 first block, +50 first copper. Exploration bonus (entropy) 0.01 (was 0.03 until 2026-10-30). 50 starting copper. World processors and other privileged blocks are invisible to the bot.
- **Evolution metric** on `runs/scoreboard.html` (log scale; 1 = random, 1,000 = beats GZ@30%, 1,000,000 = beats Bloodshed Bastion), shown as three bars: the whole ladder, human skill levels (Noob … Oscar … Nobody yet) and a zoom on the current map. About 1,800 now.
- **Climb rule:** any win counts (no core-health requirement since 2026-10-30); 5 wins in the last 10 games at a level moves up (30% → 55% → 80% → 100% → next map).
- **Ladder:** Ground Zero → Frozen Forest → Cratered Battleground → Fungal Pass → USS Iowa → (self-play on my Space Gardens map, but only once Fungal Pass is beaten reliably) → Bloodshed Bastion.
- **Mod:** `mod/` builds `BloodBot.zip` and syncs to Steam's mods folder. It still uses the old evolution brains, because the **PPO → mod brain export isn't built yet**.

How to run things:
- Train: `.venv/bin/python -m bloodbot.ppo_train --servers 3 --minutes 60 --nice 0 --steps 1024` (updates every 1,024 decisions since 2026-10-31; exploration 0.005 was tried and made it build less, so it stays 0.01; never more than 3 servers) in the background, plus a backup-stop timer whose pkill pattern is split (`"server-rel""ease.jar"`, `"bloodbot.ppo""_train"`) so it can't kill itself. Never run `plugin/build.sh` while servers are running.
- Pages: `python3 -m bloodbot.gallery` → `runs/index.html` (replay viewer) and `runs/scoreboard.html`.
- Stop everything: `pkill -TERM -f bloodbot.ppo_train; pkill -f "java .*server-release.jar"`.

Session 2026-10-31 (end at game 1794, Ground Zero 80%, about 6% wins there; still not cleared). Changes made today, all live:
brain 64 -> 256 neurons per layer; two views (minimap + 32x32-tile close-up around the ship); PPO brain exported to the mod
(bloodbot/export_ppo.py, pick_brains defaults to runs/ppo); rewards: -10 per 1% core HP lost, +100 extra per win, +100 per
mace killed, walls earn +1 per 20 HP of enemy damage soaked (max 300/game), turret hits / mace kills / router-fed turrets pay
x1..x3 by distance from the core (forward defense), +30 per router-fed turret (was +50 once per game); updates every 1,024
decisions. Tried and reverted: exploration 0.005 (made it timid). The last three reward changes (walls, forward, routers)
had only ~30 games before stopping: judge them first next session (watch "wall soak", "router-fed" in trainer.log).

Open ideas, not done:
- **(next, from me) Reward chokepoints and walls:** give points for walls that make enemies take a longer path or bunch up in front of turrets (chokepoints), so it learns to steer enemies into kill zones instead of ringing the core with duos.
- (a) Export PPO brains to the mod.
- (b) A turrets-built column on the scoreboard.
- (c) Make "remove" only work on idle blocks.
- (d) An in-game readout of what the bot is doing, in the mod.

First: check whether training is running and give me a short, spoiler-free status (games, learning steps, evolution, building/action mix, whether we have any wins yet). Then ask what's next.

---
