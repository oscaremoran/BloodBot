# BloodBot plateau fixes

*2026-11-10. Built from two research parts (PPO drift and stability; network and action design), plus facts checked in the code. Full notes: `research_notes/BloodBot plateau fixes/`. The game case-study and sparse-reward parts were cut to save tokens.*

## Why it plateaus

There are two separate problems.

**1. It can't aim at the enemy path (action design).**
- **Summed placement scores (confirmed in `bloodbot/ppo.py`, `split()`):** the map cell is chosen as a row score plus a column score. Raising the odds of two path cells also raises the two off-path cells that share their rows and columns. A thin, curved or diagonal path can't be targeted; the best the bot can express is a rectangle that mostly misses it.
- **Independent heads:** the fine x/y offset is picked without knowing the chosen cell, and the location is picked without knowing the chosen block.
- **Result:** landing a turret exactly on the path is close to luck. That matches what we see: 0–1 turrets on the path in typical games, and one lucky win in about 2,500 games.
- **What strong agents do:** every strong grid-game agent found outputs location as a per-cell map from a convolutional network, conditioned on what's being built. Examples: PySC2 FullyConv, Gym-µRTS Gridnet, the Lux AI Season 1 winner, AlphaStar's location head. Separate x and y heads were the baseline those designs replaced.
- **Mask:** a per-cell map can also mask unbuildable or unaffordable cells per block. In Gym-µRTS, the full mask "considerably improves performance", while masking only the action type had "little impact".

**2. It forgets what it learns (training stability).**
- **Returns are not normalized:** returns range from about −40 to +120, and the switch to gamma 0.999 made them larger and noisier.
- **What that causes:** value-loss spikes, which we've seen, send large gradients into the trunk the policy shares. Pop-Art (van Hasselt 2016) shows unnormalized targets make gradient sizes swing over orders of magnitude. Andrychowicz et al. 2020 rate value normalization among the settings that matter most.
- **Shared trunk:** this hurts most with sparse rewards. Moalla et al. (NeurIPS 2024) found sharing collapsed feature rank and performance on sparse-reward Atari games.
- **4 epochs per batch:** more epochs brought representation collapse earlier in Moalla's study, and PPO's clipping didn't prevent it.
- **Small batches:** a 2,048-decision batch is only 1–2 games, so a rare win barely appears in the data (inference, not tested in the sources).

Self-imitation and the fall-back treat the symptom (forgetting). They can't fix problem 1: the bot can't reliably produce the winning build in the first place.

## Ranked recommendations

All of these keep self-learning and human-level controls.

| # | Change | Fixes | Evidence | Cost |
|---|---|---|---|---|
| 1 | **Normalize returns / value targets** (running std of the discounted return, or Pop-Art) | drift, value spikes | Pop-Art; Andrychowicz 2020 (strong effect); Engstrom 2020 | ~20 lines of Python; no plugin change; ~0 CPU |
| 2 | **Separate value network**, or stop value gradients into the shared trunk | drift, feature collapse | Moalla 2024; PPG (Cobbe 2021); Andrychowicz 2020 | ~40 lines; old value head discarded; small CPU |
| 3 | **1–2 epochs per batch, target-KL ~0.01, batches of 4–8k decisions** | drift; rare wins diluted | Moalla 2024 (epochs vs collapse); PPG (1 policy epoch near-optimal) | flags only; slower updates but less work per update |
| 4 | **Per-cell placement map:** replace the row+column cell head and the fine-x/y heads with a 1×1-conv map from a stride-1/UNet-lite trunk at 16–32×32. Condition it on the chosen block, and mask cells per block. | can't aim at the path | PySC2, Gym-µRTS, Lux S1 winner, AlphaStar; masking results (Huang & Ontañón) | big job: new brain (no warm start for location), Java mask changes, Steam re-sync; est. 1.5–3.5 min per update at 32×32 (or ~4× less at 16×16 upsampled), not benchmarked |
| 5 | **Test gamma 0.999 against 0.995, and lambda 0.9 against 0.95**; don't assume 0.999 is better | horizon | Andrychowicz ranks gamma among the most important settings | runs only |
| 6 | **Cheap extra input layers:** distance-to-route and turret coverage gaps along the route | path awareness | inference from the architecture notes | Java Vision change + brain upgrade |

**Skip:** value clipping (neutral or harmful in two studies), entropy schedules, and advantage-normalization variants (little effect).

**Optional:**
- **KL penalty toward the best checkpoint:** costs one extra forward pass. PPG's snapshot term is the closest tested analogue; there's no direct game-RL evidence.
- **Small hyperparameter perturbations at each fall-back:** essentially a serial version of population-based training, with no direct evidence.

## Suggested order

1. **Do #1–#3 together**: Python only, about 1 hour of work. Then judge whether the after-restore drift stops. If it doesn't, the stability problem is elsewhere.
2. **Then #4**, the real fix for "can't place turrets on the path". It's a rebuild of the brain's output, so the bot starts its placement learning over. Build it at the 16×16-upsampled size first, to keep it within the CPU budget.

## Gaps

- **Placement head:** there's no published ablation of the exact row+column-sum cell head. The conclusion comes from the maths and from what top agents use.
- **CPU timings:** no CPU-only training benchmarks were found, so the timings above are estimates that need a short micro-benchmark (needs your OK under the CPU-load rule).
- **Restore drift:** no paper studies drift after restoring a checkpoint specifically.
- **Sources:** effect sizes come from MuJoCo, Atari and Procgen, not Mindustry.
