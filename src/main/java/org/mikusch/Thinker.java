package org.mikusch;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.attribute.IAgeRestrictedChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.channel.ChannelDeleteEvent;
import net.dv8tion.jda.api.events.channel.update.ChannelUpdateArchivedEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageBulkDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
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
import org.mikusch.entity.ThinkerMessageId;
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
    private static final String INVALID_MESSAGE_REFERENCE = "Invalid message ID or message link.";
    private static final int MAX_SEND_ATTEMPTS = 10;
    private static final int COLLECT_BATCH_SIZE = 1000;
    private static final Duration SEND_LEASE_TIMEOUT = Duration.ofMinutes(2);
    private static final Predicate<Throwable> UNAVAILABLE_MESSAGE_ERRORS = ErrorResponse.test(
                    ErrorResponse.UNKNOWN_MESSAGE, ErrorResponse.UNKNOWN_CHANNEL, ErrorResponse.MISSING_ACCESS)
            .or(UnavailableChannelException.class::isInstance);

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

    private record MessageRef(long channelId, long messageId) {}

    private static final class UnavailableChannelException extends RuntimeException {
        private UnavailableChannelException(long channelId) {
            super("Channel %d is not available as a source for this Thinker.".formatted(channelId));
        }
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

    private static boolean isAgeRestricted(GuildChannel channel) {
        GuildChannel target = channel instanceof ThreadChannel thread ? thread.getParentChannel() : channel;
        return target instanceof IAgeRestrictedChannel restricted && restricted.isNSFW();
    }

    private static ThinkerMessage toThinkerMessage(Message message, ThinkerConfig config) {
        return new ThinkerMessage(
                message.getIdLong(),
                message.getChannel().getIdLong(),
                message.getGuild().getIdLong(),
                config.getWebhookId());
    }

    private void discardBlockedMessage(Message message, ThinkerConfig config) {
        log.info(
                "Message {} contains a blocked word, removing it from the pool of webhook {}",
                message.getIdLong(),
                config.getWebhookId());
        removeFromPool(message.getIdLong(), config);
    }

    private void discardUnavailableMessage(MessageRef ref, ThinkerConfig config, Throwable cause) {
        log.info(
                "Message {} in channel {} is no longer available ({}), removing it from the pool of webhook {}",
                ref.messageId(),
                ref.channelId(),
                cause.getMessage(),
                config.getWebhookId());
        removeFromPool(ref.messageId(), config);
    }

    private void removeFromPool(long messageId, ThinkerConfig config) {
        messageRepository.deleteByWebhookIdAndMessageId(config.getWebhookId(), messageId);
    }

    private Optional<ThinkerConfig> getConfigForChannel(long channelId) {
        return configs.values().stream()
                .filter(c -> c.getChannelId().equals(channelId))
                .findFirst();
    }

    private Optional<GuildMessageChannel> getDestinationChannel(ThinkerConfig config) {
        return Optional.ofNullable(jda.getChannelById(GuildMessageChannel.class, config.getChannelId()));
    }

    private boolean isEligibleSource(GuildMessageChannel source, ThinkerConfig config) {
        return getDestinationChannel(config)
                .filter(destination ->
                        destination.getGuild().getIdLong() == source.getGuild().getIdLong())
                .filter(destination -> isAgeRestricted(destination) || !isAgeRestricted(source))
                .isPresent();
    }

    private List<GuildMessageChannel> getSourceChannels(Guild guild, ThinkerConfig config) {
        Member selfMember = guild.getSelfMember();
        return Stream.concat(guild.getChannels().stream(), guild.getThreadChannels().stream())
                .filter(GuildMessageChannel.class::isInstance)
                .map(GuildMessageChannel.class::cast)
                .filter(channel -> !(channel instanceof ThreadChannel thread) || !thread.isArchived())
                .filter(channel ->
                        selfMember.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY))
                .filter(channel -> isEligibleSource(channel, config))
                .toList();
    }

    private Optional<MessageRef> resolveMessageRef(String input, ThinkerConfig config) {
        String value = input.trim();
        try {
            Matcher link = Message.JUMP_URL_PATTERN.matcher(value);
            if (link.matches()) {
                return Optional.of(
                        new MessageRef(Long.parseLong(link.group("channel")), Long.parseLong(link.group("message"))));
            }

            long messageId = Long.parseLong(value);
            long channelId = messageRepository
                    .findById(new ThinkerMessageId(messageId, config.getWebhookId()))
                    .map(ThinkerMessage::getChannelId)
                    .orElse(config.getChannelId());
            return Optional.of(new MessageRef(channelId, messageId));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private RestAction<Message> retrieveMessage(MessageRef ref, ThinkerConfig config) {
        GuildMessageChannel source = jda.getChannelById(GuildMessageChannel.class, ref.channelId());
        if (source == null || !isEligibleSource(source, config)) {
            return new CompletedRestAction<>(jda, new UnavailableChannelException(ref.channelId()));
        }
        return source.retrieveMessageById(ref.messageId());
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
            String messageOption = event.getOption(THINKER_COMMAND_MESSAGE_PARAM_NAME, OptionMapping::getAsString);
            if (messageOption == null || messageOption.isBlank()) {
                sendRandomMessageImmediate(config)
                        .flatMap(message -> message == null
                                ? hook.editOriginal("No messages available to think of yet. Run /thinkercollect first.")
                                : hook.editOriginal("Thought of a random message: " + message.getJumpUrl()))
                        .queue();
                return;
            }

            Optional<MessageRef> ref = resolveMessageRef(messageOption, config);
            if (ref.isEmpty()) {
                hook.editOriginal(INVALID_MESSAGE_REFERENCE).queue();
                return;
            }

            retrieveMessage(ref.get(), config)
                    .flatMap(msg -> {
                        if (containsBlockedWord(msg, config)) {
                            discardBlockedMessage(msg, config);
                            return hook.editOriginal(
                                    "That message contains a trigger word (or %s), so the Thinker won't repost it."
                                            .formatted(MarkdownUtil.monospace(ALWAYS_BLOCKED_WORD)));
                        }
                        return sendMessageImmediate(msg, config)
                                .flatMap(message -> message == null
                                        ? hook.editOriginal(
                                                "Couldn't send that message; the Thinker may not be fully set up.")
                                        : hook.editOriginal("Thought of a specific message: " + message.getJumpUrl()));
                    })
                    .queue(
                            null,
                            error -> hook.editOriginal(
                                            "Failed to repost that message: %s".formatted(error.getMessage()))
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
            String messageOption = event.getOption(THINKER_COMMAND_MESSAGE_PARAM_NAME, OptionMapping::getAsString);

            if (messageOption == null || messageOption.isBlank()) {
                setPreset(config, null);
                hook.editOriginal("Preset message cleared. The Thinker will think of a random message next.")
                        .queue();
                return;
            }

            Optional<MessageRef> ref = resolveMessageRef(messageOption, config);
            if (ref.isEmpty()) {
                hook.editOriginal(INVALID_MESSAGE_REFERENCE).queue();
                return;
            }

            retrieveMessage(ref.get(), config)
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
                                setPreset(
                                        config,
                                        new MessageRef(message.getChannel().getIdLong(), message.getIdLong()));
                                hook.editOriginal(
                                                "Preset the next message: %s\nIt will be posted the next time the Thinker speaks (trigger, reply, or idle timer)."
                                                        .formatted(message.getJumpUrl()))
                                        .queue();
                            },
                            error -> hook.editOriginal("Failed to find that message: %s".formatted(error.getMessage()))
                                    .queue());
        });
    }

    private void setPreset(ThinkerConfig config, MessageRef ref) {
        ReentrantLock lock = sendLocks.computeIfAbsent(config.getWebhookId(), k -> new ReentrantLock());
        lock.lock();
        try {
            config.setPresetChannelId(ref == null ? null : ref.channelId());
            config.setPresetMessageId(ref == null ? null : ref.messageId());
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

            Guild guild = event.getGuild();
            if (guild == null) {
                hook.editOriginal("This command can only be used in a server.").queue();
                return;
            }

            ThinkerConfig config = configOpt.get();
            if (!collectingWebhookIds.add(config.getWebhookId())) {
                hook.editOriginal("A collection is already running for this channel. Wait for it to finish.")
                        .queue();
                return;
            }

            List<GuildMessageChannel> sources = getSourceChannels(guild, config);
            hook.editOriginal(
                            "Collecting messages from %d channels. On a busy server this can take a while; if this message stops updating, the result is in the bot log."
                                    .formatted(sources.size()))
                    .queue();

            CompletableFuture.runAsync(() -> collectMessages(sources, config, hook))
                    .whenComplete((ignored, error) -> {
                        collectingWebhookIds.remove(config.getWebhookId());
                        if (error != null) {
                            log.error("Thinker message collection failed for webhook {}", config.getWebhookId(), error);
                        }
                    });
        });
    }

    private void collectMessages(List<GuildMessageChannel> sources, ThinkerConfig config, InteractionHook hook) {
        long webhookId = config.getWebhookId();
        long startCount = messageRepository.countByWebhookId(webhookId);
        log.info(
                "Starting Thinker message collection for webhook {} across {} channels. Current messages in database: {}",
                webhookId,
                sources.size(),
                startCount);

        long savedCount = 0;
        for (int i = 0; i < sources.size(); i++) {
            GuildMessageChannel source = sources.get(i);
            try {
                savedCount += collectChannel(source, config);
            } catch (Exception e) {
                log.warn(
                        "Failed to collect messages from channel {} ({}) for webhook {}",
                        source.getName(),
                        source.getId(),
                        webhookId,
                        e);
            }
            hook.editOriginal("Collected %d new messages so far (%d/%d channels done)."
                            .formatted(savedCount, i + 1, sources.size()))
                    .queue(
                            null,
                            error -> log.debug(
                                    "Could not report collection progress for webhook {}: {}",
                                    webhookId,
                                    error.getMessage()));
        }

        long endCount = messageRepository.countByWebhookId(webhookId);
        log.info(
                "Collection complete for webhook {}. Collected {} messages from {} channels. Total in database: {}",
                webhookId,
                endCount - startCount,
                sources.size(),
                endCount);

        hook.editOriginal("Collected %d messages from %d channels. Total messages in database: %d"
                        .formatted(endCount - startCount, sources.size(), endCount))
                .queue(
                        null,
                        reportError -> log.warn(
                                "Could not report the collection result for webhook {}, the interaction has likely expired: {}",
                                webhookId,
                                reportError.getMessage()));
    }

    private long collectChannel(GuildMessageChannel source, ThinkerConfig config) {
        log.info(
                "Collecting messages from channel {} ({}) for webhook {}",
                source.getName(),
                source.getId(),
                config.getWebhookId());

        AtomicLong savedCount = new AtomicLong();
        List<ThinkerMessage> batch = new ArrayList<>();
        source.getIterableHistory().cache(false).forEach(message -> {
            if (shouldSaveMessage(message, config)) {
                batch.add(toThinkerMessage(message, config));
                if (batch.size() >= COLLECT_BATCH_SIZE) {
                    savedCount.addAndGet(saveBatch(batch, config));
                }
            }
        });
        savedCount.addAndGet(saveBatch(batch, config));

        log.info(
                "Collected {} new messages from channel {} ({}) for webhook {}",
                savedCount.get(),
                source.getName(),
                source.getId(),
                config.getWebhookId());
        return savedCount.get();
    }

    private long saveBatch(List<ThinkerMessage> batch, ThinkerConfig config) {
        if (batch.isEmpty()) {
            return 0;
        }

        List<Long> messageIds = batch.stream().map(ThinkerMessage::getMessageId).toList();
        Set<Long> existingIds =
                new HashSet<>(messageRepository.findExistingMessageIds(config.getWebhookId(), messageIds));
        List<ThinkerMessage> newMessages = batch.stream()
                .filter(message -> !existingIds.contains(message.getMessageId()))
                .toList();
        batch.clear();

        if (newMessages.isEmpty()) {
            return 0;
        }

        try {
            messageRepository.saveAll(newMessages);
            return newMessages.size();
        } catch (Exception e) {
            log.error("Failed to save batch: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if (!event.isFromGuild() || event.getMessage().isWebhookMessage()) return;

        Message message = event.getMessage();
        GuildMessageChannel channel = event.getGuildChannel();

        configs.values().stream()
                .filter(config -> isEligibleSource(channel, config))
                .forEach(config -> {
                    trackMessage(message, config);
                    if (channel.getIdLong() == config.getChannelId()) {
                        respondIfTriggered(config, message);
                    }
                });
    }

    private void trackMessage(Message message, ThinkerConfig config) {
        if (shouldSaveMessage(message, config)) {
            messageRepository.save(toThinkerMessage(message, config));
        }
    }

    private void respondIfTriggered(ThinkerConfig config, Message message) {
        Long defaultUserId = webhookDefaultUserIds.get(config.getWebhookId());
        if (defaultUserId != null) {
            respondIfTriggered(config, defaultUserId, message);
            return;
        }

        jda.retrieveWebhookById(config.getWebhookId())
                .queue(
                        webhook -> {
                            long resolved = webhook.getDefaultUser().getIdLong();
                            webhookDefaultUserIds.put(config.getWebhookId(), resolved);
                            respondIfTriggered(config, resolved, message);
                        },
                        error -> log.warn(
                                "Failed to resolve webhook {} for received message: {}",
                                config.getWebhookId(),
                                error.getMessage()));
    }

    private void respondIfTriggered(ThinkerConfig config, long webhookDefaultUserId, Message message) {
        if (message.getAuthor().getIdLong() == webhookDefaultUserId) {
            return;
        }

        boolean isReply = !config.getTriggers().isEmpty()
                && message.getType() == MessageType.INLINE_REPLY
                && message.getReferencedMessage() != null
                && message.getReferencedMessage().getAuthor().getIdLong() == webhookDefaultUserId;
        String content = message.getContentDisplay().toLowerCase(Locale.ROOT);
        boolean hasChatTrigger =
                config.getTriggers().stream().anyMatch(trigger -> content.contains(trigger.toLowerCase(Locale.ROOT)));

        if (isReply || hasChatTrigger) {
            sendRandomMessageImmediate(config).queue();
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

    private RestAction<Message> sendRandomMessageImmediate(ThinkerConfig config) {
        long webhookId = config.getWebhookId();
        if (!tryAcquireSendLease(webhookId)) {
            log.info("A send is already in progress for webhook {}, refusing to send another message", webhookId);
            return new CompletedRestAction<>(jda, null);
        }

        return releasingSendLease(webhookId, sendNextMessage(config));
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

    private RestAction<Message> sendNextMessage(ThinkerConfig config) {
        MessageRef preset = consumePreset(config);
        if (preset == null) {
            return selectAndSendRandomMessage(config, 0);
        }

        log.info("Sending preset message {} for webhook {}", preset.messageId(), config.getWebhookId());
        return sendStoredMessage(preset, config, () -> selectAndSendRandomMessage(config, 0));
    }

    private MessageRef consumePreset(ThinkerConfig config) {
        ReentrantLock lock = sendLocks.computeIfAbsent(config.getWebhookId(), k -> new ReentrantLock());
        lock.lock();
        try {
            Long presetMessageId = config.getPresetMessageId();
            if (presetMessageId == null) {
                return null;
            }
            Long presetChannelId = config.getPresetChannelId();
            config.setPresetChannelId(null);
            config.setPresetMessageId(null);
            configRepository.save(config);
            return new MessageRef(presetChannelId != null ? presetChannelId : config.getChannelId(), presetMessageId);
        } finally {
            lock.unlock();
        }
    }

    private RestAction<Message> selectAndSendRandomMessage(ThinkerConfig config, int attempt) {
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
                .map(thinkerMessage -> sendStoredMessage(
                        new MessageRef(thinkerMessage.getChannelId(), thinkerMessage.getMessageId()),
                        config,
                        () -> selectAndSendRandomMessage(config, attempt + 1)))
                .orElseGet(() -> new CompletedRestAction<>(jda, null));
    }

    private RestAction<Message> sendStoredMessage(
            MessageRef ref, ThinkerConfig config, Supplier<RestAction<Message>> fallback) {
        return retrieveMessage(ref, config)
                .onErrorFlatMap(UNAVAILABLE_MESSAGE_ERRORS, error -> {
                    discardUnavailableMessage(ref, config, error);
                    return new CompletedRestAction<>(jda, null);
                })
                .flatMap(message -> {
                    if (message == null) {
                        return fallback.get();
                    }
                    if (containsBlockedWord(message, config)) {
                        discardBlockedMessage(message, config);
                        return fallback.get();
                    }
                    return sendMessageImmediate(message, config);
                });
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
    public void onMessageBulkDelete(@NotNull MessageBulkDeleteEvent event) {
        messageRepository.deleteByMessageIdIn(
                event.getMessageIds().stream().map(Long::valueOf).toList());
    }

    @Override
    public void onMessageDelete(@NotNull MessageDeleteEvent event) {
        messageRepository.deleteByMessageIdIn(List.of(event.getMessageIdLong()));
    }

    @Override
    public void onChannelDelete(@NotNull ChannelDeleteEvent event) {
        messageRepository.deleteByChannelId(event.getChannel().getIdLong());
    }

    @Override
    public void onChannelUpdateArchived(@NotNull ChannelUpdateArchivedEvent event) {
        if (Boolean.TRUE.equals(event.getNewValue())) {
            messageRepository.deleteByChannelId(event.getChannel().getIdLong());
        }
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
                    .addOption(
                            OptionType.STRING,
                            THINKER_COMMAND_MESSAGE_PARAM_NAME,
                            "The ID or link of the message to copy (from any channel in this server)")
                    .queue();

            guild.upsertCommand(THINKER_TRIGGER_COMMAND_NAME, "Sets a new trigger word for the thinker")
                    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_WEBHOOKS))
                    .addOption(OptionType.STRING, THINKER_TRIGGER_COMMAND_TRIGGER_PARAM_NAME, "The new trigger word")
                    .queue();

            guild.upsertCommand(THINKER_COLLECT_COMMAND_NAME, "Collects messages from every channel in this server")
                    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_WEBHOOKS))
                    .queue();

            guild.upsertCommand(THINKER_PRESET_COMMAND_NAME, "Pre-sets the next Thinker message without posting it now")
                    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_WEBHOOKS))
                    .addOption(
                            OptionType.STRING,
                            THINKER_COMMAND_MESSAGE_PARAM_NAME,
                            "The ID or link of the message to pre-set (omit to clear the preset)")
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
                                    .flatMap(messages -> sendRandomMessageIfDue(config, webhook, messages));
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
            ThinkerConfig config, Webhook webhook, List<Message> recentMessages) {
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

        return sendRandomMessageImmediate(config);
    }
}
