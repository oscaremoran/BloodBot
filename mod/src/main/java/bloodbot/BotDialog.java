package bloodbot;

import arc.scene.ui.layout.Table;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.game.Teams.TeamData;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;

/** In-game menu: for every team with a core, choose Off or a difficulty for the bot to play it. */
public class BotDialog extends BaseDialog{
    final BotManager bots;

    BotDialog(BotManager bots){
        super("BloodBot");
        this.bots = bots;
        addCloseButton();
        shown(this::rebuild);
    }

    void rebuild(){
        cont.clear();
        cont.add("Choose which teams the bot plays, and how hard.").padBottom(6).row();
        cont.add("[lightgray]Put it on an enemy team to fight it, or on your own team to play alongside it.").padBottom(16).row();

        Table list = new Table();
        boolean any = false;
        for(TeamData data : Vars.state.teams.getActive()){
            if(data.cores.isEmpty()) continue;
            any = true;
            Team team = data.team;
            boolean you = Vars.player != null && team == Vars.player.team();
            list.add(team.coloredName() + (you ? " [lightgray](you)" : "")).left().padRight(24);

            Difficulty current = bots.difficultyOf(team);
            list.button("Off", Styles.togglet, () -> {
                bots.stop(team);
                rebuild();
            }).checked(current == null).size(100, 48).pad(2);
            for(Difficulty d : Difficulty.values()){
                list.button(d.title, Styles.togglet, () -> {
                    String error = bots.start(team, d);
                    if(error != null) Vars.ui.showInfo(error);
                    rebuild();
                }).checked(current == d).size(100, 48).pad(2);
            }
            list.row();
        }
        if(!any) list.add("[lightgray]No teams with a core in this game.");
        cont.add(list).row();
        cont.button("Stop all bots", () -> {
            bots.stopAll();
            rebuild();
        }).size(220, 50).padTop(20);
    }
}
