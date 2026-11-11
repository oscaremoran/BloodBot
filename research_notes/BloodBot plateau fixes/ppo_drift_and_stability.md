# PPO drift after a peak: mechanisms and remedies with evidence (applied to BloodBot)

BloodBot setup these notes assume: PPO, CPU only. 2,048 decisions per update from 2 servers, 4 epochs, minibatch 256, Adam lr 1e-4, clip 0.2, target-KL 0.02, entropy 0.01, gamma 0.999, lambda 0.95, grad clip 0.5. One shared trunk with policy and value heads. No reward or value normalization. Episode returns are about -40 for losses and about +120 for rare wins. Episodes are 1-2k decisions. Symptom: a restored checkpoint plays to about wave 7, then drifts to about wave 5 within about 20 updates. Entropy swings between 2 and 10, and value loss spikes.

## Q1. Why does PPO drift down from a peak?

### Takeaway
The literature supports three mechanisms. (a) Value-target scale and non-stationarity: large or shifting return magnitudes destabilise learning. (b) Interference between policy and value objectives when they share parameters. This is worst under sparse rewards. (c) Representation and plasticity collapse that PPO's clip does not prevent, made worse by more epochs per batch and stronger non-stationarity. All three match BloodBot's symptoms: value-loss spikes, a shared trunk, sparse wins, and 4 epochs.

### Cited Findings
- **Reward/target scale.** Value magnitudes differ between tasks and shift as the policy improves. Learning is sensitive to the scale of the function being approximated. Without normalization, Atari DQN gradient norms span more than 6 orders of magnitude across games, versus about 2 with Pop-Art. Unnormalized learning suffered high error after large target spikes. — [van Hasselt et al. 2016, Pop-Art](https://ar5iv.labs.arxiv.org/html/1602.07714)
- **Reward scaling is a top code-level factor.** Engstrom et al. find that "reward normalization, Adam annealing, and network initialization each significantly impact the rewards landscape." Code-level optimizations had a larger effect on final reward than the choice of PPO vs TRPO (ACLI 421-557 vs AAI 99-242 on MuJoCo tasks). — [Engstrom et al. 2020, Implementation Matters](https://ar5iv.labs.arxiv.org/html/2005.12729)
- **PPO does not actually enforce a ratio trust region.** PPO and PPO-M both fail to keep probability ratios inside the clip range. Removing clipping barely changed results (Walker 2867 vs 3292, Hopper 2371 vs 2513, Humanoid 831 vs 806). — [Engstrom et al. 2020](https://ar5iv.labs.arxiv.org/html/2005.12729)
- **Shared-network interference.** "Interference between policy and value function optimization can negatively impact performance when parameters are shared." Sharing also forces a hand-tuned weighting between the two losses. — [Cobbe et al. 2020, Phasic Policy Gradient](https://ar5iv.labs.arxiv.org/html/2009.04416)
- The ICLR PPO-details blog found that separate networks "clearly outperform" shared ones in simpler environments. It attributes this to "competing objectives of the policy and value functions." — [Huang et al. 2022, 37 Implementation Details of PPO](https://iclr-blog-track.github.io/2022/03/25/ppo-implementation-details/)
- **Representation collapse in PPO.** PPO agents lose feature rank and capacity (plasticity) over training on Atari and MuJoCo. Stronger non-stationarity drives the actor's performance to collapse "even when the critic performs well." Representation collapse and trust-region failure reinforce each other. — [Moalla et al. 2024, No Representation, No Trust (NeurIPS 2024)](https://arxiv.org/abs/2405.00662)
- More epochs per rollout speed up pre-activation norm growth and the decline in policy feature rank. Collapse eventually occurs at every epoch setting, but more epochs bring it earlier. — [Moalla et al. 2024](https://arxiv.org/html/2405.00662v3)
- **Sparse rewards plus a shared trunk.** On dense-reward games, sharing sometimes helped. On sparse-reward Gravitar, the shared trunk's rank collapsed and performance collapsed. With 90% of rewards masked on Phoenix, sharing became detrimental. Sparse rewards also collapse the critic's rank. — [Moalla et al. 2024](https://arxiv.org/html/2405.00662v3)
- In Moalla et al.'s runs, collapse was described as unrecoverable. Once plasticity is lost, the surrogate objective gain goes to zero and the model stops changing. — [Moalla et al. 2024](https://arxiv.org/html/2405.00662v3)
- **High entropy can hide collapse.** Policy variance across states can drop to zero while entropy stays high, because the policy outputs the same high-entropy distribution everywhere. — [Moalla et al. 2024](https://arxiv.org/html/2405.00662v3)
- Long PPO training with Adam shows "policy collapse," with performance dropping gradually over time on MuJoCo. Weight clipping and the AdaUPGD optimizer are reported to keep improving where Adam collapses. — [Elsayed et al. 2024, Weight Clipping for Deep Continual and RL](https://arxiv.org/pdf/2407.01704), citing Dohare et al. 2023, [Overcoming Policy Collapse in Deep RL (EWRL 2023)](https://www.bohrium.com/paper-details/overcoming-policy-collapse-in-deep-reinforcement-learning/1133080218353795072-2000000)

### Inferences
- BloodBot's raw returns (about -40 to +120, with gamma 0.999 giving an effective horizon of about 1,000 decisions) feed unnormalized value targets that can be large and that jump whenever a rare win happens. With a shared trunk, each value-loss spike sends a large gradient through the policy's features. The 0.5 global grad clip then scales the *policy* gradient down within that same step. This is the most likely direct cause of "value loss spikes and then play degrades." Raising gamma from 0.995 to 0.999 increases return magnitude and variance, so it probably makes this worse.
- The drift is fast (about 20 updates) and happens after *restoring* a good checkpoint. That points more to destabilisation from scale and interference than to slow plasticity loss. Plasticity loss is a slow, long-training effect in Moalla et al. and Dohare et al. Rare-success forgetting is also plausible. A batch of 2,048 decisions holds only 1-2 episodes, so winning trajectories almost never appear in a batch. The advantage signal then mostly comes from losses (inference; not directly tested in the sources).
- Entropy swinging between 2 and 10 per update suggests the policy moves a lot per update even with the KL stop. Per Moalla et al., entropy level alone is not a reliable health signal.

### Gaps
- I found no paper that isolates "drift after restoring a checkpoint" specifically. The mechanisms above are inferred from general collapse and instability studies.
- I could not retrieve Dohare et al. 2023's exact Adam fix (reportedly equal beta1 = beta2). A secondary arXiv report mentions setting both betas to 0.99, but it does not name its source and still saw collapse. Treat this as unverified.

## Q2. Evidence for specific remedies

### Takeaway
The best-supported, cheapest fixes are: (1) normalize returns or value targets (reward scaling or PopArt); (2) separate the value network from the policy, or detach it; (3) do not use value clipping; (4) tune gamma and lambda, since gamma is among the most important hyperparameters; (5) use fewer policy epochs per batch. Entropy regularization and advantage-normalization variants show little effect. KL-to-anchor has strong practice in LLM RL but no controlled evidence I could find for game PPO.

### Cited Findings
- **Observation and value normalization:** "Always use observation normalization and check if value function normalization improves performance." Value-function normalization was found to strongly affect results. — [Andrychowicz et al. 2020, What Matters in On-Policy RL](https://ar5iv.labs.arxiv.org/html/2006.05990)
- **Reward scaling:** PPO's reference implementation divides rewards by the std of a rolling discounted return. Engstrom et al. found it significant. Reward *clipping* after scaling has "no clear evidence" of helping. — [Huang et al. 2022](https://iclr-blog-track.github.io/2022/03/25/ppo-implementation-details/); [Engstrom et al. 2020](https://ar5iv.labs.arxiv.org/html/2005.12729)
- **PopArt** adaptively normalizes value targets and rewrites the last layer so unnormalized outputs are preserved. Unclipped Double-DQN plus Pop-Art matched or beat clipped Double-DQN on 32 of 57 games, with a median of +0.4% and a mean of +34%. The overhead is a few scalars plus a last-layer rewrite per update. Step size β is about 1e-4 in Atari. — [van Hasselt et al. 2016](https://ar5iv.labs.arxiv.org/html/1602.07714)
- **Separate networks:** "Use a wide value MLP (no layers shared with the policy) but tune the policy width." Separate networks "generally lead to better performance" (decision C47). — [Andrychowicz et al. 2020](https://ar5iv.labs.arxiv.org/html/2006.05990); [Huang et al. 2022](https://iclr-blog-track.github.io/2022/03/25/ppo-implementation-details/)
- **Counterpoint:** PPG reports that sharing parameters is "critical" in Procgen (pixel inputs, where value features help the policy). That is the motivation for PPG's distillation phase. — [Cobbe et al. 2020](https://ar5iv.labs.arxiv.org/html/2009.04416)
- **Phasic Policy Gradient:** PPG uses separate policy and value nets. Every N_π = 32 policy iterations it runs an auxiliary phase (E_aux = 6 epochs) that distills value features into the policy net with a behavioural-cloning KL (β_clone = 1) to the pre-phase policy. It shows "significantly better sample efficiency than PPO in nearly every environment" on Procgen. Defaults are E_π = 1 and E_V = 1. A single policy epoch is "almost always optimal or near-optimal." More value or auxiliary epochs help up to about 6. A single-network variant with detached gradients recovers most of the benefit. — [Cobbe et al. 2020](https://ar5iv.labs.arxiv.org/html/2009.04416)
- **Value clipping:** Use GAE with λ = 0.9 "but neither Huber loss nor PPO-style value loss clipping." Value clipping may even hurt (C13). Engstrom et al. found no evidence that it helps. — [Andrychowicz et al. 2020](https://ar5iv.labs.arxiv.org/html/2006.05990); [Huang et al. 2022](https://iclr-blog-track.github.io/2022/03/25/ppo-implementation-details/)
- **Advantage normalization:** Per-minibatch normalization "seems not to affect the performance too much." — [Andrychowicz et al. 2020](https://ar5iv.labs.arxiv.org/html/2006.05990)
- **Entropy / regularizers:** "We do not find evidence that any of the investigated regularizers helps significantly." — [Andrychowicz et al. 2020](https://ar5iv.labs.arxiv.org/html/2006.05990)
- **Gradient clipping** "might slightly help but is of secondary importance." — [Andrychowicz et al. 2020](https://ar5iv.labs.arxiv.org/html/2006.05990)
- **Batch size:** "Tune the number of transitions in each iteration." Larger minibatches did "not appear to hurt the sample complexity in the range we tested." — [Andrychowicz et al. 2020](https://ar5iv.labs.arxiv.org/html/2006.05990)
- **Target KL** "can be considered as an additional mechanism to explicitly enforce the trust-region constraint." Huang et al. use 0.01 when it is enabled, and approx_kl usually stays below 0.02. Higher values often signal a bug or overly fast policy change. — [Huang et al. 2022](https://iclr-blog-track.github.io/2022/03/25/ppo-implementation-details/)
- **Representation regularization (PFO):** an auxiliary loss c·E‖φ_θ(s) − φ_old(s)‖² on pre-activations, relative to the policy that collected the batch. It gave the most consistent improvement and removed lower-tail collapse on NameThisGame and Phoenix. Adam-moment resets reduced feature norm but did not prevent collapse. — [Moalla et al. 2024](https://arxiv.org/html/2405.00662v3)
- **Learning-rate annealing** helped in 4 of 5 tasks, with small gains. — [Huang et al. 2022](https://iclr-blog-track.github.io/2022/03/25/ppo-implementation-details/)
- **KL to a reference/anchor policy:** this is standard in RLHF, where a frozen reference model gives a per-token KL penalty. The cited motivation is preventing reward hacking and catastrophic forgetting, but the forgetting claim is mostly asserted rather than ablated. — [Prime Intellect RL glossary](https://primeintellect.ai/rl-glossary/kl-penalty); [arXiv 2505.17508](https://arxiv.org/pdf/2505.17508v4)

### Inferences
- **Return normalization** is the highest expected value per line of code for BloodBot. Divide rewards by a running std of the discounted return, or regress the value head on normalized returns and keep a running mean and std (PopArt-lite). CPU cost is essentially zero. It directly targets the value-loss spikes that propagate through the shared trunk.
- **Detaching or separating the value head.** The cheapest version is a separate small value MLP on the same inputs, or stopping value gradients into the trunk. For a small MLP it roughly doubles network forward/backward cost. That cost is likely negligible next to Mindustry server simulation time (not measured here). The sparse-reward evidence (Moalla: sharing hurts on Gravitar and masked Phoenix) makes this more relevant to BloodBot than the Procgen pro-sharing result.
- **Epochs.** PPG and Moalla both suggest 4 policy epochs is on the high side for stability. Combined with a separate value net, 1-2 policy epochs plus more value epochs is a cheap experiment that *reduces* CPU per update. This is not the same as lowering the learning rate, which the user rejected. It limits overfitting each batch rather than slowing all learning.
- **Batch size.** Use 2-4x larger batches (4-8k decisions, about 3-8 episodes). This lowers advantage variance and raises the chance a batch contains a good episode. CPU cost per decision is unchanged, and updates are just less frequent.
- **KL to best checkpoint.** This can be added as β·KL(π_best ‖ π_θ) on the batch states. Its cost is one extra forward pass of the frozen best net. PPG's β_clone term is the closest validated analogue: it keeps the policy close to a snapshot while doing other optimization. I found no controlled game-RL evidence that it prevents drift. It would also cap improvement beyond the anchor unless the anchor is refreshed whenever a new best appears.
- **Value clipping.** Do not add it. If the code already has PPO-style value clipping, removing it is supported by evidence.
- **Entropy.** Tuning or scheduling the entropy coefficient has weak evidence. It is unlikely to be the main fix.

### Gaps
- No source gives effect sizes for these remedies in sparse-reward, long-episode (1-2k step) games specifically. Andrychowicz et al. used MuJoCo continuous control. PPG used Procgen. PopArt used Atari DQN.
- I found no controlled study of "KL to best-so-far policy" as an anti-drift device outside LLM fine-tuning.
- Compute costs for PPG and PFO were not reported in the papers. The CPU estimates above are reasoning, not measurements.

## Q3. "37 Implementation Details" and "What Matters in On-Policy RL": which settings matter most?

### Takeaway
Andrychowicz et al. rank these as most important: discount factor gamma (tune per environment), learning rate, multiple passes over the data, policy-output initialization, observation normalization, and value normalization. They found little effect from advantage normalization, gradient clipping and entropy regularizers, and value clipping is neutral or harmful. The 37-details blog agrees: separate networks, reward scaling and observation normalization matter, and value clipping does not.

### Cited Findings
- "Discount factor γ is one of the most important hyperparameters and should be tuned per environment." — [Andrychowicz et al. 2020](https://ar5iv.labs.arxiv.org/html/2006.05990)
- The learning rate "influences the performance very strongly." Use Adam with β1 = 0.9 and a tuned lr ("0.0003 is a safe default"). — [Andrychowicz et al. 2020](https://ar5iv.labs.arxiv.org/html/2006.05990)
- Going over experience multiple times "appears to be crucial for good sample complexity." Shuffle individual transitions across minibatches. — [Andrychowicz et al. 2020](https://ar5iv.labs.arxiv.org/html/2006.05990)
- The policy initialization scheme "significantly influences the performance." "Initialize the last policy layer with 100× smaller weights." — [Andrychowicz et al. 2020](https://ar5iv.labs.arxiv.org/html/2006.05990)
- PPO policy loss with clip threshold of about 0.25 as a starting point. GAE λ = 0.9. — [Andrychowicz et al. 2020](https://ar5iv.labs.arxiv.org/html/2006.05990)
- Observation normalization is "very helpful" (C64). Observation clipping did not help (C65). Gradient clipping gives a small boost (C68). — [Huang et al. 2022](https://iclr-blog-track.github.io/2022/03/25/ppo-implementation-details/)
- Orthogonal initialization outperforms Xavier on the highest return achieved. PPO uses Adam eps 1e-5 rather than PyTorch's 1e-8, but only for reproduction fidelity. — [Huang et al. 2022](https://iclr-blog-track.github.io/2022/03/25/ppo-implementation-details/)

### Inferences
- BloodBot already does multiple epochs, grad clipping and advantage normalization. Those are the low-impact items. The high-impact items it lacks are value or return normalization, separate networks, and (unconfirmed) observation normalization. Gamma, which is ranked very important, has just been changed. The evidence says gamma should be tuned, so 0.995 vs 0.999 deserves a direct A/B test, not an assumption that 0.999 is better.
- BloodBot's lr of 1e-4 is already below the 3e-4 "safe default," so a lower lr is not where these papers point either.

### Gaps
- I did not verify whether BloodBot normalizes observations or how its last policy layer is initialized. Check this in `/Users/seanmoran/Desktop/Proj26/BloodBot/bloodbot/ppo_train.py`.

## Q4. Population-based training on 2-3 workers?

### Takeaway
PBT and PB2 can work with small populations (about 4). PB2 is designed for that. But each member needs its own environment stream. With 2 game servers on a 4-core Mac shared with gaming, a population of 2-3 would split already scarce data and is unlikely to pay off.

### Cited Findings
- The original PBT reports faster wall-clock convergence and higher final performance in deep RL. — [Jaderberg et al. 2017](https://arxiv.org/pdf/1711.09846)
- PB2 claims to find strong configurations with "far fewer agents than typically required by PBT," and is aimed at small labs. — [Parker-Holder et al. 2020, PB2](https://arxiv.org/abs/2002.02518)
- A larger population size "does not necessarily yield substantial benefits for every task." Another study found 4 to 8 agents improved rewards on Ant and HalfCheetah, while 16 added little. — [Benchmarking population-based RL, 2404.03336](https://arxiv.org/pdf/2404.03336); [2408.15421](https://arxiv.org/pdf/2408.15421)

### Inferences
- A cheap stand-in is "exploit-only PBT." The automatic fall-back to the best checkpoint that BloodBot already has is essentially the exploit half. Adding a small hyperparameter perturbation on each fall-back gives a serial, population-of-1 variant at no extra CPU cost. There is no direct evidence for this variant.

### Gaps
- I found no PBT results with populations of 2-3, or under CPU-bound simulators with very few environments.

## Q5. Cheapest fixes with the largest expected impact for BloodBot (sparse reward, long episodes, CPU only)

### Takeaway
In priority order: (1) return/value normalization; (2) separate or detached value network; (3) fewer policy epochs (1-2) with the KL stop at about 0.01; (4) a larger batch; (5) an A/B test of gamma 0.995 vs 0.999 and λ 0.9 vs 0.95; (6) optionally, a KL-to-best-checkpoint penalty or PFO-style feature anchoring. All are near-zero or negative CPU cost, except a separate value net (about 2x a small network's compute).

### Cited Findings
- Value normalization strongly affects results. Separate value MLP. No value clipping. Gamma is among the most important hyperparameters. — [Andrychowicz et al. 2020](https://ar5iv.labs.arxiv.org/html/2006.05990)
- Reward normalization has a significant effect. — [Engstrom et al. 2020](https://ar5iv.labs.arxiv.org/html/2005.12729)
- Pop-Art overhead is a few scalars per update. — [van Hasselt et al. 2016](https://ar5iv.labs.arxiv.org/html/1602.07714)
- One policy epoch is near-optimal once value training is separated. — [Cobbe et al. 2020](https://ar5iv.labs.arxiv.org/html/2009.04416)
- More epochs accelerate collapse. Sharing the trunk hurts under sparse rewards. PFO is the most consistent anti-collapse intervention. — [Moalla et al. 2024](https://arxiv.org/html/2405.00662v3)
- Target KL of about 0.01 is the conventional setting. — [Huang et al. 2022](https://iclr-blog-track.github.io/2022/03/25/ppo-implementation-details/)

### Inferences
- Expected effect sizes cannot be taken from these papers for Mindustry. The published deltas are in very different domains: Pop-Art +34% mean on Atari, and code-level optimizations worth about 17-21% on Hopper. Treat these as signals of direction, not magnitude.
- **Diagnostics to add (cheap):** log the value-loss magnitude next to the policy-gradient norm before clipping, to test the "value gradient swamps the trunk" hypothesis. Also log approximate feature rank and dead-unit count of the trunk, and per-update approx_kl and clip fraction (Moalla's warning signs). If the drift is fixed by normalization or separation alone, the rarer plasticity remedies (PFO, weight clipping) are not needed.
- Self-imitation on the top 12 games pushes the policy toward old trajectories. This is another non-stationary objective mixed into a shared trunk. Its interaction with drift has not been tested in these sources. An ablation (SIL off for one run) would show whether it helps or contributes.

### Gaps
- I have no Mindustry or RTS-specific evidence. All effect sizes are from MuJoCo, Atari and Procgen.
- I found no measured CPU timings for these remedies.
