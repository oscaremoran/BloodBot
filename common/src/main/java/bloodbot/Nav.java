package bloodbot;

import arc.math.geom.Point2;
import arc.struct.IntIntMap;
import arc.struct.IntSet;
import arc.struct.Queue;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.gen.Building;
import mindustry.type.Item;
import mindustry.world.Block;
import mindustry.world.Build;
import mindustry.world.Tile;
import mindustry.world.blocks.production.Drill;

/** Map geometry shared by the bots: free-tile checks, ore spots, enemy route and conveyor routing. */
public class Nav{
    static final Point2[] D4 = {new Point2(1, 0), new Point2(0, 1), new Point2(-1, 0), new Point2(0, -1)};

    interface TileCheck{ boolean get(Tile t); }

    final Builder builder;
    /** Tiles promised to the plan being assembled right now (not yet in the builder's queue). */
    final IntSet reserved = new IntSet();

    Nav(Builder builder){
        this.builder = builder;
    }

    /** Whether a block can go at this origin: valid in-game and not claimed by any pending plan. */
    boolean free(int x, int y, Block block){
        if(!Build.validPlace(block, builder.team, x, y, 0)) return false;
        int o = block.sizeOffset;
        for(int dx = 0; dx < block.size; dx++){
            for(int dy = 0; dy < block.size; dy++){
                int tx = x + o + dx, ty = y + o + dy;
                if(reserved.contains(Point2.pack(tx, ty)) || builder.claimed(tx, ty)) return false;
            }
        }
        return true;
    }

    void reserve(Block block, int x, int y){
        int o = block.sizeOffset;
        for(int dx = 0; dx < block.size; dx++){
            for(int dy = 0; dy < block.size; dy++) reserved.add(Point2.pack(x + o + dx, y + o + dy));
        }
    }

    /** Nearest free origin for a block within a radius of (x, y); null if none. */
    Tile nearestFree(Block block, int x, int y, int radius){
        for(int r = 0; r <= radius; r++){
            for(Point2 d : ring(r)){
                Tile t = Vars.world.tile(x + d.x, y + d.y);
                if(t != null && free(t.x, t.y, block)) return t;
            }
        }
        return null;
    }

    /** Best drill origins for an item near a point, most ore first. */
    Seq<Tile> drillSpots(Drill drill, Item item, Tile near, int radius, int max){
        Seq<Tile> spots = new Seq<>();
        IntIntMap score = new IntIntMap();
        int minOre = Math.max(1, drill.size * drill.size / 2);
        for(int x = near.x - radius; x <= near.x + radius; x++){
            for(int y = near.y - radius; y <= near.y + radius; y++){
                Tile t = Vars.world.tile(x, y);
                if(t == null || !free(x, y, drill)) continue;
                int ore = 0;
                for(Tile f : t.getLinkedTilesAs(drill, new Seq<>())){
                    if(f.drop() == item && drill.canMine(f)) ore++;
                }
                if(ore < minOre) continue;
                score.put(t.pos(), ore * 12 - (int)Math.sqrt(near.dst2(t)) / Vars.tilesize);
                spots.add(t);
            }
        }
        spots.sort(t -> -score.get(t.pos()));
        spots.truncate(Math.min(spots.size, max));
        return spots;
    }

    /** Ground route from the first enemy spawn to the core, ordered from the core outwards. */
    Seq<Tile> enemyPath(){
        Seq<Tile> spawns = Vars.spawner.getSpawns();
        Building core = builder.core();
        if(spawns.isEmpty() || core == null) return new Seq<>();
        Seq<Tile> path = bfs(Seq.with(spawns.first()), t -> t.build == core, t -> !t.solid() && !t.floor().isDeep(), Integer.MAX_VALUE);
        return path == null ? new Seq<>() : path.reverse();
    }

    Seq<Builder.Plan> conveyorRoute(Seq<Tile> footprint, TileCheck goal){
        return route(footprint, goal, Blocks.conveyor);
    }

    /** A line of a 1x1 transport block (conveyor, conduit...) from beside a footprint to a tile adjacent to the goal; null if unreachable. */
    Seq<Builder.Plan> route(Seq<Tile> footprint, TileCheck goal, Block line){
        Seq<Tile> starts = new Seq<>();
        for(Tile f : footprint){
            for(Point2 d : D4){
                Tile n = f.nearby(d.x, d.y);
                if(n != null && n.build == null && free(n.x, n.y, line) && !starts.contains(n)) starts.add(n);
            }
        }
        // Empty tiles only: a route laid over a conveyor, router or junction would cut the line that's already there.
        Seq<Tile> path = bfs(starts, goal, t -> t.build == null && free(t.x, t.y, line), 3000);
        if(path == null) return null;

        Seq<Builder.Plan> plans = new Seq<>();
        for(int i = 0; i < path.size; i++){
            Tile t = path.get(i);
            Tile next = i + 1 < path.size ? path.get(i + 1) : adjacentMatch(t, goal);
            plans.add(new Builder.Plan(line, t.x, t.y, t.relativeTo(next)));
        }
        return plans;
    }

    /** Breadth-first search; the path ends on the last walkable tile that touches a goal tile. */
    static Seq<Tile> bfs(Seq<Tile> starts, TileCheck goal, TileCheck walkable, int maxNodes){
        IntIntMap parent = new IntIntMap();
        Queue<Tile> open = new Queue<>();
        for(Tile s : starts){
            parent.put(s.pos(), -1);
            open.addLast(s);
        }
        int visited = 0;
        while(!open.isEmpty() && visited++ < maxNodes){
            Tile t = open.removeFirst();
            if(adjacentMatch(t, goal) != null){
                Seq<Tile> path = new Seq<>();
                for(int pos = t.pos(); pos != -1; pos = parent.get(pos, -1)) path.add(Vars.world.tile(pos));
                return path.reverse();
            }
            for(Point2 d : D4){
                Tile n = t.nearby(d.x, d.y);
                if(n == null || parent.containsKey(n.pos()) || !walkable.get(n)) continue;
                parent.put(n.pos(), t.pos());
                open.addLast(n);
            }
        }
        return null;
    }

    static Tile adjacentMatch(Tile t, TileCheck goal){
        for(Point2 d : D4){
            Tile n = t.nearby(d.x, d.y);
            if(n != null && goal.get(n)) return n;
        }
        return null;
    }

    static Seq<Point2> ring(int r){
        Seq<Point2> out = new Seq<>();
        for(int x = -r; x <= r; x++){
            for(int y = -r; y <= r; y++){
                if(Math.max(Math.abs(x), Math.abs(y)) == r) out.add(new Point2(x, y));
            }
        }
        return out;
    }
}
