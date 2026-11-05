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
- Train: `.venv/bin/python -m bloodbot.ppo_train --servers 3 --minutes 60 --nice 0 --steps 2048 --lr 2e-4` (2,048 decisions per update and lr 2e-4 since 2026-11-01, halfway between the old 1,024/3e-4 and the too-slow 4,096/1e-4; best brain kept in runs/ppo/best.pt; exploration 0.005 was tried and made it build less, so it stays 0.01; never more than 3 servers) in the background, plus a backup-stop timer whose pkill pattern is split (`"server-rel""ease.jar"`, `"bloodbot.ppo""_train"`) so it can't kill itself. Never run `plugin/build.sh` while servers are running.
- Pages: `python3 -m bloodbot.gallery` → `runs/index.html` (replay viewer) and `runs/scoreboard.html`.
- Stop everything: `pkill -TERM -f bloodbot.ppo_train; pkill -f "java .*server-release.jar"`.

Session 2026-10-31 (end at game 1794, Ground Zero 80%, about 6% wins there; still not cleared). Changes made today, all live:
brain 64 -> 256 neurons per layer; two views (minimap + 32x32-tile close-up around the ship); PPO brain exported to the mod
(bloodbot/export_ppo.py, pick_brains defaults to runs/ppo); rewards: -10 per 1% core HP lost, +100 extra per win, +1 per 10 HP of damage dealt to maces (x forward, since 2026-11-01), +100 per
mace killed, walls earn +1 per 20 HP of enemy damage soaked (max 300/game), turret hits / mace kills / router-fed turrets pay
x1..x3 by distance from the core (forward defense), +30 per router-fed turret (was +50 once per game); updates every 1,024
decisions. Tried and reverted: exploration 0.005 (made it timid). The last three reward changes (walls, forward, routers)
had only ~30 games before stopping: judge them first next session (watch "wall soak", "router-fed" in trainer.log).

**Prediction (2026-11-01, at game 2186):** the user guessed the bot clears Ground Zero 80% at game **2230** (missed); new guess at game 2230: **2375**. My guess: 2900. Tell them when it clears and how close the guess was.

**Runs (2026-11-01):** run 2 ended at game 2239 and is archived as `runs/ppo-run2` (log, replays, trainer.log, checkpoints). The active folder is still `runs/ppo` (run 3): it was seeded with run 2's checkpoint.pt, best.pt and brains, so it carries on at Ground Zero 80% with game numbers continuing from 2239; its log.csv starts empty. Run 2's last hour (games 2018-2239, mace-damage reward, --steps 2048 --lr 2e-4): 10 wins in 222 (4.5%), 44% reached wave 9+, 11 mace kills; best 10-game stretch had 2 wins.

**Mace practice (built 2026-11-02, on by default):** normal games that reach wave 8 save their own base (runs/ppo/practice/<map>_<strength>/game_N.msav + .json sidecar with core items and the agent's per-game memory; newest 40 kept); `--practice 0.33` (default; 0.5 was tried 2026-11-02 and normal games got weaker: 27% reached wave 9 vs 45%) starts that share of games from a save, and saves from games that went on to lose (file name ends _loss) are picked 3x as often as ones that won. Practice rows have start=wave8 in log.csv and never count toward climb, best brain, best wave or evolution. 20-min test (games 2477-2573): practice won 3/30, normal 2/67 incl. game 2559 (81% core, best ever). log.csv uses \r\n line endings: strip \r in awk.

**Early-game forgetting (2026-11-02):** with practice at 1/3 + losing saves 3x, normal games slid from 2% to 38% dying by wave 3 (games 2901-3124, avg wave 7.6 -> 5.4). Rolled back to best.pt (game 2613) at game 3124 (backup checkpoint.before-rollback-940.pt): next 24 normal games 3 wins, 4% early deaths, avg wave 8.2. Watch early deaths; if they creep up, cut practice to 1/5 and drop the 3x loss weighting. Train detached (nohup ... & disown): a killed Claude Code background task took training down once.

**Frozen test + rollback (2026-11-04):** `bloodbot/ppo_eval.py` plays normal games with brains that don't learn, turn about, results in runs/eval/ (touches nothing in runs/ppo; only while no training runs): `.venv/bin/python -m bloodbot.ppo_eval runs/ppo/best.pt runs/ppo/checkpoint.pt --games 20 --servers 3`. Test 1 (cut at 31 games): best.pt (game 2613) 3 wins in 16, 75% wave 9+; game-3679 brain 0 in 15, 27%. So the checkpoint was rolled back to best.pt a second time (backup checkpoint.before-rollback-1050.pt). Current settings: --lr 1e-4 --steps 2048 --practice 0.2 (now the default), losing saves no longer weighted (LOSS_WEIGHT 1). Run after that (games 3681-3771): normal 5 wins in 76, 47% wave 9+, 4% early deaths. Test 2 (cut at 38 games): best.pt 0 wins, 58% wave 9+, avg wave 7.9; game-3771 brain 0 wins, 58%, avg wave 8.4, more mace damage: no sign of drift at 1e-4. Rollback kept the newer value head. Tests this small are noisy (best.pt went 3 of 16, then 0 of 19).

**Outlast (2026-11-04):** `BLOODBOT_RUN=outlast .venv/bin/python -m bloodbot.ppo_train --servers 3 --minutes 60 --nice 0 --steps 512 --lr 1e-4 --outlast archipelago` trains in runs/outlast (seeded from runs/ppo; rival.pt is the frozen rival, 1 game in 3, logged start=rival; learner replaces it when its last 20 games score higher, score = wave + core health). maps/campaign/archipelago.msav is the built-in map with waves switched on in its rules: 90 player blocks (13 pneumatic drills, 53 conveyors, 4 duos, 3 mass drivers), naval waves. `bbblocks` plugin command lists a loaded map's blocks. Result so far: learner 77 games avg wave 7.03, rival 37 games avg 7.08; nearly every game ends at wave 7 with turrets hitting ~nothing; few decisions per game, so updates are slow. Not checked: whether an idle bot also reaches wave 7. User switched back to Ground Zero after ~15 min at 512 steps; no rival comparison ever fired.

**Suggested next (2026-11-04, not started):** tuning stopped moving Ground Zero 80% (stuck since ~game 1290). Idea: a general "connect A to B with conveyors" action, since long conveyor lines are hard for PPO to learn one placement at a time. Unproven.

Open ideas, not done:
- **(TODO, user asked 2026-11-01) Highlight reel GIF** of its best wins (2131 with 60% core, game 1397's 1,047 blocks, the first win): niced, only when no training is running.
- **Race the bot (2026-11-01):** user plays Ground Zero at 80% with the bot's rules (copper duos, 50 copper, no ship gun); add the result to the scoreboard as a human data point.
- **(next, from me) Reward chokepoints and walls:** give points for walls that make enemies take a longer path or bunch up in front of turrets (chokepoints), so it learns to steer enemies into kill zones instead of ringing the core with duos.
- (a) Export PPO brains to the mod.
- (b) A turrets-built column on the scoreboard.
- (c) Make "remove" only work on idle blocks.
- (d) An in-game readout of what the bot is doing, in the mod.

First: check whether training is running and give me a short, spoiler-free status (games, learning steps, evolution, building/action mix, whether we have any wins yet). Then ask what's next.

---
