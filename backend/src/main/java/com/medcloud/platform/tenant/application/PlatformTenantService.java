package com.medcloud.platform.tenant.application;

import com.medcloud.platform.common.error.BusinessException;
import com.medcloud.platform.common.error.ErrorCode;
import com.medcloud.platform.identity.domain.UserAccount;
import com.medcloud.platform.identity.domain.UserRole;
import com.medcloud.platform.identity.domain.UserStatus;
import com.medcloud.platform.identity.infrastructure.UserAccountRepository;
import com.medcloud.platform.security.CurrentUser;
import com.medcloud.platform.tenant.domain.Tenant;
import com.medcloud.platform.tenant.domain.TenantStatus;
import com.medcloud.platform.tenant.infrastructure.TenantRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class PlatformTenantService {
    private final TenantRepository tenants;
    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;

    public PlatformTenantService(
            TenantRepository tenants,
            UserAccountRepository users,
            PasswordEncoder passwordEncoder
    ) {
        this.tenants = tenants;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public Tenant createTenant(CurrentUser actor, CreateTenantCommand command) {
        requirePlatformAdmin(actor);
        return tenants.save(Tenant.create(command.name().trim()));
    }

    @Transactional
    public UserAccount createHospitalAdmin(CurrentUser actor, UUID tenantId, CreateAccountCommand command) {
        requirePlatformAdmin(actor);
        if (tenants.findByIdAndStatus(tenantId, TenantStatus.ACTIVE).isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        String username = command.username().trim();
        if (users.existsByUsername(username)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        UserAccount account = UserAccount.createHospitalAdmin(
                tenantId, username, passwordEncoder.encode(command.password()));
        return users.save(account);
    }

    @Transactional
    public Tenant changeTenantStatus(CurrentUser actor, UUID tenantId, TenantStatus status) {
        requirePlatformAdmin(actor);
        Tenant tenant = tenants.findById(tenantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        tenant.changeStatus(status);
        return tenant;
    }

    @Transactional
    public UserAccount changeAccountStatus(CurrentUser actor, UUID accountId, UserStatus status) {
        requirePlatformAdmin(actor);
        UserAccount account = users.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        account.changeStatus(status);
        return account;
    }

    private void requirePlatformAdmin(CurrentUser actor) {
        if (actor == null || actor.role() != UserRole.PLATFORM_ADMIN) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
    }

    public record CreateTenantCommand(
            @NotBlank @Size(max = 100) String name
    ) {
    }

    public record CreateAccountCommand(
            @NotBlank @Size(max = 64) String username,
            @NotBlank @Size(min = 8, max = 72) String password
    ) {
    }
}
