package com.medcloud.course.paas.attachment;

import com.medcloud.course.contract.*;
import com.medcloud.course.paas.auth.*;
import com.medcloud.course.paas.appointment.Appointment;
import com.medcloud.course.paas.tenant.Tenant;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AttachmentAccessPolicyTest extends AttachmentFixture {
    @Test void roleOwnerTenantAndDisabledAccountMatrix() {
        tx(em -> {
            em.persist(new Tenant("elsewhere","Other demo hospital"));
            em.persist(new Account("doctor2","doctor2","unused",Role.DOCTOR,"tenant"));
            em.persist(new Account("doctor3","doctor3","unused",Role.DOCTOR,"elsewhere"));
            em.persist(new Account("platform","platform","unused",Role.PLATFORM_ADMIN,null));
            return null;
        });
        for(CurrentActor actor:Arrays.asList(new CurrentActor("doctor2",Role.DOCTOR,"tenant"),new CurrentActor("doctor3",Role.DOCTOR,"elsewhere"),new CurrentActor("other",Role.PATIENT,null),new CurrentActor("platform",Role.PLATFORM_ADMIN,null),new CurrentActor("admin",Role.HOSPITAL_ADMIN,"tenant"),new CurrentActor("patient",Role.DOCTOR,"tenant")))
            code("FORBIDDEN",() -> tx(em -> new AttachmentAccessPolicy(em).readable(actor,"appointment1")));
        assertNotNull(tx(em -> new AttachmentAccessPolicy(em).readable(PATIENT,"appointment1")));
        assertNotNull(tx(em -> new AttachmentAccessPolicy(em).readable(DOCTOR,"appointment1")));
        tx(em -> { em.find(Account.class,"patient").disable(); return null; });
        code("UNAUTHORIZED",() -> tx(em -> new AttachmentAccessPolicy(em).readable(PATIENT,"appointment1")));
    }
    @Test void corruptedCrossTenantFileIsNeverReadableOrSignable() {
        String id=prepare("appointment1"); publish(id,claim(id));
        tx(em -> { em.persist(new Tenant("elsewhere","Other demo hospital")); em.find(FileMetadata.class,id).tenantId="elsewhere"; return null; });
        code("FORBIDDEN",() -> tx(em -> new AttachmentDatabaseService(em).downloadCandidate(PATIENT,id)));
    }
    @Test void canceledAndCompletedAppointmentsRejectNewPatientWrites() {
        tx(em -> { em.find(Appointment.class,"appointment1").cancel(); Appointment a=em.find(Appointment.class,"appointment2"); a.start(NOW); a.complete(); return null; });
        code("FILE_STATE_CONFLICT",() -> prepare("appointment1"));
        code("FILE_STATE_CONFLICT",() -> prepare("appointment2"));
    }
    @Test void reservedAndUploadingAreHiddenAndLeaseIsFenced() {
        String id=prepare("appointment1");
        assertEquals(0L,(long)tx(em -> new AttachmentDatabaseService(em).listForPatient(PATIENT,"appointment1",null,null).getTotal()));
        code("FILE_STATE_CONFLICT",() -> tx(em -> new AttachmentDatabaseService(em).downloadCandidate(PATIENT,id)));
        UploadAttempt attempt=claim(id); code("UPLOAD_IN_PROGRESS",() -> claim(id));
        code("FILE_STATE_CONFLICT",() -> tx(em -> new AttachmentDatabaseService(em).publish(PATIENT,id,attempt.getAttemptToken(),validated(),NOW.plusSeconds(600),context(AuditEventType.FILE_UPLOAD))));
        code("FILE_STATE_CONFLICT",() -> tx(em -> new AttachmentDatabaseService(em).resolveUpload(PATIENT,id,UUID.randomUUID().toString())));
    }
    @Test void signedAuthorizationRechecksVersionExpiryAndAccount() {
        String id=prepare("appointment1"); publish(id,claim(id));
        AttachmentSnapshot snapshot=tx(em -> new AttachmentDatabaseService(em).downloadCandidate(PATIENT,id));
        code("FILE_STATE_CONFLICT",() -> tx(em -> { new AttachmentDatabaseService(em).commitDownloadAuthorization(PATIENT,id,snapshot.getVersion()+1,NOW.plusSeconds(300),NOW,context(AuditEventType.FILE_DOWNLOAD_AUTHORIZE)); return null; }));
        code("FILE_STATE_CONFLICT",() -> tx(em -> { new AttachmentDatabaseService(em).commitDownloadAuthorization(PATIENT,id,snapshot.getVersion(),NOW,NOW,context(AuditEventType.FILE_DOWNLOAD_AUTHORIZE)); return null; }));
        tx(em -> { em.find(Account.class,"patient").disable(); return null; });
        code("UNAUTHORIZED",() -> tx(em -> { new AttachmentDatabaseService(em).commitDownloadAuthorization(PATIENT,id,snapshot.getVersion(),NOW.plusSeconds(300),NOW,context(AuditEventType.FILE_DOWNLOAD_AUTHORIZE)); return null; }));
    }
}
