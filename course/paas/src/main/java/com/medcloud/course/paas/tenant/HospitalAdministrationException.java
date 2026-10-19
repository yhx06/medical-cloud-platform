package com.medcloud.course.paas.tenant;

import javax.ejb.ApplicationException;

@ApplicationException(rollback = true)
public class HospitalAdministrationException extends RuntimeException {
    private final int status;
    private final String code;
    public HospitalAdministrationException(int status, String code) {
        super("Hospital administration request rejected");
        if (!((status == 400 && "HOSPITAL_ONBOARDING_INVALID".equals(code))
                || (status == 409 && ("USERNAME_EXISTS".equals(code) || "OPERATION_KEY_CONFLICT".equals(code)
                || "ADMIN_STATUS_CONFLICT".equals(code) || "LAST_ACTIVE_HOSPITAL_ADMIN".equals(code)))))
            throw new IllegalArgumentException("Unsupported hospital administration failure");
        this.status = status;
        this.code = code;
    }
    public int getStatus() { return status; }
    public String getCode() { return code; }
}
