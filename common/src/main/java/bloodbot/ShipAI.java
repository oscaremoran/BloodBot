package bloodbot;

import arc.math.geom.Vec2;
import mindustry.Vars;
import mindustry.entities.Units;
import mindustry.entities.units.AIController;
import mindustry.gen.Building;
import mindustry.gen.Teamc;
import mindustry.gen.Unit;
import mindustry.type.Item;
import mindustry.world.Tile;
import mindustry.world.blocks.storage.CoreBlock;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

/**
 * Flies the bot's core ship (alpha) with the same abilities a player has. Building always comes first:
 * with build plans queued it flies within building range and the game constructs them for real
 * (gradual cost, interruptible by enemies). Otherwise it does its current task: guard the core,
 * mine an ore and carry it home, fight around a spot, or ferry items from the core to a building.
 * It shoots whatever enemy is in range the whole time, as players do, unless the gun is switched off.
 */
public class ShipAI extends AIController{
    enum Task{ GUARD, MINE, FIGHT, DELIVER }

    static final float GUARD_LEASH = 22 * Vars.tilesize;

    /**
     * The ship's gun. Training turns it off with -Dbloodbot.shipGun=false (the user's choice, 2026-10-30), so turrets
     * are the only defense; the mod never sets the property, so in the game the ship keeps its gun.
     */
    static final boolean GUN = !"false".equals(System.getProperty("bloodbot.shipGun"));

    /**
     * Enemies neither target nor hit the ship. Training turns this on with -Dbloodbot.shipUntouchable=true
     * (the user's choice, 2026-10-30); the mod never sets it.
     */
    static final boolean UNTOUCHABLE = Boolean.getBoolean("bloodbot.shipUntouchable");

    @Override
    public void updateWeapons(){
        if(GUN){
            super.updateWeapons();
            return;
        }
        target = null;
        unit.isShooting = false;
        for(var mount : unit.mounts) mount.shoot = false;
    }

    /** A new order only replaces the current one after it has run this long (about how fast a person re-tasks a ship). */
    static final float COMMIT_TICKS = 600;

    Task task = Task.GUARD;
    float committedAt = -COMMIT_TICKS;
    Item item;
    Vec2 spot = new Vec2();
    Building deliverTo;

    /** Whether a new order would be accepted now (no side effects). */
    boolean ready(){
        return Vars.state.tick - committedAt >= COMMIT_TICKS;
    }

    boolean canChange(){
        if(Vars.state.tick - committedAt < COMMIT_TICKS) return false;
        committedAt = (float)Vars.state.tick;
        return true;
    }

    void guard(){ if(canChange()) task = Task.GUARD; }

    void mine(Item ore){ if(canChange()){ task = Task.MINE; item = ore; } }

    void fight(Tile at){ if(canChange()){ task = Task.FIGHT; spot.set(at.worldx(), at.worldy()); } }

    void deliver(Item what, Building target){ if(canChange()){ task = Task.DELIVER; item = what; deliverTo = target; } }

    @Override
    public void updateMovement(){
        if(Boolean.getBoolean("bloodbot.debug") && Vars.state.updateId % 300 == 0){
            arc.util.Log.info("[ship] task=@ item=@ mineTile=@ plans=@ pos=@,@ vel=@ speed=@ ctrl=@ stack=@",
                task, item, unit.mineTile(), unit.plans.size, unit.tileX(), unit.tileY(), unit.vel.len(), unit.speed(),
                unit.controller().getClass().getSimpleName(), unit.stack);
        }
        CoreBuild core = unit.closestCore();
        if(core == null) return;

        // Build first, but only when the core can pay for it; otherwise construction would stall forever
        // with the ship hovering beside it. Like a player, it gets on with its task (e.g. mining) meanwhile.
        boolean build = unit.plans.size > 0 && canAfford(unit.buildPlan(), core) && !miningForPlan(core);
        // The game won't let a unit mine while it counts as building, so building is switched off during other tasks.
        unit.updateBuilding(build);
        if(build){
            var plan = unit.buildPlan();
            unit.mineTile(null);
            moveTo(Vars.world.tile(plan.x, plan.y), Vars.buildingRange * 0.6f);
            return;
        }
        switch(task){
            case GUARD -> {
                unit.mineTile(null);
                if(target != null && target.within(core, GUARD_LEASH)) moveTo(target, unit.type.range * 0.8f);
                else moveTo(core, 3 * Vars.tilesize);
            }
            case FIGHT -> {
                unit.mineTile(null);
                if(target != null) moveTo(target, unit.type.range * 0.8f);
                else moveTo(spot, 2 * Vars.tilesize);
            }
            case MINE -> updateMining(core);
            case DELIVER -> updateDelivery(core);
        }
        if(target != null) unit.lookAt(target);
    }

    /** Told to mine with the core nearly out of what the next plan needs: finish the trip instead of darting back for each trickle. */
    boolean miningForPlan(CoreBuild core){
        if(task != Task.MINE || item == null || core.items.get(item) >= unit.type.itemCapacity) return false;
        var plan = unit.buildPlan();
        if(plan.breaking || plan.block == null) return false;
        for(var stack : plan.block.requirements){
            if(stack.item == item) return true;
        }
        return false;
    }

    /** Removals are free; a placement needs at least one of each of its materials in the core to make progress. */
    static boolean canAfford(mindustry.entities.units.BuildPlan plan, CoreBuild core){
        if(plan.breaking || plan.block == null) return true;
        for(var stack : plan.block.requirements){
            if(core.items.get(stack.item) <= 0) return false;
        }
        return true;
    }

    /** Mine the nearest ore of the chosen item; when full, fly home and drop it in the core. */
    void updateMining(CoreBuild core){
        if(item == null || !unit.canMine(item)){ task = Task.GUARD; return; }
        boolean full = unit.stack.amount >= unit.type.itemCapacity || (unit.hasItem() && unit.stack.item != item);
        if(full){
            unit.mineTile(null);
            if(unit.within(core, Vars.mineTransferRange)){
                if(core.acceptStack(unit.stack.item, unit.stack.amount, unit) > 0){
                    core.handleStack(unit.stack.item, core.acceptStack(unit.stack.item, unit.stack.amount, unit), unit);
                }
                unit.clearItem();
            }else{
                moveTo(core, Vars.mineTransferRange * 0.5f);
            }
            return;
        }
        Tile ore = unit.mineTile();
        if(ore == null || !unit.validMine(ore) || ore.drop() != item || !handMineable(ore)){
            ore = Vars.indexer.findClosestOre(unit, item);
            if(ore != null && !handMineable(ore)) ore = null;
            unit.mineTile(ore);
        }
        if(ore == null){ task = Task.GUARD; return; }
        moveTo(ore, unit.type.mineRange * 0.6f);
    }

    /** Players can't mine floors flagged playerUnmineable (sand, dark sand) by hand, only ore overlays; drills still can. */
    static boolean handMineable(Tile t){
        return !t.floor().playerUnmineable || t.overlay().itemDrop != null;
    }

    /** Take a load of an item from the core and drop it into a building (e.g. ammo into a turret). */
    void updateDelivery(CoreBuild core){
        unit.mineTile(null);
        if(deliverTo == null || !deliverTo.isValid() || item == null){ task = Task.GUARD; return; }
        if(!unit.hasItem() || unit.stack.item != item){
            if(unit.within(core, Vars.itemTransferRange)){
                if(unit.hasItem()) core.handleStack(unit.stack.item, core.acceptStack(unit.stack.item, unit.stack.amount, unit), unit);
                unit.clearItem();
                int amount = Math.min(core.items.get(item), unit.type.itemCapacity);
                if(amount <= 0){ task = Task.GUARD; return; }
                core.items.remove(item, amount);
                unit.addItem(item, amount);
            }else{
                moveTo(core, Vars.itemTransferRange * 0.5f);
            }
            return;
        }
        if(unit.within(deliverTo, Vars.itemTransferRange)){
            int accepted = deliverTo.acceptStack(item, unit.stack.amount, unit);
            if(accepted > 0){
                deliverTo.handleStack(item, accepted, unit);
                unit.stack.amount -= accepted;
                if(unit.stack.amount <= 0) unit.clearItem();
            }else{
                task = Task.GUARD; // target is full or doesn't take this item
            }
        }else{
            moveTo(deliverTo, Vars.itemTransferRange * 0.5f);
        }
    }

    @Override
    public Teamc findTarget(float x, float y, float range, boolean air, boolean ground){
        return Units.closestTarget(unit.team, x, y, range, u -> u.checkTarget(air, ground), b -> ground);
    }

    static boolean alive(Unit u){
        return u != null && u.isValid() && !u.dead();
    }

    /** Spawns the core's unit type at the core. Not flagged spawnedByCore: the game despawns those without a player. */
    static Unit spawn(mindustry.game.Team team){
        CoreBuild core = team.core();
        if(core == null) return null;
        var type = ((CoreBlock)core.block).unitType;
        if(UNTOUCHABLE){
            // Applies to the whole unit type, which is fine on the training server: only the bot flies core ships there.
            type.targetable = false;
            type.hittable = false;
        }
        Unit unit = type.spawn(team, core.x, core.y);
        unit.controller(new ShipAI());
        return unit;
    }
}
