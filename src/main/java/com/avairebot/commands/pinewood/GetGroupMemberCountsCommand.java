package com.avairebot.commands.pinewood;

import com.avairebot.AvaIre;
import com.avairebot.commands.CommandMessage;
import com.avairebot.contracts.commands.Command;
import com.avairebot.contracts.commands.CommandGroup;
import com.avairebot.contracts.commands.CommandGroups;
import net.dv8tion.jda.api.entities.TextChannel;
import org.json.JSONObject;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.avairebot.utils.JsonReader.readJsonFromUrl;

public class GetGroupMemberCountsCommand extends Command {

    public GetGroupMemberCountsCommand(AvaIre avaire) {
        super(avaire);
    }

    @Override
    public String getName() {
        return "Group Count Command";
    }

    @Override
    public String getDescription() {
        return "Show the amount of members in all roblox groups.";
    }

    @Override
    public List <String> getUsageInstructions() {
        return Collections.singletonList(
            "`:command` - Show the amount of members in all roblox groups."
        );
    }

    @Override
    public List <String> getExampleUsage() {
        return Collections.singletonList(
            "`:command` - Show the amount of members in all roblox groups."
        );
    }


    @Override
    public List <String> getTriggers() {
        return Arrays.asList("groupcount", "pbcounts");
    }

    @Nonnull
    @Override
    public List <CommandGroup> getGroups() {
        return Collections.singletonList(
            CommandGroups.MISCELLANEOUS
        );
    }

    @Override
    public boolean onCommand(CommandMessage context, String[] args) {
        TextChannel tc = context.channel;
        int PB = getMemberCount(159511);
        int PBST = getMemberCount(645836);
        int PET = getMemberCount(2593707);
        int TMS = getMemberCount(4890641);
        int PBM = getMemberCount(4032816);

        if (tc != null) {
            tc.sendMessage("Retrieving PBST member count <a:loading:742658561414266890>").queue(pbst -> {
                try {
                    Thread.sleep(4000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                pbst.editMessage("<:PB:543160237805535284>: " + PB + "\n" +
                    "<:pbst:452100887025025038>: " + PBST + "\n" +
                    "<:TMS:572920815595683841>: " + TMS + "\n" +
                    "<:PET:694389856071319593>: " + PET + "\n" +
                    "<:PridePBM:720733180016984066>: " + PBM + "").queue();
            });

            return false;
        }
        return false;
    }
    public int getMemberCount(int i) {
        try {
            JSONObject json = readJsonFromUrl("https://groups.roblox.com/v1/groups/" + i);
            return json.getInt("memberCount");
        } catch (Exception e) {
            return 0;
        }
    }
    @Override
    public List <String> getMiddleware() {
        return Collections.singletonList(
            "throttle:user,1,200"
        );
    }


}
