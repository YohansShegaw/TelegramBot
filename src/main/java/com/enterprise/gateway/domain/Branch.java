package com.enterprise.gateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "branches")
public class Branch {

    @Id
    @Column(name = "branch_code", length = 32, nullable = false)
    private String branchCode;

    @Column(name = "branch_name", length = 120, nullable = false)
    private String branchName;

    @Column(name = "telegram_group_id", nullable = false, unique = true)
    private Long telegramGroupId;

    @Column(name = "default_channel", length = 120)
    private String defaultChannel;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public String getBranchCode() { return branchCode; }
    public void setBranchCode(String branchCode) { this.branchCode = branchCode; }

    public String getBranchName() { return branchName; }
    public void setBranchName(String branchName) { this.branchName = branchName; }

    public Long getTelegramGroupId() { return telegramGroupId; }
    public void setTelegramGroupId(Long telegramGroupId) { this.telegramGroupId = telegramGroupId; }

    public String getDefaultChannel() { return defaultChannel; }
    public void setDefaultChannel(String defaultChannel) { this.defaultChannel = defaultChannel; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public Instant getCreatedAt() { return createdAt; }
}