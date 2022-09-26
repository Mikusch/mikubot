package org.mikusch;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimaps;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.StatusChangeEvent;
import net.dv8tion.jda.api.events.channel.ChannelDeleteEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceJoinEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceLeaveEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceMoveEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

import java.util.EnumSet;

@Component
public class TempChannelListener extends ListenerAdapter {

    private static final EnumSet<Permission> CHANNEL_AUTHOR_PERMISSIONS_ALLOW = EnumSet.of(
            Permission.MANAGE_CHANNEL,
            Permission.PRIORITY_SPEAKER,
            Permission.VOICE_SPEAK,
            Permission.VOICE_MOVE_OTHERS,
            Permission.VOICE_USE_VAD,
            Permission.VOICE_STREAM,
            Permission.VOICE_START_ACTIVITIES
    );

    // ListMultimap<Guild ID, Voice Channel ID>
    private final ListMultimap<Long, Long> activeChannels = Multimaps.synchronizedListMultimap(ArrayListMultimap.create());

    public TempChannelListener(JDA jda) {
        jda.addEventListener(this);
    }

    private static String getChannelName(Member owner) {
        String name = owner.getUser().getName();
        if ("s".equalsIgnoreCase(StringUtils.right(name, 1))) {
            name += "'";
        } else {
            name += "'s";
        }

        return name + " Channel";
    }

    @Override
    public void onGuildVoiceJoin(@NotNull GuildVoiceJoinEvent event) {
        if (event.getChannelJoined().getType() == ChannelType.VOICE) {
            VoiceChannel channelJoined = event.getChannelJoined().asVoiceChannel();
            Member member = event.getMember();

            // Create a new channel if the user joins a specially named voice channel
            if (!activeChannels.containsValue(channelJoined.getIdLong()) && channelJoined.getName().toLowerCase().contains("new channel")) {
                channelJoined.createCopy()
                        .addPermissionOverride(member, CHANNEL_AUTHOR_PERMISSIONS_ALLOW, EnumSet.noneOf(Permission.class))
                        .reason("Creating new temporary channel for " + member.getUser().getAsTag())
                        .queue(vc -> {
                            activeChannels.put(event.getGuild().getIdLong(), vc.getIdLong());
                            vc.getManager().setName(getChannelName(member)).queue();
                            event.getGuild().moveVoiceMember(member, vc).queue();
                        });
            }
        }
    }

    @Override
    public void onGuildVoiceLeave(@NotNull GuildVoiceLeaveEvent event) {
        if (activeChannels.containsValue(event.getChannelLeft().getIdLong())) {
            onMemberLeaveAutoChannel(event.getChannelLeft().asVoiceChannel());
        }
    }

    @Override
    public void onGuildVoiceMove(@NotNull GuildVoiceMoveEvent event) {
        if (activeChannels.containsValue(event.getChannelLeft().getIdLong())) {
            onMemberLeaveAutoChannel(event.getChannelLeft().asVoiceChannel());
        }
    }

    private void onMemberLeaveAutoChannel(VoiceChannel vc) {
        if (vc.getMembers().isEmpty()) {
            vc.delete().reason("All members have left the channel").queue();
        }
    }

    @Override
    public void onChannelDelete(@NotNull ChannelDeleteEvent event) {
        if (activeChannels.containsValue(event.getChannel().getIdLong())) {

            activeChannels.remove(event.getGuild().getIdLong(), event.getChannel().getIdLong());
        }
    }

    @Override
    public void onStatusChange(@NotNull StatusChangeEvent event) {
        // Delete all temporary channels before shutting down
        if (event.getNewStatus() == JDA.Status.SHUTTING_DOWN) {
            activeChannels.forEach((guildId, channelId) -> {
                Guild guild = event.getJDA().getGuildById(guildId);
                if (guild != null) {
                    VoiceChannel vc = guild.getVoiceChannelById(channelId);
                    if (vc != null) {
                        vc.delete().reason("Bot is shutting down").complete();
                    }
                }
            });
        }
    }
}
