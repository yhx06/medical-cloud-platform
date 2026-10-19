package com.medcloud.course.paas.tenant;

import com.medcloud.course.contract.*;
import com.medcloud.course.paas.auth.*;
import com.medcloud.course.paas.audit.*;
import javax.ejb.*;
import javax.persistence.*;
import java.time.Instant;
import java.util.*;
import org.mindrot.jbcrypt.BCrypt;

@Stateless
public class PlatformAdministrationService {
    @PersistenceContext(unitName = "medicalCloudCourse") private EntityManager manager;
    @EJB private AuditWritePort journal;
    public PlatformAdministrationService() { }
    public PlatformAdministrationService(EntityManager manager) { this(manager, new AuditJournal(manager)); }
    public PlatformAdministrationService(EntityManager manager, AuditWritePort journal) { this.manager = manager; this.journal = journal; }

    public HospitalAdminCreationView openHospital(CurrentActor actor, HospitalOpenRequest request, AuditContext context) {
        Account platform = platform(actor, context);
        String name, username, password, key;
        try {
            if (request == null) throw new IllegalArgumentException();
            name = HospitalAdministrationRules.requireHospitalName(request.getHospitalName());
            username = HospitalAdministrationRules.requireUsername(request.getUsername());
            password = HospitalAdministrationRules.requireInitialPassword(request.getInitialPassword());
            key = HospitalAdministrationRules.requireOperationKey(request.getOperationKey());
        } catch (IllegalArgumentException invalid) { throw invalid(); }
        HospitalAdminOperation previous = operation(platform.getId(), key);
        if (previous != null) return retry(previous, "HOSPITAL_OPEN", null, name, username, password, context);
        requireAvailableUsername(username);
        Tenant tenant = new Tenant(UUID.randomUUID().toString(), name);
        manager.persist(tenant);
        return create(platform, tenant, username, password, key, "HOSPITAL_OPEN", context);
    }

    public HospitalAdminCreationView addAdministrator(CurrentActor actor, String tenantId,
                                                       HospitalAdministratorCreateRequest request, AuditContext context) {
        Account platform = platform(actor, context);
        String username, password, key;
        try {
            HospitalAdministrationRules.requireTargetId(tenantId);
            if (request == null) throw new IllegalArgumentException();
            username = HospitalAdministrationRules.requireUsername(request.getUsername());
            password = HospitalAdministrationRules.requireInitialPassword(request.getInitialPassword());
            key = HospitalAdministrationRules.requireOperationKey(request.getOperationKey());
        } catch (IllegalArgumentException invalid) { throw invalid(); }
        HospitalAdminOperation previous = operation(platform.getId(), key);
        if (previous != null) return retry(previous, "ADMIN_ADD", tenantId, null, username, password, context);
        Tenant tenant = new TenantAccessPolicy(manager).lockPlatformTenant(tenantId);
        requireAvailableUsername(username);
        return create(platform, tenant, username, password, key, "ADMIN_ADD", context);
    }

    public HospitalAdministratorPage administrators(CurrentActor actor, String tenantId, int page, int size) {
        Account platform = new TenantAccessPolicy(manager).requireAccount(actor, Role.PLATFORM_ADMIN);
        requirePlatformTenantless(platform);
        int offset;
        try { HospitalAdministrationRules.requireTargetId(tenantId); offset = HospitalAdministrationRules.requireOffset(page,size); }
        catch (IllegalArgumentException invalid) { throw invalid(); }
        Tenant tenant = manager.find(Tenant.class, tenantId);
        if (tenant == null) throw new AccessDeniedException();
        manager.refresh(tenant);
        if (!"ACTIVE".equals(tenant.getStatus()) && !"DISABLED".equals(tenant.getStatus())) throw new AccessDeniedException();
        long total = manager.createQuery("select count(a) from Account a where a.tenantId = :tenant and a.role = :role",Long.class)
                .setParameter("tenant",tenantId).setParameter("role",Role.HOSPITAL_ADMIN).getSingleResult();
        long active = manager.createQuery("select count(a) from Account a where a.tenantId = :tenant and a.role = :role and a.status = 'ACTIVE'",Long.class)
                .setParameter("tenant",tenantId).setParameter("role",Role.HOSPITAL_ADMIN).getSingleResult();
        List<Object[]> rows = manager.createQuery("select a.id, a.tenantId, a.username, a.status from Account a where a.tenantId = :tenant and a.role = :role order by a.username, a.id",Object[].class)
                .setParameter("tenant",tenantId).setParameter("role",Role.HOSPITAL_ADMIN).setFirstResult(offset).setMaxResults(size).getResultList();
        List<HospitalAdministratorView> items = new ArrayList<HospitalAdministratorView>();
        for (Object[] row : rows) items.add(new HospitalAdministratorView((String)row[0],(String)row[1],(String)row[2],(String)row[3]));
        return new HospitalAdministratorPage(new TenantView(tenant.getId(),tenant.getName(),tenant.getStatus()),items,total,page,size,active);
    }

    public HospitalAdministratorView changeAdministratorStatus(CurrentActor actor, String tenantId, String adminId,
                                                               TenantStatusRequest request, AuditContext context) {
        platform(actor,context);
        try {
            HospitalAdministrationRules.requireTargetId(tenantId); HospitalAdministrationRules.requireTargetId(adminId);
            if (request == null) throw new IllegalArgumentException();
            HospitalAdministrationRules.requireStatus(request.getStatus()); HospitalAdministrationRules.requireStatus(request.getExpectedStatus());
        } catch (IllegalArgumentException invalid) { throw invalid(); }
        new TenantAccessPolicy(manager).lockPlatformTenant(tenantId);
        Account administrator = lockAdministrator(tenantId,adminId);
        boolean unchanged = request.getStatus().equals(administrator.getStatus());
        if (!unchanged) {
            if (!request.getExpectedStatus().equals(administrator.getStatus())) throw conflict("ADMIN_STATUS_CONFLICT");
            if ("DISABLED".equals(request.getStatus())) {
                // Locking read sees committed status after the hospital lock, including under MySQL RR.
                List<Account> other = manager.createQuery("select a from Account a where a.tenantId = :tenant and a.role = :role and a.status = 'ACTIVE' and a.id <> :id order by a.id",Account.class)
                        .setParameter("tenant",tenantId).setParameter("role",Role.HOSPITAL_ADMIN).setParameter("id",adminId)
                        .setMaxResults(1).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
                if (other.isEmpty()) throw conflict("LAST_ACTIVE_HOSPITAL_ADMIN");
                manager.refresh(other.get(0),LockModeType.PESSIMISTIC_WRITE);
                if (!"ACTIVE".equals(other.get(0).getStatus())) throw conflict("LAST_ACTIVE_HOSPITAL_ADMIN");
            }
            administrator.setStatus(request.getStatus());
            manager.flush();
        }
        context.attachVerifiedTenant(tenantId);
        journal.append(context,administrator.getId(),unchanged ? AuditResult.NO_CHANGE : AuditResult.SUCCESS,Instant.now());
        return new HospitalAdministratorView(administrator.getId(),tenantId,administrator.getUsername(),administrator.getStatus());
    }

    private Account platform(CurrentActor actor, AuditContext context) {
        Account account = new TenantAccessPolicy(manager).lockPlatformAccount(actor);
        context.attachActor(new CurrentActor(account.getId(),account.getRole(),account.getTenantId()));
        return account;
    }
    private static void requirePlatformTenantless(Account account) {
        if (account.getTenantId() != null) throw new AccessDeniedException();
    }
    private HospitalAdminOperation operation(String actorId,String key) {
        List<HospitalAdminOperation> rows = manager.createQuery("select o from HospitalAdminOperation o where o.actorId = :actor and o.operationKey = :key",HospitalAdminOperation.class)
                .setParameter("actor",actorId).setParameter("key",key).setMaxResults(1).getResultList();
        return rows.isEmpty() ? null : rows.get(0);
    }
    private HospitalAdminCreationView retry(HospitalAdminOperation operation,String type,String target,String name,
                                             String username,String password,AuditContext context) {
        new TenantAccessPolicy(manager).lockPlatformTenant(operation.getTenantId());
        Account administrator = lockAdministrator(operation.getTenantId(),operation.getAdminId());
        if (!type.equals(operation.getOperationType()) || (target != null && !target.equals(operation.getTenantId()))
                || (name != null && !name.equals(operation.getHospitalName()))
                || !HospitalAdministrationRules.usernameKey(username).equals(HospitalAdministrationRules.usernameKey(operation.getAdminUsername()))
                || !HospitalAdministrationRules.usernameKey(administrator.getUsername()).equals(HospitalAdministrationRules.usernameKey(operation.getAdminUsername())))
            throw conflict("OPERATION_KEY_CONFLICT");
        boolean matches;
        try { matches = BCrypt.checkpw(password,administrator.getPasswordHash()); }
        catch (IllegalArgumentException invalidHash) { throw new AccessDeniedException(); }
        if (!matches) throw conflict("OPERATION_KEY_CONFLICT");
        context.attachVerifiedTenant(operation.getTenantId());
        journal.append(context,"HOSPITAL_OPEN".equals(type) ? operation.getTenantId() : operation.getAdminId(),AuditResult.NO_CHANGE,Instant.now());
        return view(operation);
    }
    private Account lockAdministrator(String tenant,String id) {
        Account account = manager.find(Account.class,id,LockModeType.PESSIMISTIC_WRITE);
        if (account == null) throw new AccessDeniedException();
        try { manager.refresh(account,LockModeType.PESSIMISTIC_WRITE); }
        catch (EntityNotFoundException missing) { throw new AccessDeniedException(); }
        if (account.getRole() != Role.HOSPITAL_ADMIN || !tenant.equals(account.getTenantId())
                || (!"ACTIVE".equals(account.getStatus()) && !"DISABLED".equals(account.getStatus()))) throw new AccessDeniedException();
        return account;
    }
    private void requireAvailableUsername(String username) {
        if (!manager.createQuery("select a.id from Account a where lower(a.username) = :username",String.class)
                .setParameter("username",HospitalAdministrationRules.usernameKey(username)).setMaxResults(1).getResultList().isEmpty())
            throw conflict("USERNAME_EXISTS");
    }
    private HospitalAdminCreationView create(Account platform,Tenant tenant,String username,String password,String key,String type,AuditContext context) {
        Account administrator = new Account(UUID.randomUUID().toString(),username,BCrypt.hashpw(password,BCrypt.gensalt()),Role.HOSPITAL_ADMIN,tenant.getId());
        Instant now=Instant.now();
        HospitalAdminOperation operation = new HospitalAdminOperation(platform.getId(),key,type,tenant,administrator,now);
        try {
            manager.persist(administrator); manager.persist(operation); manager.flush();
        } catch (PersistenceException failure) {
            if (UsernameConstraint.violatedBy(failure)) throw conflict("USERNAME_EXISTS");
            throw failure;
        }
        context.attachVerifiedTenant(tenant.getId());
        journal.append(context,"HOSPITAL_OPEN".equals(type) ? tenant.getId() : administrator.getId(),AuditResult.SUCCESS,now);
        return view(operation);
    }
    private static HospitalAdminCreationView view(HospitalAdminOperation operation) {
        return new HospitalAdminCreationView(operation.getTenantId(),operation.getHospitalName(),operation.getAdminId(),operation.getAdminUsername());
    }
    private static HospitalAdministrationException invalid() { return new HospitalAdministrationException(400,"HOSPITAL_ONBOARDING_INVALID"); }
    private static HospitalAdministrationException conflict(String code) { return new HospitalAdministrationException(409,code); }
}
