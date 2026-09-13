package com.medcloud.platform.appointment.domain;

public enum AppointmentStatus {
    BOOKED, IN_PROGRESS, COMPLETED, CANCELLED;

    public boolean canTransitionTo(AppointmentStatus target) {
        return switch (this) {
            case BOOKED -> target == IN_PROGRESS || target == CANCELLED;
            case IN_PROGRESS -> target == COMPLETED;
            case COMPLETED, CANCELLED -> false;
        };
    }
}
