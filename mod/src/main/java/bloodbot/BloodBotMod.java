package bloodbot;

import arc.Events;
import arc.util.CommandHandler;
import arc.util.Log;
import arc.util.Structs;
import mindustry.Vars;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.game.Team;
import mindustry.mod.Mod;

/**
 * BloodBot as a Mindustry mod: let the self-taught bot play any team, at four difficulty levels.
 * In game, use the BloodBot button (bottom left). On a server, use the "bloodbot" console command.
 */
public class BloodBotMod extends Mod{
    public static BotManager bots;

    public BloodBotMod(){
        Events.on(ClientLoadEvent.class, e -> {
            BotDialog dialog = new BotDialog(bots);
            Vars.ui.hudGroup.fill(t -> {
                t.bottom().left();
                t.button("BloodBot", dialog::show).size(140, 50).margin(4)
                    .visible(() -> Vars.state.isGame() && Vars.ui.hudfrag.shown && !Vars.net.client());
            });
        });
    }

    @Override
    public void init(){
        bots = new BotManager(Vars.mods.getMod(BloodBotMod.class).root.child("brains"));
        Events.on(WorldLoadEvent.class, e -> bots.reset());
        Events.run(Trigger.update, () -> bots.update());
    }

    @Override
    public void registerServerCommands(CommandHandler handler){
        handler.register("bloodbot", "<team> <off/easy/normal/hard/insane>", "Let the bot play a team (or stop it).", args -> {
            Team team = Structs.find(Team.all, t -> t.name.equalsIgnoreCase(args[0]));
            if(team == null){
                Log.err("No team named @", args[0]);
                return;
            }
            if(args[1].equalsIgnoreCase("off")){
                bots.stop(team);
                Log.info("Bot stopped for @", team.name);
                return;
            }
            Difficulty d = Structs.find(Difficulty.values(), x -> x.name().equalsIgnoreCase(args[1]));
            if(d == null){
                Log.err("Difficulty must be off, easy, normal, hard or insane");
                return;
            }
            String error = bots.start(team, d);
            if(error != null) Log.err(error);
        });
    }
}
