package bloodbot;

import arc.Core;
import arc.files.Fi;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.game.Rules;
import mindustry.game.Team;
import mindustry.game.Teams.TeamData;
import mindustry.io.MapIO;
import mindustry.maps.Map;

/**
 * Plays each map once with a bot that does nothing and records how the map treats it.
 * How fast an idle player dies, and how much enemy base there is to destroy, are cheap
 * difficulty signals for ordering maps into a training ladder.
 */
public class Survey{
    final long capTicks;
    final Fi out;
    final Seq<Map> maps = new Seq<>();
    int index = -1;
    boolean loading;
    long realStart;
    StringBuilder start;

    Survey(float capSeconds, Fi out, String source){
        this.capTicks = (long)(capSeconds * BloodBotPlugin.TICKS_PER_SECOND);
        this.out = out;
        if(source.equals("builtin")){
            maps.addAll(Vars.maps.all().select(m -> !m.custom));
        }else{
            for(Fi file : Core.files.absolute(new java.io.File(source).getAbsolutePath()).list(".msav")){
                try{
                    maps.add(MapIO.createMap(file, true));
                }catch(Exception e){
                    Log.err("[bloodbot] survey: unreadable map @: @", file.name(), e.getMessage());
                }
            }
        }
        out.writeString("", false);
    }

    void start(BloodBotPlugin plugin){
        Log.info("[bloodbot] survey: @ maps, cap @s game time each", maps.size, capTicks / BloodBotPlugin.TICKS_PER_SECOND);
        next(plugin);
    }

    void next(BloodBotPlugin plugin){
        index++;
        if(index >= maps.size){
            Log.info("[bloodbot] SURVEY DONE -> @", out.absolutePath());
            plugin.survey = null;
            if(Boolean.getBoolean("bloodbot.exitWhenDone")) Core.app.post(() -> System.exit(0));
            return;
        }
        loading = true;
        // Load outside the update loop; swapping the world mid-update is unsafe.
        Core.app.post(() -> {
            Map map = maps.get(index);
            try{
                plugin.loadMap(map);
                start = describeStart(map);
            }catch(Throwable e){
                Log.err("[bloodbot] survey: failed to load @: @", map.plainName(), e.toString());
                out.writeString("{\"file\":" + q(map.file.name()) + ",\"error\":" + q(e.toString()) + "}\n", true);
                loading = false;
                next(plugin);
                return;
            }
            realStart = Time.nanos();
            loading = false;
        });
    }

    void update(BloodBotPlugin plugin){
        if(loading) return;
        boolean over = Vars.state.gameOver || plugin.winner != null;
        if(!over && plugin.ticks < capTicks) return;

        loading = true;
        Rules r = Vars.state.rules;
        Team player = r.defaultTeam;
        TeamData pd = player.data();
        float coreHealth = pd.cores.sumf(c -> c.health), coreMax = pd.cores.sumf(c -> c.maxHealth);

        StringBuilder s = start;
        s.append(",\"outcome\":").append(q(plugin.winner == null ? "timeout" : plugin.winner == player ? "win" : "loss"));
        s.append(",\"winner\":").append(q(plugin.winner == null ? "" : plugin.winner.name));
        s.append(",\"gameSeconds\":").append(Math.round(plugin.ticks / BloodBotPlugin.TICKS_PER_SECOND));
        s.append(",\"realSeconds\":").append(String.format("%.2f", (Time.nanos() - realStart) / 1e9));
        s.append(",\"waveReached\":").append(Vars.state.wave);
        s.append(",\"playerCoreHealthFrac\":").append(coreMax <= 0 ? 0 : String.format("%.3f", coreHealth / coreMax));
        s.append(",\"enemyAtEnd\":").append(teamStats(player, true));
        s.append("}\n");
        out.writeString(s.toString(), true);
        Log.info("[bloodbot] survey @/@ done: @", index + 1, maps.size, maps.get(index).plainName());
        next(plugin);
    }

    StringBuilder describeStart(Map map){
        Rules r = Vars.state.rules;
        TeamData pd = r.defaultTeam.data();
        StringBuilder s = new StringBuilder("{");
        s.append("\"file\":").append(q(map.file.name()));
        s.append(",\"name\":").append(q(map.plainName()));
        s.append(",\"author\":").append(q(map.author()));
        s.append(",\"width\":").append(Vars.world.width()).append(",\"height\":").append(Vars.world.height());
        s.append(",\"planet\":").append(q(r.planet == null ? "" : r.planet.name));
        s.append(",\"attackMode\":").append(r.attackMode);
        s.append(",\"waves\":").append(r.waves).append(",\"waveTimer\":").append(r.waveTimer);
        s.append(",\"winWave\":").append(r.winWave);
        s.append(",\"waveSpacingSeconds\":").append(Math.round(r.waveSpacing / 60f));
        s.append(",\"groundSpawns\":").append(Vars.spawner.countGroundSpawns());
        s.append(",\"flyerSpawns\":").append(Vars.spawner.countFlyerSpawns());
        s.append(",\"playerCores\":").append(pd.cores.size);
        s.append(",\"playerCoreTypes\":").append(q(pd.cores.map(c -> c.block.name).toString(",")));
        s.append(",\"playerStartItems\":").append(pd.cores.isEmpty() ? 0 : pd.core().items.total());
        s.append(",\"playerBuildings\":").append(pd.buildings.size);
        s.append(",\"enemyAtStart\":").append(teamStats(r.defaultTeam, false));
        return s;
    }

    /** Sums every non-player team: what the bot would have to destroy. */
    static String teamStats(Team player, boolean end){
        int cores = 0, buildings = 0, units = 0;
        float health = 0;
        Seq<String> teams = new Seq<>();
        Seq<String> ai = new Seq<>();
        for(TeamData data : Vars.state.teams.getActive()){
            if(data.team == player) continue;
            teams.add(data.team.name);
            cores += data.cores.size;
            buildings += data.buildings.size;
            units += data.units.size;
            health += data.buildings.sumf(b -> b.health);
            Rules.TeamRule tr = Vars.state.rules.teams.get(data.team);
            if(tr.buildAi) ai.add("build");
            if(tr.rtsAi) ai.add("rts");
        }
        return "{\"teams\":" + q(teams.toString(",")) + ",\"cores\":" + cores + ",\"buildings\":" + buildings +
            ",\"units\":" + units + ",\"buildingHealth\":" + Math.round(health) +
            (end ? "" : ",\"ai\":" + q(ai.distinct().toString(","))) + "}";
    }

    static String q(String s){
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"";
    }
}
