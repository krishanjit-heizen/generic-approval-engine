package com.approvalengine.adapter.persistence.repository;

import com.approvalengine.adapter.persistence.entity.DirectoryUserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DirectoryUserRepository extends JpaRepository<DirectoryUserEntity, String> {

    Optional<DirectoryUserEntity> findByUserIdAndActiveTrue(String userId);

    List<DirectoryUserEntity> findByDepartmentAndActiveTrue(String department);

    List<DirectoryUserEntity> findByUserIdInAndActiveTrue(List<String> userIds);
}
