package com.enterprise.gateway.repository;

import com.enterprise.gateway.domain.CustomerTicket;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerTicketRepository extends JpaRepository<CustomerTicket, Long> {
    Optional<CustomerTicket> findFirstByTelegramUserIdAndStatusOrderByIdDesc(Long telegramUserId, String status);
    Optional<CustomerTicket> findFirstByTelegramGroupIdAndTopicIdAndStatusOrderByIdDesc(
            Long telegramGroupId, Integer topicId, String status
    );
}