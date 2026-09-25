package com.medcloud.platform.tenant.infrastructure;

import com.medcloud.platform.tenant.domain.Department;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DepartmentRepository extends JpaRepository<Department, UUID> {
    List<Department> findAllByTenantIdOrderByNameAsc(UUID tenantId);

    List<Department> findAllByTenantIdAndStatusOrderByNameAsc(UUID tenantId, com.medcloud.platform.tenant.domain.TenantStatus status);

    boolean existsByTenantIdAndName(UUID tenantId, String name);

    boolean existsByTenantIdAndNameAndIdNot(UUID tenantId, String name, UUID id);
}
