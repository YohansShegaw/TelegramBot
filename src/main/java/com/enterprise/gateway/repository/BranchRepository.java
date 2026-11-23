package com.enterprise.gateway.repository;

import com.enterprise.gateway.domain.Branch;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BranchRepository extends JpaRepository<Branch, String> {
    List<Branch> findAllByActiveTrueOrderByBranchCodeAsc();
    Optional<Branch> findByBranchCodeAndActiveTrue(String branchCode);
    Optional<Branch> findByTelegramGroupIdAndActiveTrue(Long telegramGroupId);
}