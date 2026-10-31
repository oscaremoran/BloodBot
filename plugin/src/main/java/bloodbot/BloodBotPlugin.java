package bloodbot;

import arc.Core;
import arc.Events;
import arc.backend.headless.HeadlessApplication;
import arc.files.Fi;
import arc.util.CommandHandler;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.game.EventType.GameOverEvent;
import mindustry.game.EventType.ServerLoadEvent;
import mindustry.game.EventType.TileChangeEvent;
import mindustry.game.EventType.UnitDestroyEvent;
import mindustry.game.EventType.UnitDamageEvent;
import mindustry.game.EventType.BuildDamageEvent;
import mindustry.game.EventType.Trigger;
import mindustry.game.Rules;
import mindustry.game.Team;
import mindustry.game.Teams.TeamData;
import mindustry.io.MapIO;
import mindustry.maps.Map;
import mindustry.mod.Plugin;
import mindustry.server.ServerControl;

import java.lang.reflect.Field;

/**
 * Training bridge for Mindustry: loads maps headlessly, runs the simulation as fast as the CPU
 * allows, and owns the game-over lifecycle instead of the stock server's map rotation.
 */
public class BloodBotPlugin extends Plugin{
    /** Game logic runs at 60 ticks per second of game time. */
    static final float TICKS_PER_SECOND = 60f;
    static final int STARTING_COPPER = 50;

    long ticks;
    long benchStartNanos;
    long benchTargetTicks = -1;
    Team winner;
    Survey survey;
    Episodes episodes;
    Trainer trainer;
    PpoClient ppo;
    /** The recorder of the game in progress, fed tile changes; null between games. */
    Recorder recorder;

    @Override
    public void init(){
        Events.on(ServerLoadEvent.class, e -> {
            // The stock listener announces the winner and rotates to the next map; we decide what loads next.
            ServerControl.instance.gameOverListener = event -> {};

            // Commands passed as -Dbloodbot.run="cmd1;cmd2" run at startup, so scripts need no console input.
            String run = System.getProperty("bloodbot.run");
            if(run != null){
                for(String cmd : run.split(";")){
                    Core.app.post(() -> ServerControl.instance.handleCommandString(cmd.trim()));
                }
            }
        });

        Events.run(Trigger.update, () -> {
            if(!Vars.state.isPlaying()) return;
            ticks++;
            if(benchTargetTicks > 0 && ticks >= benchTargetTicks){
                reportBench("time limit");
            }
            if(survey != null) survey.update(this);
            if(episodes != null) episodes.update(this);
            if(trainer != null) trainer.update(this);
            if(ppo != null) ppo.update();
        });

        Events.on(TileChangeEvent.class, e -> {
            if(recorder != null) recorder.tileChanged(e.tile);
        });

        Events.on(UnitDestroyEvent.class, e -> {
            if(trainer != null && trainer.agent != null && !trainer.loading && e.unit.team != trainer.agent.team) trainer.agent.kills++;
            if(ppo != null && ppo.agent != null && !ppo.loading && e.unit.team != ppo.agent.team){
                ppo.agent.kills++;
                if(e.unit.type == mindustry.content.UnitTypes.mace) ppo.agent.maceKilled(e.unit.x, e.unit.y);
            }
        });

        // +5 per turret hit on an enemy, +50 for each turret's first hit (the user's rules), for the PPO bot.
        Events.on(UnitDamageEvent.class, e -> turretHit(e.bullet, e.unit.team, e.unit.x, e.unit.y));
        Events.on(BuildDamageEvent.class, e -> {
            turretHit(e.source, e.build.team, e.build.x, e.build.y);
            wallHit(e.build, e.source);
        });

        Events.on(GameOverEvent.class, e -> {
            winner = e.winner;
            Log.info("[bloodbot] GAME OVER winner=@ at tick @ (@s game time)", e.winner, ticks, ticks / TICKS_PER_SECOND);
            if(benchTargetTicks > 0) reportBench("game over");
        });
    }

    @Override
    public void registerServerCommands(CommandHandler handler){
        handler.register("bbload", "<map...>", "Load a map (path to .msav or built-in map name) with the map's own rules.", args -> {
            Map map = findMap(args[0]);
            if(map != null) loadMap(map);
        });

        handler.register("bbfast", "<on/off>", "Run the simulation unthrottled at exactly 1 tick per frame.", args -> setFast(args[0].equals("on")));

        handler.register("bbbench", "<gameSeconds>", "Run for N seconds of game time and report the speed-up.", args -> {
            ticks = 0;
            benchStartNanos = Time.nanos();
            benchTargetTicks = (long)(Float.parseFloat(args[0]) * TICKS_PER_SECOND);
        });

        handler.register("bbinfo", "Print teams, cores and rules of the loaded map.", args -> printInfo());

        handler.register("bbkillenemy", "Destroy every enemy core, to test game-over detection.", args -> {
            Team enemy = Vars.state.rules.waveTeam;
            enemy.cores().copy().each(c -> c.kill());
        });

        handler.register("bbsurvey", "<capSeconds> <outFile> <dir...>",
            "Play every .msav in a directory (or 'builtin') with an idle bot and write difficulty stats as JSON lines.", args -> {
            setFast(true);
            survey = new Survey(Float.parseFloat(args[0]), Core.files.absolute(new java.io.File(args[1]).getAbsolutePath()), args[2]);
            survey.start(this);
        });

        handler.register("bbepisodes", "<capSeconds> <outDir> <maps...>",
            "Play and record games with the starter bot. Maps are 'path.msav:winWave' separated by spaces.", args -> {
            setFast(true);
            episodes = new Episodes(Core.files.absolute(new java.io.File(args[1]).getAbsolutePath()), Float.parseFloat(args[0]), args[2].split(" +"));
            episodes.next(this);
        });

        handler.register("bbtrain", "<outDir> <maps...>",
            "Self-learning by evolution. Maps are 'path.msav:winWave[:all]' in ladder order; resumes from outDir/state.bin.", args -> {
            setFast(true);
            trainer = new Trainer(Core.files.absolute(new java.io.File(args[0]).getAbsolutePath()), args[1].split(" +"));
            trainer.startGame(this);
        });

        handler.register("bbppo", "<port> <worker>", "Play games for the Python PPO trainer listening on this port.", args -> {
            setFast(true);
            ppo = new PpoClient(this, Integer.parseInt(args[1]));
            ppo.connect(Integer.parseInt(args[0]));
            ppo.awaitReset();
        });

        handler.register("bbrecordall", "Record every training game for the viewer.", args -> {
            if(trainer != null) trainer.recordAll = true;
        });

        handler.register("bbstopafter", "<games>", "Stop training (and the server) after N more games.", args -> {
            if(trainer != null) trainer.stopAfter = Integer.parseInt(args[0]);
        });
    }

    /** Enemy fire landing on one of the bot's walls (the wall-soak reward). */
    void wallHit(mindustry.gen.Building build, mindustry.gen.Bullet bullet){
        if(ppo == null || ppo.agent == null || ppo.loading || bullet == null) return;
        Team us = ppo.agent.team;
        if(build.team == us && bullet.team != us && build.block.category == mindustry.type.Category.defense){
            ppo.agent.wallSoaked(build, bullet.damage * bullet.type.buildingDamageMultiplier);
        }
    }

    void turretHit(mindustry.gen.Bullet bullet, Team victim, float x, float y){
        if(ppo == null || ppo.agent == null || ppo.loading || bullet == null) return;
        Team us = ppo.agent.team;
        if(bullet.team == us && victim != us && bullet.owner instanceof mindustry.gen.Building b
            && b.block instanceof mindustry.world.blocks.defense.turrets.Turret){
            ppo.agent.turretHit(b, x, y);
        }
    }

    Map findMap(String name){
        try{
            if(name.endsWith(".msav")){
                return MapIO.createMap(Core.files.absolute(new java.io.File(name).getAbsolutePath()), true);
            }
            Map map = Vars.maps.all().find(m -> m.plainName().equalsIgnoreCase(name) || m.file.nameWithoutExtension().equalsIgnoreCase(name));
            if(map == null) Log.err("[bloodbot] no map named @", name);
            return map;
        }catch(Exception e){
            Log.err("[bloodbot] could not read map @", name);
            Log.err(e);
            return null;
        }
    }

    /** Loads a map with the rules saved in the map file, as the desktop client does for custom maps. */
    void loadMap(Map map){
        Vars.logic.reset();
        Rules rules = map.rules();
        Vars.world.loadMap(map, rules);
        Vars.state.rules = rules;
        Vars.logic.play();
        ticks = 0;
        winner = null;
        Log.info("[bloodbot] loaded @ (@x@)", map.plainName(), Vars.world.width(), Vars.world.height());
        printInfo();
    }

    /** Loads a map for a bot game: sets the win wave and skips scripted tutorial steps that can pause the waves. */
    void startGame(Map map, int winWave){
        loadMap(map);
        if(winWave > 0) Vars.state.rules.winWave = winWave;
        // Starting copper for every bot game (the user's choice, 2026-10-29), so building is possible from the first second.
        var core = Vars.state.rules.defaultTeam.core();
        if(core != null) core.items.add(mindustry.content.Items.copper, STARTING_COPPER);
        if(Vars.state.rules.waves && Vars.state.rules.objectives.any()){
            Vars.state.rules.objectives.clear();
            Log.info("[bloodbot] tutorial objectives skipped");
        }
    }

    void printInfo(){
        Rules r = Vars.state.rules;
        Log.info("[bloodbot] attackMode=@ waves=@ waveTeam=@ defaultTeam=@ enemyCoreBuildRadius=@",
            r.attackMode, r.waves, r.waveTeam, r.defaultTeam, r.enemyCoreBuildRadius);
        Log.info("[bloodbot] waveTimer=@ wavetime=@s waveSpacing=@s winWave=@ objectives=@",
            r.waveTimer, (int)(Vars.state.wavetime / 60), (int)(r.waveSpacing / 60), r.winWave,
            r.objectives.all.map(o -> o.getClass().getSimpleName()).toString(","));
        Log.info("[bloodbot] bannedBlocks(@)=@ bannedUnits(@)=@ planet=@",
            r.bannedBlocks.size, r.bannedBlocks.toSeq().map(b -> b.name).toString(","),
            r.bannedUnits.size, r.bannedUnits.toSeq().map(u -> u.name).toString(","), r.planet == null ? "?" : r.planet.name);
        for(TeamData data : Vars.state.teams.getActive()){
            Log.info("[bloodbot]   team @: cores=@ buildings=@ units=@ buildAi=@ rtsAi=@",
                data.team, data.cores.size, data.buildings.size, data.units.size,
                r.teams.get(data.team).buildAi, r.teams.get(data.team).rtsAi);
        }
    }

    void setFast(boolean fast){
        try{
            Field interval = HeadlessApplication.class.getDeclaredField("renderInterval");
            interval.setAccessible(true);
            // The server loop sleeps renderInterval ns per frame. Fast mode shortens it but never below the
            // -Dbloodbot.maxSpeed cap (set by run_server.sh) so the machine stays usable.
            float maxSpeed = Math.min(Float.parseFloat(System.getProperty("bloodbot.maxSpeed", "20")), 40f);
            interval.setLong(Core.app, (long)(1_000_000_000L / (60f * (fast ? maxSpeed : 1f))));
        }catch(Exception e){
            Log.err(e);
            return;
        }
        // Fixed delta keeps the simulation identical to normal play regardless of wall-clock speed.
        Time.setDeltaProvider(() -> 1f);
        Log.info("[bloodbot] fast mode @ (cap @x real time)", fast ? "ON" : "OFF", System.getProperty("bloodbot.maxSpeed", "20"));
    }

    void reportBench(String reason){
        double seconds = (Time.nanos() - benchStartNanos) / 1e9;
        double gameSeconds = ticks / TICKS_PER_SECOND;
        Log.info("[bloodbot] BENCH (@): @ ticks, @s game time in @s real -> @x real time, @ ticks/s",
            reason, ticks, gameSeconds, String.format("%.2f", seconds), String.format("%.1f", gameSeconds / seconds), String.format("%.0f", ticks / seconds));
        benchTargetTicks = -1;
    }
}
