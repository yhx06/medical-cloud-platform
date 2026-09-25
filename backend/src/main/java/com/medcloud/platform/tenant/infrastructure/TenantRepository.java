package com.medcloud.platform.tenant.infrastructure;

import com.medcloud.platform.tenant.domain.Tenant;
import com.medcloud.platform.tenant.domain.TenantStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {
    Optional<Tenant> findByIdAndStatus(UUID id, TenantStatus status);

    List<Tenant> findAllByStatusOrderByNameAsc(TenantStatus status);
}
