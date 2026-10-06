package com.medcloud.course.paas.audit;

import javax.ejb.ApplicationException;

@ApplicationException(rollback = true)
public class AuditInfrastructureException extends RuntimeException {
    public AuditInfrastructureException(Throwable cause) { super("Audit persistence unavailable", cause); }
}
