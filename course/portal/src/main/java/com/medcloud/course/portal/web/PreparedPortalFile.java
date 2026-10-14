package com.medcloud.course.portal.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Owns only the randomly named file created for this request. */
public final class PreparedPortalFile implements AutoCloseable {
    private final Path path;
    private final String fileName, contentType, sha256, uploadKey, targetId;
    private final long sizeBytes;
    PreparedPortalFile(Path path, String fileName, String contentType, long sizeBytes,
            String sha256, String uploadKey, String targetId) {
        this.path=path; this.fileName=fileName; this.contentType=contentType; this.sizeBytes=sizeBytes;
        this.sha256=sha256; this.uploadKey=uploadKey; this.targetId=targetId;
    }
    public Path getPath(){return path;}
    public String getFileName(){return fileName;}
    public String getContentType(){return contentType;}
    public long getSizeBytes(){return sizeBytes;}
    public String getSha256(){return sha256;}
    public String getUploadKey(){return uploadKey;}
    public String getTargetId(){return targetId;}
    @Override public void close() throws IOException {Files.deleteIfExists(path);}
}
