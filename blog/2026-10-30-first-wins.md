# 2026-10-30: First wins

> ✍️ *Intro: what BloodBot is, and what today was like.*

## Where it stands

| | |
|---|---|
| Ladder level | Ground Zero, 30% enemy strength (rung 1 of 6) |
| Learner | PPO (PyTorch, CPU only), 254 learning updates of 2,048 decisions each |
| Games in the current run | ~960 |
| Wins | 23 |
| Wins with the core at 50% or more ("beaten") | 7 |
| Best streak | 3 wins in a row (games 948–950) |
| Most blocks built in one game | 488 (game 950, a win) |
| Evolution score | 79 (1 = random, 1,000 = beats Ground Zero at 30%, 1,000,000 = beats Bloodshed Bastion) |

> ✍️ *What the numbers mean to you. Is it a "human noob" yet?*

## How we got here

| Learner | Games | Wins | Best wave | Avg wave | Avg blocks/game |
|---|---|---|---|---|---|
| Evolution (neuroevolution, pure Java) | 289 | 0 | 9 | 5.5 | 6 |
| PPO, first run | 88 | 0 | 9 | 4.9 | 28 |
| PPO, current run | ~960 | 23 | 10 | 4.7 | 35 |

Evolution plateaued at waves 5–6, so on 2026-10-29 the bot switched to PPO.

> ✍️ *Why switch, and what it felt like watching it plateau.*

## The current run, 100 games at a time

| Games (log order) | Avg wave | Avg blocks | Wins | Beaten (core ≥ 50%) | Most blocks |
|---|---|---|---|---|---|
| 1–100 | 4.5 | 27 | 0 | 0 | 158 |
| 101–200 | 4.7 | 18 | 0 | 0 | 113 |
| 201–300 | 4.4 | 12 | 0 | 0 | 40 |
| 301–400 | 4.4 | 21 | 0 | 0 | 92 |
| 401–500 | 5.1 | 20 | 1 | 1 | 113 |
| 501–600 | 5.5 | 22 | 0 | 0 | 125 |
| 601–700 | 5.3 | 28 | 0 | 0 | 82 |
| 701–800 | 3.3 | 33 | 2 | 0 | 199 |
| 801–900 | 4.5 | 79 | 11 | 3 | 323 |
| 901–963 | 5.2 | 126 | 9 | 3 | 488 |

What the bot spends its actions on:

| | Wait | Build | Supply items | Configure | Remove |
|---|---|---|---|---|---|
| First 100 games | 58% | 15% | 7% | 7% | 9% |
| Last 100 games | 4% | 39% | 46% | 9% | ~0% |

> ✍️ *The turning point around game 800: what changed?*

## Reward rules in force

- −5 per block removed; −2.5 per wait (max −500 per game).
- −2.5 per built block that never carried items or ammo, counted at game end.
- +50 once per game each for the first drill→conveyor, conveyor→core and conveyor→turret copper.
- +50 per turret's first ammo; +5 per turret hit on an enemy.
- One-time +100 for the first block and +50 for the first copper.
- The bot's ship has no gun in training, so it has to win with buildings.

> ✍️ *Which rules mattered most?*

## Next

> ✍️ *What's next: 55% strength, then Frozen Forest.*
