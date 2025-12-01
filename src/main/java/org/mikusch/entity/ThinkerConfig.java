package org.mikusch.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "thinker_config")
@Data
@NoArgsConstructor
public class ThinkerConfig {

    @Id
    private Long webhookId;

    private Long channelId;

    @ElementCollection(fetch = FetchType.EAGER)
    private List<String> triggers = new ArrayList<>();

    private long durationMultiplier = 40L;

    public ThinkerConfig(Long webhookId, Long channelId) {
        this.webhookId = webhookId;
        this.channelId = channelId;
    }

}
