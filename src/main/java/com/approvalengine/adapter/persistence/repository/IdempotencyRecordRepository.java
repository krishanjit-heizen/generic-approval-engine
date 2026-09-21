package com.approvalengine.adapter.persistence.repository;

import com.approvalengine.adapter.persistence.entity.IdempotencyRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyRecordRepository
        extends JpaRepository<IdempotencyRecordEntity, IdempotencyRecordEntity.Key> {
}
