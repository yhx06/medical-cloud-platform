package com.medcloud.platform.identity.infrastructure;

import com.medcloud.platform.identity.domain.UserAccount;
import com.medcloud.platform.identity.domain.UserRole;
import com.medcloud.platform.identity.domain.UserStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {
    Optional<UserAccount> findByUsername(String username);

    Optional<UserAccount> findByIdAndStatus(UUID id, UserStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from UserAccount account where account.id = :id")
    Optional<UserAccount> findByIdForUpdate(@Param("id") UUID id);

    boolean existsByUsername(String username);

    List<UserAccount> findAllByTenantIdAndRoleOrderByUsernameAsc(UUID tenantId, UserRole role);
}
