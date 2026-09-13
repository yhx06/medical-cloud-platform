package com.medcloud.platform.order.domain;

public enum OrderStatus {
    PENDING, PAID, CANCELLED;

    public boolean canTransitionTo(OrderStatus target) {
        return this == PENDING && (target == PAID || target == CANCELLED);
    }
}
