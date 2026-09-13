package com.medcloud.platform.tenant.infrastructure;

import com.medcloud.platform.tenant.domain.DoctorProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DoctorProfileRepository extends JpaRepository<DoctorProfile, UUID> {
    List<DoctorProfile> findAllByTenantIdOrderByDisplayNameAsc(UUID tenantId);
}
