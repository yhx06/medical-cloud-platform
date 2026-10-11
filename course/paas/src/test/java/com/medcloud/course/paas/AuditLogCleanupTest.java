package com.medcloud.course.paas;

import com.medcloud.course.paas.audit.AuditOperationRunner;
import com.medcloud.course.paas.api.ApiFailureMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.logging.*;
import static org.junit.jupiter.api.Assertions.*;

/** A privacy assertion must fail visibly while still releasing its real capture handlers. */
class AuditLogCleanupTest {
    @Test void loginPrivacyFailureStillDetachesHandlers() {
        verifyFailureCleanup(true);
    }
    @Test void managementPrivacyFailureStillDetachesHandlers() {
        verifyFailureCleanup(false);
    }
    private void verifyFailureCleanup(boolean login) {
        Logger[] loggers={Logger.getLogger(AuditOperationRunner.class.getName()),Logger.getLogger(ApiFailureMapper.class.getName())};
        List<List<Handler>> before=new ArrayList<>();
        for(Logger logger:loggers)before.add(Arrays.asList(logger.getHandlers()));
        AuditLoginTest loginTest=login?new AuditLoginTest():null;
        AuditManagementTest managementTest=login?null:new AuditManagementTest();
        try {
            loggers[0].warning(login?"login_username_sentinel":"source_username_sentinel");
            assertThrows(AssertionError.class,() -> {if(login)loginTest.safeLogs();else managementTest.safeLogs();});
            for(int i=0;i<loggers.length;i++)assertEquals(before.get(i),Arrays.asList(loggers[i].getHandlers()),"Handler retained after failed privacy assertion");
        } finally {
            // Even a RED regression cannot contaminate later tests with our intentionally failing record.
            for(int i=0;i<loggers.length;i++)for(Handler handler:loggers[i].getHandlers())if(!before.get(i).contains(handler))loggers[i].removeHandler(handler);
        }
    }
}
