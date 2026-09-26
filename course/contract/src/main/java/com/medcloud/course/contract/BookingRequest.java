package com.medcloud.course.contract;

public final class BookingRequest {
    private String slotId;

    public BookingRequest() { }
    public BookingRequest(String slotId) { this.slotId = slotId; }
    public String getSlotId() { return slotId; }
    public void setSlotId(String slotId) { this.slotId = slotId; }
}
