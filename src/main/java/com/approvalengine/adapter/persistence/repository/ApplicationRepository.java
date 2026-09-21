package com.approvalengine.adapter.persistence.repository;

import com.approvalengine.adapter.persistence.entity.ApplicationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ApplicationRepository extends JpaRepository<ApplicationEntity, String> {

    /**
     * Authenticates an inbound call. Lookup is by hash, never by plaintext — the engine has
     * no code path that can read an API key back out of the database.
     */
    Optional<ApplicationEntity> findByApiKeyHashAndActiveTrue(String apiKeyHash);

    Optional<ApplicationEntity> findByName(String name);
}
