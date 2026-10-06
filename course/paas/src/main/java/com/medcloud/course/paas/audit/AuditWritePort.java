package com.medcloud.course.paas.audit;

import com.medcloud.course.contract.AuditResult;
import javax.ejb.Local;
import java.time.Instant;

@Local
public interface AuditWritePort {
    void append(AuditContext context, String verifiedObjectId, AuditResult result, Instant recordedAt);
}
