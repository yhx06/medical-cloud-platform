package com.medcloud.platform.appointment.domain;

import org.junit.jupiter.api.Test;

import static com.medcloud.platform.appointment.domain.AppointmentStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

class AppointmentStatusTest {
    @Test void allowsOnlyDefinedWorkflowTransitions() {
        assertThat(BOOKED.canTransitionTo(IN_PROGRESS)).isTrue();
        assertThat(BOOKED.canTransitionTo(CANCELLED)).isTrue();
        assertThat(IN_PROGRESS.canTransitionTo(COMPLETED)).isTrue();
        assertThat(COMPLETED.canTransitionTo(BOOKED)).isFalse();
        assertThat(CANCELLED.canTransitionTo(IN_PROGRESS)).isFalse();
    }
}
