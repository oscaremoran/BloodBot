package bloodbot;

import arc.struct.IntSet;
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
    /** A plan the ship is actively building (in range, its current plan) that makes no progress for this long leaves the queue (user OK, 2026-11-12):
     * the queue was full ~73% of every game (90% after build-before-afford), which blocks building and left the
     * bot waiting ~75% of its decisions. Plans still waiting their turn behind ACTIVE_PLANS are never timed. */
    static final int STALL_TICKS = 30 * 60;

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
    /** Per plan: last construction progress seen, and frames spent actively building it since it last progressed (stall timer). */
    final ObjectMap<Plan, float[]> progress = new ObjectMap<>();
    int frames, stalled, evicted, evictedItems;
    int built, removed;
    Building lastBuilt;
    /** Positions of every block this builder finished this game (for the unused-block rule). */
    final IntSet builtPos = new IntSet();
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
                forget(p);
                if(p.breaking){
                    removed++;
                }else{
                    built++;
                    lastBuilt = tile.build;
                    if(tile.build != null) builtPos.add(tile.build.pos());
                    if(p.then != null) p.then.run(tile.build);
                }
            }else if(!possible(p, tile)){
                queue.removeIndex(i--);
                BuildPlan bp = issued.remove(p);
                if(bp != null) ship.plans.remove(bp, true);
                forget(p);
            }
        }

        // Drop active plans that have made no progress for STALL_TICKS (no items for them, or unreachable).
        frames++;
        for(int i = 0; i < queue.size; i++){
            Plan p = queue.get(i);
            float[] st = progress.get(p);
            if(st == null) progress.put(p, st = new float[]{-1f, 0f});
            // Only time a plan while the ship is in range actively building exactly this plan (user, 2026-11-12: plans
            // must not drop while the ship is fighting, delivering, flying or the plan is waiting its turn; the first
            // version dropped ~1 plan per block built, 126 in a game where the ship made 31 kills).
            BuildPlan current = issued.get(p);
            if(i >= ACTIVE_PLANS || current == null || !ship.activelyBuilding() || ship.buildPlan() != current) continue; // paused, not reset: the builder rotates past plans it can't pay for
            Tile tile = Vars.world.tile(p.x, p.y);
            float now = tile != null && tile.build instanceof ConstructBuild cb ? cb.progress : 0f;
            if(now != st[0]){
                st[0] = now;
                st[1] = 0f;
            }else if(++st[1] > STALL_TICKS){
                queue.removeIndex(i--);
                BuildPlan bp = issued.remove(p);
                if(bp != null) ship.plans.remove(bp, true);
                forget(p);
                stalled++;
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
        return tile.build != null && tile.build.block == p.block && tile.build.team == team && tile.build.tile == tile
            && (!p.block.rotate || tile.build.rotation == p.rotation); // a conveyor facing the wrong way isn't finished
    }

    boolean possible(Plan p, Tile tile){
        if(p.breaking) return tile.build != null && (tile.build.team == team || tile.build.team == Team.derelict);
        if(tile.build instanceof ConstructBuild cb && cb.current == p.block) return true; // under construction
        return Build.validPlace(p.block, team, p.x, p.y, p.rotation);
    }

    /** Construction (or deconstruction) of this plan has begun on its tile. */
    boolean started(Plan p){
        Tile tile = Vars.world.tile(p.x, p.y);
        return tile != null && tile.build instanceof ConstructBuild;
    }

    /**
     * List items (user, 2026-11-12): one bot action is one item, however many blocks it plans. A build line (or a rebuild
     * of several ghosts) is a single item, like one drag in the game; a single placement or removal is one item.
     * Before, every block counted, so a 24-block line overfilled the list and the next placement evicted it
     * (game 7689: 2,070 line blocks planned, 2,641 plans evicted, 316 blocks built).
     */
    final ObjectMap<Plan, Integer> groupOf = new ObjectMap<>();
    int nextGroup, openGroup = -1;

    void beginGroup(){ openGroup = nextGroup++; }

    void endGroup(){ openGroup = -1; }

    /** Number of list items (distinct groups) in the queue. */
    int items(){
        IntSet seen = new IntSet();
        for(Plan p : queue) seen.add(groupOf.get(p, -1));
        return seen.size;
    }

    /** Groups with at least one started plan stay; the others can be evicted whole. */
    IntSet startedGroups(){
        IntSet started = new IntSet();
        for(Plan p : queue) if(started(p)) started.add(groupOf.get(p, -1));
        return started;
    }

    boolean hasUnstarted(){
        IntSet started = startedGroups();
        for(Plan p : queue) if(!started.contains(groupOf.get(p, -1))) return true;
        return false;
    }

    /** Drop the oldest items none of whose plans has started until at most {@code size} items remain (user OK, 2026-11-12:
     * with core copper at 0 the queue filled ~2 min into each game and stayed full, so ~80% of decisions had only "wait"
     * legal). Every dropped plan counts in {@code evicted}. */
    void makeRoom(int size){
        IntSet started = startedGroups();
        while(items() > size){
            int victim = Integer.MIN_VALUE;
            for(Plan p : queue){
                int g = groupOf.get(p, -1);
                if(!started.contains(g)){ victim = g; break; }
            }
            if(victim == Integer.MIN_VALUE) return;
            evictedItems++;
            for(int i = 0; i < queue.size; ){
                Plan p = queue.get(i);
                if(groupOf.get(p, -1) != victim){ i++; continue; }
                queue.removeIndex(i);
                forget(p);
                BuildPlan bp = issued.remove(p);
                if(bp != null && ShipAI.alive(ship)) ship.plans.remove(bp, true);
                evicted++;
            }
        }
    }

    /** Bookkeeping for a plan that left the queue. */
    void forget(Plan p){
        progress.remove(p);
        groupOf.remove(p);
    }

    void enqueue(Plan p){
        queue.addLast(p);
        groupOf.put(p, openGroup >= 0 ? openGroup : nextGroup++);
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
