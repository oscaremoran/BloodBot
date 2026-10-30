package bloodbot;

import arc.Core;
import arc.files.Fi;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.maps.Map;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Arrays;
import java.util.Random;

/**
 * Self-learning by evolution strategies (OpenAI-ES with mirrored sampling and Adam):
 * each generation plays 2*PAIRS games with randomly nudged copies of the brain, moves the brain toward the
 * nudges that scored better, then plays one game with the brain itself to measure progress.
 * Climbs a ladder of maps; a map is "beaten" after the brain itself wins it twice in a row.
 * All state is saved after every game, so training can be stopped at any time and resumed later.
 */
public class Trainer{
    record Rung(String path, int winWave, boolean allBlocks){}

    static final int PAIRS = 6;
    static final float SIGMA = 0.15f, LR = 0.03f, WEIGHT_DECAY = 0.002f;
    static final float CAP_SECONDS = 45 * 60;
    /**
     * Temporary build bonus (the user's rule, 2026-10-29): early on, any game that finished building something
     * scores extra, so evolution picks up the habit of building. Full strength through BUILD_BONUS_FULL_UNTIL,
     * fading to nothing by BUILD_BONUS_GONE_AT, after which only real outcomes count again.
     */
    static final int BUILD_BONUS_FULL_UNTIL = 20, BUILD_BONUS_GONE_AT = 25;
    /** Flat bonuses (the user's rule, 2026-10-30): +100 for building anything, +50 for getting any copper to the core. */
    static final float BUILD_BONUS = 100f, COPPER_BONUS = 50f;
    static final int STATE_VERSION = 3;
    /** Enemy strength while learning a map: units and buildings at 30%, then 55%, 80%, and finally the real 100%. */
    static final float[] DIFFICULTY = {0.3f, 0.55f, 0.8f, 1f};

    final Fi out;
    final Seq<Rung> ladder = new Seq<>();
    final int[] layers = Agent.shape();
    final int n = Brain.paramCount(layers);

    // ---- persistent state ----
    int rung, level, gen, member, adamT, centerWinsInRow;
    long games, firstWinRecorded;
    float[] theta, m, v, fitness = new float[2 * PAIRS];

    // ---- current game ----
    boolean loading = true;
    long realStart;
    Agent agent;
    Recorder recorder;
    String mapName;
    boolean crashed;
    /** Stop the server after this many more games (test runs); -1 = train until stopped. */
    int stopAfter = -1;
    /** Save every game for the viewer, not just the scheduled ones. */
    boolean recordAll;

    // ---- parallel play ----
    /**
     * Several servers can share one training run: each plays the games of a round whose slot % workers == worker,
     * writing each score to rounds/<generation>/<slot>.txt. Worker 0 (the leader) also plays the brain check,
     * and once all results are in it updates the brain and saves state.bin; the others wait for that, then reload.
     */
    final int worker = Integer.getInteger("bloodbot.worker", 0), workers = Math.max(1, Integer.getInteger("bloodbot.workers", 1));
    arc.util.Timer.Task waiting;

    Trainer(Fi out, String[] specs){
        this.out = out;
        for(String spec : specs){
            String[] parts = spec.split(":");
            ladder.add(new Rung(parts[0], Integer.parseInt(parts[1]), parts.length > 2 && parts[2].equals("all")));
        }
        out.mkdirs();
        if(out.child("state.bin").exists()){
            load();
            // Runs from before parallel play kept this round's scores in state.bin; move them to result files.
            if(worker == 0){
                for(int k = 0; k < member && k < 2 * PAIRS; k++){
                    if(!round().child(k + ".txt").exists()) round().child(k + ".txt").writeString(fitness[k] + " 0", false);
                }
            }
            Log.info("[bloodbot] TRAIN resumed (worker @ of @): generation @, map @", worker + 1, workers, gen, rungName());
        }else{
            theta = Brain.random(layers, 12345).params;
            m = new float[n];
            v = new float[n];
            out.child("log.csv").writeString("game,generation,member,map,difficulty,outcome,wave,winWave,coreHealth,fitness,gameSeconds,realSeconds,blocksBuilt,actions\n", false);
            Log.info("[bloodbot] TRAIN new run: brain has @ parameters, @ buildable blocks", n, Agent.catalog().size);
        }
    }

    String rungName(){
        return Fi.get(ladder.get(rung).path).nameWithoutExtension();
    }

    // ---- game lifecycle ----

    void startGame(BloodBotPlugin plugin){
        loading = true;
        int next = nextMember();
        if(next < 0){
            waitForRound(plugin);
            return;
        }
        member = next;
        Core.app.post(() -> {
            Rung r = ladder.get(rung);
            Map map = plugin.findMap(r.path);
            if(map == null){
                Log.err("[bloodbot] TRAIN cannot load @; stopping", r.path);
                plugin.trainer = null;
                return;
            }
            plugin.startGame(map, r.winWave);
            mapName = map.file.nameWithoutExtension();
            applyDifficulty(DIFFICULTY[level]);
            // Mirrored variants share a seed (same luck), so their difference reflects the brain, not chance.
            long seed = member == 2 * PAIRS ? gen * 31L + 7 : gen * 1009L + member / 2;
            agent = new Agent(new Brain(layers, memberParams()), Vars.state.rules.defaultTeam, r.allBlocks, seed);
            recorder = new Recorder();
            plugin.recorder = recorder;
            crashed = false;
            realStart = Time.nanos();
            loading = false;
        });
    }

    static void applyDifficulty(float mult){
        var rules = Vars.state.rules;
        Seq<Team> enemies = Seq.with(rules.waveTeam);
        for(var data : Vars.state.teams.getActive()) if(data.team != rules.defaultTeam) enemies.addUnique(data.team);
        for(Team t : enemies){
            var tr = rules.teams.get(t);
            tr.unitHealthMultiplier *= mult;
            tr.unitDamageMultiplier *= mult;
            tr.blockHealthMultiplier *= mult;
            tr.blockDamageMultiplier *= mult;
        }
    }

    void update(BloodBotPlugin plugin){
        if(loading) return;
        if(Vars.state.rules.waves) Vars.state.rules.waveTimer = true;
        try{
            agent.update();
        }catch(Throwable e){
            if(!crashed) Log.err("[bloodbot] agent error (game continues without it): @", e.toString());
            crashed = true;
        }
        recorder.update(plugin.ticks, agent.builder);

        boolean over = Vars.state.gameOver || plugin.winner != null;
        if(!over && plugin.ticks < CAP_SECONDS * 60) return;
        loading = true;
        endGame(plugin);
    }

    void endGame(BloodBotPlugin plugin){
        Team player = Vars.state.rules.defaultTeam;
        boolean won = plugin.winner == player;
        String outcome = plugin.winner == null ? "timeout" : won ? "win" : "loss";
        float coreHealth = player.core() == null ? 0f : player.core().health / player.core().maxHealth;
        float bonus = buildBonus(gen, agent.builder.built) + copperBonus(gen, agent.copperIn);
        float score = agent.fitness(won, plugin.ticks, CAP_SECONDS * 60) + bonus;
        boolean center = member == 2 * PAIRS;
        games = (long)gen * (2 * PAIRS + 1) + member + 1;

        String result = String.format(
            "{\"map\":\"%s\",\"outcome\":\"%s\",\"gameSeconds\":%d,\"realSeconds\":%.1f,\"wave\":%d,\"winWave\":%d,\"coreHealth\":%.3f," +
            "\"fitness\":%.2f,\"game\":%d,\"generation\":%d,\"member\":\"%s\",\"blocksBuilt\":%d,\"difficulty\":%.2f,\"buildBonus\":%.1f}\n",
            mapName, outcome, plugin.ticks / 60, (Time.nanos() - realStart) / 1e9, Vars.state.wave, Vars.state.rules.winWave,
            coreHealth, score, games, gen, center ? "brain" : "variant " + (member + 1), agent.builder.built, DIFFICULTY[level], bonus);

        boolean firstWin = won && (firstWinRecorded & (1L << rung)) == 0;
        if(recordAll || scheduled(games) || firstWin){
            recorder.save(out.child(mapName).child(String.format("game_%05d", games)), plugin.ticks, agent.builder, result);
            if(firstWin) firstWinRecorded |= 1L << rung;
        }
        plugin.recorder = null;

        StringBuilder actions = new StringBuilder();
        for(int i = 0; i < Agent.TYPES; i++){
            if(agent.actionCounts[i] == 0) continue;
            if(actions.length() > 0) actions.append(';');
            actions.append(Agent.TYPE_NAMES[i]).append('=').append(agent.actionCounts[i]);
        }
        out.child("log.csv").writeString(String.format("%d,%d,%s,%s,%.2f,%s,%d,%d,%.3f,%.2f,%d,%.1f,%d,%s\n",
            games, gen, center ? "brain" : String.valueOf(member + 1), mapName, DIFFICULTY[level], outcome, Vars.state.wave, Vars.state.rules.winWave,
            coreHealth, score, plugin.ticks / 60, (Time.nanos() - realStart) / 1e9, agent.builder.built, actions), true);
        Log.info("[bloodbot] TRAIN game @ (gen @ @): @ on @ (difficulty @%) at wave @, fitness @",
            games, gen, center ? "brain" : "variant " + (member + 1), outcome, mapName, (int)(DIFFICULTY[level] * 100), Vars.state.wave, String.format("%.1f", score));

        boolean beaten = center && won && (Vars.state.rules.winWave <= 0 || coreHealth >= 0.5f);
        round().child(member + ".txt").writeString(score + " " + (beaten ? 1 : 0), false);
        if(stopAfter > 0 && --stopAfter == 0){
            Log.info("[bloodbot] TRAIN stopping after requested number of games");
            Core.app.post(() -> System.exit(0));
            return;
        }
        startGame(plugin);
    }

    /** 1 through BUILD_BONUS_FULL_UNTIL, fading to 0 at BUILD_BONUS_GONE_AT. */
    static float bonusStrength(int generation){
        if(generation >= BUILD_BONUS_GONE_AT) return 0f;
        return generation < BUILD_BONUS_FULL_UNTIL ? 1f
            : 1f - (generation - BUILD_BONUS_FULL_UNTIL + 1f) / (BUILD_BONUS_GONE_AT - BUILD_BONUS_FULL_UNTIL + 1f);
    }

    static float buildBonus(int generation, int built){
        return built > 0 ? bonusStrength(generation) * BUILD_BONUS : 0f;
    }

    static float copperBonus(int generation, int copperIn){
        return copperIn > 0 ? bonusStrength(generation) * COPPER_BONUS : 0f;
    }

    // ---- rounds ----

    Fi round(){
        return out.child("rounds").child(String.valueOf(gen));
    }

    /** The next game of this round that is this worker's to play; -1 when it has played all of them. */
    int nextMember(){
        for(int k = 0; k <= 2 * PAIRS; k++){
            if(k % workers == worker && !round().child(k + ".txt").exists()) return k;
        }
        return -1;
    }

    void waitForRound(BloodBotPlugin plugin){
        if(waiting != null) return;
        if(workers > 1) Log.info("[bloodbot] TRAIN worker @ waiting for the rest of round @", worker + 1, gen);
        waiting = arc.util.Timer.schedule(() -> Core.app.post(() -> poll(plugin)), 0.2f, 1f);
    }

    /** Leader: once every result is in, update the brain. Others: wait until the leader has saved the new round. */
    void poll(BloodBotPlugin plugin){
        if(waiting == null) return;
        if(worker == 0){
            for(int k = 0; k <= 2 * PAIRS; k++) if(!round().child(k + ".txt").exists()) return;
            for(int k = 0; k < 2 * PAIRS; k++) fitness[k] = Float.parseFloat(round().child(k + ".txt").readString().trim().split(" ")[0]);
            boolean beaten = round().child(2 * PAIRS + ".txt").readString().trim().endsWith(" 1");
            finishRound(beaten);
        }else{
            if(savedGeneration() <= gen) return;
            load();
        }
        waiting.cancel();
        waiting = null;
        startGame(plugin);
    }

    void finishRound(boolean beaten){
        centerWinsInRow = beaten ? centerWinsInRow + 1 : 0;
        int finished = gen;
        step();
        if(centerWinsInRow >= 2){
            centerWinsInRow = 0;
            if(level + 1 < DIFFICULTY.length){
                level++;
                Log.info("[bloodbot] TRAIN LEVEL UP: @ now at @% enemy strength", rungName(), (int)(DIFFICULTY[level] * 100));
            }else if(rung + 1 < ladder.size){
                Log.info("[bloodbot] TRAIN PROMOTED: @ beaten at full strength -> next map", rungName());
                rung++;
                level = 0;
            }
        }
        save();
        out.child("rounds").child(String.valueOf(finished)).deleteDirectory();
    }

    /** The generation number stored in state.bin, read without loading the whole file. */
    int savedGeneration(){
        try(java.io.DataInputStream in = new java.io.DataInputStream(out.child("state.bin").read(64))){
            in.readInt(); in.readInt(); in.readInt(); in.readInt();
            return in.readInt();
        }catch(Exception e){
            return -1;
        }
    }

    // ---- evolution ----

    float[] noise(int pair){
        Random rand = new Random(gen * 1_000_003L + pair * 7919L + 42);
        float[] eps = new float[n];
        for(int i = 0; i < n; i++) eps[i] = (float)rand.nextGaussian();
        return eps;
    }

    float[] memberParams(){
        if(member == 2 * PAIRS) return theta.clone();
        float[] eps = noise(member / 2);
        float sign = member % 2 == 0 ? 1 : -1;
        float[] p = new float[n];
        for(int i = 0; i < n; i++) p[i] = theta[i] + sign * SIGMA * eps[i];
        return p;
    }

    /** One Adam step up the rank-normalised fitness gradient, then archive the brain. */
    void step(){
        float[] ranks = centeredRanks(fitness);
        float[] grad = new float[n];
        for(int pair = 0; pair < PAIRS; pair++){
            float w = ranks[2 * pair] - ranks[2 * pair + 1];
            float[] eps = noise(pair);
            for(int i = 0; i < n; i++) grad[i] += w * eps[i];
        }
        adamT++;
        float b1 = 0.9f, b2 = 0.999f;
        float c1 = 1 - (float)Math.pow(b1, adamT), c2 = 1 - (float)Math.pow(b2, adamT);
        for(int i = 0; i < n; i++){
            float g = grad[i] / (2 * PAIRS * SIGMA) - WEIGHT_DECAY * theta[i];
            m[i] = b1 * m[i] + (1 - b1) * g;
            v[i] = b2 * v[i] + (1 - b2) * g * g;
            theta[i] += LR * (m[i] / c1) / ((float)Math.sqrt(v[i] / c2) + 1e-8f);
        }
        Log.info("[bloodbot] TRAIN generation @ done: variant fitness @", gen, Arrays.toString(fitness));
        Fi brains = out.child("brains");
        new Brain(layers, theta).save(brains.child("latest.bin"));
        if(gen % 10 == 0) new Brain(layers, theta).save(brains.child(String.format("gen_%05d.bin", gen)));
        gen++;
        member = 0;
        Arrays.fill(fitness, 0);
    }

    static float[] centeredRanks(float[] x){
        Integer[] order = new Integer[x.length];
        for(int i = 0; i < x.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Float.compare(x[a], x[b]));
        float[] r = new float[x.length];
        for(int i = 0; i < x.length; i++) r[order[i]] = x.length == 1 ? 0 : i / (float)(x.length - 1) - 0.5f;
        return r;
    }

    /** Games 1, 5, 10, 25, 50, 100, 250, 500, 1000, ... are recorded. */
    static boolean scheduled(long game){
        if(game == 1) return true;
        for(long scale = 1; scale <= game; scale *= 10){
            if(game == 5 * scale || game == 10 * scale || game == 25 * scale) return true;
        }
        return false;
    }

    // ---- persistence ----

    void save(){
        Fi tmp = out.child("state.bin.tmp");
        try(DataOutputStream o = new DataOutputStream(tmp.write(false, 1 << 16))){
            o.writeInt(STATE_VERSION);
            o.writeInt(n);
            o.writeInt(rung); o.writeInt(level); o.writeInt(gen); o.writeInt(member); o.writeInt(adamT); o.writeInt(centerWinsInRow);
            o.writeLong(games); o.writeLong(firstWinRecorded);
            for(float f : fitness) o.writeFloat(f);
            for(float f : theta) o.writeFloat(f);
            for(float f : m) o.writeFloat(f);
            for(float f : v) o.writeFloat(f);
        }catch(Exception e){
            throw new RuntimeException(e);
        }
        tmp.moveTo(out.child("state.bin")); // atomic-ish: never leaves a half-written state behind
    }

    void load(){
        try(DataInputStream in = new DataInputStream(out.child("state.bin").read(1 << 16))){
            if(in.readInt() != STATE_VERSION) throw new IllegalStateException("state.bin is from another version");
            if(in.readInt() != n) throw new IllegalStateException("brain size changed (block list differs); start a new run directory");
            rung = in.readInt(); level = in.readInt(); gen = in.readInt(); member = in.readInt(); adamT = in.readInt(); centerWinsInRow = in.readInt();
            games = in.readLong(); firstWinRecorded = in.readLong();
            for(int i = 0; i < fitness.length; i++) fitness[i] = in.readFloat();
            theta = new float[n]; m = new float[n]; v = new float[n];
            for(int i = 0; i < n; i++) theta[i] = in.readFloat();
            for(int i = 0; i < n; i++) m[i] = in.readFloat();
            for(int i = 0; i < n; i++) v[i] = in.readFloat();
        }catch(Exception e){
            throw new RuntimeException(e);
        }
        rung = Math.min(rung, ladder.size - 1);
    }
}
