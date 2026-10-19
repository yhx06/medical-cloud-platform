package com.medcloud.course.paas.tenant;

import com.medcloud.course.contract.Role;
import com.medcloud.course.paas.auth.Account;
import com.medcloud.course.paas.auth.CurrentActor;
import com.medcloud.course.paas.auth.AccessDeniedException;
import javax.persistence.EntityManager;
import javax.persistence.LockModeType;
import javax.persistence.EntityNotFoundException;

/** Transaction-scoped access helper; the caller owns the transaction. */
public final class TenantAccessPolicy {
    private final EntityManager manager;

    public TenantAccessPolicy(EntityManager manager) { this.manager = manager; }

    public Account requireAccount(CurrentActor actor, Role requiredRole) {
        if (actor == null || actor.getUserId() == null) throw new SecurityException("authentication required");
        // Reject an incompatible claimed role before touching persistence; matching claims still require DB validation.
        if (actor.getRole() != requiredRole) throw new AccessDeniedException();
        Account account = manager.find(Account.class, actor.getUserId());
        if (account == null) throw new SecurityException("inactive account");
        try { manager.refresh(account); }
        catch (EntityNotFoundException missing) { throw new SecurityException("inactive account", missing); }
        if (!"ACTIVE".equals(account.getStatus()) || account.getRole() == null)
            throw new SecurityException("inactive account");
        if (account.getRole() != requiredRole || actor.getRole() != account.getRole()) throw new AccessDeniedException();
        return account;
    }

    public Tenant readableTenant(CurrentActor actor, Role requiredRole) {
        return readableTenant(requireAccount(actor, requiredRole).getTenantId());
    }

    public Tenant readableTenant(String tenantId) {
        if (tenantId == null) throw new SecurityException("hospital unavailable");
        Tenant tenant = manager.find(Tenant.class, tenantId);
        if (tenant == null) throw new SecurityException("hospital unavailable");
        try { manager.refresh(tenant); }
        catch (EntityNotFoundException missing) { throw new SecurityException("hospital unavailable", missing); }
        if (!"ACTIVE".equals(tenant.getStatus()) && !"DISABLED".equals(tenant.getStatus()))
            throw new SecurityException("hospital unavailable");
        return tenant;
    }

    public Tenant lockWritableTenant(String tenantId) {
        if (tenantId == null) throw new IllegalStateException("hospital unavailable");
        Tenant tenant = manager.find(Tenant.class, tenantId, LockModeType.PESSIMISTIC_READ);
        if (tenant == null) throw new IllegalStateException("hospital unavailable");
        try { manager.refresh(tenant, LockModeType.PESSIMISTIC_READ); }
        catch (EntityNotFoundException missing) { throw new IllegalStateException("hospital unavailable", missing); }
        if (!"ACTIVE".equals(tenant.getStatus())) throw new IllegalStateException("hospital business frozen");
        return tenant;
    }

    public Account lockPlatformAccount(CurrentActor actor) {
        if (actor == null || actor.getUserId() == null) throw new SecurityException("authentication required");
        if (actor.getRole() != Role.PLATFORM_ADMIN) throw new AccessDeniedException();
        Account account = manager.find(Account.class, actor.getUserId(), LockModeType.PESSIMISTIC_WRITE);
        if (account == null) throw new SecurityException("inactive account");
        try { manager.refresh(account, LockModeType.PESSIMISTIC_WRITE); }
        catch (EntityNotFoundException missing) { throw new SecurityException("inactive account"); }
        if (!"ACTIVE".equals(account.getStatus())) throw new SecurityException("inactive account");
        if (account.getRole() != Role.PLATFORM_ADMIN || account.getTenantId() != null) throw new AccessDeniedException();
        return account;
    }

    public Tenant lockPlatformTenant(String tenantId) {
        if (tenantId == null) throw new AccessDeniedException();
        Tenant tenant = manager.find(Tenant.class, tenantId, LockModeType.PESSIMISTIC_WRITE);
        if (tenant == null) throw new AccessDeniedException();
        try { manager.refresh(tenant, LockModeType.PESSIMISTIC_WRITE); }
        catch (EntityNotFoundException missing) { throw new AccessDeniedException(); }
        if (!"ACTIVE".equals(tenant.getStatus()) && !"DISABLED".equals(tenant.getStatus())) throw new AccessDeniedException();
        return tenant;
    }

    /** Caller already owns the tenant lock; refreshes authorization after any wait. */
    public Account recheckHospitalWriter(CurrentActor actor, String tenantId) {
        if (actor == null || actor.getUserId() == null) throw new SecurityException("authentication required");
        if (actor.getRole() != Role.HOSPITAL_ADMIN) throw new AccessDeniedException();
        // A locking refresh is a current read even if the earlier authorization opened an RR snapshot.
        Account account = manager.find(Account.class, actor.getUserId(), LockModeType.PESSIMISTIC_READ);
        if (account == null) throw new SecurityException("inactive account");
        try { manager.refresh(account, LockModeType.PESSIMISTIC_READ); }
        catch (EntityNotFoundException missing) { throw new SecurityException("inactive account"); }
        if (!"ACTIVE".equals(account.getStatus())) throw new SecurityException("inactive account");
        if (account.getRole() != Role.HOSPITAL_ADMIN) throw new AccessDeniedException();
        if (tenantId == null || !tenantId.equals(account.getTenantId()))
            throw new AccessDeniedException();
        return account;
    }
}
