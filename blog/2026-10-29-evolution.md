# 2026-10-29: Evolution, 289 games and no wins

> ✍️ *Intro: the first learner, neuroevolution in pure Java, and what you hoped it would do.*

## Totals

| | |
|---|---|
| Learner | neuroevolution (pure Java, inside the game plugin) |
| Generations | 23 (13 games each) |
| Games | 289 |
| Wins | 0 |
| Best wave | 9 (of 10) |
| Average wave | 5.5 (median 5) |
| Average blocks built per game | 5.6 |
| Most blocks in one game | 260 |
| Average game length | 707 game seconds, 27.8 s real time |
| Total real time | 2.2 hours |

## Every generation

| Gen | Avg wave | Best wave | Avg blocks | Best fitness | Mean fitness |
|---|---|---|---|---|---|
| 0 | 5.7 | 9 | 0.0 | 104 | 62 |
| 1 | 6.0 | 9 | 1.5 | 104 | 65 |
| 2 | 4.4 | 7 | 0.3 | 81 | 45 |
| 3 | 5.2 | 9 | 0.2 | 105 | 56 |
| 4 | 4.1 | 9 | 0.6 | 104 | 41 |
| 5 | 5.0 | 9 | 0.3 | 118 | 58 |
| 6 | 5.8 | 9 | 0.0 | 105 | 62 |
| 7 | 4.8 | 9 | 0.2 | 102 | 53 |
| 8 | 5.8 | 9 | 0.0 | 103 | 62 |
| 9 | 5.6 | 9 | 0.2 | 105 | 66 |
| 10 | 6.6 | 9 | 0.0 | 105 | 74 |
| 11 | 6.3 | 9 | 0.0 | 105 | 69 |
| 12 | 6.2 | 9 | 0.2 | 174 | 78 |
| 13 | 5.9 | 9 | 0.0 | 128 | 71 |
| 14 | 5.6 | 9 | 3.6 | 228 | 103 |
| 15 | 6.2 | 9 | 0.0 | 101 | 67 |
| 16 | 6.4 | 9 | 9.1 | 254 | 177 |
| 17 | 5.6 | 9 | 12.3 | 254 | 211 |
| 18 | 4.5 | 9 | 12.9 | 256 | 197 |
| 19 | 4.8 | 9 | 10.8 | 256 | 200 |
| 20 | 5.1 | 9 | 38.0 | 230 | 180 |
| 21 | 5.5 | 9 | 27.5 | 204 | 153 |
| 22 | 5.3 | 7 | 29.0 | 153 | 131 |

> ✍️ *Fitness tripled after generation 16 but the waves didn't move. What was going on?*

## Where games ended

| Wave reached | 3 | 4 | 5 | 6 | 7 | 8 | 9 |
|---|---|---|---|---|---|---|---|
| Games | 85 | 46 | 41 | 16 | 19 | 22 | 60 |

## What it spent its actions on

| Action | Share |
|---|---|
| Remove | 12.1% |
| Wait | 9.6% |
| Build | 9.0% |
| Ship guard | 6.6% |
| Ship deliver | 6.2% |
| Rebuild | 5.4% |
| Supply liquid | 5.4% |
| Configure | 5.1% |
| Supply items | 5.1% |
| Logic link | 5.1% |
| Army attack | 4.4% |
| Army move | 4.3% |
| Payload drop | 4.2% |
| Logic write | 4.1% |
| Ship mine | 3.7% |
| Supply power | 3.5% |
| Payload pick up | 3.2% |
| Ship fight | 3.0% |

Spread almost evenly over 18 actions: close to random.

> ✍️ *Why you decided to give up on evolution.*
