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

package com.avairebot.mute.automute;

import com.avairebot.AvaIre;
import com.avairebot.contracts.blacklist.PunishmentLevel;
import com.avairebot.database.controllers.GuildController;
import com.avairebot.database.transformers.GuildTransformer;
import com.avairebot.factories.MessageFactory;
import com.avairebot.middleware.ThrottleMiddleware;
import com.avairebot.modlog.Modlog;
import com.avairebot.modlog.ModlogAction;
import com.avairebot.modlog.ModlogType;
import com.avairebot.time.Carbon;
import com.avairebot.utilities.CacheUtil;
import com.avairebot.utilities.RestActionUtil;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.message.GenericMessageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.awt.*;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class MuteRatelimit {

    /**
     * This represents that amount of times the rate limit can
     * be hit within the {@link #hitTime ratelimit timeframe}
     * before the user is auto muted.
     */
    static final int hitLimit = 10;

    /**
     * This represents the amount of time in milliseconds that each
     * hit should be valid for, if all hit slots for a given user
     * is taken up, but one of the times exceeds the hit time
     * limit, the rate limit won't count it as exceeding
     * the rate limit.
     */
    static final long hitTime = 300 * 1000;

    /**
     * The cache loader for holding all the ratelimiter rates.
     */
    public static final LoadingCache<Long, Rate> cache = CacheBuilder.newBuilder()
        .recordStats()
        .expireAfterWrite(hitTime, TimeUnit.MILLISECONDS)
        .build(CacheLoader.from(Rate::new));

    /**
     * The slf4j logger instance.
     */
    private static final Logger log = LoggerFactory.getLogger(MuteRatelimit.class);

    /**
     * The punishment level holder, this map holds all the users and their current
     * punishment level, with each offence, the punishment level(value) will go
     * up, increasing the time the user get auto-blacklisted for.
     */
    private static final Map<Long, Integer> punishments = new HashMap<>();

    /**
     * The punishment levels, each index of the levels list should be an
     * increasingly harsher punishment for repeating offenders.
     */
    private static final List<PunishmentLevel> levels = Arrays.asList(
        () -> Carbon.now().addMinutes(15),
        () -> Carbon.now().addMinutes(30),
        () -> Carbon.now().addHour(),
        () -> Carbon.now().addHours(6),
        () -> Carbon.now().addHours(12),
        () -> Carbon.now().addDay(),
        () -> Carbon.now().addDays(3),
        () -> Carbon.now().addDays(7),
        () -> Carbon.now().addDays(14),
        () -> Carbon.now().addDays(21),
        () -> Carbon.now().addDays(28),
        () -> Carbon.now().addMonth()
    );


    /**
     * Sends the blacklist message to the given use in a direct
     * message to let the user know that they have been added
     * to the blacklist automatically.
     *
     * @param user    The user that was blacklisted.
     * @param expires The carbon time instance for when the blacklist expires.
     */
    public static void sendMuteMessage(User user, Carbon expires) {
        user.openPrivateChannel().queue(channel -> {
            channel.sendMessage(MessageFactory.createEmbeddedBuilder()
                .setColor(Color.decode("#A5306B"))
                .setTitle("Whoa there!", "https://avairebot.com/")
                .setFooter("Expires", null)
                .setTimestamp(expires.getTime().toInstant())
                .setDescription("Looks like you're triggering my filter a bit too fast, I've muted you "
                    + "on the discord that you have done this.\n"
                    + "Your mute expires in " + expires.addSecond().diffForHumans(true) + ", "
                    + "keep in mind repeating the behavior will get you muted for longer "
                    + "periods of time, eventually if you keep it up you will be permanently"
                ).build()
            ).queue();
        }, RestActionUtil.ignore);
    }

    /**
     * Sends the blacklist message to the given channel to let
     * the user/guild know that they have been added to the
     * blacklist automatically.
     *
     * @param channel The channel that the message should be sent to.
     * @param expires The carbon time instance for when the blacklist expires.
     */
    public void sendBlacklistMessage(MessageChannel channel, Carbon expires) {
        channel.sendMessage(MessageFactory.createEmbeddedBuilder()
            .setColor(Color.decode("#A5306B"))
            .setTitle("Whoa there!", "https://avairebot.com/")
            .setFooter("Expires", null)
            .setTimestamp(expires.getTime().toInstant())
            .setDescription("Looks like people on the server are using commands a bit too fast, "
                + "I've banned the server from using any commands, or earning any XP until everyone "
                + "clams down a bit.\n"
                + "The ban expires in " + expires.addSecond().diffForHumans(true) + ", "
                + "keep in mind repeating the behavior will get the server banned for longer "
                + "periods of time, eventually if you keep it up the server will be banned "
                + "from using any of my commands permanently."
            ).build()
        ).queue();
    }

    /**
     * His the blacklisting ratelimit, the type will determine if the
     * user or the guild with the given ID will hit the blacklist.
     * <p>
     * If the given ID has reached the  maximum number of hits within
     * the allowed timeframe, entity with the given ID will be auto
     * blacklisted for a certain amount of time, the time the
     * entity is blacklisted for depends on how many earlier
     * offense they have, all the punishment levels can be
     * seen in the {@link #levels punishments array}.
     *
     * @param type The type of throttle request that hit the mutelist.
     * @param id   The ID of the user or guild that should hit the ratelimit.
     * @param e
     * @return Possibly-null, the time object matching when the blacklist expires,
     *         or <code>null</code> if the user was not blacklisted.
     */
    @Nullable
    public static Carbon hit(ThrottleMiddleware.ThrottleType type, long id, Guild g, Message e) {
        Rate rate = CacheUtil.getUncheckedUnwrapped(cache, id);
        if (rate == null) {
            // This should never happen, if it does we'll just return
            // null to not block any commands by a valid user.
            return null;
        }

        //noinspection SynchronizationOnLocalVariableOrMethodParameter
        synchronized (rate) {
            rate.hit();

            if (rate.getHits() < hitLimit) {
                return null;
            }
        }

        Long last = rate.getLast();

        // Checks if the user was blacklisted within the last two and half seconds,
        // the command handling process uses its own thread pool, because of that
        // it's possible to have two commands come in from the same user in a
        // very quick succession, instead of punishing the user twice, we
        // just cancel the blacklist hit here instead.
        if (last != null && last < System.currentTimeMillis() - 2500) {
            return null;
        }

        Carbon punishment = getPunishment(id);

        log.info("{}:{} has been added to the mutelits for excessive filter triggers, the mute expires {}.",
            type.getName(), id, punishment.toDayDateTimeString()
        );

        muteUser(g, punishment, e);

        return punishment;
    }

    private static void muteUser(Guild g, Carbon punishment, Message context) {


        GuildTransformer transformer = GuildController.fetchGuild(AvaIre.getInstance(), g);
        if (transformer == null) {
            return;
        }
        if (transformer.getModlog() == null) {
            return;
        }
        if (transformer.getMuteRole() == null) {
            return;
        }

        Role muteRole = g.getRoleById(transformer.getMuteRole());
        if (muteRole == null) {
            return;
        }

        ModlogType type = punishment.getTime() == null ? ModlogType.MUTE : ModlogType.TEMP_MUTE;

        final Carbon finalExpiresAt = punishment;
        context.getGuild().addRoleToMember(
            context.getMember(), muteRole
        ).reason("You have triggered the automod way to fast").queue(aVoid -> {
            ModlogAction modlogAction = new ModlogAction(
                type, context.getAuthor(), context.getAuthor(),
                finalExpiresAt != null
                    ? finalExpiresAt.toDayDateTimeString() + " (" + finalExpiresAt.diffForHumans(true) + ")" + "\n" + "You have triggered the automod way to fast"
                    : "\n" + "You have triggered the automod way to fast"
            );

            String caseId = Modlog.log(AvaIre.getInstance(), context, modlogAction);
            MuteRatelimit.sendMuteMessage(context.getAuthor(), punishment);

            try {
                AvaIre.getInstance().getMuteManger().registerMute(caseId, context.getGuild().getIdLong(), context.getAuthor().getIdLong(), finalExpiresAt);
            } catch (SQLException e) {
                AvaIre.getLogger().error(e.getMessage(), e);
            }
        });
    }

    /**
     * Gets the punishment for the given user ID, as well as
     * increasing their punishment level in the process.
     *
     * @param userId The ID of the user that the punishment should be fetched for.
     * @return The Carbon instance with the punishment expire time.
     */
    private static Carbon getPunishment(long userId) {
        int level = punishments.getOrDefault(userId, -1) + 1;

        punishments.put(userId, level);

        return getPunishment(level);
    }

    /**
     * Gets the {@link Carbon} punishment object, representing
     * the time that the punishment should expire.
     *
     * @param level The level to get the punishment for.
     * @return The Carbon instance with the punishment expire time.
     */
    private static Carbon getPunishment(int level) {
        if (level < 0) {
            return levels.get(0).generateTime();
        }
        return levels.get(level >= levels.size() ? levels.size() - 1 : level).generateTime();
    }
}
