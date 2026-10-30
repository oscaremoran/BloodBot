package bloodbot;

import arc.math.geom.Point2;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.game.Team;
import mindustry.type.Item;
import mindustry.world.Tile;
import mindustry.world.blocks.production.Drill;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

/**
 * Fixed hand-written strategy, kept only as a benchmark for the learning bot to beat (never as a teacher):
 * mine ore into the core and build drill-fed duo pods beside the enemy's route.
 */
public class StarterBot{
    static final int SEARCH_RADIUS = 45;

    final Builder builder;
    final Nav nav;
    final Drill drill = (Drill)Blocks.mechanicalDrill;
    final Seq<Tile> enemyPath;
    int coreDrills, pods, failures;

    StarterBot(Team team){
        builder = new Builder(team);
        nav = new Nav(builder);
        enemyPath = nav.enemyPath();
        Log.info("[bloodbot] starter bot: enemy path length @", enemyPath.size);
    }

    void update(){
        builder.update();
        // The ship mines when it isn't building, alternating copper and lead like a player early on.
        ShipAI ship = builder.shipAI();
        if(ship != null && ship.task == ShipAI.Task.GUARD){
            var core = builder.core();
            ship.mine(core != null && core.items.get(Items.lead) * 2 < core.items.get(Items.copper) ? Items.lead : Items.copper);
        }
        if(!builder.busy() && Vars.state.updateId % 60 == 0) decide();
    }

    /** Alternate economy and defence: two drills per pod early, more economy later. */
    void decide(){
        boolean wantDrill = coreDrills < 2 || (pods >= 1 && coreDrills < 2 + pods * 2 && coreDrills < 14);
        boolean ok = wantDrill ? planCoreDrill() : planPod();
        if(!ok) ok = wantDrill ? planPod() : planCoreDrill();
        if(!ok && ++failures % 20 == 1) Log.info("[bloodbot] starter bot: nothing left to plan (@ drills, @ pods)", coreDrills, pods);
    }

    boolean planCoreDrill(){
        Item item = coreDrills % 3 == 2 ? Items.lead : Items.copper;
        CoreBuild core = builder.core();
        for(Tile spot : nav.drillSpots(drill, item, core.tile, SEARCH_RADIUS, 8)){
            nav.reserved.clear();
            Seq<Tile> footprint = spot.getLinkedTilesAs(drill, new Seq<>());
            nav.reserve(drill, spot.x, spot.y);
            Seq<Builder.Plan> route = nav.conveyorRoute(footprint, t -> t.build == core);
            if(route == null) continue;
            builder.enqueue(new Builder.Plan(drill, spot.x, spot.y, 0));
            route.each(builder::enqueue);
            nav.reserved.clear();
            coreDrills++;
            return true;
        }
        nav.reserved.clear();
        if(item == Items.lead){ coreDrills++; return true; } // no lead nearby: skip a turn of lead
        return false;
    }

    /** A router fed by a copper drill, with duo turrets on its free sides, beside the enemy route. */
    boolean planPod(){
        if(enemyPath.isEmpty()) return false;
        Tile anchor = enemyPath.get(Math.min(enemyPath.size - 1, 8 + 5 * (pods / 2)));
        int side = pods % 2 == 0 ? 3 : -3;
        for(int r = 0; r <= 6; r++){
            for(Point2 d : Nav.ring(r)){
                Tile router = Vars.world.tile(anchor.x + d.x + side, anchor.y + d.y);
                if(router == null || nearPath(router) || !nav.free(router.x, router.y, Blocks.router)) continue;
                if(tryPod(router)) return true;
            }
        }
        return false;
    }

    boolean tryPod(Tile router){
        for(Tile spot : nav.drillSpots(drill, Items.copper, router, 22, 6)){
            nav.reserved.clear();
            nav.reserve(Blocks.router, router.x, router.y);
            Seq<Tile> footprint = spot.getLinkedTilesAs(drill, new Seq<>());
            if(footprint.contains(t -> t == router)) continue;
            nav.reserve(drill, spot.x, spot.y);
            Seq<Builder.Plan> feed = nav.conveyorRoute(footprint, t -> t == router);
            if(feed == null) continue;
            feed.each(p -> nav.reserve(Blocks.conveyor, p.x(), p.y()));

            Seq<Builder.Plan> turrets = new Seq<>();
            for(Point2 d : Nav.D4){
                Tile n = router.nearby(d.x, d.y);
                if(n != null && nav.free(n.x, n.y, Blocks.duo)) turrets.add(new Builder.Plan(Blocks.duo, n.x, n.y, 0));
            }
            if(turrets.size < 2) continue;

            turrets.each(builder::enqueue);
            builder.enqueue(new Builder.Plan(Blocks.router, router.x, router.y, 0));
            builder.enqueue(new Builder.Plan(drill, spot.x, spot.y, 0));
            feed.each(builder::enqueue);
            nav.reserved.clear();
            pods++;
            return true;
        }
        nav.reserved.clear();
        return false;
    }

    boolean nearPath(Tile t){
        for(int i = 0; i < Math.min(enemyPath.size, 80); i++){
            if(enemyPath.get(i).dst2(t) <= 4f * Vars.tilesize * Vars.tilesize) return true;
        }
        return false;
    }
}
