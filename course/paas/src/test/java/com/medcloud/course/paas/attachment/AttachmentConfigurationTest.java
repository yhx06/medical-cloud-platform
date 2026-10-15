package com.medcloud.course.paas.attachment;

import java.util.*;
import javax.enterprise.context.*;
import javax.enterprise.inject.Produces;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AttachmentConfigurationTest {
    static Map<String,String> valid() {
        Map<String,String> e=new HashMap<>();e.put("COURSE_ATTACHMENTS_ENABLED","true");
        e.put("COURSE_S3_INTERNAL_ENDPOINT","http://127.0.0.1:9991");e.put("COURSE_S3_PUBLIC_ENDPOINT","http://localhost:9991");
        e.put("COURSE_S3_BUCKET","course-files");e.put("COURSE_S3_REGION","us-east-1");e.put("COURSE_S3_ACCESS_KEY","course-test-app");e.put("COURSE_S3_SECRET_KEY","fictional-test-only-secret");return e;
    }
    @Test void badStorageConfigurationDoesNotDisableCore() {
        AttachmentConfiguration disabled=AttachmentConfiguration.fromEnvironment(Collections.emptyMap());assertFalse(disabled.isAvailable());assertEquals(503,assertThrows(AttachmentException.class,disabled::createStore).getStatus());
        for(String bad:Arrays.asList("ftp://127.0.0.1","http://example.org","https://user:pass@example.org","https://example.org/?secret=x","https://example.org/#x")) {
            Map<String,String> e=valid();e.put("COURSE_S3_PUBLIC_ENDPOINT",bad);AttachmentConfiguration c=assertDoesNotThrow(()->AttachmentConfiguration.fromEnvironment(e));assertFalse(c.isAvailable());assertEquals(503,assertThrows(AttachmentException.class,c::createStore).getStatus());
        }
    }
    @Test void nondefaultLimitsUsedByValidationAndGate() throws Exception {
        Map<String,String> e=valid();e.put("COURSE_ATTACHMENTS_MAX_BYTES","1234");e.put("COURSE_ATTACHMENTS_MAX_PIXELS","321");e.put("COURSE_ATTACHMENTS_MAX_PDF_PAGES","12");e.put("COURSE_ATTACHMENTS_CONCURRENCY","1");e.put("COURSE_ATTACHMENTS_UPLOADER_PENDING_LIMIT","3");
        AttachmentConfiguration c=AttachmentConfiguration.fromEnvironment(e);assertTrue(c.isAvailable());assertNotNull(c.createStore());
        assertEquals(1234,c.getLimits().getMaxBytes());assertEquals(321,c.getLimits().getMaxPixels());assertEquals(12,c.getLimits().getMaxPdfPages());assertEquals(3,c.getLimits().getUploaderPendingLimit());
        AttachmentProcessingGate gate=new AttachmentProcessingGate(c.getLimits());try(AutoCloseable lease=gate.tryAcquire()){assertEquals(429,assertThrows(AttachmentException.class,gate::tryAcquire).getStatus());}
    }
    @Test void badNumericConfigurationFailsClosed() {
        for(String bad:Arrays.asList("0","-1","1.5"," 4","2147483648","garbage")) {Map<String,String> e=valid();e.put("COURSE_ATTACHMENTS_CONCURRENCY",bad);assertFalse(AttachmentConfiguration.fromEnvironment(e).isAvailable());}
    }
    @Test void configurationIsProxyableAndFinalLimitsProducerIsDependent() throws Exception {
        assertNotNull(AttachmentConfiguration.class.getConstructor());assertTrue(AttachmentConfiguration.class.isAnnotationPresent(ApplicationScoped.class));
        assertTrue(AttachmentConfiguration.class.getMethod("getLimits").isAnnotationPresent(Produces.class));assertTrue(AttachmentConfiguration.class.getMethod("getLimits").isAnnotationPresent(Dependent.class));
    }
}
