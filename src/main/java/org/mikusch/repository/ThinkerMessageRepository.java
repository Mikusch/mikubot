package org.mikusch.repository;

import org.mikusch.entity.ThinkerMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ThinkerMessageRepository extends JpaRepository<ThinkerMessage, Long> {

    List<ThinkerMessage> findByWebhookId(Long webhookId);

    void deleteByWebhookIdAndMessageIdIn(Long webhookId, List<Long> messageIds);

    @Query(value = "SELECT * FROM thinker_messages WHERE webhook_id = ?1 ORDER BY RANDOM() LIMIT 1", nativeQuery = true)
    Optional<ThinkerMessage> findRandomByWebhookId(Long webhookId);

    long countByWebhookId(Long webhookId);

    @Query(value = "SELECT * FROM thinker_messages WHERE webhook_id = :webhookId LIMIT 1 OFFSET :offset", nativeQuery = true)
    Optional<ThinkerMessage> findByWebhookIdWithOffset(@Param("webhookId") Long webhookId, @Param("offset") long offset);

    @Query("SELECT tm.messageId FROM ThinkerMessage tm WHERE tm.webhookId = :webhookId AND tm.messageId IN :messageIds")
    List<Long> findExistingMessageIds(@Param("webhookId") Long webhookId, @Param("messageIds") List<Long> messageIds);

}
