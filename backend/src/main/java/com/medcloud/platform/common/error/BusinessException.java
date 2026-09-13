package com.medcloud.platform.common.error;

public class BusinessException extends RuntimeException {
    private final ErrorCode code;

    public BusinessException(ErrorCode code) {
        super(code.message());
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }
}
