package com.medcloud.platform.identity.application;

import com.medcloud.platform.common.error.BusinessException;
import com.medcloud.platform.common.error.ErrorCode;
import com.medcloud.platform.identity.domain.UserAccount;
import com.medcloud.platform.identity.domain.UserStatus;
import com.medcloud.platform.identity.infrastructure.UserAccountRepository;
import com.medcloud.platform.security.CurrentUser;
import com.medcloud.platform.tenant.domain.TenantStatus;
import com.medcloud.platform.tenant.infrastructure.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountAccessPolicy {
    private final UserAccountRepository users;
    private final TenantRepository tenants;

    public AccountAccessPolicy(UserAccountRepository users, TenantRepository tenants) {
        this.users = users;
        this.tenants = tenants;
    }

    @Transactional(readOnly = true)
    public void requireActive(CurrentUser user) {
        UserAccount account = users.findByIdAndStatus(user.userId(), UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_DISABLED));
        if (account.getTenantId() != null
                && tenants.findByIdAndStatus(account.getTenantId(), TenantStatus.ACTIVE).isEmpty()) {
            throw new BusinessException(ErrorCode.TENANT_DISABLED);
        }
    }
}
