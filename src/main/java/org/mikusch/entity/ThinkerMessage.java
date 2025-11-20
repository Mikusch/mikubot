package org.mikusch.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "thinker_messages")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ThinkerMessage {

    @Id
    private Long messageId;

    private Long channelId;

    private Long guildId;

    private Long webhookId;

}
