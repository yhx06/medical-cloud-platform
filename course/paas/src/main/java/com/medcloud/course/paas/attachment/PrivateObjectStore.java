package com.medcloud.course.paas.attachment;

import java.time.Instant;
import java.util.List;

public interface PrivateObjectStore {
    void put(String objectKey, ValidatedAttachment content);
    StoreObjectInfo head(String objectKey);
    StoreDownload signGet(AttachmentSnapshot file, Instant issuedAt, int ttlSeconds);
    void delete(String objectKey);
    List<String> listKeys(String prefix, String afterKey, int limit);
}
