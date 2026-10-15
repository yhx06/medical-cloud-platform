package com.medcloud.course.paas.attachment;

import com.medcloud.course.contract.AuditReasonCode;
import com.medcloud.course.paas.audit.AuditContext;
import com.medcloud.course.paas.audit.AuditFailureJournal;
import com.medcloud.course.paas.auth.CurrentActor;

import javax.ejb.Stateless;
import javax.ejb.LocalBean;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.time.Instant;
import java.util.Objects;

/**
 * Independent failure audit for multi-step attachment flows. Runs in its own transaction
 * after the business database proxy has returned; only call it when the business outcome
 * is already known not-succeeded — an unknown commit must never be guessed.
 */
@Stateless
@LocalBean
@TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
public class AttachmentFailureRecorder {
    @PersistenceContext(unitName = "medicalCloudCourse") private EntityManager manager;
    private final EntityManager provided;

    public AttachmentFailureRecorder() { this.provided = null; }

    public AttachmentFailureRecorder(EntityManager manager) { this.provided = Objects.requireNonNull(manager); }

    public void record(AuditContext context, CurrentActor actor, AuditReasonCode reason) {
        if (context == null || reason == null) throw new IllegalArgumentException("Audit context and reason required");
        if (actor != null) context.attachActor(actor);
        new AuditFailureJournal(em()).append(context, reason, Instant.now());
    }

    private EntityManager em() {
        EntityManager target = provided != null ? provided : manager;
        if (target == null) throw new IllegalStateException("Persistence context unavailable");
        return target;
    }
}
