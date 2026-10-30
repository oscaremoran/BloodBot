package bloodbot;

import arc.files.Fi;
import arc.struct.ObjectIntMap;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.io.SaveIO;
import mindustry.type.UnitType;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

/**
 * Records a game as replay data for runs/viewer.html: the terrain once, every block placed or removed,
 * and every few game seconds the units, stats, damaged buildings and the bot's upcoming build queue.
 * Also writes real .msav snapshots that open in the desktop client.
 */
public class Recorder{
    static final int FRAME_TICKS = 120; // one frame per 2 game seconds
    static final int QUEUE_SHOWN = 12;

    final ObjectIntMap<Block> blockIds = new ObjectIntMap<>();
    final Seq<Block> blocks = new Seq<>();
    final ObjectIntMap<UnitType> unitIds = new ObjectIntMap<>();
    final Seq<UnitType> units = new Seq<>();
    final StringBuilder initial = new StringBuilder(), events = new StringBuilder(), frames = new StringBuilder();
    long ticks;

    /** Recording happens in memory; nothing touches the disk unless {@link #save} is called at the end. */
    Recorder(){
        for(Building b : Groups.build){
            if(initial.length() > 0) initial.append(',');
            initial.append(buildingRow(b));
        }
    }

    /** Called for every tile change; records placements at a building's centre and removals anywhere. */
    void tileChanged(Tile tile){
        Building b = tile.build;
        if(b != null && b.tile != tile) return; // non-centre tile of a multi-tile block
        if(events.length() > 0) events.append(',');
        if(b == null){
            events.append('[').append(ticks).append(',').append(tile.x).append(',').append(tile.y).append(",-1]");
        }else{
            events.append('[').append(ticks).append(',').append(buildingRow(b), 1, buildingRow(b).length() - 1).append(']');
        }
    }

    void update(long ticks, Builder builder){
        this.ticks = ticks;
        if(ticks % FRAME_TICKS == 0) writeFrame(builder);
    }

    /** Writes the replay, thumbnails, result and a final .msav snapshot into {@code dir}. */
    void save(Fi dir, long ticks, Builder builder, String resultJson){
        this.ticks = ticks;
        writeFrame(builder);
        dir.mkdirs();
        dir.child("result.json").writeString(resultJson, false);
        writeImage(dir, "terrain.png", false);
        writeImage(dir, "final.png", true);
        try{
            SaveIO.save(dir.child("final.msav"));
        }catch(Throwable e){
            Log.err("[bloodbot] snapshot failed: @", e.toString());
        }

        StringBuilder s = new StringBuilder("window.REPLAY = {\n");
        s.append("\"map\":").append(Survey.q(Vars.state.map.plainName()));
        s.append(",\"width\":").append(Vars.world.width()).append(",\"height\":").append(Vars.world.height());
        s.append(",\"frameTicks\":").append(FRAME_TICKS);
        s.append(",\"result\":").append(resultJson.trim());
        s.append(",\n\"blocks\":[");
        for(int i = 0; i < blocks.size; i++){
            Block b = blocks.get(i);
            if(i > 0) s.append(',');
            s.append("{\"name\":").append(Survey.q(b.localizedName == null ? b.name : b.localizedName))
                .append(",\"id\":").append(Survey.q(b.name))
                .append(",\"category\":").append(Survey.q(b.category == null ? "" : b.category.name()))
                .append(",\"size\":").append(b.size).append(",\"offset\":").append(b.sizeOffset)
                .append(",\"rotate\":").append(b.rotate).append('}');
        }
        s.append("],\n\"units\":[");
        for(int i = 0; i < units.size; i++){
            if(i > 0) s.append(',');
            s.append(Survey.q(units.get(i).localizedName == null ? units.get(i).name : units.get(i).localizedName));
        }
        s.append("],\n\"teams\":{");
        boolean first = true;
        for(var team : mindustry.game.Team.baseTeams){
            if(!first) s.append(',');
            first = false;
            s.append('"').append(team.id).append("\":{\"name\":").append(Survey.q(team.name))
                .append(",\"color\":\"#").append(team.color.toString(), 0, 6).append("\"}");
        }
        s.append("},\n\"initial\":[").append(initial).append("],\n\"events\":[").append(events);
        s.append("],\n\"frames\":[").append(frames).append("]\n};\n");
        dir.child("replay.js").writeString(s.toString(), false);
    }

    void writeFrame(Builder builder){
        CoreBuild core = Vars.state.rules.defaultTeam.core();
        StringBuilder f = new StringBuilder();
        if(frames.length() > 0) f.append(",\n");
        f.append("{\"t\":").append(ticks).append(",\"wave\":").append(Vars.state.wave)
            .append(",\"enemies\":").append(Vars.state.enemies)
            .append(",\"core\":").append(core == null ? 0 : Math.round(core.health / core.maxHealth * 1000) / 1000f);

        f.append(",\"items\":{");
        if(core != null){
            boolean[] first = {true};
            core.items.each((item, amount) -> {
                if(!first[0]) f.append(',');
                first[0] = false;
                f.append('"').append(item.localizedName == null ? item.name : item.localizedName).append("\":").append(amount);
            });
        }
        f.append('}');

        f.append(",\"units\":[");
        boolean[] firstUnit = {true};
        Groups.unit.each(u -> {
            if(!firstUnit[0]) f.append(',');
            firstUnit[0] = false;
            f.append('[').append(Math.round(u.x / Vars.tilesize * 10) / 10f).append(',')
                .append(Math.round(u.y / Vars.tilesize * 10) / 10f).append(',')
                .append(unitId(u.type)).append(',').append(u.team.id).append(',')
                .append(Math.round(u.health / u.maxHealth * 100)).append(']');
        });

        // Damaged buildings, so hovering shows health and the viewer can shade them.
        f.append("],\"dmg\":[");
        boolean[] firstDmg = {true};
        Groups.build.each(b -> {
            if(b.health >= b.maxHealth) return;
            if(!firstDmg[0]) f.append(',');
            firstDmg[0] = false;
            f.append('[').append(b.tile.x).append(',').append(b.tile.y).append(',').append(Math.round(b.health / b.maxHealth * 100)).append(']');
        });

        // What the bot is about to build, in order; the first entry is in progress.
        f.append("],\"queue\":[");
        if(builder != null){
            int n = 0;
            for(Builder.Plan p : builder.queue){
                if(n >= QUEUE_SHOWN) break;
                if(n++ > 0) f.append(',');
                f.append('[').append(blockId(p.block())).append(',').append(p.x()).append(',').append(p.y()).append(',').append(p.rotation())
                    .append(',').append(p.breaking() ? 1 : 0).append(']');
            }
        }
        f.append("]}");
        frames.append(f);
    }

    String buildingRow(Building b){
        return "[" + b.tile.x + "," + b.tile.y + "," + blockId(b.block) + "," + b.team.id + "," + b.rotation + "]";
    }

    int blockId(Block block){
        if(!blockIds.containsKey(block)){
            blockIds.put(block, blocks.size);
            blocks.add(block);
        }
        return blockIds.get(block);
    }

    int unitId(UnitType type){
        if(!unitIds.containsKey(type)){
            unitIds.put(type, units.size);
            units.add(type);
        }
        return unitIds.get(type);
    }

    /** One pixel per tile; terrain only, or terrain plus buildings for the gallery thumbnail. */
    void writeImage(Fi dir, String name, boolean withBuildings){
        int w = Vars.world.width(), h = Vars.world.height();
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for(Tile tile : Vars.world.tiles){
            // Mindustry's y axis points up; images point down.
            int rgb = withBuildings && tile.build != null ? tile.build.team.color.rgba() >>> 8 : terrainColor(tile);
            img.setRGB(tile.x, h - 1 - tile.y, rgb);
        }
        try{
            ImageIO.write(img, "png", dir.child(name).file());
        }catch(Exception e){
            Log.err(e);
        }
    }

    /**
     * Floor and wall map colours come from textures, which a headless server never loads,
     * so terrain uses a fixed palette; ores keep their real colours.
     */
    static int terrainColor(Tile tile){
        if(tile.block().isStatic()) return 0x4b4136;
        if(tile.overlay() != null && tile.overlay().itemDrop != null) return tile.overlay().itemDrop.color.rgba() >>> 8;
        var floor = tile.floor();
        if(floor.isLiquid) return floor.isDeep() ? 0x264a85 : 0x3a64a8;
        if(floor.itemDrop != null && floor.itemDrop != mindustry.content.Items.sand) return floor.itemDrop.color.cpy().mul(0.7f).rgba() >>> 8;
        // Slight per-floor-type tint so different ground types are distinguishable.
        int tint = (floor.name.hashCode() & 0x0f) - 8;
        return ((0x34 + tint) << 16) | ((0x30 + tint) << 8) | (0x2a + tint);
    }
}
