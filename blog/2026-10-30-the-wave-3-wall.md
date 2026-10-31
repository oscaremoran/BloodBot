# 2026-10-30: The wave 3 wall

> ✍️ *Intro: you spotted that the bot dies on wave 3 whenever no turret gets ammo.*

## Where the current run's games ended (games 1–1,003)

| Wave reached | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
|---|---|---|---|---|---|---|---|---|---|
| Games | 2 | 323 | 336 | 108 | 54 | 32 | 24 | 82 | 42 |

Two thirds of all games ended on wave 3 or 4.

## Fed turrets decide the game (games 668–995, 329 games with turret data)

| Turrets fed with ammo | Games | Average wave | Reached wave 10 |
|---|---|---|---|
| 0 | 202 | 3.2 | 0 |
| 1–2 | 52 | 3.9 | 0 |
| 3–5 | 24 | 7.3 | 5 |
| 6–9 | 28 | 9.2 | 15 |
| 10+ | 23 | 9.9 | 21 |

## The same games, by the wave they ended on

| Wave reached | Games | Avg turrets fed | Games with 0 fed | Avg blocks | Avg unused blocks |
|---|---|---|---|---|---|
| 2 | 2 | 0.0 | 2 | 10 | 6 |
| 3 | 229 | 0.3 | 186 | 33 | 11 |
| 4 | 8 | 0.9 | 5 | 55 | 20 |
| 5 | 11 | 1.6 | 4 | 91 | 28 |
| 6 | 2 | 2.5 | 0 | 116 | 35 |
| 7 | 6 | 3.7 | 1 | 133 | 43 |
| 8 | 8 | 3.4 | 3 | 108 | 29 |
| 9 | 22 | 5.0 | 1 | 192 | 47 |
| 10 | 41 | 10.8 | 0 | 326 | 86 |

Not one game that reached wave 10 had zero fed turrets.

> ✍️ *What this tells you about how the bot has to play.*

## Copper chains (games 668–995)

| Milestone | Games that hit it |
|---|---|
| Drill → conveyor copper | 218 of 329 (66%) |
| Conveyor → core copper | 0 of 329 |
| Conveyor → turret copper | 0 of 329 |

The bot feeds its turrets by hand ("supply items"), not with conveyors.

> ✍️ *Is hand-feeding a problem for harder maps?*

## Learning signals over the run

| Update | Policy loss | Value loss | Entropy (randomness) |
|---|---|---|---|
| 1 | −0.009 | 0.031 | 8.15 |
| 31 | −0.007 | 0.017 | 3.48 |
| 61 | −0.006 | 0.009 | 4.36 |
| 91 | −0.008 | 0.020 | 9.36 |
| 121 | −0.010 | 0.028 | 11.50 |
| 151 | −0.016 | 0.006 | 14.02 |
| 181 | −0.012 | 0.014 | 12.49 |
| 211 | −0.010 | 0.035 | 12.08 |
| 241 | −0.016 | 0.148 | 13.37 |
| 271 | −0.010 | 0.085 | 12.21 |

> ✍️ *Anything here you want to explain?*
