package com.medcloud.platform.file.application;

import java.io.InputStream;
import java.util.UUID;

public interface FileAccessPort {
    String store(UUID actorId, UUID tenantId, UUID businessObjectId, String fileName, InputStream content);
    InputStream load(UUID actorId, UUID tenantId, UUID businessObjectId, String objectKey);
}
