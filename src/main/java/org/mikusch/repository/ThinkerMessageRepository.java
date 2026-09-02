package org.mikusch.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.mikusch.entity.ThinkerMessage;
import org.mikusch.entity.ThinkerMessageId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface ThinkerMessageRepository extends JpaRepository<ThinkerMessage, ThinkerMessageId> {

    @Modifying
    @Transactional
    @Query("DELETE FROM ThinkerMessage tm WHERE tm.webhookId = :webhookId AND tm.messageId = :messageId")
    void deleteByWebhookIdAndMessageId(@Param("webhookId") Long webhookId, @Param("messageId") Long messageId);

    @Modifying
    @Transactional
    @Query("DELETE FROM ThinkerMessage tm WHERE tm.messageId IN :messageIds")
    void deleteByMessageIdIn(@Param("messageIds") Collection<Long> messageIds);

    @Modifying
    @Transactional
    @Query("DELETE FROM ThinkerMessage tm WHERE tm.channelId = :channelId")
    void deleteByChannelId(@Param("channelId") Long channelId);

    @Query(value = "SELECT * FROM thinker_messages WHERE webhook_id = ?1 ORDER BY RANDOM() LIMIT 1", nativeQuery = true)
    Optional<ThinkerMessage> findRandomByWebhookId(Long webhookId);

    long countByWebhookId(Long webhookId);

    @Query(
            value = "SELECT * FROM thinker_messages WHERE webhook_id = :webhookId LIMIT 1 OFFSET :offset",
            nativeQuery = true)
    Optional<ThinkerMessage> findByWebhookIdWithOffset(
            @Param("webhookId") Long webhookId, @Param("offset") long offset);

    @Query("SELECT tm.messageId FROM ThinkerMessage tm WHERE tm.webhookId = :webhookId AND tm.messageId IN :messageIds")
    List<Long> findExistingMessageIds(@Param("webhookId") Long webhookId, @Param("messageIds") List<Long> messageIds);
}
