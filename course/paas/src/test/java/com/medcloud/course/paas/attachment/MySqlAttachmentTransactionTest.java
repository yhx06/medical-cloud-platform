package com.medcloud.course.paas.attachment;

import com.medcloud.course.contract.*;
import com.medcloud.course.paas.audit.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Database rollback proof through real EntityTransaction boundaries, NOT an EJB/JTA proxy. */
class MySqlAttachmentTransactionTest extends MySqlAttachmentFixture {
    @Test void injectAuditFailureRollsBackPublicationAndAuthorization() {
        String id=prepare("appointment1"); UploadAttempt attempt=claim(id);
        AuditContext publicationFault=context(AuditEventType.FILE_UPLOAD);
        seedDuplicateAudit(publicationFault,"publication-fault-marker");
        assertThrows(AuditInfrastructureException.class,() -> tx(em -> new AttachmentDatabaseService(em).publish(PATIENT,id,attempt.getAttemptToken(),validated(),NOW,publicationFault)));
        assertEquals(AttachmentState.UPLOADING,tx(em -> em.find(FileMetadata.class,id).state));
        assertEquals(0L,countAuditFor(id,AuditEventType.FILE_UPLOAD));
        publish(id,attempt); AttachmentSnapshot snapshot=tx(em -> new AttachmentDatabaseService(em).downloadCandidate(PATIENT,id));
        AuditContext authorizationFault=context(AuditEventType.FILE_DOWNLOAD_AUTHORIZE);
        seedDuplicateAudit(authorizationFault,"authorization-fault-marker");
        assertThrows(AuditInfrastructureException.class,() -> tx(em -> { new AttachmentDatabaseService(em).commitDownloadAuthorization(PATIENT,id,snapshot.getVersion(),NOW.plusSeconds(300),NOW,authorizationFault); return null; }));
        assertEquals(AttachmentState.AVAILABLE,tx(em -> em.find(FileMetadata.class,id).state));
        assertEquals(0L,countAuditFor(id,AuditEventType.FILE_DOWNLOAD_AUTHORIZE));
    }
    private void seedDuplicateAudit(AuditContext context,String marker) {
        tx(em -> { context.attachActor(PATIENT); context.attachVerifiedTenant("tenant"); new AuditJournal(em).append(context,marker,AuditResult.SUCCESS,NOW); return null; });
    }
    private long countAuditFor(String objectId,AuditEventType event) {
        return ((Number)tx(em -> em.createNativeQuery("select count(*) from audit_event where object_id=:object and event_type=:event").setParameter("object",objectId).setParameter("event",event.name()).getSingleResult())).longValue();
    }
    @Test void migrationSchemaValidatesAndPublicationAuditCommitsTogether() {
        String id=prepare("appointment1"); publish(id,claim(id));
        assertEquals(AttachmentState.AVAILABLE,tx(em -> em.find(FileMetadata.class,id).state));
        assertEquals(1L,(long)tx(em -> em.createQuery("select count(e) from AuditEvent e where e.eventType=:event and e.result=:result",Long.class).setParameter("event",AuditEventType.FILE_UPLOAD).setParameter("result",AuditResult.SUCCESS).getSingleResult()));
        assertEquals(23,((Number)tx(em -> em.createNativeQuery("select count(*) from information_schema.columns where table_schema=database() and table_name='file_metadata'").getSingleResult())).intValue());
        assertEquals(6,((Number)tx(em -> em.createNativeQuery("select count(*) from information_schema.referential_constraints where constraint_schema=database() and table_name='file_metadata'").getSingleResult())).intValue());
    }
}
