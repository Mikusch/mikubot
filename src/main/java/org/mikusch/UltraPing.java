package org.mikusch;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class UltraPing extends ListenerAdapter {

    private static final String ULTRAPING_COMMAND_NAME = "ultraping";
    private static final String ULTRAPING_USER_PARAM_NAME = "user";

    private final JDA jda;

    @Autowired
    public UltraPing(JDA jda) {
        this.jda = jda;
        jda.addEventListener(this);
    }

    @PostConstruct
    public void postConstruct() {
        jda.getGuilds()
                .forEach(guild -> guild.upsertCommand(
                                ULTRAPING_COMMAND_NAME,
                                "Mentions a user in every channel, deleting each message immediately")
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR))
                        .addOption(OptionType.USER, ULTRAPING_USER_PARAM_NAME, "The user to ping", true)
                        .queue());
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (!event.getName().equals(ULTRAPING_COMMAND_NAME)) {
            return;
        }

        event.deferReply(true).queue(hook -> {
            Guild guild = event.getGuild();
            Member member = event.getMember();
            if (guild == null || member == null) {
                hook.editOriginal("This command can only be used in a server.").queue();
                return;
            }

            User target = event.getOption(ULTRAPING_USER_PARAM_NAME, OptionMapping::getAsUser);
            if (target == null) {
                hook.editOriginal("You must supply a user to ping.").queue();
                return;
            }

            ultraPing(hook, guild, target);
        });
    }

    private void ultraPing(@NotNull InteractionHook hook, @NotNull Guild guild, @NotNull User target) {
        Member selfMember = guild.getSelfMember();

        List<GuildMessageChannel> channels = Stream.concat(
                        guild.getChannels().stream(), guild.getThreadChannels().stream())
                .filter(GuildMessageChannel.class::isInstance)
                .map(GuildMessageChannel.class::cast)
                .filter(channel -> !(channel instanceof ThreadChannel thread) || !thread.isArchived())
                .filter(channel -> channel.canTalk(selfMember))
                .toList();

        if (channels.isEmpty()) {
            hook.editOriginal("There are no channels I can send messages in.").queue();
            return;
        }

        String mention = target.getAsMention();
        AtomicInteger pinged = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        List<CompletableFuture<Void>> futures = new ArrayList<>(channels.size());
        for (GuildMessageChannel channel : channels) {
            futures.add(channel.sendMessage(mention)
                    .mentionUsers(target.getIdLong())
                    .flatMap(Message::delete)
                    .submit()
                    .orTimeout(30, TimeUnit.SECONDS)
                    .whenComplete((ignored, error) -> {
                        if (error != null) {
                            failed.incrementAndGet();
                            log.warn("/ultraping failed in channel {} ({})", channel.getName(), channel.getId(), error);
                        } else {
                            pinged.incrementAndGet();
                        }
                    }));
        }

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, _) -> hook.editOriginal("Ultra-pinged %s across %d channel(s)%s."
                                .formatted(
                                        mention,
                                        pinged.get(),
                                        failed.get() > 0 ? " (%d failed)".formatted(failed.get()) : ""))
                        .queue());
    }
}
