package bloodbot;

import arc.math.geom.Point2;
import arc.math.geom.Vec2;
import arc.struct.IntSet;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.ai.types.CommandAI;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.Planets;
import mindustry.entities.Units;
import mindustry.game.Team;
import mindustry.game.Teams.TeamData;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Teamc;
import mindustry.gen.Unit;
import mindustry.type.Category;
import mindustry.type.Item;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.defense.turrets.ItemTurret;
import mindustry.world.blocks.distribution.Junction;
import mindustry.world.blocks.distribution.Conveyor;
import mindustry.world.blocks.distribution.ItemBridge;
import mindustry.world.blocks.distribution.Router;
import mindustry.world.blocks.liquid.LiquidBlock;
import mindustry.world.blocks.power.PowerNode;
import mindustry.world.blocks.production.Drill;
import mindustry.world.blocks.production.GenericCrafter;
import mindustry.world.blocks.production.Pump;
import mindustry.world.blocks.storage.CoreBlock;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;
import mindustry.world.blocks.units.UnitFactory;
import mindustry.world.consumers.Consume;
import mindustry.world.consumers.ConsumeItemFilter;
import mindustry.world.consumers.ConsumeItems;
import mindustry.world.consumers.ConsumeLiquidBase;
import mindustry.world.meta.BuildVisibility;

/**
 * The learning bot. Once per game second (unless its build list is backed up) the brain looks at the map
 * picture plus some summary numbers and picks one action, choosing every detail itself: which block, the exact
 * tile and rotation, what to configure, where to send which army group, what its ship does. It has the same
 * controls a player has; helper actions (supply items/power/liquid) only save it from laying every conveyor
 * tile one decision at a time. Which strategy emerges is entirely up to evolution.
 */
public class Agent{
    // ---- action types ----
    static final String[] TYPE_NAMES = {"wait", "build", "remove", "rebuild", "supply items", "supply power", "supply liquid",
        "configure", "army move", "army attack", "ship guard", "ship mine", "ship fight", "ship deliver",
        "logic link", "logic write", "payload pick up", "payload drop"};
    static final int WAIT = 0, BUILD = 1, REMOVE = 2, REBUILD = 3, SUPPLY_ITEMS = 4, SUPPLY_POWER = 5, SUPPLY_LIQUID = 6,
        CONFIGURE = 7, ARMY_MOVE = 8, ARMY_ATTACK = 9, SHIP_GUARD = 10, SHIP_MINE = 11, SHIP_FIGHT = 12, SHIP_DELIVER = 13,
        LOGIC_LINK = 14, LOGIC_WRITE = 15, PAYLOAD_PICKUP = 16, PAYLOAD_DROP = 17;

    /**
     * Real Mindustry logic instructions the bot can append to a processor's program, one per action.
     * {L} = one of the processor's linked blocks (picked by the rotation choice), {U} = a unit type.
     * Variables t/tx/ty (target), cx/cy/b (core) are shared between instructions, so useful programs
     * can be assembled step by step. The last entry clears the program.
     */
    static final String[] LOGIC = {
        "radar enemy any any distance {L} 1 t", "sensor tx t @x", "sensor ty t @y", "control shoot {L} tx ty 1 0",
        "control enabled {L} 0 0 0 0", "control enabled {L} 1 0 0 0", "ubind @{U}", "ucontrol approach tx ty 5 0 0",
        "uradar enemy any any distance 0 1 t", "ucontrol target tx ty 1 0 0", "ulocate building core false @copper cx cy found b",
        "ucontrol move cx cy 0 0 0", "ucontrol itemTake b @copper 30 0 0", "ucontrol itemDrop {L} 30 0 0 0",
        "jump 0 always 0 0", null};
    static final String[] LOGIC_UNITS = {"mono", "poly", "mega", "flare"};
    static final int MAX_LOGIC_LINES = 60;
    static final int TYPES = TYPE_NAMES.length, FINE = 16, ROTATIONS = 4, ARGS = 16, GROUPS = 3;

    static final Item[] ITEMS = {Items.copper, Items.lead, Items.metaglass, Items.graphite, Items.sand, Items.coal,
        Items.titanium, Items.thorium, Items.scrap, Items.silicon, Items.plastanium, Items.phaseFabric,
        Items.surgeAlloy, Items.sporePod, Items.blastCompound, Items.pyratite};
    static final Item[] ORES = {Items.copper, Items.lead, Items.sand, Items.coal, Items.titanium, Items.scrap, Items.thorium};
    static final Category[] CATEGORIES = {Category.turret, Category.production, Category.distribution, Category.liquid,
        Category.power, Category.defense, Category.crafting, Category.units, Category.effect, Category.logic};

    static final int DECIDE_TICKS = 60, MAX_QUEUE = 6, MAX_SUPPLY_QUEUE = 60, HIDDEN = 64;

    /** Every block the bot could ever build, in a fixed order so brains stay compatible across maps. */
    static Seq<Block> catalog;

    static Seq<Block> catalog(){
        if(catalog == null){
            // Vanilla blocks only, so other installed mods can't change the list a brain was trained on.
            catalog = Vars.content.blocks().select(b -> b.minfo.mod == null && b.requirements.length > 0
                && b.buildVisibility == BuildVisibility.shown && b.isOnPlanet(Planets.serpulo) && !(b instanceof CoreBlock));
        }
        return catalog;
    }

    static int scalars(){ return ITEMS.length + 8 + CATEGORIES.length + 6 + GROUPS + 4 + 3 + TYPES + 1; }

    static int outputs(){ return TYPES + catalog().size + 2 * Vision.G + 2 * FINE + ROTATIONS + ARGS; }

    static int[] shape(){ return new int[]{Vision.CHANNELS, Vision.G, 12, 16, 16, scalars(), HIDDEN, outputs()}; }

    // ---- state ----
    final Brain brain;
    final Team team;
    final Builder builder;
    final Nav nav;
    final Vision vision;
    final boolean[] allowed;
    final java.util.Random rand;
    /** Actions are sampled from the brain's preferences; lower = more decisive (0 = always the top choice). */
    float temperature = 1f;
    final IntSet supplied = new IntSet();
    final float enemyHealthStart;
    final int enemyCoresStart;
    int lastType, kills, maceKills;
    /** +100 per enemy mace killed (the user's rule, 2026-10-31): maces on waves 9-10 are what beat it at 80%. */
    static final float MACE_BONUS = 100f;

    /** +1 point per 10 HP of damage the bot's turrets deal to maces, times the forward multiplier (the user's rule,
     * 2026-11-01): kills are too rare to learn from, every hit on a mace teaches something. */
    static final float MACE_DAMAGE_PER_POINT = 10f;
    float maceDamage;

    void maceDamaged(float damage, float x, float y){
        maceDamage += damage;
        addPoints(damage / MACE_DAMAGE_PER_POINT * forward(x, y));
    }

    /**
     * Ship rule (the user's, 2026-11-06; raised from -25 the same day): -100 for each enemy the ship's gun finishes off, so turrets do the defending. It stays
     * below the cost of core damage, so shooting still beats letting the core die.
     */
    static final float SHIP_KILL_PENALTY = 100f;
    int shipKills;

    void shipKilled(){
        shipKills++;
        addPoints(-SHIP_KILL_PENALTY);
    }

    void maceKilled(float x, float y){
        maceKills++;
        addPoints(MACE_BONUS * forward(x, y));
    }

    /**
     * Forward-defense multiplier (the user's rule, 2026-10-31): turret hits and mace kills pay more the farther from the
     * core they happen, x1 at the core, x2 at 20 tiles, x3 from 40 tiles on, so stopping enemies early beats a ring of
     * turrets around the core.
     */
    float forward(float x, float y){
        CoreBuild core = builder.core();
        if(core == null) return 1f;
        return 1f + Math.min(2f, core.dst(x, y) / Vars.tilesize / 20f);
    }

    /**
     * Wall rule (the user's, 2026-10-31): +1 point per 20 HP of enemy damage a bot-built wall soaks, at most 300 per game.
     * Only walls on the enemies' path get hit, so this pays for well-placed walls, not for wall spam.
     */
    static final float WALL_SOAK_CAP = 300f;
    float wallSoakPoints;

    void wallSoaked(Building wall, float damage){
        if(!builder.builtPos.contains(wall.pos()) || wallSoakPoints >= WALL_SOAK_CAP) return;
        float points = Math.min(WALL_SOAK_CAP - wallSoakPoints, damage / 20f);
        wallSoakPoints += points;
        addPoints(points);
    }
    /**
     * Action rules (the user's): -5 points per remove (2026-10-29); -2.5 per wait (2026-10-30).
     * The +5-for-acting-after-a-wait rule was removed on 2026-10-30 (it paid for wait/act alternation).
     * pending = not yet handed out as reward; total = for the game's final score.
     */
    float rulePointsPending, rulePointsTotal;
    /** Turrets that have had ammo (tracked for the log; the +50 moved to the first hit on 2026-10-30). */
    final IntSet fedTurrets = new IntSet();
    /** Bot-built turrets credited for their first hit on an enemy: +50 each (the user's rule, 2026-10-30). */
    final IntSet hitTurrets = new IntSet();
    /** Copper only until a bot has beaten Ground Zero at 30%; then any ammo counts (the user's rule). */
    boolean anyAmmo;
    /** The -2.5-per-wait rule costs at most this much per game (the user's cap, 2026-10-30; raised from 50). */
    static final float WAIT_PENALTY_CAP = 500f;
    float waitPenaltyPoints;

    void addPoints(float points){
        rulePointsPending += points;
        rulePointsTotal += points;
    }

    /** +5 per hit landed by one of the bot's turrets on an enemy, +50 more for a bot-built turret's first hit
     * (the user's rules); called from game events. A turret where enemies never go earns nothing. */
    void turretHit(Building turret, float x, float y){
        addPoints(10f * forward(x, y)); // doubled 2026-11-06 (the user): 5 -> 10, first hit 50 -> 100
        if(builder.builtPos.contains(turret.pos()) && hitTurrets.add(turret.pos())) addPoints(100f);
    }

    /** Blocks the bot built that have ever carried items or ammo (the user's unused-block rule, 2026-10-30). */
    final IntSet usedBlocks = new IntSet();
    static final float UNUSED_PENALTY = 5f; // the user, 2026-10-30: 2.5 -> 10 (bot stopped building) -> 5

    /** Only blocks whose job is moving or holding items can be judged; walls, power, sorters etc. are exempt. */
    static boolean judged(Building b){
        return b instanceof ItemTurret.ItemTurretBuild || b instanceof Junction.JunctionBuild
            || (b.block.hasItems && !b.block.instantTransfer && b.items != null);
    }

    static boolean inUse(Building b){
        if(b instanceof ItemTurret.ItemTurretBuild t) return t.totalAmmo > 0;
        if(b instanceof Junction.JunctionBuild j){
            for(int n : j.buffer.indexes) if(n > 0) return true;
            return false;
        }
        return b.items != null && b.items.total() > 0;
    }

    /**
     * Copper-chain milestones (the user's rule, 2026-10-30): +50 once per game each, the first time
     * a drill puts copper on a conveyor, a conveyor carries copper into the core, and a conveyor carries copper into a turret.
     * Added 2026-10-30: the first time a router passes copper into a turret beside it.
     * Only blocks this bot built count.
     */
    boolean drillToConveyor, conveyorToCore, conveyorToTurret, routerToTurret;
    static final float CHAIN_BONUS = 50f;

    void checkUsed(){
        for(Building b : ownBuildings()){
            if(!usedBlocks.contains(b.pos()) && judged(b) && inUse(b)) usedBlocks.add(b.pos());
            if(b instanceof Conveyor.ConveyorBuild c && builder.builtPos.contains(c.pos()) && c.items.has(Items.copper)) checkChain(c);
            if(b instanceof Router.RouterBuild r && builder.builtPos.contains(r.pos())
                && (r.lastItem == Items.copper || r.items.has(Items.copper))) checkRouter(r);
        }
    }

    /**
     * Router rule (the user's, 2026-10-31; was +50 once per game): +30 for each bot-built turret a bot-built router feeds
     * copper into, times the forward multiplier, so a router line feeding a row of duos on the enemy path pays the most.
     */
    static final float ROUTER_TURRET_BONUS = 30f;
    final IntSet routerFedTurrets = new IntSet();

    void checkRouter(Router.RouterBuild r){
        for(Building o : r.proximity){
            if(o instanceof ItemTurret.ItemTurretBuild t && t.team == team && builder.builtPos.contains(t.pos())
                && !routerFedTurrets.contains(t.pos())
                && t.ammo.contains(e -> e instanceof ItemTurret.ItemEntry ie && ie.item == Items.copper)){
                routerToTurret = true;
                routerFedTurrets.add(t.pos());
                addPoints(ROUTER_TURRET_BONUS * forward(t.x, t.y));
            }
        }
    }

    void checkChain(Conveyor.ConveyorBuild c){
        if(!drillToConveyor){
            for(Building o : c.proximity){
                if(o instanceof Drill.DrillBuild d && d.team == team && builder.builtPos.contains(d.pos())
                    && d.dominantItem == Items.copper){
                    drillToConveyor = true;
                    addPoints(CHAIN_BONUS);
                    break;
                }
            }
        }
        // The field, not c.next(): that method only returns the next conveyor, so a core or turret never matched (fixed 2026-11-06).
        Building next = c.next;
        if(next == null || next.team != team) return;
        if(!conveyorToCore && next instanceof CoreBuild){
            conveyorToCore = true;
            addPoints(CHAIN_BONUS);
        }
        if(!conveyorToTurret && next instanceof ItemTurret.ItemTurretBuild t && builder.builtPos.contains(t.pos())
            && t.ammo.contains(e -> e instanceof ItemTurret.ItemEntry ie && ie.item == Items.copper)){
            conveyorToTurret = true;
            addPoints(CHAIN_BONUS);
        }
    }

    /**
     * Wave 8 rule (the user's, 2026-11-06): +20 once per game for each bot-built turret that has ammo and has hit an enemy
     * when wave 8 arrives, because games with 15+ fed turrets at the mace win far more. Never paid in practice games.
     */
    static final int TURRET_CHECK_WAVE = 8;
    static final float WAVE_TURRET_BONUS = 40f; // 20 until the user doubled turret rewards, 2026-11-06
    boolean waveTurretsPaid;
    int waveTurrets;

    void checkWaveTurrets(){
        if(waveTurretsPaid || Vars.state.wave < TURRET_CHECK_WAVE) return;
        waveTurretsPaid = true;
        for(Building b : ownBuildings()){
            if(b instanceof ItemTurret.ItemTurretBuild t && builder.builtPos.contains(t.pos())
                && hitTurrets.contains(t.pos()) && t.totalAmmo > 0) waveTurrets++;
        }
        addPoints(WAVE_TURRET_BONUS * waveTurrets);
    }

    /**
     * Route rule (the user's, 2026-11-06): +30 once for each bot-built turret that has ammo and the enemy route in range, for at
     * most 10 turrets a game. It pays before any enemy arrives, so the bot can learn to build on the lane from the route layer.
     */
    static final float ROUTE_TURRET_BONUS = 60f; // 30 until the user doubled turret rewards, 2026-11-06
    static final int ROUTE_TURRET_CAP = 10;
    final IntSet routeTurrets = new IntSet();
    int routeTurretsPaid;

    void checkRouteTurrets(){
        if(routeTurretsPaid >= ROUTE_TURRET_CAP) return;
        Seq<Tile> route = vision.route(team);
        for(Building b : ownBuildings()){
            if(!(b instanceof ItemTurret.ItemTurretBuild t) || t.totalAmmo <= 0 || !builder.builtPos.contains(t.pos())
                || routeTurrets.contains(t.pos())) continue;
            float range = t.range();
            if(!route.contains(r -> r.within(t, range))) continue;
            routeTurrets.add(t.pos());
            routeTurretsPaid++;
            addPoints(ROUTE_TURRET_BONUS);
            if(routeTurretsPaid >= ROUTE_TURRET_CAP) return;
        }
    }

    /** End of game: -2.5 for each judged block the bot built that never carried anything. Returns how many. */
    int penalizeUnused(){
        int unused = 0;
        for(Building b : ownBuildings()){
            if(builder.builtPos.contains(b.pos()) && judged(b) && !usedBlocks.contains(b.pos())) unused++;
        }
        addPoints(-UNUSED_PENALTY * unused);
        return unused;
    }

    void checkTurretAmmo(){
        for(Building b : ownBuildings()){
            if(b instanceof ItemTurret.ItemTurretBuild t && !fedTurrets.contains(b.pos())
                && t.ammo.contains(e -> e instanceof ItemTurret.ItemEntry ie && (anyAmmo || ie.item == Items.copper))){
                fedTurrets.add(b.pos());
            }
        }
    }
    /** Copper that has reached the core this game (sampled increases), for the trainer's temporary copper bonus. */
    int copperIn, lastCopper;
    final int[] actionCounts = new int[TYPES];

    /** @param allBlocks ignore the map's banned-block list (used for Bloodshed Bastion). */
    Agent(Brain brain, Team team, boolean allBlocks, long seed){
        this.brain = brain;
        this.team = team;
        this.rand = new java.util.Random(seed);
        builder = new Builder(team);
        nav = new Nav(builder);
        vision = new Vision();

        Seq<Block> cat = catalog();
        allowed = new boolean[cat.size];
        for(int i = 0; i < cat.size; i++){
            Block b = cat.get(i);
            allowed[i] = (allBlocks || !Vars.state.rules.isBanned(b)) && b.supportsEnv(Vars.state.rules.env) && b.environmentBuildable();
        }
        enemyHealthStart = enemyBuildingHealth();
        enemyCoresStart = enemyCores();
    }

    /** Core health at the last check, for the core-damage penalty (the user's rule, 2026-10-31): -10 per 1% lost. */
    float lastCoreHealth = 1f;
    static final float CORE_DAMAGE_PENALTY = 1000f;

    void update(){
        builder.update();
        if(Vars.state.updateId % 10 == 7 && builder.core() != null){
            float hp = builder.core().health / builder.core().maxHealth;
            if(hp < lastCoreHealth) addPoints(-CORE_DAMAGE_PENALTY * (lastCoreHealth - hp));
            lastCoreHealth = hp;
        }
        if(Vars.state.updateId % 60 == 30 && builder.core() != null){
            checkTurretAmmo();
            checkWaveTurrets();
            checkRouteTurrets();
        }
        if(Vars.state.updateId % 10 == 5 && builder.core() != null) checkUsed();
        if(Vars.state.updateId % 10 == 0 && builder.core() != null){
            int copper = builder.core().items.get(Items.copper);
            if(copper > lastCopper) copperIn += copper - lastCopper;
            lastCopper = copper;
        }
        if(Vars.state.updateId % 10 == 0) checkCopperFed();
        ShipAI miner = builder.shipAI();
        if(miner != null && miner.minedIn > 0){
            mined += miner.minedIn;
            addPoints(miner.minedIn / MINED_PER_POINT);
            miner.minedIn = 0;
        }
        if(builder.core() == null || Vars.state.updateId % DECIDE_TICKS != 0) return;
        if(policy != null) execute(policy.decide(this));
        else act(brain.forward(vision.see(team, builder.ship), scalarFeatures()));
    }

    // ---- observation (numbers; the map picture comes from Vision) ----

    float[] scalarFeatures(){
        float[] f = new float[scalars()];
        int i = 0;
        CoreBuild core = builder.core();
        for(Item item : ITEMS) f[i++] = (float)Math.log1p(core.items.get(item)) / 8f;

        var rules = Vars.state.rules;
        f[i++] = Vars.state.wave / (float)Math.max(rules.winWave, 30);
        f[i++] = rules.waveSpacing <= 0 ? 0 : 1f - Math.min(1f, Vars.state.wavetime / rules.waveSpacing);
        f[i++] = Math.min(1f, Vars.state.enemies / 30f);
        f[i++] = core.health / core.maxHealth;
        f[i++] = Math.min(1f, (float)(Vars.state.tick / (60 * 60 * 45)));
        f[i++] = rules.attackMode ? 1 : 0;
        f[i++] = rules.waves ? 1 : 0;
        f[i++] = Math.min(1f, builder.queue.size / (float)MAX_QUEUE);

        TeamData data = team.data();
        for(Category c : CATEGORIES) f[i++] = (float)Math.log1p(ownBuildings().count(b -> b.block.category == c)) / 4f;
        f[i++] = ownBuildings().isEmpty() ? 0 : ownBuildings().sumf(b -> b.health / b.maxHealth) / ownBuildings().size;
        f[i++] = powerBalance();
        int unsupplied = 0, idle = 0;
        for(Building b : ownBuildings()){
            if(!(b instanceof CoreBuild) && (needsItems(b) || needsPower(b) || needsLiquid(b)) && !supplied.contains(b.pos() * 3)) unsupplied++;
            if(output(b) != null && b.items != null && b.items.total() >= b.block.itemCapacity) idle++;
        }
        f[i++] = (float)Math.log1p(unsupplied) / 3f;
        f[i++] = (float)Math.log1p(idle) / 3f;
        f[i++] = (float)Math.log1p(data.plans.size) / 3f;
        f[i++] = Math.min(1f, Groups.unit.count(u -> u.team != team) / 50f);
        for(int g = 0; g < GROUPS; g++){
            int group = g;
            f[i++] = (float)Math.log1p(data.units.count(u -> u != builder.ship && groupOf(u) == group)) / 4f;
        }

        ShipAI ship = builder.shipAI();
        if(ship != null) f[i + ship.task.ordinal()] = 1;
        i += 4;
        f[i++] = ship == null ? 0 : builder.ship.health / builder.ship.maxHealth;
        f[i++] = enemyCoresStart == 0 ? 0 : enemyCores() / (float)enemyCoresStart;
        f[i++] = enemyHealthStart <= 0 ? 0 : enemyBuildingHealth() / enemyHealthStart;
        f[i + lastType] = 1;
        i += TYPES;
        f[i] = 1; // bias input
        return f;
    }

    float powerBalance(){
        float produced = 0, needed = 0;
        IntSet seen = new IntSet();
        for(Building b : ownBuildings()){
            if(b.power == null || !seen.add(b.power.graph.getID())) continue;
            produced += b.power.graph.getPowerProduced();
            needed += b.power.graph.getPowerNeeded();
        }
        return (float)Math.tanh((produced - needed) / 2f);
    }

    /** The bot's own buildings, minus map-scripting blocks (world processors etc.), which it must never see or touch. */
    Seq<Building> ownBuildings(){
        return team.data().buildings.select(b -> !b.block.privileged);
    }

    static int groupOf(Unit u){
        return u.type.naval ? 2 : u.type.flying ? 1 : 0;
    }

    // ---- choosing ----

    /**
     * Which action types could do anything right now. Choosing among only these is like a player's UI:
     * no "attack" order without an army, no logic writing without a processor, and so on.
     */
    boolean[] possibleTypes(){
        boolean[] ok = new boolean[TYPES];
        TeamData data = team.data();
        CoreBuild core = builder.core();
        Seq<Block> cat = catalog();
        findTargets();
        boolean producer = false, itemConsumer = false, powerSource = false, powerConsumer = false, liquidSource = false,
            liquidConsumer = false, configurable = false, processor = false, removable = false, nonCore = false;
        for(Building b : ownBuildings()){
            if(!(b instanceof CoreBuild)){ removable = true; nonCore = true; }
            if(output(b) != null) producer = true;
            if(needsItems(b) && openForItems(b)) itemConsumer = true;
            if(b.block.outputsPower && !b.block.consumesPower) powerSource = true;
            if(needsPower(b) && !supplied.contains(b.pos() * 3 + 1)) powerConsumer = true;
            if(b.block instanceof Pump || (b.block instanceof GenericCrafter c && c.outputLiquid != null)) liquidSource = true;
            if(needsLiquid(b) && !supplied.contains(b.pos() * 3 + 2)) liquidConsumer = true;
            if(configurable(b.block)) configurable = true;
            if(b instanceof mindustry.world.blocks.logic.LogicBlock.LogicBuild) processor = true;
        }
        boolean army = false, payloadUnits = false;
        for(Unit u : data.units){
            if(u == builder.ship) continue;
            army = true;
            if(u instanceof mindustry.gen.Payloadc) payloadUnits = true;
        }
        boolean enemy = enemyCores() > 0 || Groups.unit.contains(u -> u.team != team) || enemyBuildingHealth() > 0;
        ShipAI ship = builder.shipAI();
        boolean shipReady = ship != null && ship.ready();

        ok[WAIT] = true;
        for(int i = 0; i < cat.size && !ok[BUILD]; i++) ok[BUILD] = allowed[i] && core.items.has(cat.get(i).requirements);
        ok[REMOVE] = removable || !Team.derelict.data().buildings.isEmpty();
        ok[REBUILD] = !data.plans.isEmpty();
        ok[SUPPLY_ITEMS] = producer && itemConsumer;
        ok[SUPPLY_POWER] = powerSource && powerConsumer;
        ok[SUPPLY_LIQUID] = liquidSource && liquidConsumer;
        ok[CONFIGURE] = configurable;
        ok[ARMY_MOVE] = army;
        ok[ARMY_ATTACK] = army && enemy;
        ok[SHIP_GUARD] = shipReady && ship.task != ShipAI.Task.GUARD;
        ok[SHIP_MINE] = shipReady && mineableOre(ship) >= 0;
        ok[SHIP_FIGHT] = shipReady;
        ok[SHIP_DELIVER] = shipReady && nonCore && anyDelivery();
        ok[LOGIC_LINK] = processor;
        ok[LOGIC_WRITE] = processor;
        ok[PAYLOAD_PICKUP] = payloadUnits;
        ok[PAYLOAD_DROP] = payloadUnits;
        // A full build list only stops single placements; the bot keeps deciding, so with no copper it can still mine and supply
        // (2026-11-06). Supply lines have their own, higher limit so the list can't grow without end.
        if(builder.queue.size >= MAX_QUEUE) ok[BUILD] = ok[REMOVE] = ok[REBUILD] = false;
        if(builder.queue.size >= MAX_SUPPLY_QUEUE) ok[SUPPLY_ITEMS] = ok[SUPPLY_POWER] = ok[SUPPLY_LIQUID] = false;
        for(int i = 0; i < TARGETED.length; i++) if(!any(targetCells[i])) ok[TARGETED[i]] = false;
        return ok;
    }

    /** Index of any ore the ship could mine on this map, or -1. */
    int mineableOre(ShipAI ship){
        for(int i = 0; i < ORES.length; i++) if(oreUsable(ship, i)) return i;
        return -1;
    }

    boolean[] oreOnMap;

    boolean oreUsable(ShipAI ship, int i){
        if(oreOnMap == null){
            oreOnMap = new boolean[ORES.length];
            for(Tile t : Vars.world.tiles){
                for(int k = 0; k < ORES.length; k++) if(t.drop() == ORES[k] && ShipAI.handMineable(t)) oreOnMap[k] = true;
            }
        }
        return oreOnMap[i] && builder.ship != null && builder.ship.canMine(ORES[i]);
    }

    /** Something else choosing the actions (e.g. the Python PPO trainer); null = the built-in brain. */
    interface Policy{ int[] decide(Agent agent); }
    Policy policy;

    void act(float[] out){
        execute(sampleAction(out));
    }

    /** Masks the outside policy needs: what is possible right now, per head. */
    boolean[] blockMask(){
        Seq<Block> cat = catalog();
        boolean[] m = new boolean[cat.size];
        for(int i = 0; i < cat.size; i++) m[i] = allowed[i] && builder.core().items.has(cat.get(i).requirements);
        return m;
    }

    /** Whether the ship could pick this item up: from the core or a container. */
    boolean hasItem(Item item){
        return builder.core().items.get(item) > 0 || ownBuildings().contains(b -> ShipAI.freeStorage(b) && b.items.get(item) > 0);
    }

    /** An item the ship can get hold of and some building (not the core) will take: no coal into duos (2026-11-06). */
    boolean canDeliver(Item item){
        return hasItem(item) && ownBuildings().contains(b -> accepts(b, item));
    }

    boolean anyDelivery(){
        for(Item item : ITEMS) if(canDeliver(item)) return true;
        return false;
    }

    boolean accepts(Building b, Item item){
        return !(b instanceof CoreBuild) && b.acceptStack(item, 1, builder.ship) > 0;
    }

    /** The nearest building around the tile that will take the item, or null. */
    Building deliveryTarget(Tile tile, Item item){
        Building best = null;
        float bestDst = Float.MAX_VALUE;
        int r = reach();
        for(int dx = -r; dx <= r; dx++){
            for(int dy = -r; dy <= r; dy++){
                Building b = Vars.world.build(tile.x + dx, tile.y + dy);
                if(b == null || b.team != team || b.block.privileged || !accepts(b, item)) continue;
                float d = b.dst2(tile);
                if(d < bestDst){ bestDst = d; best = b; }
            }
        }
        return best;
    }

    boolean[] mineArgMask(){
        boolean[] m = new boolean[ARGS];
        ShipAI ship = builder.shipAI();
        for(int i = 0; i < ARGS; i++) m[i] = ship != null && oreUsable(ship, i % ORES.length);
        return m;
    }

    boolean[] deliverArgMask(){
        boolean[] m = new boolean[ARGS];
        for(int i = 0; i < ARGS; i++) m[i] = canDeliver(ITEMS[i]);
        return m;
    }

    /** {type, block, cell, fineX, fineY, rotation, arg} sampled from the built-in brain's outputs. */
    int[] sampleAction(float[] out){
        Seq<Block> cat = catalog();
        int o = 0;
        boolean[] possible = possibleTypes();
        int type = sample(out, o, TYPES, i -> possible[i]); o += TYPES;
        int blockIndex = sample(out, o, cat.size, i -> allowed[i] && builder.core().items.has(cat.get(i).requirements)); o += cat.size;
        int cellIndex = sampleCell(out, o, o + Vision.G, cellsFor(type)); o += 2 * Vision.G;
        int cx = cellIndex % Vision.G, cy = cellIndex / Vision.G;
        int fx = sample(out, o, FINE, null); o += FINE;
        int fy = sample(out, o, FINE, null); o += FINE;
        int rotation = sample(out, o, ROTATIONS, null); o += ROTATIONS;
        ShipAI shipAI = builder.shipAI();
        // For ship orders the extra choice is an item: only ores the ship can mine here, or items the core actually has.
        IntCheck argMask = type == SHIP_MINE && shipAI != null ? i -> oreUsable(shipAI, i % ORES.length)
            : type == SHIP_DELIVER ? i -> canDeliver(ITEMS[i]) : null;
        int arg = sample(out, o, ARGS, argMask);
        return new int[]{type, blockIndex, cellIndex, fx, fy, rotation, arg};
    }

    void execute(int[] a){
        Seq<Block> cat = catalog();
        int type = a[0], blockIndex = a[1], cx = a[2] % Vision.G, cy = a[2] / Vision.G, fx = a[3], fy = a[4], rotation = a[5], arg = a[6];
        if(type < 0 || type >= TYPES) return;
        float waitPenalty = type == WAIT ? 2.5f : 0f; // no cap since 2026-11-07 (user: fix the waiting); was 500 a game
        waitPenaltyPoints += waitPenalty;
        addPoints(-waitPenalty);
        lastType = type;
        actionCounts[type]++;

        int cell = vision.cell;
        Tile tile = Vars.world.tile(
            Math.min(Vars.world.width() - 1, cx * cell + fx % cell),
            Math.min(Vars.world.height() - 1, cy * cell + fy % cell));
        if(tile == null) return;
        ShipAI ship = builder.shipAI();

        switch(type){
            case BUILD -> { if(blockIndex >= 0 && blockIndex < cat.size) build(cat.get(blockIndex), tile, rotation, arg); }
            case REMOVE -> remove(tile);
            case REBUILD -> rebuild(tile);
            case SUPPLY_ITEMS -> supply(tile, 0);
            case SUPPLY_POWER -> supply(tile, 1);
            case SUPPLY_LIQUID -> supply(tile, 2);
            case CONFIGURE -> configure(tile, rotation, arg);
            case ARMY_MOVE -> armyMove(arg % GROUPS, tile);
            case ARMY_ATTACK -> armyAttack(arg % GROUPS, tile);
            case SHIP_GUARD -> { if(ship != null) ship.guard(); }
            case SHIP_MINE -> { if(ship != null) ship.mine(ORES[arg % ORES.length]); }
            case SHIP_FIGHT -> { if(ship != null) ship.fight(tile); }
            case SHIP_DELIVER -> { if(ship != null){ Building b = deliveryTarget(tile, ITEMS[arg]); if(b != null) ship.deliver(ITEMS[arg], b); else wasted++; } }
            case LOGIC_LINK -> logicLink(tile);
            case LOGIC_WRITE -> logicWrite(tile, rotation, arg);
            case PAYLOAD_PICKUP -> payload(arg % GROUPS, tile, arg < 8 ? mindustry.ai.UnitCommand.loadBlocksCommand : mindustry.ai.UnitCommand.loadUnitsCommand);
            case PAYLOAD_DROP -> payload(arg % GROUPS, tile, mindustry.ai.UnitCommand.unloadPayloadCommand);
            default -> {}
        }
    }

    interface IntCheck{ boolean get(int i); }

    /**
     * Picks a map cell, only among cells with usable ground (like a player, it can't click inside walls or the void).
     * A cell's preference is its column output plus its row output.
     */
    int sampleCell(float[] out, int xs, int ys, boolean[] allowed){
        int g = Vision.G, best = -1;
        float bestV = 0;
        for(int c = 0; c < g * g; c++){
            if(!allowed[c]) continue;
            float v = out[xs + c % g] + out[ys + c / g];
            if(best < 0 || v > bestV){ best = c; bestV = v; }
        }
        if(best < 0) return 0;
        if(temperature <= 0) return best;
        double[] w = new double[g * g];
        double total = 0;
        for(int c = 0; c < g * g; c++){
            if(!allowed[c]) continue;
            w[c] = Math.exp((out[xs + c % g] + out[ys + c / g] - bestV) / temperature);
            total += w[c];
        }
        double r = rand.nextDouble() * total;
        for(int c = 0; c < g * g; c++){
            r -= w[c];
            if(w[c] > 0 && r <= 0) return c;
        }
        return best;
    }

    /** Softmax sample from outputs in [start, start+count) passing the mask; -1 if none pass. */
    int sample(float[] out, int start, int count, IntCheck mask){
        int best = -1;
        for(int i = 0; i < count; i++){
            if(mask != null && !mask.get(i)) continue;
            if(best < 0 || out[start + i] > out[start + best]) best = i;
        }
        if(best < 0 || temperature <= 0) return best;
        double total = 0;
        double[] w = new double[count];
        for(int i = 0; i < count; i++){
            if(mask != null && !mask.get(i)) continue;
            w[i] = Math.exp((out[start + i] - out[start + best]) / temperature);
            total += w[i];
        }
        double r = rand.nextDouble() * total;
        for(int i = 0; i < count; i++){
            r -= w[i];
            if(w[i] > 0 && r <= 0) return i;
        }
        return best;
    }

    // ---- building ----

    /** Place a block at the chosen tile, or the nearest free spot within its map cell. Also upgrades in place. */
    void build(Block block, Tile tile, int rotation, int arg){
        nav.reserved.clear();
        Tile spot = nav.nearestFree(block, tile.x, tile.y, Math.max(2, vision.cell));
        if(spot == null) return;
        Object config = null;
        if(block instanceof UnitFactory factory && !factory.plans.isEmpty()) config = arg % factory.plans.size;
        else if(block.configurations.containsKey(Item.class)) config = ITEMS[arg];
        builder.enqueue(new Builder.Plan(block, spot.x, spot.y, rotation, config));
    }

    void remove(Tile tile){
        Building b = tile.build;
        if(b == null || b.team != team || b instanceof CoreBuild || b.block.privileged) b = removableNear(tile, reach());
        if(b == null){ wasted++; return; }
        addPoints(-5f); // only for a real removal (the user's rule; no-ops stopped paying it 2026-11-06)
        builder.enqueue(Builder.Plan.remove(b.tile));
    }

    /** The nearest block around the tile the bot may remove: its own (not the core) or a derelict one, as a player can. */
    Building removableNear(Tile tile, int radius){
        for(int r = 0; r <= radius; r++){
            for(Point2 d : Nav.ring(r)){
                Building b = Vars.world.build(tile.x + d.x, tile.y + d.y);
                if(b != null && (b.team == team || b.team == Team.derelict) && !(b instanceof CoreBuild) && !b.block.privileged) return b;
            }
        }
        return null;
    }

    static boolean configurable(Block block){
        return block instanceof UnitFactory || block instanceof ItemBridge || block instanceof PowerNode
            || block.configurations.containsKey(Item.class);
    }

    Building configurableNear(Tile tile, int radius){
        for(int r = 0; r <= radius; r++){
            for(Point2 d : Nav.ring(r)){
                Building b = Vars.world.build(tile.x + d.x, tile.y + d.y);
                if(b != null && b.team == team && !b.block.privileged && configurable(b.block)) return b;
            }
        }
        return null;
    }

    /** Re-queue destroyed blocks (the game remembers them as ghosts) nearest to the tile. */
    void rebuild(Tile tile){
        Seq<mindustry.game.Teams.BlockPlan> ghosts = new Seq<>();
        for(var p : team.data().plans) ghosts.add(p);
        ghosts.sort(p -> Math.abs(p.x - tile.x) + Math.abs(p.y - tile.y));
        int n = 0;
        for(var p : ghosts){
            if(n >= 6) break;
            if(builder.claimed(p.x, p.y)) continue;
            builder.enqueue(new Builder.Plan(p.block, p.x, p.y, p.rotation, p.config));
            n++;
        }
    }

    /** Set what a block does: sorter/unloader item, bridge link, unit type, or power node link. */
    void configure(Tile tile, int rotation, int arg){
        Building b = configurableNear(tile, reach());
        if(b == null){ wasted++; return; }
        Object before = b.config();
        applyConfig(b, rotation, arg);
        // Configure rule (user, 2026-11-07): a configure that changes nothing, or redoes the same block within 30 s,
        // costs the same as a wait, so it's no cheaper than doing nothing. The first change to a block is free.
        long now = Vars.state.updateId;
        if(java.util.Objects.deepEquals(before, b.config())){
            wasted++;
            addPoints(-2.5f);
        }else if(now < lastConfigured.get(b.pos(), Long.MIN_VALUE / 2) + RECONFIGURE_WINDOW){
            addPoints(-2.5f);
        }
        lastConfigured.put(b.pos(), now);
    }

    static final long RECONFIGURE_WINDOW = 1800;
    final arc.struct.IntMap<Long> lastConfigured = new arc.struct.IntMap<>();

    void applyConfig(Building b, int rotation, int arg){
        if(b.block instanceof UnitFactory factory && !factory.plans.isEmpty()){
            b.configureAny(arg % factory.plans.size);
        }else if(b.block instanceof ItemBridge bridge){
            Point2 d = Nav.D4[rotation & 3];
            for(int r = bridge.range; r >= 1; r--){
                Building other = Vars.world.build(b.tile.x + d.x * r, b.tile.y + d.y * r);
                if(other != null && other.block == b.block && other.team == team){
                    b.configureAny(other.pos());
                    return;
                }
            }
        }else if(b.block instanceof PowerNode node){
            Building other = null;
            float best = node.laserRange * Vars.tilesize;
            for(Building o : ownBuildings()){
                if(o == b || o.power == null || b.power.links.contains(o.pos())) continue;
                float d = b.dst(o);
                if(d < best){ best = d; other = o; }
            }
            if(other != null) b.configureAny(other.pos());
        }else if(b.block.configurations.containsKey(Item.class)){
            b.configureAny(ITEMS[arg]);
        }
    }

    Building ownBuildingNear(Tile tile, int radius){
        Building best = null;
        float bestDst = Float.MAX_VALUE;
        for(int dx = -radius; dx <= radius; dx++){
            for(int dy = -radius; dy <= radius; dy++){
                Building b = Vars.world.build(tile.x + dx, tile.y + dy);
                if(b == null || b.team != team || b.block.privileged) continue;
                float d = b.dst2(tile);
                if(d < bestDst){ bestDst = d; best = b; }
            }
        }
        return best;
    }

    // ---- logic processors ----

    mindustry.world.blocks.logic.LogicBlock.LogicBuild processorNear(Tile tile){
        Building b = null;
        float best = Float.MAX_VALUE;
        for(Building o : ownBuildings()){
            if(!(o instanceof mindustry.world.blocks.logic.LogicBlock.LogicBuild) || o.dst(tile) > 6 * Vars.tilesize) continue;
            float d = o.dst2(tile);
            if(d < best){ best = d; b = o; }
        }
        return (mindustry.world.blocks.logic.LogicBlock.LogicBuild)b;
    }

    /** Link the processor nearest the tile to its nearest not-yet-linked building in range. */
    void logicLink(Tile tile){
        var proc = processorNear(tile);
        if(proc == null) return;
        float range = ((mindustry.world.blocks.logic.LogicBlock)proc.block).range;
        Building other = null;
        float best = range;
        for(Building o : ownBuildings()){
            if(o == proc || proc.links.contains(l -> l.x == o.tile.x && l.y == o.tile.y)) continue;
            float d = proc.dst(o);
            if(d < best){ best = d; other = o; }
        }
        if(other != null) proc.configureAny(other.pos());
    }

    /** Append one logic instruction to the program of the processor nearest the tile (or clear it). */
    void logicWrite(Tile tile, int rotation, int arg){
        var proc = processorNear(tile);
        if(proc == null) return;
        String template = LOGIC[arg % LOGIC.length];
        if(template == null){
            proc.updateCode("");
            return;
        }
        String link = proc.links.isEmpty() ? "@this" : proc.links.get(rotation % proc.links.size).name;
        String line = template.replace("{L}", link).replace("{U}", LOGIC_UNITS[rotation % LOGIC_UNITS.length]);
        String code = proc.code == null ? "" : proc.code.trim();
        if(code.split("\n").length >= MAX_LOGIC_LINES) return;
        proc.updateCode(code.isEmpty() ? line : code + "\n" + line);
    }

    // ---- payloads (mega, quad, oct carry blocks and units) ----

    void payload(int group, Tile tile, mindustry.ai.UnitCommand command){
        Vec2 pos = new Vec2(tile.worldx(), tile.worldy());
        for(Unit u : team.data().units){
            if(u == builder.ship || groupOf(u) != group || !(u instanceof mindustry.gen.Payloadc)) continue;
            if(u.controller() instanceof CommandAI ai && u.type.commands.contains(command)){
                ai.command(command);
                ai.commandPosition(pos);
            }
        }
    }

    // ---- supply helpers ----

    /** Feed the nearest unsupplied building around the tile: 0 = items by conveyor, 1 = power nodes, 2 = liquid by conduit. */
    void supply(Tile tile, int kind){
        Seq<Building> sources = ownBuildings().select(b -> output(b) != null);
        Building consumer = null;
        float bestDst = Float.MAX_VALUE;
        for(Building b : ownBuildings()){
            if(b.dst(tile) > Math.max(10, reach()) * Vars.tilesize) continue;
            if(kind == 0 ? !openForItems(b) || !hasSource(b, sources) : supplied.contains(b.pos() * 3 + kind)) continue;
            boolean needs = kind == 0 ? needsItems(b) : kind == 1 ? needsPower(b) : needsLiquid(b);
            if(!needs) continue;
            float d = b.dst2(tile);
            if(d < bestDst){ bestDst = d; consumer = b; }
        }
        if(consumer == null){ wasted++; return; }
        boolean ok = kind == 0 ? supplyItems(consumer) : kind == 1 ? supplyPower(consumer) : supplyLiquid(consumer);
        if(ok) supplied.add(consumer.pos() * 3 + kind);
        else{
            wasted++;
            if(kind == 0) noRouteUntil.put(consumer.pos(), Vars.state.updateId + NO_ROUTE_REST);
        }
    }

    /** Mining rule (user, 2026-11-07): +1 point per 10 items the ship mines, counted as they enter its hold, so mining pays at once. */
    static final float MINED_PER_POINT = 10f;

    /** Copper-income rule (2026-11-07): +1 point per 10 copper that conveyors/drills hand to the core. Uses the game's own
     *  core item counter, which only counts handleItem (blocks), never the ship's drop-offs. Why: on Frozen Forest the
     *  nearest copper is 20+ tiles out; the bot fed the core ~9.5 copper/min vs ~109 on Ground Zero, spending ~47. */
    static final float COPPER_FED_PER_POINT = 10f;
    int copperFed, lastFedCount = -1;

    void checkCopperFed(){
        int count = Vars.state.stats.coreItemCount.get(Items.copper, 0);
        if(lastFedCount < 0 || count < lastFedCount){ lastFedCount = count; return; }
        if(count > lastFedCount){
            copperFed += count - lastFedCount;
            addPoints((count - lastFedCount) / COPPER_FED_PER_POINT);
            lastFedCount = count;
        }
    }
    int mined;

    /** Actions that had nothing to act on (logged; 2026-11-06). */
    int wasted;

    /** Actions that need something to act on. The bot picks the action first, then a map cell where it can work (2026-11-06). */
    static final int[] TARGETED = {REMOVE, SUPPLY_ITEMS, SUPPLY_POWER, SUPPLY_LIQUID, CONFIGURE, SHIP_DELIVER};
    final boolean[][] targetCells = new boolean[TARGETED.length][Vision.G * Vision.G];

    /** Whether this building wants items and has a source in range that it would really take from. */
    boolean hasSource(Building c, Seq<Building> sources){
        for(Building p : sources){
            if(p != c && p.dst(c) < SUPPLY_RANGE * Vars.tilesize && c.acceptItem(p, output(p))
                && !(c instanceof CoreBuild && linkedDrills.contains(p.pos()))) return true;
        }
        return false;
    }

    /** Marks, per targeted action, the map cells that hold something it can act on. */
    void findTargets(){
        for(boolean[] cells : targetCells) java.util.Arrays.fill(cells, false);
        Seq<Building> own = ownBuildings(), sources = own.select(b -> output(b) != null);
        Seq<Item> carried = new Seq<>();
        for(Item item : ITEMS) if(hasItem(item)) carried.add(item);
        for(Building b : own){
            int c = vision.index(b.tile.x, b.tile.y);
            if(!(b instanceof CoreBuild)) targetCells[0][c] = true;
            if(needsItems(b) && openForItems(b) && hasSource(b, sources)) targetCells[1][c] = true;
            if(needsPower(b) && !supplied.contains(b.pos() * 3 + 1)) targetCells[2][c] = true;
            if(needsLiquid(b) && !supplied.contains(b.pos() * 3 + 2)) targetCells[3][c] = true;
            if(configurable(b.block)) targetCells[4][c] = true;
            if(carried.contains(item -> accepts(b, item))) targetCells[5][c] = true;
        }
        for(Building b : Team.derelict.data().buildings){
            if(!b.block.privileged) targetCells[0][vision.index(b.tile.x, b.tile.y)] = true;
        }
    }

    /** Where this action can be aimed: its targets, or any usable ground if it doesn't need one. */
    boolean[] cellsFor(int type){
        for(int i = 0; i < TARGETED.length; i++) if(TARGETED[i] == type) return targetCells[i];
        return vision.usable;
    }

    static boolean any(boolean[] cells){
        for(boolean c : cells) if(c) return true;
        return false;
    }

    /** How far from the chosen tile a target can be: a cell's width, so any tile in the target's cell reaches it. */
    int reach(){
        return Math.max(4, vision.cell + 1);
    }

    /** Buildings no conveyor route could reach: left alone for 30 seconds instead of being retried every turn (2026-11-06). */
    static final long NO_ROUTE_REST = 1800;
    final arc.struct.IntMap<Long> noRouteUntil = new arc.struct.IntMap<>();

    /** Drills that already have a line from the supply action. */
    final IntSet linkedDrills = new IntSet();
    static final int SUPPLY_RANGE = 35;

    /** Each building gets one item line, except the core, which stays open while a drill in reach has no line yet (2026-11-06). */
    boolean openForItems(Building b){
        if(Vars.state.updateId < noRouteUntil.get(b.pos(), 0L)) return false;
        if(!(b instanceof CoreBuild)) return !supplied.contains(b.pos() * 3);
        return ownBuildings().contains(d -> freeProducer(d, b));
    }

    boolean freeProducer(Building p, Building core){
        return !linkedDrills.contains(p.pos()) && output(p) != null && p.dst(core) < SUPPLY_RANGE * Vars.tilesize
            && core.acceptItem(p, output(p));
    }

    static boolean needsItems(Building b){
        if(b instanceof CoreBuild || b.block instanceof ItemTurret) return true;
        if(!b.block.hasItems) return false;
        for(Consume c : b.block.consumers){
            if(c instanceof ConsumeItems || c instanceof ConsumeItemFilter) return true;
        }
        return false;
    }

    static boolean needsPower(Building b){
        return b.block.consumesPower && b.power != null && b.power.graph.getPowerProduced() <= 0f;
    }

    static boolean needsLiquid(Building b){
        if(!b.block.hasLiquids || b.block instanceof LiquidBlock) return false;
        for(Consume c : b.block.consumers){
            if(c instanceof ConsumeLiquidBase) return true;
        }
        return false;
    }

    boolean supplyItems(Building consumer){
        Seq<Building> producers = ownBuildings().select(b -> b != consumer && output(b) != null
            && b.dst(consumer) < SUPPLY_RANGE * Vars.tilesize && consumer.acceptItem(b, output(b))
            && !(consumer instanceof CoreBuild && linkedDrills.contains(b.pos())));
        producers.sort(b -> b.dst2(consumer));
        for(Building p : producers){
            nav.reserved.clear();
            Seq<Builder.Plan> route = nav.route(p.tile.getLinkedTiles(new Seq<>()), t -> t.build == consumer, Blocks.conveyor);
            if(route == null) continue;
            route.each(builder::enqueue);
            linkedDrills.add(p.pos());
            return true;
        }
        return false;
    }

    static Item output(Building b){
        if(b instanceof Drill.DrillBuild d) return d.dominantItem;
        if(b.block instanceof GenericCrafter c && c.outputItem != null) return c.outputItem.item;
        return null;
    }

    boolean supplyLiquid(Building consumer){
        Seq<Building> sources = ownBuildings().select(b -> b != consumer && b.dst(consumer) < 35 * Vars.tilesize
            && (b.block instanceof Pump || (b.block instanceof GenericCrafter c && c.outputLiquid != null)));
        sources.sort(b -> b.dst2(consumer));
        for(Building s : sources){
            nav.reserved.clear();
            Seq<Builder.Plan> route = nav.route(s.tile.getLinkedTiles(new Seq<>()), t -> t.build == consumer, Blocks.conduit);
            if(route == null) continue;
            route.each(builder::enqueue);
            return true;
        }
        return false;
    }

    boolean supplyPower(Building consumer){
        Building producer = null;
        float bestDst = Float.MAX_VALUE;
        for(Building b : ownBuildings()){
            if(!b.block.outputsPower || b.block.consumesPower) continue;
            float d = b.dst2(consumer);
            if(d < bestDst){ bestDst = d; producer = b; }
        }
        if(producer == null || bestDst > Math.pow(60 * Vars.tilesize, 2)) return false;

        PowerNode node = (PowerNode)Blocks.powerNode;
        int step = Math.max(1, (int)node.laserRange - 1);
        nav.reserved.clear();
        final Building target = consumer;
        Seq<Tile> starts = new Seq<>();
        for(Tile f : producer.tile.getLinkedTiles(new Seq<>())){
            for(Point2 d : Nav.D4){
                Tile n = f.nearby(d.x, d.y);
                if(n != null && nav.free(n.x, n.y, node)) starts.add(n);
            }
        }
        Seq<Tile> path = Nav.bfs(starts, t -> t.build == target, t -> t.build == null && !t.solid(), 4000);
        if(path == null) return false;

        Seq<Tile> nodes = new Seq<>();
        for(int i = 0; i < path.size; i += step) nodes.add(path.get(i));
        if(nodes.peek() != path.peek()) nodes.add(path.peek());
        nodes.retainAll(t -> nav.free(t.x, t.y, node));
        if(nodes.isEmpty()) return false;

        int producerPos = producer.pos(), consumerPos = consumer.pos();
        for(int i = 0; i < nodes.size; i++){
            int linkTo = i == 0 ? producerPos : nodes.get(i - 1).pos();
            boolean last = i == nodes.size - 1;
            builder.enqueue(new Builder.Plan(node, nodes.get(i).x, nodes.get(i).y, 0, placed -> {
                link(placed, linkTo);
                if(last) link(placed, consumerPos);
            }));
        }
        return true;
    }

    static void link(Building node, int targetPos){
        Building target = Vars.world.build(targetPos);
        if(target != null && target.power != null && !node.power.links.contains(targetPos)) node.configureAny(targetPos);
    }

    // ---- army ----

    void armyMove(int group, Tile tile){
        Vec2 pos = new Vec2(tile.worldx(), tile.worldy());
        for(Unit u : team.data().units){
            if(u != builder.ship && groupOf(u) == group && u.controller() instanceof CommandAI ai) ai.commandPosition(pos);
        }
    }

    /** Order a group to attack the enemy building or unit nearest the tile. */
    void armyAttack(int group, Tile tile){
        Teamc target = nearestEnemy(tile.worldx(), tile.worldy());
        if(target == null) return;
        for(Unit u : team.data().units){
            if(u != builder.ship && groupOf(u) == group && u.controller() instanceof CommandAI ai) ai.commandTarget(target);
        }
    }

    Teamc nearestEnemy(float x, float y){
        Teamc best = Units.closestEnemy(team, x, y, 40 * Vars.tilesize, u -> true);
        float bestDst = best == null ? Float.MAX_VALUE : best.dst2(x, y);
        for(TeamData data : Vars.state.teams.getActive()){
            if(data.team == team) continue;
            for(Building b : data.buildings){
                if(b.block.privileged) continue;
                float d = b.dst2(x, y);
                if(d < bestDst){ bestDst = d; best = b; }
            }
        }
        return best;
    }

    // ---- scoring ----

    float enemyBuildingHealth(){
        float sum = 0;
        for(TeamData data : Vars.state.teams.getActive()){
            if(data.team != team) sum += data.buildings.sumf(b -> b.block.privileged ? 0f : b.health);
        }
        return sum;
    }

    int enemyCores(){
        int n = 0;
        for(TeamData data : Vars.state.teams.getActive()){
            if(data.team != team) n += data.cores.size;
        }
        return n;
    }

    /**
     * How well a finished game went. Only outcomes count, never how they were achieved: how long the core
     * survived, waves reached (with progress into the current one), enemies killed, core health,
     * enemy base destroyed, and winning (sooner is better). Continuous so slightly better play scores higher.
     */
    /** The fitness so far, without the win bonus; per-decision rewards are its changes. */
    float potential(long ticks, float capTicks){
        return fitness(false, ticks, capTicks);
    }

    float fitness(boolean won, long ticks, float capTicks){
        CoreBuild core = builder.core();
        var rules = Vars.state.rules;
        float waveProgress = rules.waveSpacing <= 0 ? 0 : 1f - Math.min(1f, Vars.state.wavetime / rules.waveSpacing);
        float waves = Math.max(0, Vars.state.wave - 1 + waveProgress);
        float score = 0;
        if(rules.winWave > 0) score += 100f * waves / rules.winWave;
        else score += 2f * waves;
        score += 20f * Math.min(1f, ticks / capTicks);
        score += 0.5f * kills;
        score += 10f * (core == null ? 0 : core.health / core.maxHealth);
        if(enemyHealthStart > 0) score += 40f * (1f - enemyBuildingHealth() / enemyHealthStart);
        if(enemyCoresStart > 0) score += 15f * (enemyCoresStart - enemyCores());
        if(won) score += 100f + 50f * (1f - ticks / capTicks);
        return score;
    }
}
