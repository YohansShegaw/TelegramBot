package com.enterprise.gateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(
        name = "relayed_messages",
        indexes = {
                @Index(name = "idx_relayed_source", columnList = "source_chat_id, source_message_id"),
                @Index(name = "idx_relayed_target", columnList = "target_chat_id, target_message_id")
        }
)
public class RelayedMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ticket_id", nullable = false)
    private Long ticketId;

    @Column(name = "source_chat_id", nullable = false)
    private Long sourceChatId;

    @Column(name = "source_message_id", nullable = false)
    private Integer sourceMessageId;

    @Column(name = "target_chat_id", nullable = false)
    private Long targetChatId;

    @Column(name = "target_message_id", nullable = false)
    private Integer targetMessageId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }

    public Long getTicketId() { return ticketId; }
    public void setTicketId(Long ticketId) { this.ticketId = ticketId; }

    public Long getSourceChatId() { return sourceChatId; }
    public void setSourceChatId(Long sourceChatId) { this.sourceChatId = sourceChatId; }

    public Integer getSourceMessageId() { return sourceMessageId; }
    public void setSourceMessageId(Integer sourceMessageId) { this.sourceMessageId = sourceMessageId; }

    public Long getTargetChatId() { return targetChatId; }
    public void setTargetChatId(Long targetChatId) { this.targetChatId = targetChatId; }

    public Integer getTargetMessageId() { return targetMessageId; }
    public void setTargetMessageId(Integer targetMessageId) { this.targetMessageId = targetMessageId; }

    public Instant getCreatedAt() { return createdAt; }
}