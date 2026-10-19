package com.medcloud.course.paas.tenant;

import com.medcloud.course.paas.audit.*;
import com.medcloud.course.contract.AuditEventType;
import com.medcloud.course.contract.AuditResult;
import javax.ejb.EJB;
import java.time.Instant;
import java.util.UUID;

import com.medcloud.course.contract.Role;
import com.medcloud.course.contract.TenantContextView;
import com.medcloud.course.contract.TenantPage;
import com.medcloud.course.contract.TenantStatusRequest;
import com.medcloud.course.contract.TenantView;
import com.medcloud.course.paas.auth.AccessDeniedException;
import com.medcloud.course.paas.auth.CurrentActor;
import javax.ejb.Stateless;
import javax.persistence.EntityManager;
import javax.persistence.EntityNotFoundException;
import javax.persistence.LockModeType;
import javax.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.List;

@Stateless
public class PlatformTenantService {
    @PersistenceContext(unitName = "medicalCloudCourse")
    private EntityManager manager;

    public PlatformTenantService() { }
    @EJB private AuditWritePort journal;
    public PlatformTenantService(EntityManager manager) { this.manager = manager; this.journal = new AuditJournal(manager); }

    public TenantPage list(CurrentActor actor, int page, int size) {
        new TenantAccessPolicy(manager).requireAccount(actor, Role.PLATFORM_ADMIN);
        if (page < 1 || size < 1 || size > 100) throw new IllegalArgumentException("invalid pagination");
        long offset = ((long) page - 1) * size;
        if (offset > Integer.MAX_VALUE) throw new IllegalArgumentException("pagination offset too large");
        long total = manager.createQuery("select count(t) from Tenant t", Long.class).getSingleResult();
        List<Object[]> rows = manager.createQuery("select t.id, t.name, t.status from Tenant t order by t.name, t.id", Object[].class)
                .setFirstResult((int) offset).setMaxResults(size).getResultList();
        List<TenantView> items = new ArrayList<TenantView>();
        for (Object[] row : rows) items.add(new TenantView((String) row[0], (String) row[1], (String) row[2]));
        return new TenantPage(items, total, page, size);
    }

    public TenantView changeStatus(CurrentActor actor, String id, TenantStatusRequest request) {
        return changeStatus(actor, id, request, AuditContext.create(AuditEventType.TENANT_STATUS_CHANGE, UUID.randomUUID().toString()));
    }

    public TenantView changeStatus(CurrentActor actor, String id, TenantStatusRequest request, AuditContext context) {
        com.medcloud.course.paas.auth.Account verified = new TenantAccessPolicy(manager).lockPlatformAccount(actor);
        context.attachActor(new CurrentActor(verified.getId(), verified.getRole(), verified.getTenantId()));
        if (request == null) throw new IllegalArgumentException("request required");
        requireStatus(request.getStatus());
        requireStatus(request.getExpectedStatus());
        if (id == null || id.isEmpty()) throw new AccessDeniedException();
        Tenant tenant = manager.find(Tenant.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (tenant == null) throw new AccessDeniedException();
        try { manager.refresh(tenant, LockModeType.PESSIMISTIC_WRITE); }
        catch (EntityNotFoundException missing) { throw new AccessDeniedException(); }
        // A repeated desired state succeeds even if the original page snapshot is stale.
        boolean unchanged = request.getStatus().equals(tenant.getStatus());
        if (!unchanged) {
            if (!request.getExpectedStatus().equals(tenant.getStatus()))
                throw new IllegalStateException("hospital status changed");
            tenant.setStatus(request.getStatus());
            // Keep a subsequent status call in this transaction from refreshing away this update.
            manager.flush();
        }
        context.attachVerifiedTenant(tenant.getId());
        journal.append(context, tenant.getId(), unchanged ? AuditResult.NO_CHANGE : AuditResult.SUCCESS, Instant.now());
        return new TenantView(tenant.getId(), tenant.getName(), tenant.getStatus());
    }

    public TenantContextView context(CurrentActor actor, Role role) {
        if (role != Role.HOSPITAL_ADMIN && role != Role.DOCTOR) throw new AccessDeniedException();
        Tenant tenant = new TenantAccessPolicy(manager).readableTenant(actor, role);
        return new TenantContextView(tenant.getId(), tenant.getName(), tenant.getStatus(), "ACTIVE".equals(tenant.getStatus()));
    }

    private static void requireStatus(String status) {
        if (!"ACTIVE".equals(status) && !"DISABLED".equals(status))
            throw new IllegalArgumentException("invalid hospital status");
    }
}
