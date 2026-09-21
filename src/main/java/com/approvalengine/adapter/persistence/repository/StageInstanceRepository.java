package com.approvalengine.adapter.persistence.repository;

import com.approvalengine.adapter.persistence.entity.StageInstanceEntity;
import com.approvalengine.domain.request.StageStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StageInstanceRepository extends JpaRepository<StageInstanceEntity, String> {

    /** Full history, oldest first, including every superseded attempt. */
    List<StageInstanceEntity> findByRequestIdOrderByStageIndexAscAttemptAsc(String requestId);

    /**
     * The live attempt of a stage — the one with the highest attempt number.
     *
     * <p>Every read of "the state of stage N" must go through this rather than matching on
     * {@code stage_index} alone, or a revisited stage would be read as whatever its first
     * attempt concluded.
     */
    Optional<StageInstanceEntity> findFirstByRequestIdAndStageIndexOrderByAttemptDesc(
            String requestId, int stageIndex);

    Optional<StageInstanceEntity> findFirstByRequestIdAndStatus(String requestId, StageStatus status);

    List<StageInstanceEntity> findByRequestIdAndStatus(String requestId, StageStatus status);
}
