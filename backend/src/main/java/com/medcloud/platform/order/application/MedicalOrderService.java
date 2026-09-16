package com.medcloud.platform.order.application;

import com.medcloud.platform.common.error.BusinessException;
import com.medcloud.platform.common.error.ErrorCode;
import com.medcloud.platform.audit.application.AuditService;
import com.medcloud.platform.audit.domain.AuditAction;
import com.medcloud.platform.audit.domain.AuditObjectType;
import com.medcloud.platform.audit.domain.AuditResult;
import com.medcloud.platform.consultation.application.ConsultationCompletedEvent;
import com.medcloud.platform.consultation.infrastructure.ConsultationRepository;
import com.medcloud.platform.identity.domain.UserRole;
import com.medcloud.platform.order.domain.MedicalOrder;
import com.medcloud.platform.order.domain.OrderStatus;
import com.medcloud.platform.order.infrastructure.MedicalOrderRepository;
import com.medcloud.platform.security.CurrentUser;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
public class MedicalOrderService {
    private final MedicalOrderRepository orders;
    private final ConsultationRepository consultations;
    private final AuditService audit;

    public MedicalOrderService(
            MedicalOrderRepository orders,
            ConsultationRepository consultations,
            AuditService audit
    ) {
        this.orders = orders;
        this.consultations = consultations;
        this.audit = audit;
    }

    @EventListener
    @Transactional
    public void onConsultationCompleted(ConsultationCompletedEvent event) {
        createOrder(event);
    }

    @Transactional
    public MedicalOrderResponse createForCompletedConsultation(ConsultationCompletedEvent event) {
        return response(createOrder(event));
    }

    @Transactional
    public MedicalOrderResponse pay(CurrentUser patient, UUID orderId) {
        requirePatient(patient);
        MedicalOrder order = orders.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!order.getPatientId().equals(patient.userId())) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
        if (order.getStatus() == OrderStatus.PAID) {
            return response(order);
        }
        order.markPaid(
                "PAY-" + UUID.randomUUID().toString().replace("-", ""),
                Instant.now().truncatedTo(ChronoUnit.MICROS));
        orders.flush();
        return response(order);
    }

    @Transactional(readOnly = true)
    public List<MedicalOrderResponse> patientOrders(CurrentUser patient) {
        requirePatient(patient);
        return orders.findAllByPatientIdOrderByCreatedAtDesc(patient.userId())
                .stream()
                .map(MedicalOrderService::response)
                .toList();
    }

    @Transactional(readOnly = true)
    public MedicalOrderResponse patientOrder(CurrentUser patient, UUID orderId) {
        requirePatient(patient);
        return orders.findByIdAndPatientId(orderId, patient.userId())
                .map(MedicalOrderService::response)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private MedicalOrder createOrder(ConsultationCompletedEvent event) {
        consultations.findByIdForUpdate(event.consultationId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        return orders.findByConsultationId(event.consultationId())
                .orElseGet(() -> createAndAuditOrder(event));
    }

    private MedicalOrder createAndAuditOrder(ConsultationCompletedEvent event) {
        MedicalOrder order = orders.saveAndFlush(MedicalOrder.from(event));
        CurrentUser actor = event.doctorId() == null ? null : new CurrentUser(
                event.doctorId(), null, UserRole.DOCTOR, event.tenantId());
        audit.record(actor, AuditAction.ORDER_CREATED, AuditObjectType.MEDICAL_ORDER, order.getId(),
                AuditResult.SUCCESS, null);
        return order;
    }

    private static void requirePatient(CurrentUser actor) {
        if (actor == null || actor.userId() == null || actor.role() != UserRole.PATIENT) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
    }

    private static MedicalOrderResponse response(MedicalOrder order) {
        return new MedicalOrderResponse(
                order.getId(),
                order.getConsultationId(),
                order.getAppointmentId(),
                order.getAmount(),
                order.getStatus(),
                order.getTransactionNo(),
                order.getPaidAt(),
                order.getCreatedAt(),
                order.getUpdatedAt());
    }

    public record MedicalOrderResponse(
            UUID id,
            UUID consultationId,
            UUID appointmentId,
            BigDecimal amount,
            OrderStatus status,
            String transactionNo,
            Instant paidAt,
            Instant createdAt,
            Instant updatedAt
    ) {
    }
}
