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
        name = "customer_tickets",
        indexes = {
                @Index(name = "idx_tickets_user", columnList = "telegram_user_id"),
                @Index(name = "idx_tickets_group_topic", columnList = "telegram_group_id, topic_id")
        }
)
public class CustomerTicket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "telegram_user_id", nullable = false)
    private Long telegramUserId;

    @Column(name = "customer_name", length = 160)
    private String customerName;

    @Column(name = "customer_username", length = 120)
    private String customerUsername;

    @Column(name = "branch_code", length = 32, nullable = false)
    private String branchCode;

    @Column(name = "telegram_group_id", nullable = false)
    private Long telegramGroupId;

    @Column(name = "topic_id", nullable = false)
    private Integer topicId;

    @Column(name = "department", length = 80, nullable = false)
    private String department;

    @Column(name = "listing_ref", length = 120)
    private String listingRef;

    @Column(name = "status", length = 24, nullable = false)
    private String status = "OPEN";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Long getId() { return id; }

    public Long getTelegramUserId() { return telegramUserId; }
    public void setTelegramUserId(Long telegramUserId) { this.telegramUserId = telegramUserId; }

    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }

    public String getCustomerUsername() { return customerUsername; }
    public void setCustomerUsername(String customerUsername) { this.customerUsername = customerUsername; }

    public String getBranchCode() { return branchCode; }
    public void setBranchCode(String branchCode) { this.branchCode = branchCode; }

    public Long getTelegramGroupId() { return telegramGroupId; }
    public void setTelegramGroupId(Long telegramGroupId) { this.telegramGroupId = telegramGroupId; }

    public Integer getTopicId() { return topicId; }
    public void setTopicId(Integer topicId) { this.topicId = topicId; }

    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }

    public String getListingRef() { return listingRef; }
    public void setListingRef(String listingRef) { this.listingRef = listingRef; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}