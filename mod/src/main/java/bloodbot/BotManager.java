package bloodbot;

import arc.files.Fi;
import arc.struct.ObjectMap;
import arc.util.Log;
import mindustry.Vars;
import mindustry.game.Team;

import java.util.Arrays;

/**
 * Runs the trained bot for any number of teams in the current game. Each controlled team gets its own
 * Agent (brain + ship + builder), exactly as in training. Runs only where the game simulation runs
 * (single player, or the host/server in multiplayer), never on a joining client.
 */
public class BotManager{
    record Bot(Agent agent, Difficulty difficulty){}

    final Fi brainDir;
    final ObjectMap<Difficulty, Brain> brains = new ObjectMap<>();
    final ObjectMap<Team, Bot> bots = new ObjectMap<>();

    BotManager(Fi brainDir){
        this.brainDir = brainDir;
    }

    /** Loads (once) the brain for a difficulty; null if the mod has no brain file for it or it doesn't fit this game version. */
    Brain brain(Difficulty d){
        if(brains.containsKey(d)) return brains.get(d);
        Brain brain = null;
        Fi file = brainDir.child(d.brainFile);
        if(file.exists()){
            brain = Brain.load(file);
            // Everything but the hidden size (index 6) must match this game's inputs and outputs; the hidden size is the
            // brain's own business (64 for the old evolution brains, 256 for PPO).
            int[] want = Agent.shape().clone(), got = brain.shape.clone();
            if(got.length == want.length){ want[6] = 0; got[6] = 0; }
            if(!Arrays.equals(got, want)){
                Log.err("[bloodbot] @ was trained with a different block list or brain layout; it can't be used with this game version.", d.brainFile);
                brain = null;
            }
        }else{
            Log.err("[bloodbot] missing brain file: @", file);
        }
        brains.put(d, brain);
        return brain;
    }

    boolean canRun(){
        return Vars.state.isGame() && !Vars.net.client();
    }

    /** Hands a team over to the bot. Returns an error message, or null on success. */
    String start(Team team, Difficulty difficulty){
        if(!canRun()) return "The bot only runs in single player or on the host.";
        if(team.core() == null) return "Team " + team.name + " has no core.";
        Brain brain = brain(difficulty);
        if(brain == null) return "No usable " + difficulty.title + " brain in this mod build.";
        stop(team);
        Agent agent = new Agent(brain, team, false, System.nanoTime());
        agent.temperature = difficulty.temperature;
        bots.put(team, new Bot(agent, difficulty));
        Log.info("[bloodbot] now playing team @ on @", team.name, difficulty.title);
        return null;
    }

    void stop(Team team){
        Bot bot = bots.remove(team);
        if(bot != null && ShipAI.alive(bot.agent().builder.ship)) bot.agent().builder.ship.kill();
    }

    void stopAll(){
        for(Team team : bots.keys().toSeq()) stop(team);
    }

    /** Forget all bots (new map loaded); their units belong to the old world. */
    void reset(){
        bots.clear();
    }

    Difficulty difficultyOf(Team team){
        Bot bot = bots.get(team);
        return bot == null ? null : bot.difficulty();
    }

    void update(){
        if(bots.isEmpty() || !canRun() || !Vars.state.isPlaying()) return;
        for(var entry : bots){
            Team team = entry.key;
            if(team.core() == null) continue; // team eliminated
            try{
                entry.value.agent().update();
            }catch(Throwable e){
                Log.err("[bloodbot] bot for team @ crashed and was stopped: @", team.name, e);
                arc.Core.app.post(() -> bots.remove(team));
            }
        }
    }
}
