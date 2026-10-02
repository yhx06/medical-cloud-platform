package com.medcloud.course.paas.order;

import com.medcloud.course.contract.Role;
import com.medcloud.course.paas.auth.Account;
import com.medcloud.course.paas.auth.AccessDeniedException;
import com.medcloud.course.paas.auth.CurrentActor;
import com.medcloud.course.paas.tenant.Tenant;

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

    public PaymentOrder forPatient(CurrentActor actor, String appointmentId) {
        requirePatient(actor);
        List<PaymentOrder> orders = manager.createQuery(
                "select o from PaymentOrder o where o.appointmentId = :appointmentId", PaymentOrder.class)
                .setParameter("appointmentId", appointmentId).setMaxResults(1).getResultList();
        PaymentOrder order = orders.isEmpty() ? null : orders.get(0);
        requireOwner(actor, order);
        return order;
    }

    public PaymentOrder pay(CurrentActor actor, String orderId) {
        requirePatient(actor);
        manager.flush();
        PaymentOrder order = manager.find(PaymentOrder.class, orderId, LockModeType.PESSIMISTIC_WRITE);
        if (order != null) manager.refresh(order, LockModeType.PESSIMISTIC_WRITE);
        requireOwner(actor, order);
        order.pay(Instant.now());
        return order;
    }

    public List<PaymentOrder> forHospital(CurrentActor actor, String status, Date from, Date to) {
        if (actor == null) throw new SecurityException("authentication required");
        if (actor.getRole() != Role.HOSPITAL_ADMIN) throw new AccessDeniedException();
        Account admin = manager.find(Account.class, actor.getUserId());
        if (admin == null || admin.getRole() != Role.HOSPITAL_ADMIN || !"ACTIVE".equals(admin.getStatus())
                || admin.getTenantId() == null) {
            throw new SecurityException("inactive hospital administrator");
        }
        Tenant tenant = manager.find(Tenant.class, admin.getTenantId());
        if (tenant == null || !"ACTIVE".equals(tenant.getStatus())) {
            throw new SecurityException("inactive hospital");
        }
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
                .setParameter("tenantId", admin.getTenantId());
        if (status != null && !status.isEmpty()) rows.setParameter("status", status);
        ZoneId shanghai = ZoneId.of("Asia/Shanghai");
        if (from != null) rows.setParameter("from", Timestamp.from(
                LocalDate.parse(from.toString()).atStartOfDay(shanghai).toInstant()));
        if (to != null) rows.setParameter("to", Timestamp.from(
                LocalDate.parse(to.toString()).plusDays(1).atStartOfDay(shanghai).toInstant()));
        return rows.setMaxResults(100).getResultList();
    }

    private void requirePatient(CurrentActor actor) {
        if (actor == null) throw new SecurityException("authentication required");
        if (actor.getRole() != Role.PATIENT) throw new AccessDeniedException();
        Account patient = manager.find(Account.class, actor.getUserId());
        if (patient == null || !"ACTIVE".equals(patient.getStatus()) || patient.getRole() != Role.PATIENT) {
            throw new SecurityException("inactive patient");
        }
    }

    private void requireOwner(CurrentActor actor, PaymentOrder order) {
        if (order == null || !order.getPatientId().equals(actor.getUserId())) {
            throw new AccessDeniedException();
        }
    }
}
