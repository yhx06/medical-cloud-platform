package com.medcloud.platform.common.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "用户名或密码错误"),
    AUTH_FORBIDDEN(HttpStatus.FORBIDDEN, "无权执行此操作"),
    ACCOUNT_DISABLED(HttpStatus.FORBIDDEN, "账号已停用"),
    TENANT_DISABLED(HttpStatus.FORBIDDEN, "租户已停用"),
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "请求参数校验失败"),
    DEPARTMENT_DUPLICATE(HttpStatus.CONFLICT, "同一租户内科室名称不能重复"),
    SCHEDULE_OVERLAP(HttpStatus.CONFLICT, "医生排班时段重叠"),
    SCHEDULE_UNAVAILABLE(HttpStatus.CONFLICT, "号源不可用"),
    TENANT_ACCESS_DENIED(HttpStatus.FORBIDDEN, "无权访问该租户资源"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "资源不存在"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "系统内部错误");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String message() {
        return message;
    }
}
