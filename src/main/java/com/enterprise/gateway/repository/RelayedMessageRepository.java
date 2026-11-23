package com.enterprise.gateway.repository;

import com.enterprise.gateway.domain.RelayedMessage;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RelayedMessageRepository extends JpaRepository<RelayedMessage, Long> {

    Optional<RelayedMessage> findFirstBySourceChatIdAndSourceMessageIdOrderByIdDesc(
            Long sourceChatId, Integer sourceMessageId
    );

    Optional<RelayedMessage> findFirstByTargetChatIdAndTargetMessageIdOrderByIdDesc(
            Long targetChatId, Integer targetMessageId
    );
}