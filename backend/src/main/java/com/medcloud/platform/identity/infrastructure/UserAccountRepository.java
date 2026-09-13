package com.medcloud.platform.identity.infrastructure;

import com.medcloud.platform.identity.domain.UserAccount;
import com.medcloud.platform.identity.domain.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {
    Optional<UserAccount> findByUsername(String username);

    Optional<UserAccount> findByIdAndStatus(UUID id, UserStatus status);

    boolean existsByUsername(String username);
}
