package com.medcloud.course.portal.web;

public final class PaasUnavailableException extends RuntimeException {
    public PaasUnavailableException(Throwable cause) {
        super("诊疗服务暂时不可用，请稍后重试", cause);
    }
}
