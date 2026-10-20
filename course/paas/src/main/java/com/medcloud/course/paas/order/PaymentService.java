package com.medcloud.course.paas.order;

import com.medcloud.course.paas.audit.*;
import com.medcloud.course.contract.AuditEventType;
import com.medcloud.course.contract.AuditResult;
import javax.ejb.EJB;
import java.util.UUID;

import com.medcloud.course.contract.Role;
import com.medcloud.course.paas.PageResult;
import com.medcloud.course.paas.auth.AccessDeniedException;
import com.medcloud.course.paas.auth.CurrentActor;
import com.medcloud.course.paas.tenant.Tenant;
import com.medcloud.course.paas.tenant.TenantAccessPolicy;

import javax.ejb.Stateless;
import javax.persistence.EntityManager;
import javax.persistence.LockModeType;
import javax.persistence.PersistenceContext;
import javax.persistence.TypedQuery;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Stateless
public class PaymentService {
    @PersistenceContext(unitName = "medicalCloudCourse")
    private EntityManager manager;

    public PaymentService() { }
    @EJB private AuditWritePort journal;
    public PaymentService(EntityManager manager) { this.manager = manager; this.journal = new AuditJournal(manager); }

    public boolean businessWritable(CurrentActor actor, String orderId) {
        requirePatient(actor);
        PaymentOrder order = orderId == null ? null : manager.find(PaymentOrder.class, orderId);
        requireOwner(actor, order);
        return "ACTIVE".equals(new TenantAccessPolicy(manager).readableTenant(order.getTenantId()).getStatus());
    }

    public PaymentOrder forPatient(CurrentActor actor, String appointmentId) {
        requirePatient(actor);
        List<PaymentOrder> orders = manager.createQuery(
                "select o from PaymentOrder o where o.appointmentId = :appointmentId", PaymentOrder.class)
                .setParameter("appointmentId", appointmentId).setMaxResults(1).getResultList();
        PaymentOrder order = orders.isEmpty() ? null : orders.get(0);
        requireOwner(actor, order);
        new TenantAccessPolicy(manager).readableTenant(order.getTenantId());
        return order;
    }

    public PaymentOrder pay(CurrentActor actor, String orderId) {
        return pay(actor,orderId,AuditContext.create(AuditEventType.PAYMENT_PAY,UUID.randomUUID().toString()));
    }

    public PaymentOrder pay(CurrentActor actor, String orderId, AuditContext context) {
        requirePatient(actor);
        context.attachActor(new CurrentActor(actor.getUserId(),Role.PATIENT,null));
        manager.flush();
        PaymentOrder candidate = manager.find(PaymentOrder.class, orderId);
        requireOwner(actor, candidate);
        String tenantId = candidate.getTenantId();
        context.attachVerifiedTenant(tenantId);
        new TenantAccessPolicy(manager).lockWritableTenant(tenantId);
        PaymentOrder order = manager.find(PaymentOrder.class, orderId, LockModeType.PESSIMISTIC_WRITE);
        if (order != null) manager.refresh(order, LockModeType.PESSIMISTIC_WRITE);
        requireOwner(actor, order);
        if (!tenantId.equals(order.getTenantId())) throw new AccessDeniedException();
        boolean unchanged = "PAID".equals(order.getStatus());
        order.pay(Instant.now());
        journal.append(context,order.getId(),unchanged ? AuditResult.NO_CHANGE : AuditResult.SUCCESS,Instant.now());
        return order;
    }

    public PageResult<PaymentOrder> forHospital(CurrentActor actor, String status, Date from, Date to, int page, int size) {
        Tenant tenant = new TenantAccessPolicy(manager).readableTenant(actor, Role.HOSPITAL_ADMIN);
        if (status != null && !status.isEmpty() && !"PAID".equals(status) && !"UNPAID".equals(status)) {
            throw new IllegalArgumentException("invalid payment status");
        }
        if (from != null && to != null && from.after(to)) {
            throw new IllegalArgumentException("invalid date range");
        }
        if (page < 1 || size < 1 || size > 100) throw new IllegalArgumentException("invalid pagination");
        long offset = ((long) page - 1) * size;
        if (offset > Integer.MAX_VALUE) throw new IllegalArgumentException("pagination offset too large");
        ZoneId shanghai = ZoneId.of("Asia/Shanghai");
        Timestamp fromTs = from == null ? null : Timestamp.from(LocalDate.parse(from.toString()).atStartOfDay(shanghai).toInstant());
        Timestamp toTs = to == null ? null : Timestamp.from(LocalDate.parse(to.toString()).plusDays(1).atStartOfDay(shanghai).toInstant());
        StringBuilder where = new StringBuilder(" where o.tenantId = :tenantId");
        if (status != null && !status.isEmpty()) where.append(" and o.status = :status");
        if (fromTs != null) where.append(" and o.paidAt >= :from");
        if (toTs != null) where.append(" and o.paidAt < :to");
        TypedQuery<Long> count = manager.createQuery("select count(o) from PaymentOrder o" + where, Long.class)
                .setParameter("tenantId", tenant.getId());
        if (status != null && !status.isEmpty()) count.setParameter("status", status);
        if (fromTs != null) count.setParameter("from", fromTs);
        if (toTs != null) count.setParameter("to", toTs);
        long total = count.getSingleResult();
        StringBuilder jpql = new StringBuilder("select o from PaymentOrder o" + where)
                .append(" order by case when o.paidAt is null then 1 else 0 end, o.paidAt desc, o.id desc");
        TypedQuery<PaymentOrder> rows = manager.createQuery(jpql.toString(), PaymentOrder.class)
                .setParameter("tenantId", tenant.getId());
        if (status != null && !status.isEmpty()) rows.setParameter("status", status);
        if (fromTs != null) rows.setParameter("from", fromTs);
        if (toTs != null) rows.setParameter("to", toTs);
        rows.setFirstResult((int) offset).setMaxResults(size);
        return new PageResult<PaymentOrder>(rows.getResultList(), total, page, size);
    }

    private void requirePatient(CurrentActor actor) {
        new TenantAccessPolicy(manager).requireAccount(actor, Role.PATIENT);
    }

    private void requireOwner(CurrentActor actor, PaymentOrder order) {
        if (order == null || !order.getPatientId().equals(actor.getUserId())) {
            throw new AccessDeniedException();
        }
    }
}
