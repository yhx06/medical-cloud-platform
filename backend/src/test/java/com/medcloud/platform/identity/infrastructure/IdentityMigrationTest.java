package com.medcloud.platform.identity.infrastructure;

import com.medcloud.platform.identity.domain.UserAccount;
import com.medcloud.platform.identity.domain.UserRole;
import com.medcloud.platform.tenant.infrastructure.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class IdentityMigrationTest {
    private final UserAccountRepository users;
    private final TenantRepository tenants;

    @Autowired
    IdentityMigrationTest(UserAccountRepository users, TenantRepository tenants) {
        this.users = users;
        this.tenants = tenants;
    }

    @Test
    void loadsFourRolesAndTwoIsolatedTenants() {
        UserAccount doctorA = users.findByUsername("doctor_a").orElseThrow();
        UserAccount doctorB = users.findByUsername("doctor_b").orElseThrow();

        assertThat(tenants.count()).isEqualTo(2);
        assertThat(doctorA.getRole()).isEqualTo(UserRole.DOCTOR);
        assertThat(doctorA.getTenantId()).isNotEqualTo(doctorB.getTenantId());
        assertThat(users.findByUsername("patient_demo")).isPresent();
        assertThat(users.findByUsername("hospital_a")).isPresent();
        assertThat(users.findByUsername("platform_admin")).isPresent();
    }
}
