package org.mikusch.entity;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ThinkerMessageId implements Serializable {

    private Long messageId;

    private Long webhookId;
}
