Continue the BloodBot project (a self-learning Mindustry bot plus a mod). Read your memory files first: they hold the hard rules (CPU/lag limits, ask before background work, no opening apps, wait 10s after Steam sync, I'm a Mindustry veteran so keep it terse, self-learning only, spoiler-free progress unless I ask).

Where we are (2026-10-30):
- **Learner: PPO** (Python + PyTorch in `.venv`), replacing the evolution trainer, which plateaued (old run kept in `runs/train`). The game plugin (`plugin/PpoClient.java`) connects to `bloodbot/ppo_train.py`; the network matches `common/Brain.java`.
- **Current run: `runs/ppo`** (checkpoint.pt, log.csv, trainer.log). The first PPO run is kept as `runs/ppo-run1`. Still on Ground Zero at 30% enemy strength with no wins yet; best wave 9. The bot now builds drill → conveyor → duo chains.
- **Reward rules (mine)**, in score points (reward = points/100): −5 per remove; −2.5 per wait (max −500/game; the old +5-after-a-wait bonus was removed 2026-10-30); −2.5 per built block that never carried items/ammo, counted at game end (conveyors, routers, junctions, bridges, drills, item turrets, crafters; walls, power and sorters exempt); +50 once per game each for the first drill→conveyor copper, conveyor→core copper, and conveyor→turret copper (bot-built blocks only); the bot's alpha has NO GUN in training (server/run_server.sh passes -Dbloodbot.shipGun=false; set BLOODBOT_SHIP_GUN=true to restore; the mod keeps the gun); +50 per turret's first copper (any ammo once any bot beats GZ at 30%); +5 per turret hit on an enemy; one-time +100 first block, +50 first copper. Exploration bonus (entropy) 0.01 (was 0.03 until 2026-10-30). 50 starting copper. World processors and other privileged blocks are invisible to the bot.
- **Evolution metric** on `runs/scoreboard.html` (1 = random, 1,000 = beats GZ@30%, 1,000,000 = beats Bloodshed Bastion). It was about 25 at the start of this run.
- **Ladder:** Ground Zero → Frozen Forest → Cratered Battleground → Fungal Pass → USS Iowa → (self-play on my Space Gardens map, but only once Fungal Pass is beaten reliably) → Bloodshed Bastion.
- **Mod:** `mod/` builds `BloodBot.zip` and syncs to Steam's mods folder. It still uses the old evolution brains, because the **PPO → mod brain export isn't built yet**.

How to run things:
- Train: `.venv/bin/python -m bloodbot.ppo_train --servers 3 --minutes 60 --nice 0` in the background, plus a backup-stop timer whose pkill pattern is split (`"server-rel""ease.jar"`, `"bloodbot.ppo""_train"`) so it can't kill itself. Never run `plugin/build.sh` while servers are running.
- Pages: `python3 -m bloodbot.gallery` → `runs/index.html` (replay viewer) and `runs/scoreboard.html`.
- Stop everything: `pkill -TERM -f bloodbot.ppo_train; pkill -f "java .*server-release.jar"`.

Open ideas, not done:
- (a) Export PPO brains to the mod.
- (b) A turrets-built column on the scoreboard.
- (c) Make "remove" only work on idle blocks.
- (d) An in-game readout of what the bot is doing, in the mod.

First: check whether training is running and give me a short, spoiler-free status (games, learning steps, evolution, building/action mix, whether we have any wins yet). Then ask what's next.

---
