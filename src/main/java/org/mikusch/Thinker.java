package org.mikusch;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageBulkDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.MarkdownUtil;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.internal.requests.CompletedRestAction;
import org.jetbrains.annotations.NotNull;
import org.mikusch.entity.ThinkerMessage;
import org.mikusch.repository.ThinkerMessageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

@Service
@Slf4j
public class Thinker extends ListenerAdapter {

    private static final String THINKER_COMMAND_NAME = "thinker";
    private static final String THINKER_TRIGGER_COMMAND_NAME = "thinkertrigger";
    private static final String THINKER_COLLECT_COMMAND_NAME = "thinkercollect";
    private static final String THINKER_COMMAND_MESSAGE_PARAM_NAME = "message";
    private static final String THINKER_TRIGGER_COMMAND_TRIGGER_PARAM_NAME = "trigger";

    private final AtomicReference<List<String>> thinkerChatTriggers = new AtomicReference<>(Collections.emptyList());
    private final AtomicReference<OffsetDateTime> lastPostedTime = new AtomicReference<>(OffsetDateTime.now());
    private final ReentrantLock sendLock = new ReentrantLock();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private final JDA jda;
    private final ThinkerMessageRepository messageRepository;
    private IncomingWebhookClient client;

    @Value("${mikubot.guild.id}")
    private long guildId;

    @Value("${thinker.webhook.id}")
    private long webhookId;

    @Value("${thinker.duration.multiplier:20}")
    private long durationMultiplier;

    @Autowired
    public Thinker(JDA jda, ThinkerMessageRepository messageRepository, @Value("${thinker.chat.triggers:#{null}}") String thinkerChatTriggers) {
        this.jda = jda;
        this.messageRepository = messageRepository;
        jda.addEventListener(this);

        this.thinkerChatTriggers.set(Optional.ofNullable(thinkerChatTriggers)
                .map(triggers -> Arrays.asList(triggers.split(",")))
                .orElse(Collections.emptyList()));
    }

    public static RestAction<Duration> retrieveAvgDurationBetweenMessagesInChannel(GuildMessageChannel channel) {
        return channel.getHistory().retrievePast(100).map(messages -> {
            if (messages.isEmpty()) return null;
            Duration totalDuration = Duration.between(messages.getFirst().getTimeCreated(), OffsetDateTime.now());
            for (int i = 1; i < messages.size(); i++) {
                totalDuration = totalDuration.plus(Duration.between(messages.get(i).getTimeCreated(), messages.get(i - 1).getTimeCreated()));
            }
            return totalDuration.dividedBy(messages.size());
        });
    }

    private static @NotNull MessageCreateData getCreateDataForMessage(Message message) {
        MessageCreateBuilder builder = MessageCreateBuilder.fromMessage(message);

        var futures = message.getAttachments().stream().map(attachment ->
                attachment.getProxy().download().thenAccept(inputStream ->
                        builder.addFiles(FileUpload.fromData(inputStream, attachment.getFileName()))
                )).toArray(CompletableFuture[]::new);

        CompletableFuture.allOf(futures).join();

        return builder.setAllowedMentions(Set.of()).build();
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        String name = event.getName();
        switch (name) {
            case THINKER_COMMAND_NAME -> processThinkerCommand(event);
            case THINKER_TRIGGER_COMMAND_NAME -> processThinkerTriggerCommand(event);
            case THINKER_COLLECT_COMMAND_NAME -> processThinkerCollectCommand(event);
        }
    }

    private void processThinkerCommand(@NotNull SlashCommandInteractionEvent event) {
        event.deferReply(true).flatMap(hook ->
                jda.retrieveWebhookById(webhookId).flatMap(webhook -> {
                    Long option = event.getOption(THINKER_COMMAND_MESSAGE_PARAM_NAME, OptionMapping::getAsLong);
                    if (option == null) {
                        return sendRandomMessageImmediate(webhook)
                                .flatMap(message -> hook.setEphemeral(true).editOriginal("Thought of a random message: " + message.getJumpUrl()))
                                .onSuccess(message -> lastPostedTime.set(message.getTimeCreated()));
                    } else {
                        return webhook.getChannel().asGuildMessageChannel()
                                .retrieveMessageById(option)
                                .flatMap(this::sendMessageImmediate)
                                .flatMap(message -> hook.setEphemeral(true).editOriginal("Thought of a specific message: " + message.getJumpUrl()))
                                .onSuccess(message -> lastPostedTime.set(message.getTimeCreated()));
                    }
                })).queue();
    }

    private void processThinkerTriggerCommand(@NotNull SlashCommandInteractionEvent event) {
        event.deferReply(true).queue(hook -> {
            thinkerChatTriggers.set(Optional.ofNullable(event.getOption(THINKER_TRIGGER_COMMAND_TRIGGER_PARAM_NAME, OptionMapping::getAsString))
                    .map(option -> Arrays.asList(option.split(",")))
                    .orElse(Collections.emptyList()));
            hook.setEphemeral(true).editOriginal(
                    thinkerChatTriggers.get().isEmpty()
                            ? "Thinker trigger words cleared."
                            : "Thinker trigger words set to %s.".formatted(MarkdownUtil.monospace(String.join(", ", thinkerChatTriggers.get())))
            ).queue();
        });
    }

    private void processThinkerCollectCommand(@NotNull SlashCommandInteractionEvent event) {
        event.deferReply(true).queue(hook -> {
            jda.retrieveWebhookById(webhookId).queue(webhook -> {
                GuildMessageChannel channel = webhook.getChannel().asGuildMessageChannel();

                CompletableFuture.runAsync(() -> {
                    long startCount = messageRepository.countByWebhookId(webhookId);
                    List<ThinkerMessage> messagesToSave = new ArrayList<>();
                    AtomicLong savedCount = new AtomicLong(0);

                    log.info("Starting Thinker message collection. Current messages in database: {}", startCount);

                    channel.getIterableHistory()
                            .cache(false)
                            .forEach(message -> {
                                if (shouldSaveMessage(message)) {
                                    ThinkerMessage thinkerMessage = new ThinkerMessage(
                                            message.getIdLong(),
                                            message.getChannel().getIdLong(),
                                            message.getGuild().getIdLong(),
                                            webhookId
                                    );

                                    messagesToSave.add(thinkerMessage);

                                    if (messagesToSave.size() >= 1000) {
                                        List<ThinkerMessage> batch = new ArrayList<>(messagesToSave);
                                        messagesToSave.clear();

                                        List<Long> messageIds = batch.stream()
                                                .map(ThinkerMessage::getMessageId)
                                                .toList();
                                        Set<Long> existingIds = new HashSet<>(
                                                messageRepository.findExistingMessageIds(webhookId, messageIds)
                                        );

                                        List<ThinkerMessage> newMessages = batch.stream()
                                                .filter(msg -> !existingIds.contains(msg.getMessageId()))
                                                .toList();

                                        if (!newMessages.isEmpty()) {
                                            try {
                                                messageRepository.saveAll(newMessages);
                                                long saved = savedCount.addAndGet(newMessages.size());
                                                if (saved % 10000 == 0) {
                                                    log.info("Saved {} messages total", saved);
                                                }
                                            } catch (Exception e) {
                                                log.error("Failed to save batch: {}", e.getMessage());
                                            }
                                        }
                                    }
                                }
                            });

                    if (!messagesToSave.isEmpty()) {
                        List<Long> messageIds = messagesToSave.stream()
                                .map(ThinkerMessage::getMessageId)
                                .toList();
                        Set<Long> existingIds = new HashSet<>(
                                messageRepository.findExistingMessageIds(webhookId, messageIds)
                        );

                        List<ThinkerMessage> newMessages = messagesToSave.stream()
                                .filter(msg -> !existingIds.contains(msg.getMessageId()))
                                .toList();

                        if (!newMessages.isEmpty()) {
                            try {
                                messageRepository.saveAll(newMessages);
                                savedCount.addAndGet(newMessages.size());
                            } catch (Exception e) {
                                log.error("Failed to save batch: {}", e.getMessage());
                            }
                        }
                    }

                    long endCount = messageRepository.countByWebhookId(webhookId);
                    long collected = endCount - startCount;

                    log.info("Collection complete. Collected {} messages. Total in database: {}", collected, endCount);

                    hook.setEphemeral(true).editOriginal(
                            "Collected %d messages. Total messages in database: %d".formatted(collected, endCount)
                    ).queue();
                });
            });
        });
    }

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if (!event.isFromType(ChannelType.TEXT) || event.getMessage().isWebhookMessage())
            return;

        Message message = event.getMessage();

        event.getChannel().asTextChannel().retrieveWebhooks().queue(webhooks ->
                webhooks.stream()
                        .filter(webhook -> webhook.getIdLong() == webhookId)
                        .filter(webhook -> webhook.getDefaultUser().getIdLong() != message.getAuthor().getIdLong())
                        .findAny()
                        .ifPresent(webhook -> {
                            if (shouldSaveMessage(message)) {
                                ThinkerMessage thinkerMessage = new ThinkerMessage(
                                        message.getIdLong(),
                                        message.getChannel().getIdLong(),
                                        message.getGuild().getIdLong(),
                                        webhookId
                                );
                                messageRepository.save(thinkerMessage);
                            }

                            boolean isReply = !thinkerChatTriggers.get().isEmpty() && event.getMessage().getType() == MessageType.INLINE_REPLY && event.getMessage().getReferencedMessage() != null && event.getMessage().getReferencedMessage().getAuthor().getIdLong() == webhook.getDefaultUser().getIdLong();
                            boolean hasChatTrigger = thinkerChatTriggers.get().stream().anyMatch(trigger -> message.getContentDisplay().toLowerCase().contains(trigger));

                            if (isReply || hasChatTrigger) {
                                sendRandomMessageImmediate(webhook).queue();
                            }
                        }));
    }

    private boolean shouldSaveMessage(Message message) {
        return !message.getMentions().mentionsEveryone() &&
               message.getChannelType().isGuild() &&
               !(message.getContentRaw().isEmpty() && message.getEmbeds().isEmpty()
                 && message.getAttachments().isEmpty() && message.getComponents().isEmpty()
                 && message.getPoll() == null) &&
               !message.getType().isSystem() &&
               !message.isWebhookMessage();
    }

    private RestAction<Message> sendRandomMessageImmediate(Webhook webhook) {
        if (sendLock.tryLock()) {
            try {
                long totalMessages = messageRepository.countByWebhookId(webhookId);
                if (totalMessages == 0) {
                    return new CompletedRestAction<>(jda, null);
                }

                long randomOffset = ThreadLocalRandom.current().nextLong(totalMessages);
                Optional<ThinkerMessage> randomMessage = messageRepository.findByWebhookIdWithOffset(webhookId, randomOffset);

                if (randomMessage.isEmpty()) {
                    randomMessage = messageRepository.findRandomByWebhookId(webhookId);
                }

                return randomMessage.map(thinkerMessage -> webhook.getChannel().asGuildMessageChannel()
                                .retrieveMessageById(thinkerMessage.getMessageId())
                                .flatMap(this::sendMessageImmediate)
                                .onSuccess(message -> log.info("Successfully sent message: {}", message)))
                        .orElseGet(() -> new CompletedRestAction<>(jda, null));

            } finally {
                sendLock.unlock();
            }
        } else {
            log.info("Send lock in use, refusing to send message");
            return new CompletedRestAction<>(jda, null);
        }
    }

    private RestAction<Message> sendMessageImmediate(Message message) {
        log.info("Preparing to send message {}", message);
        return client.sendMessage(getCreateDataForMessage(message))
                .map(postedMessage -> {
                    lastPostedTime.set(postedMessage.getTimeCreated());
                    return postedMessage;
                });
    }

    @Override
    public void onMessageBulkDelete(MessageBulkDeleteEvent event) {
        messageRepository.deleteByWebhookIdAndMessageIdIn(webhookId, event.getMessageIds().stream().map(Long::valueOf).toList());
    }

    @Override
    public void onMessageDelete(MessageDeleteEvent event) {
        messageRepository.deleteById(event.getMessageIdLong());
    }

    @PostConstruct
    public void postConstruct() {
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            throw new IllegalArgumentException("Failed to find guild with ID %d".formatted(guildId));
        }

        guild.upsertCommand(THINKER_COMMAND_NAME, "Triggers the Thinker")
                .setDefaultPermissions(DefaultMemberPermissions.DISABLED)
                .addOption(OptionType.STRING, THINKER_COMMAND_MESSAGE_PARAM_NAME, "The message ID to copy.")
                .queue();

        guild.upsertCommand(THINKER_TRIGGER_COMMAND_NAME, "Sets a new trigger word for the thinker.")
                .setDefaultPermissions(DefaultMemberPermissions.DISABLED)
                .addOption(OptionType.STRING, THINKER_TRIGGER_COMMAND_TRIGGER_PARAM_NAME, "The new trigger word.")
                .queue();

        guild.upsertCommand(THINKER_COLLECT_COMMAND_NAME, "Collects messages from the webhook channel")
                .setDefaultPermissions(DefaultMemberPermissions.DISABLED)
                .queue();

        guild.retrieveWebhooks().queue(webhooks ->
                webhooks.stream().filter(webhook -> webhook.getIdLong() == webhookId)
                        .findFirst()
                        .ifPresent(webhook -> {
                            client = WebhookClient.createClient(jda, webhook.getUrl());
                            lastPostedTime.set(OffsetDateTime.now());

                            scheduler.scheduleWithFixedDelay(() -> CompletableFuture.runAsync(this::sendRandomMessageIfTimeElapsed), 1, 1, TimeUnit.MINUTES);
                        }));
    }

    private void sendRandomMessageIfTimeElapsed() {
        jda.retrieveWebhookById(webhookId).flatMap(webhook -> {
            GuildMessageChannel channel = webhook.getChannel().asGuildMessageChannel();
            return channel.retrieveMessageById(channel.getLatestMessageId()).flatMap(latestMessage ->
                    retrieveAvgDurationBetweenMessagesInChannel(channel).flatMap(duration -> {
                        if (duration != null &&
                            OffsetDateTime.now().isAfter(lastPostedTime.get().plus(duration.multipliedBy(durationMultiplier))) &&
                            messageRepository.countByWebhookId(webhookId) > 0) {
                            if (latestMessage.getAuthor().getIdLong() == webhookId) {
                                log.info("Latest message is already authored by us, refusing to post");
                                lastPostedTime.set(latestMessage.getTimeCreated());
                            } else {
                                return sendRandomMessageImmediate(webhook);
                            }
                        }
                        log.debug("Not enough time has passed yet, refusing to post");
                        return new CompletedRestAction<>(jda, null);
                    }));
        }).submit().whenComplete((msg, err) -> {
            if (err != null) {
                log.error("Failed to send message", err);
            }
        });
    }

}
