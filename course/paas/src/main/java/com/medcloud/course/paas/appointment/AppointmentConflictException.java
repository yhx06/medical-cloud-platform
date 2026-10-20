package com.medcloud.course.paas.appointment;

/** A patient already holds an active appointment overlapping the requested half-open interval. */
public class AppointmentConflictException extends RuntimeException {
    public AppointmentConflictException() {
        super("appointment time conflict");
    }
}
