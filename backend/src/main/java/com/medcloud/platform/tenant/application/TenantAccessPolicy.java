package com.medcloud.platform.tenant.application;

import com.medcloud.platform.common.error.BusinessException;
import com.medcloud.platform.common.error.ErrorCode;
import com.medcloud.platform.security.CurrentUser;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class TenantAccessPolicy {
    public void requireTenant(CurrentUser user, UUID resourceTenantId) {
        if (user == null || user.tenantId() == null || resourceTenantId == null
                || !user.tenantId().equals(resourceTenantId)) {
            throw new BusinessException(ErrorCode.TENANT_ACCESS_DENIED);
        }
    }
}
