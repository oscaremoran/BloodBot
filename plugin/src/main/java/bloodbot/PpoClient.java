package bloodbot;

import arc.Core;
import arc.files.Fi;
import arc.util.Log;
import arc.util.Time;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.maps.Map;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;

/**
 * Lets the Python PPO trainer play: the game connects to it, and at every bot decision sends what the bot sees
 * (map picture, numbers, and which choices are possible), plus the reward earned since the last decision,
 * then waits for the chosen action. The game is paused while waiting, so play is identical to normal.
 *
 * Python sends a RESET (JSON) to start each game; it can ask to save the previous game's replay.
 * Messages are big-endian. Game -> Python: int kind (1 = decision, 2 = game over), float reward, then for a
 * decision the observation and masks, for a game over a JSON result. Python -> game: int kind (1 = action + 7 ints,
 * 2 = reset + JSON).
 */
public class PpoClient implements Agent.Policy{
    static final float CAP_SECONDS = Trainer.CAP_SECONDS;

    final BloodBotPlugin plugin;
    final int worker;
    DataInputStream in;
    DataOutputStream out;

    boolean loading = true, bonuses, anyAmmo;
    Agent agent;
    Recorder recorder;
    String mapName;
    float difficulty, lastPotential;
    boolean buildRewarded, copperRewarded, crashed;
    long realStart;
    // The finished game, kept until Python says whether to save its replay.
    Recorder lastRecorder;
    Builder lastBuilder;
    long lastTicks;
    String lastResult;

    PpoClient(BloodBotPlugin plugin, int worker){
        this.plugin = plugin;
        this.worker = worker;
    }

    void connect(int port){
        for(int attempt = 0; ; attempt++){
            try{
                Socket socket = new Socket("127.0.0.1", port);
                socket.setTcpNoDelay(true);
                in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 1 << 16));
                out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 1 << 20));
                out.writeInt(worker);
                // Sizes, so the trainer's network always matches this game's.
                for(int v : new int[]{Vision.C, Vision.G, Agent.scalars(), Agent.catalog().size, Agent.TYPES,
                    Agent.FINE, Agent.ROTATIONS, Agent.ARGS}) out.writeInt(v);
                out.flush();
                Log.info("[bloodbot] PPO worker @ connected to trainer on port @", worker, port);
                return;
            }catch(Exception e){
                if(attempt > 120) throw new RuntimeException("PPO trainer not reachable on port " + port, e);
                try{ Thread.sleep(500); }catch(InterruptedException ignored){}
            }
        }
    }

    // ---- game lifecycle ----

    /** Blocks until Python sends the next game; saves the previous replay first if asked. */
    void awaitReset(){
        try{
            int kind = in.readInt();
            if(kind != 2) throw new IllegalStateException("expected reset, got " + kind);
            Jval msg = Jval.read(in.readUTF());
            if(msg.has("saveDir") && lastRecorder != null){
                Fi dir = Core.files.absolute(new java.io.File(msg.getString("saveDir")).getAbsolutePath());
                lastRecorder.save(dir, lastTicks, lastBuilder, lastResult);
            }
            lastRecorder = null;
            lastBuilder = null;
            if(msg.getBool("quit", false)){
                Core.app.post(() -> System.exit(0));
                return;
            }
            String path = msg.getString("map");
            int winWave = msg.getInt("winWave", 0);
            boolean allBlocks = msg.getBool("allBlocks", false);
            difficulty = msg.getFloat("difficulty", 1f);
            bonuses = msg.getBool("bonuses", false);
            anyAmmo = msg.getBool("anyAmmo", false);
            Core.app.post(() -> startGame(path, winWave, allBlocks));
        }catch(Exception e){
            Log.err("[bloodbot] PPO connection lost; stopping", e);
            Core.app.post(() -> System.exit(1));
        }
    }

    void startGame(String path, int winWave, boolean allBlocks){
        Map map = plugin.findMap(path);
        if(map == null){
            Log.err("[bloodbot] PPO cannot load @", path);
            System.exit(1);
            return;
        }
        plugin.startGame(map, winWave);
        mapName = map.file.nameWithoutExtension();
        Trainer.applyDifficulty(difficulty);
        agent = new Agent(null, Vars.state.rules.defaultTeam, allBlocks, Time.nanos());
        agent.policy = this;
        agent.anyAmmo = anyAmmo;
        recorder = new Recorder();
        plugin.recorder = recorder;
        lastPotential = 0;
        buildRewarded = copperRewarded = crashed = false;
        realStart = Time.nanos();
        loading = false;
    }

    void update(){
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
        endGame();
    }

    void endGame(){
        Team player = Vars.state.rules.defaultTeam;
        boolean won = plugin.winner == player;
        String outcome = plugin.winner == null ? "timeout" : won ? "win" : "loss";
        float coreHealth = player.core() == null ? 0f : player.core().health / player.core().maxHealth;
        float capTicks = CAP_SECONDS * 60;
        agent.penalizeUnused();
        float fitness = agent.fitness(won, plugin.ticks, capTicks) + agent.rulePointsTotal;
        float reward = stepReward() + (won ? (agent.fitness(true, plugin.ticks, capTicks) - agent.potential(plugin.ticks, capTicks)) / 100f : 0f);

        StringBuilder actions = new StringBuilder();
        for(int i = 0; i < Agent.TYPES; i++){
            if(agent.actionCounts[i] == 0) continue;
            if(actions.length() > 0) actions.append(';');
            actions.append(Agent.TYPE_NAMES[i]).append('=').append(agent.actionCounts[i]);
        }
        lastResult = String.format(
            "{\"map\":\"%s\",\"outcome\":\"%s\",\"gameSeconds\":%d,\"realSeconds\":%.1f,\"wave\":%d,\"winWave\":%d,\"coreHealth\":%.3f," +
            "\"fitness\":%.2f,\"blocksBuilt\":%d,\"difficulty\":%.2f,\"actions\":\"%s\",\"worker\":%d}",
            mapName, outcome, plugin.ticks / 60, (Time.nanos() - realStart) / 1e9, Vars.state.wave, Vars.state.rules.winWave,
            coreHealth, fitness, agent.builder.built, difficulty, actions, worker);
        lastRecorder = recorder;
        lastBuilder = agent.builder;
        lastTicks = plugin.ticks;
        plugin.recorder = null;

        try{
            out.writeInt(2);
            out.writeFloat(reward);
            out.writeUTF(lastResult);
            out.flush();
        }catch(Exception e){
            Log.err("[bloodbot] PPO connection lost; stopping", e);
            System.exit(1);
        }
        awaitReset();
    }

    // ---- decisions ----

    /** Reward since the last decision: change in the outcome score (scaled), plus one-time bonuses if enabled. */
    float stepReward(){
        float potential = agent.potential(plugin.ticks, CAP_SECONDS * 60);
        float r = (potential - lastPotential) / 100f + agent.rulePointsPending / 100f;
        lastPotential = potential;
        agent.rulePointsPending = 0;
        if(bonuses){
            if(!buildRewarded && agent.builder.built > 0){ buildRewarded = true; r += 1.0f; }
            if(!copperRewarded && agent.copperIn > 0){ copperRewarded = true; r += 0.5f; }
        }
        return r;
    }

    @Override
    public int[] decide(Agent a){
        try{
            float reward = stepReward();
            float[] grid = a.vision.see(a.team, a.builder.ship);
            float[] scalars = a.scalarFeatures();
            out.writeInt(1);
            out.writeFloat(reward);
            for(float v : grid) out.writeFloat(v);
            for(float v : scalars) out.writeFloat(v);
            writeMask(a.possibleTypes());
            writeMask(a.blockMask());
            writeMask(a.vision.usable);
            writeMask(a.mineArgMask());
            writeMask(a.deliverArgMask());
            out.flush();

            int kind = in.readInt();
            if(kind != 1) throw new IllegalStateException("expected action, got " + kind);
            int[] action = new int[7];
            for(int i = 0; i < 7; i++) action[i] = in.readInt();
            return action;
        }catch(Exception e){
            Log.err("[bloodbot] PPO connection lost; stopping", e);
            System.exit(1);
            return new int[]{Agent.WAIT, 0, 0, 0, 0, 0, 0};
        }
    }

    void writeMask(boolean[] mask) throws java.io.IOException{
        for(boolean b : mask) out.writeByte(b ? 1 : 0);
    }
}
