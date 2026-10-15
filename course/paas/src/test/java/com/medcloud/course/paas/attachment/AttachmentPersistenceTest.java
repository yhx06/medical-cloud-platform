package com.medcloud.course.paas.attachment;

import com.medcloud.course.contract.*;
import org.junit.jupiter.api.Test;
import javax.persistence.*;
import static org.junit.jupiter.api.Assertions.*;

class AttachmentPersistenceTest extends AttachmentFixture {
    @Test void freshManagerRetainsDescriptorAndFencesStaleVersion() {
        String id=prepare("appointment1");
        EntityManager stale=factory.createEntityManager();
        FileMetadata old=stale.find(FileMetadata.class,id);
        assertEquals("patient",old.uploaderId); assertEquals("tenant",old.tenantId);
        assertEquals("appointment1",old.appointmentId); assertEquals(SHA,old.sha256);
        assertTrue(old.objectKey.startsWith("course-attachments/"+id+"/"));
        assertNull(old.recordId); assertEquals(NOW,old.createdAt.toInstant());
        long before=old.version;
        claim(id);
        assertTrue((boolean)tx(em -> em.find(FileMetadata.class,id).version>before));
        try {
            stale.getTransaction().begin(); old.state=AttachmentState.AVAILABLE;
            assertThrows(OptimisticLockException.class,stale::flush);
        } finally { if(stale.getTransaction().isActive()) stale.getTransaction().rollback(); stale.close(); }
        assertEquals(AttachmentState.UPLOADING,tx(em -> em.find(FileMetadata.class,id).state));
    }
    @Test void descriptorChangesCannotRebindAnIdempotencyKey() {
        String key=java.util.UUID.randomUUID().toString();
        tx(em -> new AttachmentDatabaseService(em).preparePatient(PATIENT,"appointment1",request(key),NOW));
        PrepareUploadRequest[] changes={new PrepareUploadRequest(key,"demo.pdf","application/pdf",20,SHA),new PrepareUploadRequest(key,"demo.png","image/png",21,SHA),new PrepareUploadRequest(key,"demo.png","image/png",20,SHA.replace('a','b'))};
        for(PrepareUploadRequest changed:changes) code("UPLOAD_CONFLICT",() -> tx(em -> new AttachmentDatabaseService(em).preparePatient(PATIENT,"appointment1",changed,NOW)));
        assertEquals(1L,(long)tx(em -> em.createQuery("select count(f) from FileMetadata f",Long.class).getSingleResult()));
    }
    @Test void cleanupVersionRejectsLateAcknowledgementAndBacksOff() {
        String id=prepare("appointment1");
        CleanupCandidate first=tx(em -> new AttachmentDatabaseService(em).claimCleanupBatch(NOW.plusSeconds(901),100).get(0));
        tx(em -> { new AttachmentDatabaseService(em).recordCleanup(id,first.getVersion(),false,NOW.plusSeconds(902)); return null; });
        tx(em -> { new AttachmentDatabaseService(em).recordCleanup(id,first.getVersion(),true,NOW.plusSeconds(903)); return null; });
        assertEquals(1,(int)tx(em -> em.find(FileMetadata.class,id).retryCount));
        assertTrue(tx(em -> new AttachmentDatabaseService(em).claimCleanupBatch(NOW.plusSeconds(961),100)).isEmpty());
        assertEquals(1,tx(em -> new AttachmentDatabaseService(em).claimCleanupBatch(NOW.plusSeconds(962),100)).size());
    }
}
