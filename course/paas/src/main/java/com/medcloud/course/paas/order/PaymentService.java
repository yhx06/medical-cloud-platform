package com.medcloud.course.paas.order;

import com.medcloud.course.contract.Role;
import com.medcloud.course.paas.auth.Account;
import com.medcloud.course.paas.auth.AccessDeniedException;
import com.medcloud.course.paas.auth.CurrentActor;

import javax.ejb.Stateless;
import javax.persistence.EntityManager;
import javax.persistence.LockModeType;
import javax.persistence.PersistenceContext;
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
        PaymentOrder order = manager.find(PaymentOrder.class, orderId, LockModeType.PESSIMISTIC_WRITE);
        requireOwner(actor, order);
        order.pay();
        return order;
    }

    private void requirePatient(CurrentActor actor) {
        if (actor == null || actor.getRole() != Role.PATIENT) {
            throw new SecurityException("patient role required");
        }
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
