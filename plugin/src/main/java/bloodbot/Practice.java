package bloodbot;

import arc.files.Fi;
import arc.struct.IntSet;
import arc.util.Log;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.gen.Groups;
import mindustry.io.SaveIO;
import mindustry.type.Item;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

/**
 * Mace practice (the user's choice, 2026-11-02): normal games save their own base when wave {@link #SAVE_WAVE} starts,
 * and some later games start from one of those saves, so the bot gets far more practice against the late waves
 * (maces) per hour. Everything in a save was built by the bot itself: no scripted or human bases.
 *
 * A save is a .msav (the world: buildings, units, wave, rules incl. enemy strength) plus a .json sidecar with what the
 * game file doesn't keep: core items (Logic.play() resets them), and the agent's per-game memory (which blocks it
 * built, which were used, which turrets already got their first-hit bonus, one-time chain bonuses), so practice games
 * pay the same rewards a normal game would have from that point on.
 */
public class Practice{
    /** state.wave when the save is taken: 7 waves have spawned; maces come two waves later. */
    static final int SAVE_WAVE = 8;

    static void save(Agent agent, Fi msav){
        try{
            SaveIO.save(msav);
            Jval j = Jval.newObject();
            j.put("built", ints(agent.builder.builtPos));
            j.put("used", ints(agent.usedBlocks));
            j.put("hit", ints(agent.hitTurrets));
            j.put("fed", ints(agent.fedTurrets));
            j.put("routerFed", ints(agent.routerFedTurrets));
            j.put("drillToConveyor", agent.drillToConveyor);
            j.put("conveyorToCore", agent.conveyorToCore);
            j.put("conveyorToTurret", agent.conveyorToTurret);
            j.put("routerToTurret", agent.routerToTurret);
            j.put("wavetime", Vars.state.wavetime);
            Jval items = Jval.newObject();
            CoreBuild core = Vars.state.rules.defaultTeam.core();
            if(core != null) for(Item item : Vars.content.items()) if(core.items.get(item) > 0) items.put(item.name, core.items.get(item));
            j.put("items", items);
            sidecar(msav).writeString(j.toString());
        }catch(Throwable e){
            Log.err("[bloodbot] practice save failed: @", e.toString());
            msav.delete();
        }
    }

    /** After the save's world is loaded: undo what loading resets, and remove the old ship (the builder makes a new one). */
    static void restoreWorld(Fi msav){
        Jval j = Jval.read(sidecar(msav).readString());
        Vars.state.wavetime = j.getFloat("wavetime", Vars.state.wavetime);
        CoreBuild core = Vars.state.rules.defaultTeam.core();
        if(core != null){
            core.items.clear();
            for(var e : j.get("items").asObject()) {
                Item item = Vars.content.item(e.key);
                if(item != null) core.items.set(item, e.value.asInt());
            }
        }
        Groups.unit.each(u -> u.team == Vars.state.rules.defaultTeam, u -> u.remove());
    }

    /** After the agent is made: give it its memory of the saved game. */
    static void restoreAgent(Agent agent, Fi msav){
        Jval j = Jval.read(sidecar(msav).readString());
        fill(agent.builder.builtPos, j.get("built"));
        fill(agent.usedBlocks, j.get("used"));
        fill(agent.hitTurrets, j.get("hit"));
        fill(agent.fedTurrets, j.get("fed"));
        fill(agent.routerFedTurrets, j.get("routerFed"));
        agent.drillToConveyor = j.getBool("drillToConveyor", false);
        agent.conveyorToCore = j.getBool("conveyorToCore", false);
        agent.conveyorToTurret = j.getBool("conveyorToTurret", false);
        agent.routerToTurret = j.getBool("routerToTurret", false);
        CoreBuild core = Vars.state.rules.defaultTeam.core();
        if(core != null) agent.lastCopper = core.items.get(mindustry.content.Items.copper);
    }

    static Fi sidecar(Fi msav){
        return msav.sibling(msav.nameWithoutExtension() + ".json");
    }

    static Jval ints(IntSet set){
        Jval a = Jval.newArray();
        set.each(v -> a.add(v));
        return a;
    }

    static void fill(IntSet set, Jval arr){
        if(arr == null) return;
        for(Jval v : arr.asArray()) set.add(v.asInt());
    }
}
