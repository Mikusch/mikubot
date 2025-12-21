package org.mikusch;

import jakarta.annotation.PostConstruct;
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
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.utils.FileUpload;
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
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;

@Service
@Slf4j
public class Thinker extends ListenerAdapter {

    private static final String THINKER_COMMAND_NAME = "thinker";
    private static final String THINKER_TRIGGER_COMMAND_NAME = "thinkertrigger";
    private static final String THINKER_COLLECT_COMMAND_NAME = "thinkercollect";
    private static final String THINKER_SETUP_COMMAND_NAME = "thinkersetup";
    private static final String THINKER_REMOVE_COMMAND_NAME = "thinkerremove";
    private static final String THINKER_COMMAND_MESSAGE_PARAM_NAME = "message";
    private static final String THINKER_TRIGGER_COMMAND_TRIGGER_PARAM_NAME = "trigger";
    private static final String THINKER_SETUP_COMMAND_WEBHOOK_PARAM_NAME = "webhook";

    private final Map<Long, ThinkerConfig> configs = new ConcurrentHashMap<>();
    private final Map<Long, IncomingWebhookClient> clients = new ConcurrentHashMap<>();
    private final Map<Long, OffsetDateTime> lastPostedTimes = new ConcurrentHashMap<>();
    private final Map<Long, ReentrantLock> sendLocks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

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

        try {
            CompletableFuture.allOf(futures).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Failed to download attachments for message {}", message.getId(), e);
        }

        return builder.setAllowedMentions(Set.of()).build();
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

            jda.retrieveWebhookById(webhookId).queue(webhook -> {
                if (webhook.getChannel().getIdLong() != event.getChannel().getIdLong()) {
                    hook.editOriginal("Webhook must belong to this channel.").queue();
                    return;
                }

                ThinkerConfig config = new ThinkerConfig(webhookId, event.getChannel().getIdLong());
                configRepository.save(config);
                configs.put(webhookId, config);
                clients.put(webhookId, WebhookClient.createClient(jda, webhookUrl));
                lastPostedTimes.put(webhookId, OffsetDateTime.now());
                sendLocks.put(webhookId, new ReentrantLock());

                hook.editOriginal("Thinker setup complete for webhook %s.".formatted(MarkdownUtil.monospace(webhook.getName()))).queue();
            }, error -> hook.editOriginal("Failed to retrieve webhook: %s".formatted(error.getMessage())).queue());
        });
    }

    private void processThinkerRemoveCommand(@NotNull SlashCommandInteractionEvent event) {
        event.deferReply(true).queue(hook -> {
            Optional<ThinkerConfig> configOpt = getConfigForChannel(event.getChannel().getIdLong());
            if (configOpt.isEmpty()) {
                hook.editOriginal("No Thinker configured for this channel.").queue();
                return;
            }

            ThinkerConfig config = configOpt.get();
            configRepository.delete(config);
            configs.remove(config.getWebhookId());
            clients.remove(config.getWebhookId());
            lastPostedTimes.remove(config.getWebhookId());
            sendLocks.remove(config.getWebhookId());

            hook.editOriginal("Thinker removed from this channel.").queue();
        });
    }

    private void processThinkerCommand(@NotNull SlashCommandInteractionEvent event) {
        event.deferReply(true).queue(hook -> {
            Optional<ThinkerConfig> configOpt = getConfigForChannel(event.getChannel().getIdLong());
            if (configOpt.isEmpty()) {
                hook.editOriginal("No Thinker configured for this channel. Use /thinkersetup first.").queue();
                return;
            }

            ThinkerConfig config = configOpt.get();
            jda.retrieveWebhookById(config.getWebhookId()).queue(webhook -> {
                Long option = event.getOption(THINKER_COMMAND_MESSAGE_PARAM_NAME, OptionMapping::getAsLong);
                if (option == null) {
                    sendRandomMessageImmediate(webhook, config)
                            .flatMap(message -> hook.editOriginal("Thought of a random message: " + message.getJumpUrl()))
                            .onSuccess(message -> lastPostedTimes.put(config.getWebhookId(), OffsetDateTime.now()))
                            .queue();
                } else {
                    webhook.getChannel().asGuildMessageChannel()
                            .retrieveMessageById(option)
                            .flatMap(msg -> sendMessageImmediate(msg, config))
                            .flatMap(message -> hook.editOriginal("Thought of a specific message: " + message.getJumpUrl()))
                            .onSuccess(message -> lastPostedTimes.put(config.getWebhookId(), OffsetDateTime.now()))
                            .queue();
                }
            }, error -> hook.editOriginal("Failed to retrieve webhook: %s".formatted(error.getMessage())).queue());
        });
    }

    private void processThinkerTriggerCommand(@NotNull SlashCommandInteractionEvent event) {
        event.deferReply(true).queue(hook -> {
            Optional<ThinkerConfig> configOpt = getConfigForChannel(event.getChannel().getIdLong());
            if (configOpt.isEmpty()) {
                hook.editOriginal("No Thinker configured for this channel. Use /thinkersetup first.").queue();
                return;
            }

            ThinkerConfig config = configOpt.get();
            List<String> newTriggers = Optional.ofNullable(event.getOption(THINKER_TRIGGER_COMMAND_TRIGGER_PARAM_NAME, OptionMapping::getAsString))
                    .map(option -> Arrays.asList(option.split(",")))
                    .orElse(Collections.emptyList());

            config.setTriggers(newTriggers);
            configRepository.save(config);

            hook.editOriginal(
                    config.getTriggers().isEmpty()
                            ? "Thinker trigger words cleared."
                            : "Thinker trigger words set to %s.".formatted(MarkdownUtil.monospace(String.join(", ", config.getTriggers())))
            ).queue();
        });
    }

    private void processThinkerCollectCommand(@NotNull SlashCommandInteractionEvent event) {
        event.deferReply(true).queue(hook -> {
            Optional<ThinkerConfig> configOpt = getConfigForChannel(event.getChannel().getIdLong());
            if (configOpt.isEmpty()) {
                hook.editOriginal("No Thinker configured for this channel. Use /thinkersetup first.").queue();
                return;
            }

            ThinkerConfig config = configOpt.get();
            jda.retrieveWebhookById(config.getWebhookId()).queue(webhook -> {
                GuildMessageChannel channel = webhook.getChannel().asGuildMessageChannel();

                CompletableFuture.runAsync(() -> {
                    long startCount = messageRepository.countByWebhookId(config.getWebhookId());
                    List<ThinkerMessage> messagesToSave = new ArrayList<>();
                    AtomicLong savedCount = new AtomicLong(0);

                    log.info("Starting Thinker message collection for webhook {}. Current messages in database: {}", config.getWebhookId(), startCount);

                    channel.getIterableHistory()
                            .cache(false)
                            .forEach(message -> {
                                if (shouldSaveMessage(message)) {
                                    ThinkerMessage thinkerMessage = new ThinkerMessage(
                                            message.getIdLong(),
                                            message.getChannel().getIdLong(),
                                            message.getGuild().getIdLong(),
                                            config.getWebhookId()
                                    );

                                    messagesToSave.add(thinkerMessage);

                                    if (messagesToSave.size() >= 1000) {
                                        List<ThinkerMessage> batch = new ArrayList<>(messagesToSave);
                                        messagesToSave.clear();

                                        List<Long> messageIds = batch.stream()
                                                .map(ThinkerMessage::getMessageId)
                                                .toList();
                                        Set<Long> existingIds = new HashSet<>(messageRepository.findExistingMessageIds(config.getWebhookId(), messageIds));

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
                        Set<Long> existingIds = new HashSet<>(messageRepository.findExistingMessageIds(config.getWebhookId(), messageIds));

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

                    log.info("Collection complete for webhook {}. Collected {} messages. Total in database: {}", config.getWebhookId(), collected, endCount);

                    hook.editOriginal("Collected %d messages. Total messages in database: %d".formatted(collected, endCount)).queue();
                });
            }, error -> hook.editOriginal("Failed to retrieve webhook: %s".formatted(error.getMessage())).queue());
        });
    }

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if (!event.isFromType(ChannelType.TEXT) || event.getMessage().isWebhookMessage())
            return;

        Message message = event.getMessage();

        getConfigForChannel(event.getChannel().getIdLong()).ifPresent(config -> {
            event.getChannel().asTextChannel().retrieveWebhooks().queue(webhooks ->
                    webhooks.stream()
                            .filter(webhook -> webhook.getIdLong() == config.getWebhookId())
                            .filter(webhook -> webhook.getDefaultUser().getIdLong() != message.getAuthor().getIdLong())
                            .findAny()
                            .ifPresent(webhook -> {
                                if (shouldSaveMessage(message)) {
                                    ThinkerMessage thinkerMessage = new ThinkerMessage(
                                            message.getIdLong(),
                                            message.getChannel().getIdLong(),
                                            message.getGuild().getIdLong(),
                                            config.getWebhookId()
                                    );
                                    messageRepository.save(thinkerMessage);
                                }

                                boolean isReply = !config.getTriggers().isEmpty() &&
                                        event.getMessage().getType() == MessageType.INLINE_REPLY &&
                                        event.getMessage().getReferencedMessage() != null &&
                                        event.getMessage().getReferencedMessage().getAuthor().getIdLong() == webhook.getDefaultUser().getIdLong();
                                boolean hasChatTrigger = config.getTriggers().stream()
                                        .anyMatch(trigger -> message.getContentDisplay().toLowerCase().contains(trigger.toLowerCase()));

                                if (isReply || hasChatTrigger) {
                                    sendRandomMessageImmediate(webhook, config).queue();
                                }
                            }));
        });
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

    private RestAction<Message> sendRandomMessageImmediate(Webhook webhook, ThinkerConfig config) {
        ReentrantLock lock = sendLocks.computeIfAbsent(config.getWebhookId(), k -> new ReentrantLock());
        if (lock.tryLock()) {
            try {
                long totalMessages = messageRepository.countByWebhookId(config.getWebhookId());
                if (totalMessages == 0) {
                    return new CompletedRestAction<>(jda, null);
                }

                long randomOffset = ThreadLocalRandom.current().nextLong(totalMessages);
                Optional<ThinkerMessage> randomMessage = messageRepository.findByWebhookIdWithOffset(config.getWebhookId(), randomOffset);

                if (randomMessage.isEmpty()) {
                    randomMessage = messageRepository.findRandomByWebhookId(config.getWebhookId());
                }

                return randomMessage.map(thinkerMessage -> webhook.getChannel().asGuildMessageChannel()
                                .retrieveMessageById(thinkerMessage.getMessageId())
                                .flatMap(msg -> sendMessageImmediate(msg, config))
                                .onSuccess(message -> log.info("Successfully sent message: {}", message)))
                        .orElseGet(() -> new CompletedRestAction<>(jda, null));

            } finally {
                lock.unlock();
            }
        } else {
            log.info("Send lock in use for webhook {}, refusing to send message", config.getWebhookId());
            return new CompletedRestAction<>(jda, null);
        }
    }

    private RestAction<Message> sendMessageImmediate(Message message, ThinkerConfig config) {
        log.info("Preparing to send message {} for webhook {}", message, config.getWebhookId());
        IncomingWebhookClient client = clients.get(config.getWebhookId());
        if (client == null) {
            return new CompletedRestAction<>(jda, null);
        }
        return client.sendMessage(getCreateDataForMessage(message))
                .map(postedMessage -> {
                    lastPostedTimes.put(config.getWebhookId(), postedMessage.getTimeCreated());
                    return postedMessage;
                });
    }

    @Override
    public void onMessageBulkDelete(MessageBulkDeleteEvent event) {
        getConfigForChannel(event.getChannel().getIdLong()).ifPresent(config ->
                messageRepository.deleteByWebhookIdAndMessageIdIn(config.getWebhookId(),
                        event.getMessageIds().stream().map(Long::valueOf).toList()));
    }

    @Override
    public void onMessageDelete(MessageDeleteEvent event) {
        messageRepository.deleteById(event.getMessageIdLong());
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
        });

        configs.values().forEach(config ->
                jda.retrieveWebhookById(config.getWebhookId()).queue(webhook -> {
                    clients.put(config.getWebhookId(), WebhookClient.createClient(jda, webhook.getUrl()));
                    lastPostedTimes.put(config.getWebhookId(), OffsetDateTime.now());
                    sendLocks.put(config.getWebhookId(), new ReentrantLock());
                }, error -> {
                    log.warn("Failed to initialize webhook {}, removing config: {}", config.getWebhookId(), error.getMessage());
                    configs.remove(config.getWebhookId());
                    configRepository.delete(config);
                }));

        scheduler.scheduleWithFixedDelay(this::sendRandomMessagesIfTimeElapsed, 1, 1, TimeUnit.MINUTES);
    }

    private void sendRandomMessagesIfTimeElapsed() {
        configs.values().forEach(config -> {
            jda.retrieveWebhookById(config.getWebhookId()).flatMap(webhook -> {
                GuildMessageChannel channel = webhook.getChannel().asGuildMessageChannel();
                return channel.retrieveMessageById(channel.getLatestMessageId()).flatMap(latestMessage ->
                        retrieveAvgDurationBetweenMessagesInChannel(channel).flatMap(duration -> {
                            OffsetDateTime lastPosted = lastPostedTimes.getOrDefault(config.getWebhookId(), OffsetDateTime.now());
                            if (duration != null &&
                                    OffsetDateTime.now().isAfter(lastPosted.plus(duration.multipliedBy(config.getDurationMultiplier()))) &&
                                    messageRepository.countByWebhookId(config.getWebhookId()) > 0) {
                                if (latestMessage.getAuthor().getIdLong() == webhook.getDefaultUser().getIdLong()) {
                                    log.info("Latest message is already authored by webhook {}, refusing to post", config.getWebhookId());
                                    lastPostedTimes.put(config.getWebhookId(), latestMessage.getTimeCreated());
                                } else {
                                    return sendRandomMessageImmediate(webhook, config);
                                }
                            }
                            log.debug("Not enough time has passed yet for webhook {}, refusing to post", config.getWebhookId());
                            return new CompletedRestAction<>(jda, null);
                        }));
            }).submit().orTimeout(30, TimeUnit.SECONDS).whenComplete((msg, err) -> {
                if (err != null) {
                    log.error("Failed to send message for webhook {}", config.getWebhookId(), err);
                }
            });
        });
    }

}
