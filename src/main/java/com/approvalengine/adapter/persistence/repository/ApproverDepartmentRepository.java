package com.approvalengine.adapter.persistence.repository;

import com.approvalengine.adapter.persistence.entity.ApproverDepartmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApproverDepartmentRepository extends JpaRepository<ApproverDepartmentEntity, String> {

    boolean existsByDeptCodeAndActiveTrue(String deptCode);

    List<ApproverDepartmentEntity> findByActiveTrueOrderByDeptCodeAsc();
}
