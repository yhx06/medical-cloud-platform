package com.medcloud.course.paas.audit;

import com.medcloud.course.contract.AuditResult;
import javax.ejb.*;
import javax.persistence.*;
import java.time.Instant;
import java.util.Objects;

@Stateless
@TransactionAttribute(TransactionAttributeType.MANDATORY)
public class AuditJournal implements AuditWritePort {
    @PersistenceContext(unitName = "medicalCloudCourse") private EntityManager manager;
    public AuditJournal() {}
    public AuditJournal(EntityManager manager) { this.manager = Objects.requireNonNull(manager); }
    @Override public void append(AuditContext context, String verifiedObjectId, AuditResult result, Instant recordedAt) {
        AuditEvent event = AuditEvent.success(context, verifiedObjectId, result, recordedAt);
        try {
            if (!manager.isJoinedToTransaction()) throw new TransactionRequiredException("Audit transaction required");
            manager.persist(event); manager.flush();
        }
        catch (RuntimeException failure) { throw new AuditInfrastructureException(failure); }
    }
}
