package org.mikusch;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
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
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.utils.MarkdownUtil;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.internal.requests.CompletedRestAction;
import org.jetbrains.annotations.NotNull;
import org.mikusch.entity.ThinkerConfig;
import org.mikusch.entity.ThinkerMessage;
import org.mikusch.repository.ThinkerConfigRepository;
import org.mikusch.repository.ThinkerMessageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class Thinker extends ListenerAdapter {

    private static final String THINKER_COMMAND_NAME = "thinker";
    private static final String THINKER_TRIGGER_COMMAND_NAME = "thinkertrigger";
    private static final String THINKER_COLLECT_COMMAND_NAME = "thinkercollect";
    private static final String THINKER_PRESET_COMMAND_NAME = "thinkerpreset";
    private static final String THINKER_SETUP_COMMAND_NAME = "thinkersetup";
    private static final String THINKER_REMOVE_COMMAND_NAME = "thinkerremove";
    private static final String THINKER_COMMAND_MESSAGE_PARAM_NAME = "message";
    private static final String THINKER_TRIGGER_COMMAND_TRIGGER_PARAM_NAME = "trigger";
    private static final String THINKER_SETUP_COMMAND_WEBHOOK_PARAM_NAME = "webhook";
    private static final String ALWAYS_BLOCKED_WORD = "thinker";
    private static final int MAX_SEND_ATTEMPTS = 10;
    private static final Duration SEND_LEASE_TIMEOUT = Duration.ofMinutes(2);

    private final Map<Long, ThinkerConfig> configs = new ConcurrentHashMap<>();
    private final Map<Long, IncomingWebhookClient> clients = new ConcurrentHashMap<>();
    private final Map<Long, Long> webhookDefaultUserIds = new ConcurrentHashMap<>();
    private final Map<Long, OffsetDateTime> lastPostedTimes = new ConcurrentHashMap<>();
    private final Map<Long, ReentrantLock> sendLocks = new ConcurrentHashMap<>();
    private final Map<Long, OffsetDateTime> sendLeases = new ConcurrentHashMap<>();
    private final Set<Long> collectingWebhookIds = ConcurrentHashMap.newKeySet();

    private final JDA jda;
    private final ThinkerMessageRepository messageRepository;
    private final ThinkerConfigRepository configRepository;

    @Autowired
    public Thinker(JDA jda, ThinkerMessageRepository messageRepository, ThinkerConfigRepository configRepository) {
        this.jda = jda;
        this.messageRepository = messageRepository;
        this.configRepository = configRepository;
        jda.addEventListener(this);

        configRepository.findAll().forEach(config -> configs.put(config.getWebhookId(), config));
    }

    public static Duration getAvgDurationBetweenMessages(List<Message> messages) {
        if (messages.isEmpty()) return null;
        Duration totalDuration = Duration.between(messages.getFirst().getTimeCreated(), OffsetDateTime.now());
        for (int i = 1; i < messages.size(); i++) {
            totalDuration = totalDuration.plus(Duration.between(
                    messages.get(i).getTimeCreated(), messages.get(i - 1).getTimeCreated()));
        }
        return totalDuration.dividedBy(messages.size());
    }

    private static @NotNull MessageCreateData getCreateDataForMessage(Message message) {
        MessageCreateBuilder builder = MessageCreateBuilder.fromMessage(message);

        builder.addFiles(message.getAttachments().stream()
                .map(attachment -> attachment.getProxy().downloadAsFileUpload(attachment.getFileName()))
                .toList());

        return builder.setAllowedMentions(EnumSet.of(Message.MentionType.USER)).build();
    }

    private static Stream<String> getBlockedWords(ThinkerConfig config) {
        return Stream.concat(config.getTriggers().stream(), Stream.of(ALWAYS_BLOCKED_WORD))
                .map(String::trim)
                .filter(word -> !word.isEmpty())
                .map(word -> word.toLowerCase(Locale.ROOT));
    }

    private static void appendIfPresent(StringBuilder builder, String text) {
        if (text != null) {
            builder.append('\n').append(text);
        }
    }

    private static String getSearchableText(Message message) {
        StringBuilder builder = new StringBuilder(message.getContentRaw());
        appendIfPresent(builder, message.getContentDisplay());
        message.getAttachments().forEach(attachment -> appendIfPresent(builder, attachment.getFileName()));

        for (MessageEmbed embed : message.getEmbeds()) {
            appendIfPresent(builder, embed.getTitle());
            appendIfPresent(builder, embed.getDescription());
            if (embed.getAuthor() != null) {
                appendIfPresent(builder, embed.getAuthor().getName());
            }
            if (embed.getFooter() != null) {
                appendIfPresent(builder, embed.getFooter().getText());
            }
            for (MessageEmbed.Field field : embed.getFields()) {
                appendIfPresent(builder, field.getName());
                appendIfPresent(builder, field.getValue());
            }
        }

        return builder.toString().toLowerCase(Locale.ROOT);
    }

    private static boolean containsBlockedWord(Message message, ThinkerConfig config) {
        String searchableText = getSearchableText(message);
        return getBlockedWords(config).anyMatch(searchableText::contains);
    }

    private void discardBlockedMessage(Message message, ThinkerConfig config) {
        log.info(
                "Message {} contains a blocked word, removing it from the pool of webhook {}",
                message.getIdLong(),
                config.getWebhookId());
        removeFromPool(message.getIdLong(), config);
    }

    private void discardMissingMessage(long messageId, ThinkerConfig config) {
        log.info(
                "Message {} no longer exists on Discord, removing it from the pool of webhook {}",
                messageId,
                config.getWebhookId());
        removeFromPool(messageId, config);
    }

    private void removeFromPool(long messageId, ThinkerConfig config) {
        messageRepository.deleteByWebhookIdAndMessageIdIn(config.getWebhookId(), List.of(messageId));
    }

    private Optional<ThinkerConfig> getConfigForChannel(long channelId) {
        return configs.values().stream()
                .filter(c -> c.getChannelId().equals(channelId))
                .findFirst();
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        String name = event.getName();
        switch (name) {
            case THINKER_COMMAND_NAME -> processThinkerCommand(event);
            case THINKER_TRIGGER_COMMAND_NAME -> processThinkerTriggerCommand(event);
            case THINKER_COLLECT_COMMAND_NAME -> processThinkerCollectCommand(event);
            case THINKER_PRESET_COMMAND_NAME -> processThinkerPresetCommand(event);
            case THINKER_SETUP_COMMAND_NAME -> processThinkerSetupCommand(event);
            case THINKER_REMOVE_COMMAND_NAME -> processThinkerRemoveCommand(event);
        }
    }

    private void processThinkerSetupCommand(@NotNull SlashCommandInteractionEvent event) {
        event.deferReply(true).queue(hook -> {
            String webhookUrl = event.getOption(THINKER_SETUP_COMMAND_WEBHOOK_PARAM_NAME, OptionMapping::getAsString);
            if (webhookUrl == null) {
                hook.editOriginal("Webhook URL is required.").queue();
                return;
            }

            Matcher matcher = Webhook.WEBHOOK_URL.matcher(webhookUrl);
            if (!matcher.matches()) {
                hook.editOriginal("Invalid webhook URL format.").queue();
                return;
            }

            long webhookId = Long.parseLong(matcher.group("id"));

            jda.retrieveWebhookById(webhookId)
                    .queue(
                            webhook -> {
                                if (webhook.getChannel().getIdLong()
                                        != event.getChannel().getIdLong()) {
                                    hook.editOriginal("Webhook must belong to this channel.")
                                            .queue();
                                    return;
                                }

                                ThinkerConfig config = new ThinkerConfig(
                                        webhookId, event.getChannel().getIdLong());
                                configRepository.save(config);
                                configs.put(webhookId, config);
                                clients.put(webhookId, WebhookClient.createClient(jda, webhookUrl));
                                webhookDefaultUserIds.put(
                                        webhookId, webhook.getDefaultUser().getIdLong());
                                lastPostedTimes.put(webhookId, OffsetDateTime.now());
                                sendLocks.put(webhookId, new ReentrantLock());

                                hook.editOriginal("Thinker setup complete for webhook %s."
                                                .formatted(MarkdownUtil.monospace(webhook.getName())))
                                        .queue();
                            },
                            error -> hook.editOriginal("Failed to retrieve webhook: %s".formatted(error.getMessage()))
                                    .queue());
        });
    }

    private void processThinkerRemoveCommand(@NotNull SlashCommandInteractionEvent event) {
        event.deferReply(true).queue(hook -> {
            Optional<ThinkerConfig> configOpt =
                    getConfigForChannel(event.getChannel().getIdLong());
            if (configOpt.isEmpty()) {
                hook.editOriginal("No Thinker configured for this channel.").queue();
                return;
            }

            ThinkerConfig config = configOpt.get();
            configRepository.delete(config);
            configs.remove(config.getWebhookId());
            clients.remove(config.getWebhookId());
            webhookDefaultUserIds.remove(config.getWebhookId());
            lastPostedTimes.remove(config.getWebhookId());
            sendLocks.remove(config.getWebhookId());

            hook.editOriginal("Thinker removed from this channel.").queue();
        });
    }

    private void processThinkerCommand(@NotNull SlashCommandInteractionEvent event) {
        event.deferReply(true).queue(hook -> {
            Optional<ThinkerConfig> configOpt =
                    getConfigForChannel(event.getChannel().getIdLong());
            if (configOpt.isEmpty()) {
                hook.editOriginal("No Thinker configured for this channel. Use /thinkersetup first.")
                        .queue();
                return;
            }

            ThinkerConfig config = configOpt.get();
            jda.retrieveWebhookById(config.getWebhookId())
                    .queue(
                            webhook -> {
                                GuildMessageChannel channel =
                                        webhook.getChannel().asGuildMessageChannel();
                                String messageIdOption =
                                        event.getOption(THINKER_COMMAND_MESSAGE_PARAM_NAME, OptionMapping::getAsString);
                                if (messageIdOption == null || messageIdOption.isBlank()) {
                                    sendRandomMessageImmediate(channel, config)
                                            .flatMap(message -> message == null
                                                    ? hook.editOriginal(
                                                            "No messages available to think of yet. Run /thinkercollect first.")
                                                    : hook.editOriginal(
                                                            "Thought of a random message: " + message.getJumpUrl()))
                                            .queue();
                                } else {
                                    long messageId;
                                    try {
                                        messageId = Long.parseLong(messageIdOption.trim());
                                    } catch (NumberFormatException e) {
                                        hook.editOriginal("Invalid message ID.").queue();
                                        return;
                                    }
                                    channel.retrieveMessageById(messageId)
                                            .flatMap(msg -> {
                                                if (containsBlockedWord(msg, config)) {
                                                    discardBlockedMessage(msg, config);
                                                    return hook.editOriginal(
                                                            "That message contains a trigger word (or %s), so the Thinker won't repost it."
                                                                    .formatted(MarkdownUtil.monospace(
                                                                            ALWAYS_BLOCKED_WORD)));
                                                }
                                                return sendMessageImmediate(msg, config)
                                                        .flatMap(message -> message == null
                                                                ? hook.editOriginal(
                                                                        "Couldn't send that message; the Thinker may not be fully set up.")
                                                                : hook.editOriginal("Thought of a specific message: "
                                                                        + message.getJumpUrl()));
                                            })
                                            .queue();
                                }
                            },
                            error -> hook.editOriginal("Failed to retrieve webhook: %s".formatted(error.getMessage()))
                                    .queue());
        });
    }

    private void processThinkerPresetCommand(@NotNull SlashCommandInteractionEvent event) {
        event.deferReply(true).queue(hook -> {
            Optional<ThinkerConfig> configOpt =
                    getConfigForChannel(event.getChannel().getIdLong());
            if (configOpt.isEmpty()) {
                hook.editOriginal("No Thinker configured for this channel. Use /thinkersetup first.")
                        .queue();
                return;
            }

            ThinkerConfig config = configOpt.get();
            String messageIdOption = event.getOption(THINKER_COMMAND_MESSAGE_PARAM_NAME, OptionMapping::getAsString);

            if (messageIdOption == null || messageIdOption.isBlank()) {
                setPresetMessageId(config, null);
                hook.editOriginal("Preset message cleared. The Thinker will think of a random message next.")
                        .queue();
                return;
            }

            long messageId;
            try {
                messageId = Long.parseLong(messageIdOption.trim());
            } catch (NumberFormatException e) {
                hook.editOriginal("Invalid message ID.").queue();
                return;
            }

            event.getChannel()
                    .asGuildMessageChannel()
                    .retrieveMessageById(messageId)
                    .queue(
                            message -> {
                                if (containsBlockedWord(message, config)) {
                                    discardBlockedMessage(message, config);
                                    hook.editOriginal(
                                                    "That message contains a trigger word (or %s), so it can't be preset."
                                                            .formatted(MarkdownUtil.monospace(ALWAYS_BLOCKED_WORD)))
                                            .queue();
                                    return;
                                }
                                setPresetMessageId(config, messageId);
                                hook.editOriginal(
                                                "Preset the next message: %s\nIt will be posted the next time the Thinker speaks (trigger, reply, or idle timer)."
                                                        .formatted(message.getJumpUrl()))
                                        .queue();
                            },
                            error -> hook.editOriginal("Failed to find that message in this channel: %s"
                                            .formatted(error.getMessage()))
                                    .queue());
        });
    }

    private void setPresetMessageId(ThinkerConfig config, Long messageId) {
        ReentrantLock lock = sendLocks.computeIfAbsent(config.getWebhookId(), k -> new ReentrantLock());
        lock.lock();
        try {
            config.setPresetMessageId(messageId);
            configRepository.save(config);
        } finally {
            lock.unlock();
        }
    }

    private void processThinkerTriggerCommand(@NotNull SlashCommandInteractionEvent event) {
        event.deferReply(true).queue(hook -> {
            Optional<ThinkerConfig> configOpt =
                    getConfigForChannel(event.getChannel().getIdLong());
            if (configOpt.isEmpty()) {
                hook.editOriginal("No Thinker configured for this channel. Use /thinkersetup first.")
                        .queue();
                return;
            }

            ThinkerConfig config = configOpt.get();
            List<String> newTriggers = Optional.ofNullable(
                            event.getOption(THINKER_TRIGGER_COMMAND_TRIGGER_PARAM_NAME, OptionMapping::getAsString))
                    .map(option -> Arrays.stream(option.split(","))
                            .map(String::trim)
                            .filter(trigger -> !trigger.isEmpty())
                            .toList())
                    .orElse(Collections.emptyList());

            config.setTriggers(newTriggers);
            configRepository.save(config);

            hook.editOriginal(
                            config.getTriggers().isEmpty()
                                    ? "Thinker trigger words cleared."
                                    : "Thinker trigger words set to %s."
                                            .formatted(MarkdownUtil.monospace(String.join(", ", config.getTriggers()))))
                    .queue();
        });
    }

    private void processThinkerCollectCommand(@NotNull SlashCommandInteractionEvent event) {
        event.deferReply(true).queue(hook -> {
            Optional<ThinkerConfig> configOpt =
                    getConfigForChannel(event.getChannel().getIdLong());
            if (configOpt.isEmpty()) {
                hook.editOriginal("No Thinker configured for this channel. Use /thinkersetup first.")
                        .queue();
                return;
            }

            ThinkerConfig config = configOpt.get();
            if (!collectingWebhookIds.add(config.getWebhookId())) {
                hook.editOriginal("A collection is already running for this channel. Wait for it to finish.")
                        .queue();
                return;
            }

            jda.retrieveWebhookById(config.getWebhookId())
                    .queue(
                            webhook -> {
                                GuildMessageChannel channel =
                                        webhook.getChannel().asGuildMessageChannel();

                                hook.editOriginal(
                                                "Collecting messages. On a busy channel this can take a while; if this message stops updating, the result is in the bot log.")
                                        .queue();

                                CompletableFuture.runAsync(() -> {
                                            long startCount = messageRepository.countByWebhookId(config.getWebhookId());
                                            List<ThinkerMessage> messagesToSave = new ArrayList<>();
                                            AtomicLong savedCount = new AtomicLong(0);

                                            log.info(
                                                    "Starting Thinker message collection for webhook {}. Current messages in database: {}",
                                                    config.getWebhookId(),
                                                    startCount);

                                            channel.getIterableHistory()
                                                    .cache(false)
                                                    .forEach(message -> {
                                                        if (shouldSaveMessage(message, config)) {
                                                            ThinkerMessage thinkerMessage = new ThinkerMessage(
                                                                    message.getIdLong(),
                                                                    message.getChannel()
                                                                            .getIdLong(),
                                                                    message.getGuild()
                                                                            .getIdLong(),
                                                                    config.getWebhookId());

                                                            messagesToSave.add(thinkerMessage);

                                                            if (messagesToSave.size() >= 1000) {
                                                                List<ThinkerMessage> batch =
                                                                        new ArrayList<>(messagesToSave);
                                                                messagesToSave.clear();

                                                                List<Long> messageIds = batch.stream()
                                                                        .map(ThinkerMessage::getMessageId)
                                                                        .toList();
                                                                Set<Long> existingIds = new HashSet<>(
                                                                        messageRepository.findExistingMessageIds(
                                                                                config.getWebhookId(), messageIds));

                                                                List<ThinkerMessage> newMessages = batch.stream()
                                                                        .filter(msg -> !existingIds.contains(
                                                                                msg.getMessageId()))
                                                                        .toList();

                                                                if (!newMessages.isEmpty()) {
                                                                    try {
                                                                        messageRepository.saveAll(newMessages);
                                                                        long saved = savedCount.addAndGet(
                                                                                newMessages.size());
                                                                        if (saved % 10000 == 0) {
                                                                            log.info("Saved {} messages total", saved);
                                                                        }
                                                                    } catch (Exception e) {
                                                                        log.error(
                                                                                "Failed to save batch: {}",
                                                                                e.getMessage());
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    });

                                            if (!messagesToSave.isEmpty()) {
                                                List<Long> messageIds = messagesToSave.stream()
                                                        .map(ThinkerMessage::getMessageId)
                                                        .toList();
                                                Set<Long> existingIds =
                                                        new HashSet<>(messageRepository.findExistingMessageIds(
                                                                config.getWebhookId(), messageIds));

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

                                            long endCount = messageRepository.countByWebhookId(config.getWebhookId());
                                            long collected = endCount - startCount;

                                            log.info(
                                                    "Collection complete for webhook {}. Collected {} messages. Total in database: {}",
                                                    config.getWebhookId(),
                                                    collected,
                                                    endCount);

                                            hook.editOriginal("Collected %d messages. Total messages in database: %d"
                                                            .formatted(collected, endCount))
                                                    .queue(
                                                            null,
                                                            reportError -> log.warn(
                                                                    "Could not report the collection result for webhook {}, the interaction has likely expired: {}",
                                                                    config.getWebhookId(),
                                                                    reportError.getMessage()));
                                        })
                                        .whenComplete((ignored, error) -> {
                                            collectingWebhookIds.remove(config.getWebhookId());
                                            if (error != null) {
                                                log.error(
                                                        "Thinker message collection failed for webhook {}",
                                                        config.getWebhookId(),
                                                        error);
                                            }
                                        });
                            },
                            error -> {
                                collectingWebhookIds.remove(config.getWebhookId());
                                hook.editOriginal("Failed to retrieve webhook: %s".formatted(error.getMessage()))
                                        .queue();
                            });
        });
    }

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if (!event.isFromType(ChannelType.TEXT) || event.getMessage().isWebhookMessage()) return;

        Message message = event.getMessage();
        GuildMessageChannel channel = event.getChannel().asGuildMessageChannel();

        getConfigForChannel(event.getChannel().getIdLong()).ifPresent(config -> {
            Long defaultUserId = webhookDefaultUserIds.get(config.getWebhookId());
            if (defaultUserId != null) {
                handleReceivedMessage(config, defaultUserId, message, channel);
            } else {
                jda.retrieveWebhookById(config.getWebhookId())
                        .queue(
                                webhook -> {
                                    long resolved = webhook.getDefaultUser().getIdLong();
                                    webhookDefaultUserIds.put(config.getWebhookId(), resolved);
                                    handleReceivedMessage(config, resolved, message, channel);
                                },
                                error -> log.warn(
                                        "Failed to resolve webhook {} for received message: {}",
                                        config.getWebhookId(),
                                        error.getMessage()));
            }
        });
    }

    private void handleReceivedMessage(
            ThinkerConfig config, long webhookDefaultUserId, Message message, GuildMessageChannel channel) {
        if (message.getAuthor().getIdLong() == webhookDefaultUserId) {
            return;
        }

        if (shouldSaveMessage(message, config)) {
            ThinkerMessage thinkerMessage = new ThinkerMessage(
                    message.getIdLong(),
                    message.getChannel().getIdLong(),
                    message.getGuild().getIdLong(),
                    config.getWebhookId());
            messageRepository.save(thinkerMessage);
        }

        boolean isReply = !config.getTriggers().isEmpty()
                && message.getType() == MessageType.INLINE_REPLY
                && message.getReferencedMessage() != null
                && message.getReferencedMessage().getAuthor().getIdLong() == webhookDefaultUserId;
        String content = message.getContentDisplay().toLowerCase(Locale.ROOT);
        boolean hasChatTrigger =
                config.getTriggers().stream().anyMatch(trigger -> content.contains(trigger.toLowerCase(Locale.ROOT)));

        if (isReply || hasChatTrigger) {
            sendRandomMessageImmediate(channel, config).queue();
        }
    }

    private boolean shouldSaveMessage(Message message, ThinkerConfig config) {
        return !message.getMentions().mentionsEveryone()
                && message.getChannelType().isGuild()
                && !(message.getContentRaw().isEmpty()
                        && message.getEmbeds().isEmpty()
                        && message.getAttachments().isEmpty()
                        && message.getComponents().isEmpty()
                        && message.getPoll() == null)
                && !message.getType().isSystem()
                && !message.isWebhookMessage()
                && !containsBlockedWord(message, config);
    }

    private RestAction<Message> sendRandomMessageImmediate(GuildMessageChannel channel, ThinkerConfig config) {
        long webhookId = config.getWebhookId();
        if (!tryAcquireSendLease(webhookId)) {
            log.info("A send is already in progress for webhook {}, refusing to send another message", webhookId);
            return new CompletedRestAction<>(jda, null);
        }

        return releasingSendLease(webhookId, sendNextMessage(channel, config));
    }

    private boolean tryAcquireSendLease(long webhookId) {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime heldSince = sendLeases.putIfAbsent(webhookId, now);
        if (heldSince == null) {
            return true;
        }
        if (heldSince.isBefore(now.minus(SEND_LEASE_TIMEOUT))) {
            log.warn("Send lease for webhook {} was held since {}, taking it over", webhookId, heldSince);
            return sendLeases.replace(webhookId, heldSince, now);
        }
        return false;
    }

    private RestAction<Message> releasingSendLease(long webhookId, RestAction<Message> action) {
        return action.map(message -> {
                    sendLeases.remove(webhookId);
                    return message;
                })
                .onErrorFlatMap(error -> {
                    sendLeases.remove(webhookId);
                    return new CompletedRestAction<Message>(jda, error);
                });
    }

    private RestAction<Message> sendNextMessage(GuildMessageChannel channel, ThinkerConfig config) {
        Long presetMessageId = consumePresetMessageId(config);
        if (presetMessageId == null) {
            return selectAndSendRandomMessage(channel, config, 0);
        }

        log.info("Sending preset message {} for webhook {}", presetMessageId, config.getWebhookId());
        return channel.retrieveMessageById(presetMessageId)
                .flatMap(msg -> {
                    if (containsBlockedWord(msg, config)) {
                        discardBlockedMessage(msg, config);
                        return selectAndSendRandomMessage(channel, config, 0);
                    }
                    return sendMessageImmediate(msg, config);
                })
                .onErrorFlatMap(ErrorResponse.UNKNOWN_MESSAGE::test, error -> {
                    discardMissingMessage(presetMessageId, config);
                    return selectAndSendRandomMessage(channel, config, 0);
                });
    }

    private Long consumePresetMessageId(ThinkerConfig config) {
        ReentrantLock lock = sendLocks.computeIfAbsent(config.getWebhookId(), k -> new ReentrantLock());
        lock.lock();
        try {
            Long presetMessageId = config.getPresetMessageId();
            if (presetMessageId != null) {
                config.setPresetMessageId(null);
                configRepository.save(config);
            }
            return presetMessageId;
        } finally {
            lock.unlock();
        }
    }

    private RestAction<Message> selectAndSendRandomMessage(
            GuildMessageChannel channel, ThinkerConfig config, int attempt) {
        if (attempt >= MAX_SEND_ATTEMPTS) {
            log.warn(
                    "Found no postable message for webhook {} after {} attempts, refusing to post",
                    config.getWebhookId(),
                    MAX_SEND_ATTEMPTS);
            return new CompletedRestAction<>(jda, null);
        }

        long totalMessages = messageRepository.countByWebhookId(config.getWebhookId());
        if (totalMessages == 0) {
            return new CompletedRestAction<>(jda, null);
        }

        long randomOffset = ThreadLocalRandom.current().nextLong(totalMessages);
        Optional<ThinkerMessage> randomMessage =
                messageRepository.findByWebhookIdWithOffset(config.getWebhookId(), randomOffset);

        if (randomMessage.isEmpty()) {
            randomMessage = messageRepository.findRandomByWebhookId(config.getWebhookId());
        }

        return randomMessage
                .map(thinkerMessage -> channel.retrieveMessageById(thinkerMessage.getMessageId())
                        .flatMap(msg -> {
                            if (containsBlockedWord(msg, config)) {
                                discardBlockedMessage(msg, config);
                                return selectAndSendRandomMessage(channel, config, attempt + 1);
                            }
                            return sendMessageImmediate(msg, config);
                        })
                        .onErrorFlatMap(ErrorResponse.UNKNOWN_MESSAGE::test, error -> {
                            discardMissingMessage(thinkerMessage.getMessageId(), config);
                            return selectAndSendRandomMessage(channel, config, attempt + 1);
                        }))
                .orElseGet(() -> new CompletedRestAction<>(jda, null));
    }

    private RestAction<Message> sendMessageImmediate(Message message, ThinkerConfig config) {
        log.info("Preparing to send message {} for webhook {}", message, config.getWebhookId());
        IncomingWebhookClient client = clients.get(config.getWebhookId());
        if (client == null) {
            return new CompletedRestAction<>(jda, null);
        }
        if (containsBlockedWord(message, config)) {
            discardBlockedMessage(message, config);
            return new CompletedRestAction<>(jda, null);
        }
        return client.sendMessage(getCreateDataForMessage(message)).map(postedMessage -> {
            lastPostedTimes.put(config.getWebhookId(), postedMessage.getTimeCreated());
            log.info("Successfully sent message {} for webhook {}", postedMessage, config.getWebhookId());
            return postedMessage;
        });
    }

    @Override
    public void onMessageBulkDelete(MessageBulkDeleteEvent event) {
        getConfigForChannel(event.getChannel().getIdLong())
                .ifPresent(config -> messageRepository.deleteByWebhookIdAndMessageIdIn(
                        config.getWebhookId(),
                        event.getMessageIds().stream().map(Long::valueOf).toList()));
    }

    @Override
    public void onMessageDelete(MessageDeleteEvent event) {
        getConfigForChannel(event.getChannel().getIdLong())
                .ifPresent(config -> messageRepository.deleteByWebhookIdAndMessageIdIn(
                        config.getWebhookId(), List.of(event.getMessageIdLong())));
    }

    @PostConstruct
    public void postConstruct() {
        jda.getGuilds().forEach(guild -> {
            guild.upsertCommand(THINKER_SETUP_COMMAND_NAME, "Sets up Thinker for this channel")
                    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_WEBHOOKS))
                    .addOption(OptionType.STRING, THINKER_SETUP_COMMAND_WEBHOOK_PARAM_NAME, "The webhook URL", true)
                    .queue();

            guild.upsertCommand(THINKER_REMOVE_COMMAND_NAME, "Removes Thinker from this channel")
                    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_WEBHOOKS))
                    .queue();

            guild.upsertCommand(THINKER_COMMAND_NAME, "Triggers the Thinker")
                    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_WEBHOOKS))
                    .addOption(OptionType.STRING, THINKER_COMMAND_MESSAGE_PARAM_NAME, "The message ID to copy")
                    .queue();

            guild.upsertCommand(THINKER_TRIGGER_COMMAND_NAME, "Sets a new trigger word for the thinker")
                    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_WEBHOOKS))
                    .addOption(OptionType.STRING, THINKER_TRIGGER_COMMAND_TRIGGER_PARAM_NAME, "The new trigger word")
                    .queue();

            guild.upsertCommand(THINKER_COLLECT_COMMAND_NAME, "Collects messages from the webhook channel")
                    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_WEBHOOKS))
                    .queue();

            guild.upsertCommand(THINKER_PRESET_COMMAND_NAME, "Pre-sets the next Thinker message without posting it now")
                    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_WEBHOOKS))
                    .addOption(
                            OptionType.STRING,
                            THINKER_COMMAND_MESSAGE_PARAM_NAME,
                            "The message ID to pre-set (omit to clear the preset)")
                    .queue();
        });

        configs.values()
                .forEach(config -> jda.retrieveWebhookById(config.getWebhookId())
                        .queue(
                                webhook -> {
                                    clients.put(
                                            config.getWebhookId(), WebhookClient.createClient(jda, webhook.getUrl()));
                                    webhookDefaultUserIds.put(
                                            config.getWebhookId(),
                                            webhook.getDefaultUser().getIdLong());
                                    lastPostedTimes.put(config.getWebhookId(), OffsetDateTime.now());
                                    sendLocks.put(config.getWebhookId(), new ReentrantLock());
                                },
                                error -> {
                                    log.warn(
                                            "Failed to initialize webhook {}, removing config: {}",
                                            config.getWebhookId(),
                                            error.getMessage());
                                    configs.remove(config.getWebhookId());
                                    configRepository.delete(config);
                                }));
    }

    @Scheduled(initialDelay = 1, fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
    void sendRandomMessagesIfTimeElapsed() {
        configs.values()
                .forEach(config -> jda.retrieveWebhookById(config.getWebhookId())
                        .flatMap(webhook -> {
                            GuildMessageChannel channel = webhook.getChannel().asGuildMessageChannel();
                            return channel.getHistory()
                                    .retrievePast(100)
                                    .flatMap(messages -> sendRandomMessageIfDue(channel, config, webhook, messages));
                        })
                        .submit()
                        .orTimeout(30, TimeUnit.SECONDS)
                        .whenComplete((msg, err) -> {
                            if (err != null) {
                                log.error("Failed to send message for webhook {}", config.getWebhookId(), err);
                            }
                        }));
    }

    private RestAction<Message> sendRandomMessageIfDue(
            GuildMessageChannel channel, ThinkerConfig config, Webhook webhook, List<Message> recentMessages) {
        Duration duration = getAvgDurationBetweenMessages(recentMessages);
        OffsetDateTime lastPosted = lastPostedTimes.getOrDefault(config.getWebhookId(), OffsetDateTime.now());

        if (duration == null
                || !OffsetDateTime.now().isAfter(lastPosted.plus(duration.multipliedBy(config.getDurationMultiplier())))
                || messageRepository.countByWebhookId(config.getWebhookId()) == 0) {
            log.debug("Not enough time has passed yet for webhook {}, refusing to post", config.getWebhookId());
            return new CompletedRestAction<>(jda, null);
        }

        Message latestMessage = recentMessages.getFirst();
        if (latestMessage.getAuthor().getIdLong() == webhook.getDefaultUser().getIdLong()) {
            log.info("Latest message is already authored by webhook {}, refusing to post", config.getWebhookId());
            lastPostedTimes.put(config.getWebhookId(), latestMessage.getTimeCreated());
            return new CompletedRestAction<>(jda, null);
        }

        return sendRandomMessageImmediate(channel, config);
    }
}
