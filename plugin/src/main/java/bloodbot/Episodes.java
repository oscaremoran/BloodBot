package bloodbot;

import arc.Core;
import arc.files.Fi;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.maps.Map;

/**
 * Plays a list of games one after another with the starter bot, recording every game.
 * Each entry is "path/to/map.msav:winWave" (winWave 0 = win by destroying enemy cores).
 * Output per game: <outDir>/<map>/game_NNNN/{frames/, snapshots/, stats.jsonl, result.json}.
 */
public class Episodes{
    record Entry(String path, int winWave){}

    final Fi outDir;
    final long capTicks;
    final Seq<Entry> entries = new Seq<>();
    int index = -1;
    boolean loading = true;
    long realStart;
    String mapName;
    StarterBot bot;
    Recorder recorder;
    Fi gameDir;

    Episodes(Fi outDir, float capSeconds, String[] specs){
        this.outDir = outDir;
        this.capTicks = (long)(capSeconds * BloodBotPlugin.TICKS_PER_SECOND);
        for(String spec : specs){
            int colon = spec.lastIndexOf(':');
            entries.add(new Entry(spec.substring(0, colon), Integer.parseInt(spec.substring(colon + 1))));
        }
    }

    void next(BloodBotPlugin plugin){
        index++;
        loading = true;
        if(index >= entries.size){
            Log.info("[bloodbot] EPISODES DONE -> @", outDir.absolutePath());
            plugin.episodes = null;
            if(Boolean.getBoolean("bloodbot.exitWhenDone")) Core.app.post(() -> System.exit(0));
            return;
        }
        // Load outside the update loop; swapping the world mid-update is unsafe.
        Core.app.post(() -> {
            Entry e = entries.get(index);
            Map map = plugin.findMap(e.path);
            if(map == null){
                next(plugin);
                return;
            }
            plugin.startGame(map, e.winWave);
            mapName = map.file.nameWithoutExtension();
            Fi mapDir = outDir.child(mapName);
            int game = mapDir.exists() ? mapDir.seq().count(f -> f.name().startsWith("game_")) + 1 : 1;
            gameDir = mapDir.child(String.format("game_%04d", game));
            recorder = new Recorder();
            plugin.recorder = recorder;
            bot = new StarterBot(Vars.state.rules.defaultTeam);
            realStart = Time.nanos();
            loading = false;
        });
    }

    void update(BloodBotPlugin plugin){
        if(loading) return;
        // Waves always run on their timer; nothing (tutorial scripts included) may pause them.
        if(Vars.state.rules.waves) Vars.state.rules.waveTimer = true;
        bot.update();
        recorder.update(plugin.ticks, bot.builder);

        boolean over = Vars.state.gameOver || plugin.winner != null;
        if(!over && plugin.ticks < capTicks) return;

        loading = true;
        Team player = Vars.state.rules.defaultTeam;
        String outcome = plugin.winner == null ? "timeout" : plugin.winner == player ? "win" : "loss";
        String result = String.format(
            "{\"map\":\"%s\",\"outcome\":\"%s\",\"gameSeconds\":%d,\"realSeconds\":%.1f,\"wave\":%d,\"winWave\":%d,\"blocksBuilt\":%d,\"drills\":%d,\"pods\":%d,\"coreHealth\":%.3f}\n",
            mapName, outcome, plugin.ticks / 60, (Time.nanos() - realStart) / 1e9, Vars.state.wave, Vars.state.rules.winWave,
            bot.builder.built, bot.coreDrills, bot.pods,
            player.core() == null ? 0f : player.core().health / player.core().maxHealth);
        recorder.save(gameDir, plugin.ticks, bot.builder, result);
        plugin.recorder = null;
        Log.info("[bloodbot] EPISODE @ on @: @ at wave @ after @s game time", index + 1, mapName, outcome, Vars.state.wave, plugin.ticks / 60);
        next(plugin);
    }
}
