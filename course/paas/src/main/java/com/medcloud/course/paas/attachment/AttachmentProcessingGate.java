package com.medcloud.course.paas.attachment;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

@ApplicationScoped
public class AttachmentProcessingGate {
    private final Semaphore permits;
    public AttachmentProcessingGate() { this(new AttachmentLimits()); }
    @Inject public AttachmentProcessingGate(AttachmentLimits limits) { permits=new Semaphore(limits.getContainerConcurrency()); }
    public AutoCloseable tryAcquire() {
        if(!permits.tryAcquire())throw new AttachmentException(429,"FILE_LIMIT_REACHED");
        AtomicBoolean open=new AtomicBoolean(true);
        return ()->{if(open.compareAndSet(true,false))permits.release();};
    }
}
