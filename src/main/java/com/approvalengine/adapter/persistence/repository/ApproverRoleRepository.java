package com.approvalengine.adapter.persistence.repository;

import com.approvalengine.adapter.persistence.entity.ApproverRoleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApproverRoleRepository extends JpaRepository<ApproverRoleEntity, String> {

    /** Publish-time existence check. Inactive roles are treated as absent — a workflow must
     *  not be published against a role that has been retired. */
    boolean existsByRoleCodeAndActiveTrue(String roleCode);

    List<ApproverRoleEntity> findByActiveTrueOrderByRoleCodeAsc();
}
