package com.medcloud.platform.order.domain;

import org.junit.jupiter.api.Test;

import static com.medcloud.platform.order.domain.OrderStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

class OrderStatusTest {
    @Test void allowsOnlyDefinedPaymentTransitions() {
        assertThat(PENDING.canTransitionTo(PAID)).isTrue();
        assertThat(PENDING.canTransitionTo(CANCELLED)).isTrue();
        assertThat(PAID.canTransitionTo(CANCELLED)).isFalse();
        assertThat(CANCELLED.canTransitionTo(PAID)).isFalse();
    }
}
