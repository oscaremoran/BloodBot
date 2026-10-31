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
 * What the bot sees, two views like AlphaStar's minimap and screen (2026-10-31):
 * the minimap is the whole map squeezed into a G x G picture with one layer per kind of thing (each cell covers
 * {@link #cell} x {@link #cell} tiles; values are fractions of the cell, 0..1), and the close-up is a G x G window of
 * single tiles centred on the bot's ship (or its core), with the same layers. Close-up layers follow the minimap's.
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
    /** Minimap layers, then close-up layers. */
    static final int CHANNELS = 2 * C;

    final int cell, width, height;
    final float[] terrain = new float[8 * G * G];
    /** Cells with enough open, buildable ground to be worth choosing (never solid walls or the dark void). */
    final boolean[] usable = new boolean[G * G];
    final float[] grid = new float[CHANNELS * G * G];
    /** Bottom-left tile of the close-up window, for whoever needs to know where it is. */
    int closeX, closeY;

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
        java.util.Arrays.fill(grid, terrain.length, grid.length, 0f); // clears the close-up too
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
        seeClose(team, ship);
        return grid;
    }

    /** The close-up: one tile per cell, centred on the ship (or the core), kept inside the map. */
    void seeClose(Team team, mindustry.gen.Unit ship){
        int base = C * G * G;
        CoreBuild core = team.core();
        int fx = ship != null ? ship.tileX() : core != null ? core.tile.x : width / 2;
        int fy = ship != null ? ship.tileY() : core != null ? core.tile.y : height / 2;
        closeX = Math.max(0, Math.min(width - G, fx - G / 2));
        closeY = Math.max(0, Math.min(height - G, fy - G / 2));
        for(int j = 0; j < G; j++){
            for(int i = 0; i < G; i++){
                int c = j * G + i;
                Tile t = Vars.world.tile(closeX + i, closeY + j);
                if(t == null || t.isDarkened() || t.floor() == mindustry.content.Blocks.empty){ grid[base + c] = 1f; continue; }
                if(t.block().isStatic()) grid[base + c] = 1f;
                else if(t.floor().isLiquid) grid[base + G * G + c] = 1f;
                var drop = t.drop();
                int ore = drop == Items.copper ? 2 : drop == Items.lead ? 3 : drop == Items.sand ? 4 : drop == Items.coal ? 5
                    : drop == Items.titanium ? 6 : drop == Items.scrap || drop == Items.thorium ? 7 : -1;
                if(ore >= 0) grid[base + ore * G * G + c] = 1f;
                Building b = t.build;
                if(b == null || b.block.privileged) continue;
                if(b.team == team){
                    grid[base + 8 * G * G + c] = 1f;
                    Category cat = b.block.category;
                    int layer = cat == Category.turret ? 9 : cat == Category.distribution || cat == Category.liquid ? 10
                        : cat == Category.production || cat == Category.crafting || cat == Category.units ? 11
                        : cat == Category.power ? 12 : cat == Category.defense ? 13 : -1;
                    if(layer >= 0) grid[base + layer * G * G + c] = 1f;
                    if(b instanceof CoreBuild) grid[base + 20 * G * G + c] = 1f;
                }else if(b.team != Team.derelict){
                    grid[base + 14 * G * G + c] = 1f;
                    if(b.block.category == Category.turret) grid[base + 15 * G * G + c] = 1f;
                    if(b instanceof CoreBuild) grid[base + 16 * G * G + c] = 1f;
                }
            }
        }
        for(var u : Groups.unit){
            if(u == ship) continue;
            addClose(u.team == team ? 17 : 18, u.tileX(), u.tileY(), 0.5f);
        }
        for(Tile s : Vars.spawner.getSpawns()) addClose(19, s.x, s.y, 1f);
        for(var plan : team.data().plans) addClose(21, plan.x, plan.y, 1f);
        if(ship != null) addClose(22, ship.tileX(), ship.tileY(), 1f);
    }

    void addClose(int layer, int tileX, int tileY, float v){
        int i = tileX - closeX, j = tileY - closeY;
        if(i < 0 || j < 0 || i >= G || j >= G) return;
        int k = C * G * G + layer * G * G + j * G + i;
        grid[k] = Math.min(1f, grid[k] + v);
    }

    void add(int layer, int c, float v){
        int i = layer * G * G + c;
        grid[i] = Math.min(1f, grid[i] + v);
    }
}
