/*
 * Copyright (c) 2018.
 *
 * This file is part of AvaIre.
 *
 * AvaIre is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AvaIre is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AvaIre.  If not, see <https://www.gnu.org/licenses/>.
 *
 *
 */

package com.avairebot.handlers.adapter;

import com.avairebot.AppInfo;
import com.avairebot.AvaIre;
import com.avairebot.Constants;
import com.avairebot.commands.CommandContainer;
import com.avairebot.commands.CommandHandler;
import com.avairebot.contracts.handlers.EventAdapter;
import com.avairebot.database.collection.Collection;
import com.avairebot.database.collection.DataRow;
import com.avairebot.database.controllers.GuildController;
import com.avairebot.database.controllers.PlayerController;
import com.avairebot.database.controllers.ReactionController;
import com.avairebot.database.query.QueryBuilder;
import com.avairebot.database.transformers.ChannelTransformer;
import com.avairebot.database.transformers.GuildTransformer;
import com.avairebot.factories.MessageFactory;
import com.avairebot.handlers.DatabaseEventHolder;
import com.avairebot.middleware.MiddlewareStack;
import com.avairebot.middleware.ThrottleMiddleware;
import com.avairebot.modlog.Modlog;
import com.avairebot.modlog.ModlogAction;
import com.avairebot.modlog.ModlogType;
import com.avairebot.mute.automute.MuteRatelimit;
import com.avairebot.shared.DiscordConstants;
import com.avairebot.utilities.ArrayUtil;
import com.avairebot.utilities.RestActionUtil;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.message.GenericMessageEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.MessageUpdateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.sql.SQLException;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class MessageEventAdapter extends EventAdapter {

    public static final Set <Long> hasReceivedInfoMessageInTheLastMinute = new HashSet <>();
    ArrayList <String> guilds = Constants.guilds;

    private static final ExecutorService commandService = Executors.newCachedThreadPool(
        new ThreadFactoryBuilder()
            .setNameFormat("avaire-command-thread-%d")
            .build()
    );

    private static final Logger log = LoggerFactory.getLogger(MessageEventAdapter.class);
    private static final Pattern userRegEX = Pattern.compile("<@(!|)+[0-9]{16,}+>", Pattern.CASE_INSENSITIVE);
    private static final String mentionMessage = String.join("\n", Arrays.asList(
        "Hi there! I'm **%s**, a multipurpose Discord bot built for fun by %s!",
        "You can see what commands I have by using the `%s` command.",
        "",
        "My original source was from the bot: **Avaire** and has been modified",
        "by %s for Pinewood Builders.",
        "",
        "I am currently running **Kronos v%s**"
    ));

    /**
     * Instantiates the event adapter and sets the avaire class instance.
     *
     * @param avaire The AvaIre application class instance.
     */
    public MessageEventAdapter(AvaIre avaire) {
        super(avaire);
    }

    public void onFeedbackMessageEvent(MessageReceivedEvent e) {
        e.getMessage().addReaction("\uD83D\uDC4D").queue();
        e.getMessage().addReaction("\uD83D\uDC4E").queue();
    }

    public void onMessageReceived(MessageReceivedEvent event) {
        if (!isValidMessage(event.getAuthor())) {
            return;
        }

        if (event.getChannelType().isGuild() && !event.getTextChannel().canTalk()) {
            return;
        }

        if (avaire.getBlacklist().isBlacklisted(event.getMessage())) {
            return;
        }

        loadDatabasePropertiesIntoMemory(event).thenAccept(databaseEventHolder -> {
            if (databaseEventHolder.getGuild() != null && databaseEventHolder.getPlayer() != null) {
                avaire.getLevelManager().rewardPlayer(event, databaseEventHolder.getGuild(), databaseEventHolder.getPlayer());
            }

            CommandContainer container = CommandHandler.getCommand(avaire, event.getMessage(), event.getMessage().getContentRaw());
            if (container != null && canExecuteCommand(event, container)) {
                invokeMiddlewareStack(new MiddlewareStack(event.getMessage(), container, databaseEventHolder));
                return;
            }

            if (isMentionableAction(event)) {
                container = CommandHandler.getLazyCommand(ArrayUtil.toArguments(event.getMessage().getContentRaw())[1]);
                if (container != null && canExecuteCommand(event, container)) {
                    invokeMiddlewareStack(new MiddlewareStack(event.getMessage(), container, databaseEventHolder, true));
                    return;
                }

                if (avaire.getIntelligenceManager().isEnabled()) {
                    if (isAIEnabledForChannel(event, databaseEventHolder.getGuild())) {
                        avaire.getIntelligenceManager().handleRequest(
                            event.getMessage(), databaseEventHolder
                        );
                    }
                    return;
                }
            }

            if (isSingleBotMention(event.getMessage().getContentRaw().trim())) {
                sendTagInformationMessage(event);
                return;
            }

            if (!event.getChannelType().isGuild()) {
                sendInformationMessage(event);
            }


            if (checkFilter(event.getMessage().getContentRaw())) {
                if (databaseEventHolder.getGuild().getOnWatchRole() != null) {
                    Role watchRole = event.getGuild().getRoleById(databaseEventHolder.getGuild().getOnWatchRole());
                    if (event.getMember().getRoles().contains(watchRole)) {
                        event.getMessage().delete().queue();
                    }
                }
            }

        });

    }

    private boolean checkWildcardFilter(String contentStripped, GuildTransformer guild, Message messageId) {
        String words = contentStripped.toLowerCase();
        List <String> badWordsList = replace(guild.getBadWordsWildcard());
        // system.out.println("UFWords: " + words);
        // system.out.println("FWords: " + badWordsList);

        for (String word : badWordsList) {
            if (words.contains(word)) {
                warnUser(messageId, guild, "**AUTOMOD**: Filter was activated!\n**Type**: " + "``WILDCARD``\n**Word Filtered**: " + word);
                return true;
            }
        }
        return false;
    }

    private boolean checkExactFilter(String contentRaw, GuildTransformer databaseEventHolder, Message messageId) {
        // system.out.println("FILTER ENABLED");
        List <String> words = replace(Arrays.asList(contentRaw.split(" ")));
        List <String> badWordsList = replace(databaseEventHolder.getBadWordsExact());

        // system.out.println("UWords: " + words);
        // system.out.println("FWords: " + badWordsList);

        boolean b = words.stream().anyMatch(badWordsList::contains);
        if (b) {
            warnUser(messageId, databaseEventHolder, "**AUTOMOD**: Filter was activated!\n**Type**: " + "``EXACT``\n**Sentence Filtered**: \n" + contentRaw);
        }
        return b;
    }

    private boolean checkGlobalWildcardFilter(String contentStripped, GuildTransformer guild, Message messageId) {
        String words = contentStripped.toLowerCase();
        List <String> badWordsList = replace(guild.getPIAWordsWildcard());
        //System.out.println("UFWords: " + words);
        //System.out.println("FWords: " + badWordsList);

        for (String word : badWordsList) {
            if (words.contains(word)) {
                warnUserColor(messageId, guild, "**GLOBAL AUTOMOD**: Global Filter was activated!\n**Type**: " + "``WILDCARD``\n**Sentance Filtered**: " + contentStripped, new Color(0, 0, 0));
                return true;
            }
        }
        return false;
    }

    private boolean checkGlobalExactFilter(String contentRaw, GuildTransformer databaseEventHolder, Message messageId) {
        // system.out.println("FILTER ENABLED");
        List <String> words = replace(Arrays.asList(contentRaw.split(" ")));
        List <String> badWordsList = replace(databaseEventHolder.getPIAWordsExact());

        // system.out.println("UWords: " + words);
        // system.out.println("FWords: " + badWordsList);

        boolean b = words.stream().anyMatch(badWordsList::contains);
        if (b) {
            warnUserColor(messageId, databaseEventHolder, "**GLOBAL AUTOMOD**: Global Filter was activated!\n**Type**: " + "``EXACT``\n**Sentence Filtered**: \n" + contentRaw, new Color(0, 0, 0));
        }
        return b;
    }

    public static List <String> replace(List <String> strings) {
        ListIterator <String> iterator = strings.listIterator();
        while (iterator.hasNext()) {
            iterator.set(iterator.next().toLowerCase());
        }
        return strings;
    }

    private boolean checkFilter(String m) {
        return m.contains("https://") || m.contains("http://") || m.startsWith("porn") || m.contains("www.") || m.contains(".com") || m.contains(".nl") || m.contains(".net") ||
            m.startsWith("http") || m.startsWith("https") || m.contains("http//") || m.contains("https//") || m.matches("[-a-zA-Z0-9@:%._\\+~#=]{1,256}\\.[a-zA-Z0-9()]{1,6}\\b([-a-zA-Z0-9()@:%_\\+.~#?&//=]*)") || m.contains("%E2");
    }

    public void onGuildMessageUpdate(MessageUpdateEvent event) {
        loadDatabasePropertiesIntoMemory(event).thenAccept(databaseEventHolder -> {
            if (checkFilter(event.getMessage().getContentRaw())) {
                if (databaseEventHolder.getGuild().getOnWatchRole() != null) {
                    Role watchRole = event.getGuild().getRoleById(databaseEventHolder.getGuild().getOnWatchRole());
                    if (event.getMember().getRoles().contains(watchRole)) {
                        event.getMessage().delete().queue();
                    }
                }
            }
        });
    }


    public void onLocalFilterMessageReceived(MessageReceivedEvent event) {
        loadDatabasePropertiesIntoMemory(event).thenAccept(databaseEventHolder -> {
            if (Constants.guilds.contains(event.getGuild().getId())) {
                checkFilters(event, databaseEventHolder);
            }
        });
    }



    public void onLocalFilterEditReceived(MessageUpdateEvent event) {
        loadDatabasePropertiesIntoMemory(event).thenAccept(databaseEventHolder -> {
            if (Constants.guilds.contains(event.getGuild().getId())) {
                checkFilters(event, databaseEventHolder);
            }
        });
    }

    public void onGlobalFilterMessageReceived(MessageReceivedEvent event) {
        if (Constants.guilds.contains(event.getGuild().getId())) {
            loadDatabasePropertiesIntoMemory(event).thenAccept(databaseEventHolder -> {
                    checkPublicFilter(event, databaseEventHolder);
                }
            );
        }
    }

    public void onGlobalFilterEditReceived(MessageUpdateEvent event) {
        if (Constants.guilds.contains(event.getGuild().getId())) {
            loadDatabasePropertiesIntoMemory(event).thenAccept(databaseEventHolder -> {
                checkPublicFilter(event, databaseEventHolder);
            });
        }
    }

    private void checkPublicFilter(GenericMessageEvent genericMessageEvent, DatabaseEventHolder databaseEventHolder) {
        Message event = getActualMessage(genericMessageEvent);

        if (!event.getChannelType().equals(ChannelType.TEXT)) {
            return;
        }


        if (!guilds.contains(event.getGuild().getId())) {
            return;
        }


        GuildTransformer guild = databaseEventHolder.getGuild();
        if (guild != null) {

            if (event.getMember().hasPermission(Permission.BAN_MEMBERS)) {
                return;
            }
            String message = event.getContentStripped().replaceAll("[!@#$%^&*()\\[\\]\\-=';/\\\\{}:\"><?|+_`~]", " ");
            if (checkGlobalExactFilter(message, guild, event)) {
                System.out.println("Exact Filter removed: " + message);
                event.delete().queue();
                MuteRatelimit.hit(ThrottleMiddleware.ThrottleType.USER, event.getAuthor().getIdLong(), event.getGuild(), event);
                return;
            } else if (checkGlobalWildcardFilter(message, guild, event)) {
                System.out.println("Wildcard Filter removed: " + message);
                event.delete().queue();
                MuteRatelimit.hit(ThrottleMiddleware.ThrottleType.USER, event.getAuthor().getIdLong(), event.getGuild(), event);
                return;
            }
            checkPIAInviteFilter(event, databaseEventHolder, event);
        } else {
            System.out.println("Guild is null");
        }
    }

    private Message getActualMessage(GenericMessageEvent genericMessageEvent) {
        if (genericMessageEvent instanceof MessageReceivedEvent) {
            return ((MessageReceivedEvent) genericMessageEvent).getMessage();
        } else {
            return ((MessageUpdateEvent) genericMessageEvent).getMessage();
        }
    }


    private void checkFilters(GenericMessageEvent event, DatabaseEventHolder databaseEventHolder) {
        MessageReceivedEvent messageId = (MessageReceivedEvent) event;
        if (!guilds.contains(event.getGuild().getId())) {
            return;
        }

        if (!event.getChannelType().equals(ChannelType.TEXT)) {
            return;
        }

        GuildTransformer guild = databaseEventHolder.getGuild();
        if (guild != null) {

            if (!guild.isFilter()) {
                // system.out.println("Filter disabled");
                return;
            }

            if (messageId.getMember().hasPermission(Permission.MESSAGE_MANAGE)) {
                return;
            }

            String message = messageId.getMessage().getContentStripped().replaceAll("[,.!@#$%^&*()\\[\\]\\-=';/\\\\{}:\"><?|+_`~]", "");
            if (checkExactFilter(message, guild, messageId.getMessage())) {
                // system.out.println("Exact Filter removed: " + message);
                messageId.getMessage().delete().queue();

            } else if (checkWildcardFilter(message, guild, messageId.getMessage())) {
                // system.out.println("Wildcard Filter removed: " + message);
                messageId.getMessage().delete().queue();

            }
        }
    }

    private void checkPIAInviteFilter(Message message, DatabaseEventHolder databaseEventHolder, Message event) {
        for (String i : message.getInvites()) {
            Invite.resolve(message.getJDA(), i).queue(v -> {
                if (!Constants.guilds.contains(v.getGuild().getId())) {
                    message.delete().queue();
                    warnUserColor(message, databaseEventHolder.getGuild(), "**AUTOMOD**: Filter was activated!\n**Type**: " + "``INVITE``\n" +
                        "**Guild**: " + v.getGuild().getName() + "\n" +
                        "**Invite**: [Click here!](" + v.getUrl() + ")\n" +
                        "**Inviter**:" + v.getInviter(), new Color(0, 0, 0));
                    MuteRatelimit.hit(ThrottleMiddleware.ThrottleType.USER, message.getAuthor().getIdLong(), message.getGuild(), event);
                }
            });
        }
    }

    private boolean isValidMessage(User author) {
        return !author.isBot() || author.getIdLong() == DiscordConstants.SENITHER_BOT_ID;
    }

    private void warnUser(Message m, GuildTransformer databaseEventHolder, String reason) {

        if (databaseEventHolder.getModlog() == null) {
            return;
        }


        ModlogAction modlogAction = new ModlogAction(
            ModlogType.WARN,
            m.getJDA().getSelfUser(),
            m.getAuthor(),
            reason
        );

        String caseId = Modlog.log(avaire, m.getGuild(), databaseEventHolder, modlogAction);

        if (caseId == null) {
            return;
        }

        Modlog.notifyUser(m.getAuthor(), m.getGuild(), modlogAction, caseId);
    }

    private void warnUserColor(Message m, GuildTransformer databaseEventHolder, String reason, Color color) {

        if (databaseEventHolder.getModlog() == null) {
            return;
        }


        ModlogAction modlogAction = new ModlogAction(
            ModlogType.WARN,
            m.getJDA().getSelfUser(),
            m.getAuthor(),
            reason
        );

        String caseId = Modlog.logcolor(avaire, m.getGuild(), databaseEventHolder, modlogAction, color);

        if (caseId == null) {
            return;
        }

        Modlog.notifyUser(m.getAuthor(), m.getGuild(), modlogAction, caseId, color);
    }

    private void invokeMiddlewareStack(MiddlewareStack stack) {
        commandService.submit(stack::next);
    }

    private boolean canExecuteCommand(MessageReceivedEvent event, CommandContainer container) {
        if (!container.getCommand().isAllowedInDM() && !event.getChannelType().isGuild()) {
            MessageFactory.makeWarning(event.getMessage(), "<a:alerta:729735220319748117> You can not use this command in direct messages!").queue();
            return false;
        }
        return true;
    }

    private boolean isMentionableAction(MessageReceivedEvent event) {
        if (!event.getMessage().isMentioned(avaire.getSelfUser())) {
            return false;
        }

        String[] args = event.getMessage().getContentRaw().split(" ");
        return args.length >= 2 &&
            userRegEX.matcher(args[0]).matches() &&
            event.getMessage().getMentionedUsers().get(0).getId().equals(avaire.getSelfUser().getId());

    }

    private boolean isSingleBotMention(String rawContent) {
        return rawContent.equals("<@" + avaire.getSelfUser().getId() + ">") ||
            rawContent.equals("<@!" + avaire.getSelfUser().getId() + ">");
    }

    private boolean isAIEnabledForChannel(MessageReceivedEvent event, GuildTransformer transformer) {
        if (transformer == null) {
            return true;
        }

        ChannelTransformer channel = transformer.getChannel(event.getChannel().getId());
        return channel == null || channel.getAI().isEnabled();
    }

    private void sendTagInformationMessage(MessageReceivedEvent event) {
        String author = "**Senither#0001**";
        String editor = "**Stefano#7366**";
        if (event.getMessage().getChannelType().isGuild() && event.getGuild().getMemberById(173839105615069184L) != null) {
            editor = "<@173839105615069184>";
        }

        MessageFactory.makeEmbeddedMessage(event.getMessage().getChannel(), Color.decode("#E91E63"), String.format(mentionMessage,
            avaire.getSelfUser().getName(),
            author,

            CommandHandler.getLazyCommand("help").getCommand().generateCommandTrigger(event.getMessage()),
            editor,
            AppInfo.getAppInfo().version
        ))
            .setFooter("This message will be automatically deleted in one minute.")
            .queue(message -> message.delete().queueAfter(1, TimeUnit.MINUTES, null, RestActionUtil.ignore));
    }

    @SuppressWarnings("ConstantConditions")
    private void sendInformationMessage(MessageReceivedEvent event) {
        log.info("Private message received from user(ID: {}) that does not match any commands!",
            event.getAuthor().getId()
        );

        if (hasReceivedInfoMessageInTheLastMinute.contains(event.getAuthor().getIdLong())) {
            return;
        }

        hasReceivedInfoMessageInTheLastMinute.add(event.getAuthor().getIdLong());

        try {
            ArrayList <String> strings = new ArrayList <>();
            strings.addAll(Arrays.asList(
                "To invite me to your server, use this link:",
                "*:oauth*",
                "",
                "You can use `{0}help` to see a list of all the categories of commands.",
                "You can use `{0}help category` to see a list of commands for that category.",
                "For specific command help, use `{0}help command` (for example `{0}help {1}{2}`,\n`{0}help {2}` also works)"
            ));

            if (avaire.getIntelligenceManager().isEnabled()) {
                strings.add("\nYou can tag me in a message with <@:botId> to send me a message that I should process using my AI.");
            }

            strings.add("\n**Full list of commands**\n*https://avairebot.com/commands*");
            strings.add("\nAvaIre Support Server:\n*https://avairebot.com/support*");

            CommandContainer commandContainer = CommandHandler.getCommands().stream()
                .filter(container -> !container.getCategory().isGlobalOrSystem())
                .findAny()
                .get();

            /*MessageFactory.makeEmbeddedMessage(event.getMessage(), Color.decode("#E91E63"), I18n.format(
                String.join("\n", strings),
                CommandHandler.getCommand(HelpCommand.class).getCategory().getPrefix(event.getMessage()),
                commandContainer.getCategory().getPrefix(event.getMessage()),
                commandContainer.getTriggers().iterator().next()
            ))
                .set("oauth", avaire.getConfig().getString("discord.oauth"))
                .set("botId", avaire.getSelfUser().getId())
                .queue();*/
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private CompletableFuture <DatabaseEventHolder> loadDatabasePropertiesIntoMemory(final MessageReceivedEvent event) {
        return CompletableFuture.supplyAsync(() -> {
            if (!event.getChannelType().isGuild()) {
                return new DatabaseEventHolder(null, null);
            }

            GuildTransformer guild = GuildController.fetchGuild(avaire, event.getMessage());

            if (guild == null || !guild.isLevels() || event.getAuthor().isBot()) {
                return new DatabaseEventHolder(guild, null);
            }
            return new DatabaseEventHolder(guild, PlayerController.fetchPlayer(avaire, event.getMessage()));
        });
    }

    private CompletableFuture <DatabaseEventHolder> loadDatabasePropertiesIntoMemory(final MessageUpdateEvent event) {
        return CompletableFuture.supplyAsync(() -> {
            if (!event.getChannelType().isGuild()) {
                return new DatabaseEventHolder(null, null);
            }

            GuildTransformer guild = GuildController.fetchGuild(avaire, event.getMessage());

            if (guild == null || !guild.isLevels() || event.getAuthor().isBot()) {
                return new DatabaseEventHolder(guild, null);
            }
            return new DatabaseEventHolder(guild, PlayerController.fetchPlayer(avaire, event.getMessage()));
        });
    }

    public void onMessageDelete(TextChannel channel, List <String> messageIds) {
        Collection reactions = ReactionController.fetchReactions(avaire, channel.getGuild());
        if (reactions == null || reactions.isEmpty()) {
            return;
        }

        List <String> removedReactionMessageIds = new ArrayList <>();
        for (DataRow row : reactions) {
            for (String messageId : messageIds) {
                if (Objects.equals(row.getString("message_id"), messageId)) {
                    removedReactionMessageIds.add(messageId);
                }
            }
        }

        if (removedReactionMessageIds.isEmpty()) {
            return;
        }

        QueryBuilder builder = avaire.getDatabase().newQueryBuilder(Constants.REACTION_ROLES_TABLE_NAME);
        for (String messageId : removedReactionMessageIds) {
            builder.orWhere("message_id", messageId);
        }

        try {
            builder.delete();

            ReactionController.forgetCache(
                channel.getGuild().getIdLong()
            );
        } catch (SQLException e) {
            log.error("Failed to delete {} reaction messages for the guild with an ID of {}",
                removedReactionMessageIds.size(), channel.getGuild().getId(), e
            );
        }
    }

    public void onMessageUpdate(MessageUpdateEvent event) {
        if (!event.isFromGuild()) {
            return;
        }

        Collection reactions = ReactionController.fetchReactions(avaire, event.getGuild());
        if (reactions == null) {
            return;
        }

        if (reactions.where("message_id", event.getMessage().getId()).isEmpty()) {
            return;
        }

        try {
            String messageContent = event.getMessage().getContentStripped();
            if (messageContent.trim().length() == 0 && !event.getMessage().getEmbeds().isEmpty()) {
                messageContent = event.getMessage().getEmbeds().get(0).getDescription();
            }

            String finalMessageContent = messageContent;
            avaire.getDatabase().newQueryBuilder(Constants.REACTION_ROLES_TABLE_NAME)
                .where("guild_id", event.getGuild().getId())
                .where("message_id", event.getMessage().getId())
                .update(statement -> {
                    statement.set("snippet", finalMessageContent.substring(
                        0, Math.min(finalMessageContent.length(), 64)
                    ), true);
                });

            ReactionController.forgetCache(event.getGuild().getIdLong());
        } catch (SQLException e) {
            log.error("Failed to update the reaction role message with a message ID of {}, error: {}",
                event.getMessage().getId(), e.getMessage(), e
            );
        }
    }

    public void onPBFeedbackPinEvent(MessageReceivedEvent event) {
        if (event.getMessage().getType().equals(MessageType.CHANNEL_PINNED_ADD)) {
            event.getMessage().delete().queue();
        }
    }
}

