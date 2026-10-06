package com.medcloud.course.paas.audit;

import com.medcloud.course.contract.AuditReasonCode;
import javax.ejb.*;
import javax.persistence.*;
import java.time.Instant;
import java.util.Objects;

@Stateless
@LocalBean
@TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
public class AuditFailureJournal {
    @PersistenceContext(unitName = "medicalCloudCourse") private EntityManager manager;
    public AuditFailureJournal() {}
    public AuditFailureJournal(EntityManager manager) { this.manager = Objects.requireNonNull(manager); }
    public void append(AuditContext context, AuditReasonCode reason, Instant recordedAt) {
        AuditEvent event = AuditEvent.failure(context, reason, recordedAt);
        try {
            if (!manager.isJoinedToTransaction()) throw new TransactionRequiredException("Audit transaction required");
            manager.persist(event); manager.flush();
        }
        catch (RuntimeException failure) { throw new AuditInfrastructureException(failure); }
    }
}
