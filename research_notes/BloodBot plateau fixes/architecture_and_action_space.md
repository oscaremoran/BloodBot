# Network architecture, observation design and action-space parameterisation for grid building/strategy RL (BloodBot)

Context: BloodBot PPO uses a 3-layer stride-2 conv encoder (12/16/16 ch, tanh) over a 32x32 minimap (48 layers, map squeezed about 6x) plus a 32x32 close-up. It flattens, adds about 70 scalars, runs 2x256 tanh dense layers, and has one linear layer emitting every head. The map cell comes from row(32)+column(32) logits summed. Heads are sampled independently. The bot rarely builds turrets on the enemy path.

## Spatial action heads: per-cell (fully-convolutional / UNet / Gridnet) vs flatten + dense + factored row/column logits

### Takeaway
Every strong grid-game RL agent found here outputs location as a per-cell map from a convolutional trunk: PySC2 FullyConv, Gym-µRTS Gridnet, the Lux S1 1st-place agent, the Lux S2 4th-place DoubleCone, and AlphaStar's upsampling target-point head. None flattens to dense layers and then factors location into separate x and y logits. The independent x/y design (PySC2 "Atari-net") is the published baseline these designs replaced. BloodBot's row+column sum is the same kind of factorisation and is a likely structural reason it cannot target specific path cells.

### Cited Findings
- PySC2 "Atari-net" baseline: for spatial actions it "independently model[s] policies to select (discretised) x and y coordinates". The FullyConv agent "has no stride and uses padding at every layer" so that spatial detail is kept for clicks. Its spatial policy is a 1x1 conv with a single output channel, on the rationale that spatial actions live in the same space as the inputs. — [Vinyals et al. 2017, SC2LE (ar5iv)](https://ar5iv.labs.arxiv.org/html/1708.04782)
- PySC2 best mean scores (Atari-net / FullyConv / FullyConv-LSTM): MoveToBeacon 25/26/26, CollectMineralShards 96/103/104, FindAndDefeatZerglings 49/45/44, DefeatRoaches 101/100/98, BuildMarines <1/3/6. The gains from the spatial head are modest and task-dependent. The largest relative gap is on the build/economy task (BuildMarines), but all agents are poor on it. — [SC2LE (ar5iv)](https://ar5iv.labs.arxiv.org/html/1708.04782)
- Gym-µRTS defines two action parameterisations:
  - UAS: the policy is called repeatedly; each call picks a source unit, then an action type and parameters.
  - Gridnet: the policy outputs an action for every cell at once, under a mask of shape (h, w, 1+6+4+4+4+4+7+49), and the env executes only cells holding owned units.
  - The paper reports that an encoder-decoder network benefits Gridnet, IMPALA-CNN helps UAS, and Gridnet models have more parameters.
  - The best agent (PPO + invalid-action masking + diverse opponents + IMPALA-CNN, UAS) reached a 91% cumulative win rate against the bot pool after about 60 h on 1 GPU, 3 vCPUs and 16 GB RAM.
  - Ablation magnitudes appear only in figures; the text gives no numbers. — [Huang et al. 2021, Gym-µRTS (arXiv HTML)](https://arxiv.org/html/2105.13807v3); [abstract](https://arxiv.org/abs/2105.13807)
- Lux AI S1 1st place (Toad Brigade):
  - Network: a fully convolutional ResNet with squeeze-excitation, 24 residual blocks of 128-channel 5x5 convs, no normalisation, about 20M parameters, inputs padded to 32x32.
  - Actor heads output 32x32xN actions for each unit type, plus a critic. "One network outputs actions for every cell at once, and only actions from cells with units needing orders are used".
  - Training: IMPALA + UPGO + TD-lambda, with a KL loss to a frozen teacher model. Shaped reward was used for the first 20M steps, then reward was terminal only.
  - Compute: a personal PC with 8 cores/16 threads and 2 GPUs, training overnight through the competition. Growth was staged from 8 to 16 to 24 blocks. — [IsaiahPressman/Kaggle_Lux_AI_2021](https://github.com/IsaiahPressman/Kaggle_Lux_AI_2021)
- Lux S2 4th place "DoubleCone", as reimplemented for microRTS:
  - Layout: 4 residual blocks, then a stride-4 downsample, 6 residual blocks, two stride-2 transpose convs with a residual skip, 4 more residual blocks, then a GridNet-style per-cell actor and conv/global-pool critics. About 5.0M parameters, receptive field 48x48.
  - Results: about 90% vs CoacAI/Mayari after fine-tuning. It could not win on 64x64, which the author attributes to receptive field.
  - A U-Net-like "squnet" variant did worse: about 40% overall, and 0.22 vs 0.95 on BWDistantResources32x32. — [sgoodfriend rl-algo-impls technical description](https://github.com/sgoodfriend/rl-algo-impls/blob/main/rl_algo_impls/microrts/technical-description.md)
- Rules-based agents won Lux S2 (and Halite, Kore). The best RL entry used DoubleCone. — [Goodfriend 2024 (arXiv 2402.08112)](https://arxiv.org/pdf/2402.08112v1)
- AlphaStar's target-point head reshapes the autoregressive embedding to the shape of the map skip connections, concatenates them, and processes the result through up-scaling convolutions, gated ResBlocks and FiLM. This is a spatial decoder with encoder skips, i.e. U-Net-like. — summarised via search snippet of [xlnwel AlphaStar notes](https://xlnwel.github.io/blog/reinforcement%20learning/AlphaStar) (page 404 on direct fetch; see Gaps)

### Inferences
- **Row+column sum is rank-limited.** Summed row and column logits give logit(r,c) = a_r + b_c, which is an additive, rank-1 structure over cells.
  - It cannot raise (r1,c1) and (r2,c2) without also raising (r1,c2) and (r2,c1).
  - An enemy path is a thin, often diagonal or curved line. The policy can only express "rows near the path" × "columns near the path", which is a rectangle mostly off the path.
  - It cannot express "this specific cell" or "any cell on this curve". This is a strong, mechanistic explanation for rarely placing on the path.
- **The dense bottleneck discards spatial alignment.** Flattening 16-channel 4x4 maps (after 3 stride-2 convs, 32→4) into a 256-unit tanh MLP and back out to 64 location logits throws away the alignment between the "enemy route" input layer and the output cell. A per-cell head keeps it: one 1x1 conv over a feature map that contains the route layer can learn "logit high where route=1 and buildable" almost immediately.
- **Lowest-cost fix:** keep a conv trunk at a resolution of at least 16x16, and make the location head a 1x1 (or 3x3) conv producing a 32x32 (or 16x16 plus fine-offset) logit map, conditioned on the chosen type/block via FiLM or channel concat (AlphaStar-style). The global heads (type, block) can stay on the pooled/dense path.
- **Receptive field matters for path awareness, but the route layer short-circuits it.** Because BloodBot already feeds a BFS route layer, the location head needs only a small receptive field to "see" the path. A shallow fully-conv head is therefore likely enough, and deep DoubleCone-style stacks are not needed for this specific behaviour.

### Gaps
- No source found with a direct, numeric ablation of a "row+col summed logits" location head vs a per-cell map. The PySC2 independent-x/y comparison is the closest and shows only modest differences on minigames.
- Gym-µRTS ablation magnitudes (Gridnet vs UAS, encoder-decoder vs not) are figure-only and could not be extracted.
- AlphaStar supplementary ablations of the spatial head were not retrieved. The direct page fetch failed, so the target-point description rests on a search-result summary.
- The Lux S1 Kaggle write-up page did not render. The details come from the winner's GitHub README.

## Autoregressive vs independent factored heads (type → block → location)

### Takeaway
Large-scale successes (AlphaStar; the Lux S1 winner's per-cell heads) condition location on the chosen action type. A 2026 multi-algorithm study finds autoregressive factorisation gives the highest performance overall, and that independent heads also do well per action type. Independent heads are known to degrade as action dimensions must coordinate. For BloodBot, conditioning location on type/block is probably the second most important change after per-cell location.

### Cited Findings
- AlphaStar chains its heads: action type, then delay, queue, selected units, target unit and target location. Each later head is driven by an autoregressive embedding that includes earlier choices. — [xlnwel AlphaStar notes (search summary)](https://xlnwel.github.io/blog/reinforcement%20learning/AlphaStar); [RL for StarCraft (UPenn course PDF)](https://www.seas.upenn.edu/~cis520/papers/RL_for_starcraft.pdf)
- Flavin & Sen compared independent networks, shared encoder, VDN, QPLEX, joint and auto-regressive factorisations across PPO, SAC and DQN (220 configurations). They report:
  - Auto-regressive actions reach "the highest performance overall".
  - Branching dueling architectures balance compute and performance best.
  - Their VDN-PPO/PPO-MIX, which use a branching critic for credit assignment in multi-head PPO, beat all other PPO factorisations.
  - This is a v1 preprint on lightweight environments; the abstract gives no numbers. — [Flavin & Sen 2026, arXiv 2606.26574](https://arxiv.org/abs/2606.26574)
- Tavakoli et al. (Action Branching / BDQ): independent heads scale linearly in outputs, and a shared decision module is important for coordinating the branches. The search-snippet summary reports that fully independent learners (IDQ) "quickly deteriorate with increasing action dimensionality", implying an inability to coordinate. — [Tavakoli et al. 2018, arXiv 1711.08946](https://arxiv.org/abs/1711.08946); [NIPS-2017 DRLS workshop PDF](https://kormushev.com/papers/Tavakoli_NIPS-2017_DRLS.pdf)
- The Lux S1 winner sums log-probabilities of all chosen unit actions per timestep to approximate the joint log-probability. Each unit type has its own per-cell action head. — [Kaggle_Lux_AI_2021](https://github.com/IsaiahPressman/Kaggle_Lux_AI_2021)

### Inferences
- With independent sampling, the location head must put mass on cells that are good for an average of all types/blocks. Good turret cells (on the path) and good drill cells (on ore) conflict, so the marginal location distribution blurs. Conditioning location on block, e.g. via FiLM or by concatenating a block embedding broadcast over the map before the 1x1 location conv, removes this conflict cheaply.
- Autoregressive sampling is cheap here: only one action per step. One extra small forward pass of a conv head per step is negligible compared to game-step cost.
- Independent row/column plus independent fine-x/fine-y heads also cannot coordinate within the location itself. A per-cell map (optionally at 64x64 or with a per-coarse-cell fine-offset map) solves both.

### Gaps
- No numeric autoregressive-vs-independent ablation was found specifically for RTS/base-building with PPO. AlphaStar's Fig. 3f ablation was not retrieved.
- "Growing Action Spaces" and OpenAI Five head designs were not researched within the tool budget.

## Invalid action masking (and masking irrelevant heads)

### Takeaway
Masking is decisively better than penalties once invalid actions dominate, and the advantage grows with map size. Use the "canonical" mask (logits set to a large negative value before softmax, same mask at train time), not post-hoc renormalisation. Masking per-cell legality (e.g. buildable cells) is standard in Gridnet/Lux agents.

### Cited Findings
- Masking vs penalty in µRTS, with tsolve = % of training needed to reach return 40:
  - Masking reached return 40 on every map size (4x4, 10x10, 16x16, 24x24). tsolve was 8.67%, 11.13%, 11.47% and 18.38% respectively.
  - Penalty agents never reached 40 on 10x10, 16x16 or 24x24 (returns 0–0.25).
  - The chance of randomly picking a valid source unit falls from about 12.5% (4x4) to about 0.34% (24x24). — [Huang & Ontañón 2020/2022, "A Closer Look at Invalid Action Masking" (arXiv HTML)](https://arxiv.org/html/2006.14171)
- Removing the mask after training on 24x24 drops return to 17.37. "Naive" masking (renormalising without masking gradients) slowed 24x24 tsolve to 49.14% and showed much higher policy KL. — [same](https://arxiv.org/html/2006.14171)
- That paper's network for 24x24 was tiny: Conv(27→16,3x3) + maxpool, Conv(16→32,2x2) + maxpool, then Linear(800→128) → Linear(128→1188). This shows masking alone works with small nets on simple tasks. — [same](https://arxiv.org/html/2006.14171)
- In Gym-µRTS full game, a partial mask (action types only) had "little impact" while the full mask (including parameters) "considerably improves performance". — [Gym-µRTS](https://arxiv.org/html/2105.13807v3)
- The Lux S1 winner sets illegal logits to −inf and keeps no-op always legal. DoubleCone masks with large negative logits. — [Kaggle_Lux_AI_2021](https://github.com/IsaiahPressman/Kaggle_Lux_AI_2021); [rl-algo-impls](https://github.com/sgoodfriend/rl-algo-impls/blob/main/rl_algo_impls/microrts/technical-description.md)

### Inferences
- The Gym-µRTS "partial mask has little impact" result suggests BloodBot's type-only masking is the weak variant. Masking the location map per block (cells where that block can legally be placed and the player can afford it) is the full-mask analogue. This is only possible with a per-cell location head; a row+col factorisation cannot mask individual cells.
- Heads irrelevant to the sampled type (e.g. rotation for non-rotatable blocks) should not contribute log-prob or entropy to the PPO loss. Otherwise they add gradient noise; this is the same logic as Gym-µRTS's per-parameter masks.

### Gaps
- There is no direct evidence on masking "irrelevant heads" out of the entropy term specifically, beyond the general full-mask result.

## Network size, activations, normalisation, separate value nets

### Takeaway
MuJoCo-derived PPO advice (tanh, 2x64–256 MLPs, separate value net, small last-layer init) applies to shallow MLPs. Grid-game winners use deep ReLU-style residual conv nets without a dense bottleneck for spatial heads. Deeper ResNets (IMPALA-CNN) beat shallow 3-conv nets consistently. A 256-unit dense bottleneck is plausibly too small *as a carrier of spatial information*, though not necessarily for global decisions.

### Cited Findings
- Andrychowicz et al. (on-policy RL, MuJoCo):
  - Separate policy/value nets were better on 4/5 environments. "Use a wide value MLP (no layers shared with the policy)".
  - Two hidden layers work well.
  - "tanh activations perform best and relu worst". They recommend tanh only "if the networks are not too deep".
  - "Always use observation normalization".
  - "Initialize the last policy layer with 100× smaller weights" (+66% on Humanoid).
  - Value normalisation is environment-dependent.
  - All of this comes from 5 MuJoCo tasks. — [Andrychowicz et al. 2020, "What Matters in On-Policy RL" (ar5iv)](https://ar5iv.labs.arxiv.org/html/2006.05990)
- IMPALA's deep ResNet beat the shallow 3-conv net "throughout the entire training": DMLab-30 46.5% vs 37.1%; Atari-57 median 191.8% vs 93.2%. — [Espeholt et al. 2018, IMPALA (ar5iv)](https://ar5iv.labs.arxiv.org/html/1802.01561)
- Gym-µRTS: IMPALA-CNN improved the UAS agent and was part of the best configuration. — [Gym-µRTS](https://arxiv.org/html/2105.13807v3)
- Lux S1 winner: 128-channel 5x5 residual blocks, no normalisation layers, squeeze-excitation, grown progressively from 8 to 24 blocks using teacher-KL. DoubleCone uses SE residual blocks and three separate conv/global-pool value heads. — [Kaggle_Lux_AI_2021](https://github.com/IsaiahPressman/Kaggle_Lux_AI_2021); [rl-algo-impls](https://github.com/sgoodfriend/rl-algo-impls/blob/main/rl_algo_impls/microrts/technical-description.md)

### Inferences
- The tanh recommendation is for shallow MLPs. Going to a residual conv trunk, use ReLU/GELU/SiLU inside residual blocks, as all the grid-game agents above do. Keep the small-init last layer for every policy head, including the 1x1 location conv.
- **The 32x32 squeeze.** At about 6 tiles per minimap cell, a 1–2 tile-wide path becomes a sub-cell feature. The coarse cell plus 16x16 fine offset can address any tile in principle, but fine x/y are sampled independently of the cell and of each other, so "put it exactly on the path tile inside this cell" is unlearnable except by chance. Options:
  - (a) a per-cell coarse map plus a per-block fine-offset map computed from the close-up view;
  - (b) a 64x64 location map (about 3 tiles per cell);
  - (c) make placement relative to the ship close-up (32x32 at full resolution) with a per-cell head there. This fits the "human-level controls" constraint because humans place near where they are looking.
- **Separate value network.** It is cheap at this size and recommended by Andrychowicz. A conv + global-pool critic (DoubleCone-style) is standard.

### Gaps
- No source directly tested whether a 256-unit bottleneck is too small for spatial policies. The inference rests on the architectural argument and on the universal absence of such bottlenecks in successful grid agents.
- Craftax/Crafter agent architectures were not retrieved; a search hit returned an unrelated Minecraft paper.

## Observation design: egocentric vs global, path/threat maps; tower-defense / base-building RL

### Takeaway
Successful grid agents use global, map-aligned planes with per-cell outputs aligned to those planes (Gridnet, Lux, PySC2 screen+minimap). Hand-computed feature planes such as BloodBot's BFS route layer are the kind of input a per-cell head exploits directly. Published tower-defense RL is small-scale and gives little architectural evidence.

### Cited Findings
- PySC2 provides both a screen (local, egocentric camera) and a minimap (global) feature-layer set. FullyConv processes both with convs and emits spatial actions on the screen grid. — [SC2LE (ar5iv)](https://ar5iv.labs.arxiv.org/html/1708.04782)
- The Lux S1 winner feeds global map planes; discrete features use learned embeddings and continuous features use 1x1 convs. It uses 180° rotation test-time augmentation. — [Kaggle_Lux_AI_2021](https://github.com/IsaiahPressman/Kaggle_Lux_AI_2021)
- Tower-defense RL found:
  - Terminal (Princeton): CNN + PPO for placement, early-stage work.
  - Bloons TD PPO in Unity with varying paths/placement areas.
  - A UTDG MaskablePPO agent with grid state and action masking, trained on one map and noted as possibly not generalising.
  - PvZ hybrid PPO + scripted placement heuristics, which the authors found more robust.
  - TowerMind's MLP baseline uses a 759-length state vector → 1200-dim policy.
  
  None reports architecture ablations for placement. — [Terminal RL poster](https://shivamkak19.github.io/assets/projects/p4/terminal_r1_poster.pdf); [BTD RL (Millersville)](https://cs.millersville.edu/~chogg/static/papers/pacise2024-harrison_rivera_stevens_hogg-btdrl.pdf); [UTDG MaskablePPO card](https://corsage-trickily-pungent5.pages.dev/chrisjcc/utdg-maskableppo-policy); [PvZ HRL review](https://themoonlight.io/en/review/reinforcement-learning-for-high-level-strategic-control-in-tower-defense-games); [TowerMind](https://arxiv.org/pdf/2601.05899)

### Inferences
- BloodBot's BFS route layer is good input design. It is wasted, though, if it passes through stride-2 convs and a dense bottleneck before reaching a factored row/col head.
- Useful extra planes, cheap to compute in a BFS:
  - distance-to-route;
  - route "choke" weight (time enemies spend near a cell);
  - existing turret coverage / DPS along the route;
  - buildable-and-affordable mask per block class.
- Coverage-gap planes make "put a turret where the path is uncovered" a near-local decision for a per-cell head.
- Egocentric close-up vs global: keep both (PySC2 pattern), but emit placement logits on a map-aligned grid so input and output cells coincide.

### Gaps
- No tower-defense RL paper with an ablation of path/threat feature maps or of placement-head architecture was found. The Terminal paper PDF could not be parsed.

## CPU feasibility (4-core i7-7700, 32 GB, no GPU)

### Takeaway
Published grid-game agents trained on GPUs: Gym-µRTS about 60 h on 1 GPU + 3 vCPUs; Lux S1 winner on 2 GPUs; DoubleCone 300M+ steps. A full DoubleCone/24-block ResNet is not feasible on this CPU. A small fully-conv trunk at 16–32x32 with 32 channels and a per-cell head is feasible: roughly 10–100x the current encoder cost, but small in absolute terms relative to game-step time. These costs are estimates; see Inferences.

### Cited Findings
- Gym-µRTS: best agent about 60–63 h on 1 GPU + 3 vCPUs + 16 GB. Experiments ranged 37–117 h. Ablations totalled about 6,000 CPU+GPU hours. — [Gym-µRTS](https://arxiv.org/html/2105.13807v3)
- DoubleCone inference took about 50 ms/turn at 16x16 and about 450 ms at 64x64, on 2 CPU threads at 2.2 GHz (Colab). squnet-map64 averaged about 70 ms. Training used 300M + about 100M steps per fine-tune. — [rl-algo-impls](https://github.com/sgoodfriend/rl-algo-impls/blob/main/rl_algo_impls/microrts/technical-description.md)
- Lux S1 winner (20M parameters) took 2–2.5 s per inference on Kaggle servers at batch 2, and trained on an 8c/16t PC with 2 GPUs. — [Kaggle_Lux_AI_2021](https://github.com/IsaiahPressman/Kaggle_Lux_AI_2021)
- The masking paper's working µRTS agent used about 2 small conv layers + a 128-unit MLP. — [Huang & Ontañón](https://arxiv.org/html/2006.14171)

### Inferences
These are my back-of-envelope MAC counts, not measured.
- Current encoder, first layer: 3x3 stride 2, 48→12 at 16x16 output, about 1.3M MACs. The whole encoder is under 2M MACs.
- 32-channel fully-conv trunk at 32x32: each 3x3 conv is 32·32·32·32·9 ≈ 9.4M MACs. 8 convs (4 residual blocks) ≈ 75M MACs forward, about 0.23 GMAC fwd+bwd per sample. At an assumed 20–50 effective GFLOP/s for PyTorch CPU convs on 4 cores, that is about 10–25 ms per sample per training pass. For about 8k sample-passes per PPO update, that is about 1.5–3.5 min. This is feasible but heavy given the user's CPU-load rule.
- A cheaper variant runs the trunk at 16x16 (4x fewer MACs, about 20M) and bilinearly upsamples or uses one transpose conv to 32x32 for the location logits. That gives roughly 3–6 ms per sample. This is a UNet-lite and likely the sweet spot.
- At 64 channels, cost is 4x. At 64x64 resolution, 4x again. A 64ch@64x64 trunk is about 16x the 32ch@32x32 cost and likely impractical on this CPU.
- Inference for acting is batch-1, a few ms for the small variants. This is negligible next to Mindustry step time.
- Throughput levers: channels_last + torch.set_num_threads(≤3) under nice; smaller PPO epochs/minibatches; reuse the current scalar/dense path for global heads.

### Gaps
- No published CPU-only training benchmark for small UNets/ResNets on 32–64 px RL inputs was found. The numbers above are analytic estimates and should be verified with a short, user-approved micro-benchmark under the CPU-load rule.
