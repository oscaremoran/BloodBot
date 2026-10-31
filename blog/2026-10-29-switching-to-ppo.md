# 2026-10-29: Switching to PPO

> ✍️ *Intro: what PPO is, in your own words, and why it might work better than evolution.*

## The first PPO run

| | |
|---|---|
| Learner | PPO (PyTorch 2.2.2, CPU, 4 threads) |
| Learning updates | 25 (2,048 decisions each) |
| Games | 88 |
| Wins | 0 |
| Best wave | 9 |
| Average wave | 4.9 (median 4) |
| Average blocks per game | 28.3 (evolution: 5.6) |
| Most blocks in one game | 401 |
| Average game length | 648 game seconds, 30.8 s real time |
| Total real time | 45 minutes |

## Quarter by quarter

| Games | Avg wave | Best wave | Avg blocks | Most blocks | Avg fitness |
|---|---|---|---|---|---|
| 1–22 | 5.0 | 9 | 46 | 401 | 52 |
| 23–44 | 5.2 | 9 | 32 | 165 | 56 |
| 45–66 | 5.1 | 9 | 26 | 78 | 55 |
| 67–88 | 4.3 | 6 | 9 | 22 | 46 |

> ✍️ *It built less and less as the run went on. Why do you think it backed off?*

## Where games ended

| Wave reached | 3 | 4 | 5 | 6 | 7 | 8 | 9 |
|---|---|---|---|---|---|---|---|
| Games | 14 | 35 | 15 | 9 | 6 | 3 | 6 |

## What it spent its actions on

| Action | First PPO run | Evolution |
|---|---|---|
| Wait | 53.7% | 9.6% |
| Remove | 24.3% | 12.1% |
| Build | 9.3% | 9.0% |
| Supply items | 4.5% | 5.1% |
| Rebuild | 4.1% | 5.4% |
| Configure | 3.3% | 5.1% |
| Ship actions | 0.8% | 19.5% |

78% of its actions were waiting or removing blocks. That led to the next round of reward rules: a cost on removing and on waiting.

> ✍️ *How the reward rules changed after this run.*

## Evolution vs. first PPO run

| | Evolution | PPO run 1 |
|---|---|---|
| Games | 289 | 88 |
| Wins | 0 | 0 |
| Best wave | 9 | 9 |
| Average wave | 5.5 | 4.9 |
| Average blocks | 5.6 | 28.3 |
| Most blocks | 260 | 401 |

> ✍️ *Was it worth switching?*
