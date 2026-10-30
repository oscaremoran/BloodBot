package bloodbot;

import arc.struct.ObjectMap;
import arc.struct.Queue;
import mindustry.Vars;
import mindustry.entities.units.BuildPlan;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Unit;
import mindustry.world.Block;
import mindustry.world.Build;
import mindustry.world.Tile;
import mindustry.world.blocks.ConstructBlock.ConstructBuild;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

/**
 * The bot's build list, carried out by its core ship exactly as a player's ship would: plans are handed to
 * the ship a few at a time, it must fly within building range, and the game constructs them for real
 * (items drawn gradually from the core, construction sites can be shot down). Re-issues plans if the ship
 * dies (it respawns at the core like a player's) and notices when each plan is finished.
 */
public class Builder{
    static final int ACTIVE_PLANS = 3, RESPAWN_TICKS = 180;

    /** Something to build (or remove). {@code then} runs once it exists, e.g. to link power nodes. */
    record Plan(Block block, int x, int y, int rotation, Object config, boolean breaking, PlacedAction then){
        Plan(Block block, int x, int y, int rotation){ this(block, x, y, rotation, null, false, null); }
        Plan(Block block, int x, int y, int rotation, Object config){ this(block, x, y, rotation, config, false, null); }
        Plan(Block block, int x, int y, int rotation, PlacedAction then){ this(block, x, y, rotation, null, false, then); }
        static Plan remove(Tile tile){ return new Plan(tile.block(), tile.x, tile.y, 0, null, true, null); }
    }

    interface PlacedAction{ void run(Building placed); }

    final Team team;
    final Queue<Plan> queue = new Queue<>();
    final ObjectMap<Plan, BuildPlan> issued = new ObjectMap<>();
    int built, removed;
    Building lastBuilt;
    Unit ship;
    int respawn;

    Builder(Team team){
        this.team = team;
    }

    CoreBuild core(){
        return team.core();
    }

    ShipAI shipAI(){
        return ShipAI.alive(ship) && ship.controller() instanceof ShipAI ai ? ai : null;
    }

    boolean busy(){
        return !queue.isEmpty();
    }

    void update(){
        if(core() == null) return;
        if(!ShipAI.alive(ship)){
            if(--respawn <= 0){
                ship = ShipAI.spawn(team);
                issued.clear();
                respawn = RESPAWN_TICKS;
            }
            return;
        }

        // Retire finished or impossible plans.
        for(int i = 0; i < queue.size; i++){
            Plan p = queue.get(i);
            Tile tile = Vars.world.tile(p.x, p.y);
            if(done(p, tile)){
                queue.removeIndex(i--);
                issued.remove(p);
                if(p.breaking){
                    removed++;
                }else{
                    built++;
                    lastBuilt = tile.build;
                    if(p.then != null) p.then.run(tile.build);
                }
            }else if(!possible(p, tile)){
                queue.removeIndex(i--);
                BuildPlan bp = issued.remove(p);
                if(bp != null) ship.plans.remove(bp, true);
            }
        }

        // Keep the first few plans in the ship's own build queue.
        int active = 0;
        for(Plan p : queue){
            if(active >= ACTIVE_PLANS) break;
            active++;
            BuildPlan bp = issued.get(p);
            if(bp == null || !ship.plans.contains(bp, true)){
                bp = p.breaking ? new BuildPlan(p.x, p.y) : new BuildPlan(p.x, p.y, p.rotation, p.block, p.config);
                issued.put(p, bp);
                ship.addBuild(bp, false);
            }
        }
    }

    boolean done(Plan p, Tile tile){
        if(tile == null) return true;
        if(p.breaking) return tile.build == null && !tile.block().isStatic() && tile.block() == mindustry.content.Blocks.air;
        return tile.build != null && tile.build.block == p.block && tile.build.team == team && tile.build.tile == tile;
    }

    boolean possible(Plan p, Tile tile){
        if(p.breaking) return tile.build != null && tile.build.team == team;
        if(tile.build instanceof ConstructBuild cb && cb.current == p.block) return true; // under construction
        return Build.validPlace(p.block, team, p.x, p.y, p.rotation);
    }

    void enqueue(Plan p){
        queue.addLast(p);
    }

    /** True if a queued plan already claims this tile (any tile of its footprint). */
    boolean claimed(int x, int y){
        for(Plan p : queue){
            if(p.breaking) continue;
            int o = p.block.sizeOffset;
            if(x >= p.x + o && x < p.x + o + p.block.size && y >= p.y + o && y < p.y + o + p.block.size) return true;
        }
        return false;
    }
}
