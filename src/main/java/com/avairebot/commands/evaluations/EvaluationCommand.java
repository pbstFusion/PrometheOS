package com.avairebot.commands.evaluations;

import com.avairebot.AvaIre;
import com.avairebot.Constants;
import com.avairebot.commands.CommandMessage;
import com.avairebot.contracts.commands.Command;
import com.avairebot.contracts.commands.CommandGroup;
import com.avairebot.contracts.commands.CommandGroups;
import com.avairebot.database.collection.Collection;
import com.avairebot.database.collection.DataRow;
import com.avairebot.database.transformers.GuildTransformer;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import org.json.JSONObject;

import javax.annotation.Nonnull;
import java.awt.*;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.avairebot.utils.JsonReader.readJsonFromUrl;

public class EvaluationCommand extends Command {
    public EvaluationCommand(AvaIre avaire) {
        super(avaire);
    }

    @Override
    public String getName() {
        return "Evaluation Command";
    }

    @Override
    public String getDescription() {
        return "Commands to manage the evaluations.";
    }

    @Override
    public List <String> getUsageInstructions() {
        return Arrays.asList(
            "`:command <roblox username>` - Get the eval status of a user.",
            "`:command <roblox username> failed/passed quiz/patrol` - Fail/Succeed someone for a Quiz/Patrol"
        );
    }

    @Override
    public List <String> getExampleUsage() {
        return Arrays.asList(
            "`:command superstefano4` - Get the eval status of **superstefano4**.",
            "`:command Cdsi passed quiz` - Succeed **Csdi** for a Quiz"
        );
    }


    @Override
    public List <String> getTriggers() {
        return Arrays.asList("evaluation", "evals");
    }

    @Nonnull
    @Override
    public List <CommandGroup> getGroups() {
        return Collections.singletonList(
            CommandGroups.EVALUATIONS
        );
    }

    @Override
    public List <String> getMiddleware() {
        return Arrays.asList(
            "isOfficialPinewoodGuild",
            "hasAnyRole:Evaluation Host,Evaluators,Trainer",
            "throttle:user,1,3"
        );
    }

    @Override
    public boolean onCommand(CommandMessage context, String[] args) {
        if (args.length < 1) {
            context.makeError("Invalid usage of command. Please add the required arguments. (Begin with the roblox name)").queue();
            return false;
        }

        if (args[0].equalsIgnoreCase("evaluator")) {
            if (args.length < 2) {
                context.makeError("You didn't specify a user:\n``c!evals evaluator <@user>``").queue();
                return false;
            }

            if (!(context.getMessage().getMentionedMembers().size() > 0)) {
                context.makeError("Please mention members in this guild.").queue();
                return false;
            }
            List <Member> members = context.getMessage().getMentionedMembers();
            Role r = context.guild.getRolesByName("Evaluators", true).get(0);


            for (Member m : members) {
                if (m.getRoles().contains(r)) {
                    context.guild.removeRoleFromMember(m, r).queue(p -> {
                        GuildTransformer transformer = context.getGuildTransformer();

                        if (transformer == null) {
                            context.makeError("The guild informormation coudn't be pulled. Please check with Stefano#7366.").queue();
                            return;
                        }

                        if (transformer.getModlog() != null) {
                            context.getGuild().getTextChannelById(transformer.getModlog())
                                .sendMessage(context.makeEmbeddedMessage()
                                    .setTitle("\uD83D\uDCDC Evaluator Role Removed")
                                    .addField("User", m.getEffectiveName(), true).addField("Command Executor", context.member.getEffectiveName(), true)
                                    .buildEmbed()).queue();
                        }
                    });
                    context.makeSuccess("Successfully removed the ``Evaluator`` role from " + m.getAsMention()).queue();

                } else {
                    context.guild.addRoleToMember(m, r).queue(p -> {
                        GuildTransformer transformer = context.getGuildTransformer();


                        if (transformer == null) {
                            context.makeError("The guild informormation coudn't be pulled. Please check with Stefano#7366.").queue();
                            return;
                        }

                        if (transformer.getModlog() != null) {
                            context.getGuild().getTextChannelById(transformer.getModlog())
                                .sendMessage(context.makeEmbeddedMessage()
                                    .setTitle("\uD83D\uDCDC Evaluator Role Added")
                                    .addField("User", m.getEffectiveName(), true).addField("Command Executor", context.member.getEffectiveName(), true)
                                    .buildEmbed()).queue();
                        }
                    });
                    context.makeSuccess("Successfully added the ``Evaluator`` role to " + m.getAsMention()).queue();
                }
            }


            return true;
        }
        if (!isValidRobloxUser(args[0])) {
            context.makeError("This user is not a valid robloxian.").queue();
            return false;
        }
        try {
            Collection collection = avaire.getDatabase().newQueryBuilder(Constants.EVALS_DATABASE_TABLE_NAME).where("roblox_id", getRobloxId(args[0])).get();

            if (args.length < 2) {

                if (collection.size() < 1) {
                    context.makeEmbeddedMessage(new Color(21, 34, 255)).setDescription("This user has this information in the database:\n\n" +
                        "**Passed Quiz**: <:no:694270050257076304>\n" +
                        "**Passed Patrol**: <:no:694270050257076304>\n\n" +
                        "**Last Evaluator**: Unknown").queue();
                    return true;
                }

                if (collection.size() > 2) {
                    context.makeError("Something is wrong in the database, there are records with multiple usernames, but the same user id. Please check if this is correct.").queue();
                    return false;
                }
                if (collection.size() == 1) {
                    DataRow row = collection.get(0);
                    String passed_quiz = row.getBoolean("passed_quiz") ? "<:yes:694268114803621908>" : "<:no:694270050257076304>";
                    String passed_patrol = row.getBoolean("passed_patrol") ? "<:yes:694268114803621908>" : "<:no:694270050257076304>";
                    String evaluator = row.getString("evaluator") != null ? row.getString("evaluator") : "Unkown Evaluator";

                    context.makeEmbeddedMessage(new Color(21, 34, 255)).setDescription("This user has this information in the database:\n\n" +
                        "**Passed Quiz**: " + passed_quiz + "\n" +
                        "**Passed Patrol**: " + passed_patrol + "\n"
                        + (row.getBoolean("passed_quiz") && row.getBoolean("passed_patrol") ? "**User has passed both!**\n\n" : "\n") +
                        "**Last Evaluator**: " + evaluator).queue();
                    return true;
                }

            }
            if (args[1].equalsIgnoreCase("passed")) {
                if (args.length == 2) {
                    context.makeError("Do you want to pass the user in the quiz or in the patrol?\n" +
                        "**Patrol**: ``c!evals " + args[0] + " passed patrol``\n" +
                        "**Quiz**: ``c!evals " + args[0] + " passed quiz``").queue();
                    return false;
                }
                if (args.length == 3) {
                    if (args[2].equalsIgnoreCase("patrol") || args[2].equalsIgnoreCase("quiz")) {
                        if (collection.size() < 1) {
                            int roblox_id = getRobloxId(args[0]);
                            avaire.getDatabase()
                                .newQueryBuilder(Constants.EVALS_DATABASE_TABLE_NAME)
                                .where("roblox_id", roblox_id)
                                .insert(statement -> {
                                    statement
                                        .set("roblox_username", getRobloxUsernameFromId(roblox_id))
                                        .set("roblox_id", roblox_id).set("evaluator", context.getMember().getEffectiveName());
                                    if (args[2].equalsIgnoreCase("patrol")) {
                                        statement.set("passed_patrol", true);
                                    } else if (args[2].equalsIgnoreCase("quiz")) {
                                        statement.set("passed_quiz", true);
                                    }
                                });
                            context.makeSuccess("Successfully added the record to the database").queue();
                            return true;
                        }
                        if (collection.size() > 2) {
                            context.makeError("Something is wrong in the database, there are records with multiple usernames, but the same user id. Please check if this is correct.").queue();
                            return false;
                        }
                        if (collection.size() == 1) {
                            int roblox_id = getRobloxId(args[0]);
                            avaire.getDatabase()
                                .newQueryBuilder(Constants.EVALS_DATABASE_TABLE_NAME)
                                .where("roblox_id", roblox_id)
                                .update(statement -> {
                                    if (args[2].equalsIgnoreCase("patrol")) {
                                        statement.set("passed_patrol", true);
                                    } else if (args[2].equalsIgnoreCase("quiz")) {
                                        statement.set("passed_quiz", true);
                                    }
                                    statement.set("evaluator", context.getMember().getEffectiveName());
                                });
                            context.makeSuccess("Successfully updated the record in the database").queue();
                        }

                        return true;
                    } else {
                        context.makeError("Do you want to pass the user in the quiz or in the patrol?\n" +
                            "**Patrol**: ``c!evals " + args[0] + " passed patrol``\n" +
                            "**Quiz**: ``c!evals " + args[0] + " passed quiz``").queue();
                        return false;
                    }
                }
            }

            if (args[1].equalsIgnoreCase("failed")) {
                if (args.length == 2) {
                    context.makeError("Do you want to fail the user in the quiz or in the patrol?\n" +
                        "**Patrol**: ``c!evals " + args[0] + " failed patrol``\n" +
                        "**Quiz**: ``c!evals " + args[0] + " failed quiz``").queue();
                    return false;
                }
                if (args.length == 3) {
                    if (args[2].equalsIgnoreCase("patrol") || args[2].equalsIgnoreCase("quiz")) {
                        if (collection.size() < 1) {
                            int roblox_id = getRobloxId(args[0]);
                            avaire.getDatabase()
                                .newQueryBuilder(Constants.EVALS_DATABASE_TABLE_NAME)
                                .where("roblox_id", roblox_id)
                                .insert(statement -> {
                                    statement
                                        .set("roblox_username", getRobloxUsernameFromId(roblox_id))
                                        .set("roblox_id", roblox_id).set("evaluator", context.getMember().getEffectiveName());
                                    if (args[2].equalsIgnoreCase("patrol")) {
                                        statement.set("passed_patrol", false);
                                    } else if (args[2].equalsIgnoreCase("quiz")) {
                                        statement.set("passed_quiz", false);
                                    }
                                    statement.set("evaluator", context.getMember().getEffectiveName());
                                });
                            context.makeSuccess("Successfully added the record to the database").queue();
                            return true;
                        }
                        if (collection.size() > 2) {
                            context.makeError("Something is wrong in the database, there are records with multiple usernames, but the same user id. Please check if this is correct.").queue();
                            return false;
                        }
                        if (collection.size() == 1) {
                            int roblox_id = getRobloxId(args[0]);
                            avaire.getDatabase()
                                .newQueryBuilder(Constants.EVALS_DATABASE_TABLE_NAME)
                                .where("roblox_id", roblox_id)
                                .update(statement -> {
                                    if (args[2].equalsIgnoreCase("patrol")) {
                                        statement.set("passed_patrol", false);
                                    } else if (args[2].equalsIgnoreCase("quiz")) {
                                        statement.set("passed_quiz", false);
                                    }
                                });
                            context.makeSuccess("Successfully updated the record in the database").queue();
                            return true;
                        }


                    } else {
                        context.makeError("Do you want to pass the user in the quiz or in the patrol?\n" +
                            "**Patrol**: ``c!evals " + args[0] + " passed patrol``\n" +
                            "**Quiz**: ``c!evals " + args[0] + " passed quiz``").queue();
                        return false;
                    }
                }
            } else {
                context.makeError("Do you want to pass the user in the quiz or in the patrol?\n" +
                    "**Patrol**: ``c!evals " + args[0] + " passed patrol``\n" +
                    "**Quiz**: ``c!evals " + args[0] + " passed quiz``" +
                    "\nDo you want to fail the user in the quiz or in the patrol?\n" +
                    "**Patrol**: ``c!evals " + args[0] + " failed patrol``\n" +
                    "**Quiz**: ``c!evals " + args[0] + " failed quiz``").queue();
                return false;
            }


        } catch (SQLException e) {
            e.printStackTrace();
        }

        return true;
    }

    private static String getRobloxUsernameFromId(int id) {
        try {
            JSONObject json = readJsonFromUrl("http://api.roblox.com/users/" + id);
            return json.getString("Username");
        } catch (Exception e) {
            return "Unknown";
        }
    }

    public int getRobloxId(String un) {

        try {
            JSONObject json = readJsonFromUrl("http://api.roblox.com/users/get-by-username?username=" + un);
            return json.getInt("Id");
        } catch (Exception e) {
            return 0;
        }
    }

    public boolean isValidRobloxUser(String un) {

        try {
            JSONObject json = readJsonFromUrl("http://api.roblox.com/users/get-by-username?username=" + un);
            String username = json.getString("Username");

            return username != null;
        } catch (Exception e) {
            return false;
        }
    }
}
