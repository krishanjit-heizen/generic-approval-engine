package com.approvalengine.adapter.persistence.repository;

import com.approvalengine.adapter.persistence.entity.ApproverTeamEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApproverTeamRepository extends JpaRepository<ApproverTeamEntity, String> {

    boolean existsByTeamCodeAndActiveTrue(String teamCode);

    List<ApproverTeamEntity> findByActiveTrueOrderByTeamCodeAsc();
}
