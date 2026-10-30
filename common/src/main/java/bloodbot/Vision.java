package bloodbot;

import mindustry.Vars;
import mindustry.content.Items;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.type.Category;
import mindustry.world.Tile;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

/**
 * What the bot sees: the whole map squeezed into a G x G picture with one layer per kind of thing.
 * Each cell covers {@link #cell} x {@link #cell} tiles; values are fractions of the cell (0..1).
 */
public class Vision{
    static final int G = 32;
    static final String[] LAYERS = {
        "wall", "liquid floor", "copper ore", "lead ore", "sand", "coal", "titanium", "scrap/thorium",
        "own buildings", "own turrets", "own transport", "own production", "own power", "own walls",
        "enemy buildings", "enemy turrets", "enemy cores", "own units", "enemy units", "enemy spawns",
        "own core", "destroyed (rebuildable)", "own ship"
    };
    static final int C = LAYERS.length;

    final int cell, width, height;
    final float[] terrain = new float[8 * G * G];
    /** Cells with enough open, buildable ground to be worth choosing (never solid walls or the dark void). */
    final boolean[] usable = new boolean[G * G];
    final float[] grid = new float[C * G * G];

    Vision(){
        width = Vars.world.width();
        height = Vars.world.height();
        cell = Math.max(1, (int)Math.ceil(Math.max(width, height) / (float)G));
        float per = 1f / (cell * cell);
        float[] open = new float[G * G];
        for(Tile t : Vars.world.tiles){
            int c = index(t.x, t.y);
            if(!t.block().isStatic() && !t.floor().isDeep() && t.floor() != mindustry.content.Blocks.empty && !t.isDarkened()) open[c] += per;
            if(t.block().isStatic()) terrain[c] += per;
            else if(t.floor().isLiquid) terrain[G * G + c] += per;
            var drop = t.drop();
            if(drop == Items.copper) terrain[2 * G * G + c] += per;
            else if(drop == Items.lead) terrain[3 * G * G + c] += per;
            else if(drop == Items.sand) terrain[4 * G * G + c] += per;
            else if(drop == Items.coal) terrain[5 * G * G + c] += per;
            else if(drop == Items.titanium) terrain[6 * G * G + c] += per;
            else if(drop == Items.scrap || drop == Items.thorium) terrain[7 * G * G + c] += per;
        }
        for(int c = 0; c < G * G; c++) usable[c] = open[c] >= 0.1f;
    }

    int index(int tileX, int tileY){
        return Math.min(G - 1, tileY / cell) * G + Math.min(G - 1, tileX / cell);
    }

    float[] see(Team team, mindustry.gen.Unit ship){
        System.arraycopy(terrain, 0, grid, 0, terrain.length);
        java.util.Arrays.fill(grid, terrain.length, grid.length, 0f);
        float per = 1f / (cell * cell);

        for(Building b : Groups.build){
            if(b.block.privileged) continue; // map scripting (world processors etc.) is invisible to the bot
            int area = b.block.size * b.block.size, c = index(b.tile.x, b.tile.y);
            float v = area * per;
            if(b.team == team){
                add(8, c, v);
                Category cat = b.block.category;
                if(cat == Category.turret) add(9, c, v);
                else if(cat == Category.distribution || cat == Category.liquid) add(10, c, v);
                else if(cat == Category.production || cat == Category.crafting || cat == Category.units) add(11, c, v);
                else if(cat == Category.power) add(12, c, v);
                else if(cat == Category.defense) add(13, c, v);
                if(b instanceof CoreBuild) add(20, c, 1f);
            }else if(b.team != Team.derelict){
                add(14, c, v);
                if(b.block.category == Category.turret) add(15, c, v);
                if(b instanceof CoreBuild) add(16, c, 1f);
            }
        }
        for(var u : Groups.unit){
            if(u == ship) continue;
            add(u.team == team ? 17 : 18, index(u.tileX(), u.tileY()), 0.25f);
        }
        for(Tile s : Vars.spawner.getSpawns()) add(19, index(s.x, s.y), 1f);
        for(var plan : team.data().plans) add(21, index(plan.x, plan.y), plan.block.size * plan.block.size * per);
        if(ship != null) add(22, index(ship.tileX(), ship.tileY()), 1f);
        return grid;
    }

    void add(int layer, int c, float v){
        int i = layer * G * G + c;
        grid[i] = Math.min(1f, grid[i] + v);
    }
}
