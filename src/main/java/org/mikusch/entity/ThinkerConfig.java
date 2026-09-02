package org.mikusch.entity;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "thinker_config")
@Data
@NoArgsConstructor
public class ThinkerConfig {

    @Id
    private Long webhookId;

    private Long channelId;

    @ElementCollection(fetch = FetchType.EAGER)
    private volatile List<String> triggers = new ArrayList<>();

    private long durationMultiplier = 40L;

    private volatile Long presetChannelId;

    private volatile Long presetMessageId;

    public ThinkerConfig(Long webhookId, Long channelId) {
        this.webhookId = webhookId;
        this.channelId = channelId;
    }
}
