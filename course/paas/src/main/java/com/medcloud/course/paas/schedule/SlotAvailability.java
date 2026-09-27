package com.medcloud.course.paas.schedule;

public final class SlotAvailability {
    private final ScheduleSlot slot;
    private final int remaining;
    public SlotAvailability(ScheduleSlot slot, int remaining) { this.slot = slot; this.remaining = remaining; }
    public ScheduleSlot getSlot() { return slot; }
    public int getRemaining() { return remaining; }
}
