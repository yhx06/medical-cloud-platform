package com.medcloud.course.paas.order;

import com.medcloud.course.contract.Role;
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
import java.util.List;

@Stateless
public class PaymentService {
    @PersistenceContext(unitName = "medicalCloudCourse")
    private EntityManager manager;

    public PaymentService() { }
    public PaymentService(EntityManager manager) { this.manager = manager; }

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
        requirePatient(actor);
        manager.flush();
        PaymentOrder candidate = manager.find(PaymentOrder.class, orderId);
        requireOwner(actor, candidate);
        String tenantId = candidate.getTenantId();
        new TenantAccessPolicy(manager).lockWritableTenant(tenantId);
        PaymentOrder order = manager.find(PaymentOrder.class, orderId, LockModeType.PESSIMISTIC_WRITE);
        if (order != null) manager.refresh(order, LockModeType.PESSIMISTIC_WRITE);
        requireOwner(actor, order);
        if (!tenantId.equals(order.getTenantId())) throw new AccessDeniedException();
        order.pay(Instant.now());
        return order;
    }

    public List<PaymentOrder> forHospital(CurrentActor actor, String status, Date from, Date to) {
        Tenant tenant = new TenantAccessPolicy(manager).readableTenant(actor, Role.HOSPITAL_ADMIN);
        if (status != null && !status.isEmpty() && !"PAID".equals(status) && !"UNPAID".equals(status)) {
            throw new IllegalArgumentException("invalid payment status");
        }
        if (from != null && to != null && from.after(to)) {
            throw new IllegalArgumentException("invalid date range");
        }
        StringBuilder jpql = new StringBuilder("select o from PaymentOrder o where o.tenantId = :tenantId");
        if (status != null && !status.isEmpty()) jpql.append(" and o.status = :status");
        if (from != null) jpql.append(" and o.paidAt >= :from");
        if (to != null) jpql.append(" and o.paidAt < :to");
        jpql.append(" order by case when o.paidAt is null then 1 else 0 end, o.paidAt desc, o.id desc");
        TypedQuery<PaymentOrder> rows = manager.createQuery(jpql.toString(), PaymentOrder.class)
                .setParameter("tenantId", tenant.getId());
        if (status != null && !status.isEmpty()) rows.setParameter("status", status);
        ZoneId shanghai = ZoneId.of("Asia/Shanghai");
        if (from != null) rows.setParameter("from", Timestamp.from(
                LocalDate.parse(from.toString()).atStartOfDay(shanghai).toInstant()));
        if (to != null) rows.setParameter("to", Timestamp.from(
                LocalDate.parse(to.toString()).plusDays(1).atStartOfDay(shanghai).toInstant()));
        return rows.setMaxResults(100).getResultList();
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
