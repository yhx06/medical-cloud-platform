package com.medcloud.platform.file.infrastructure;

import com.medcloud.platform.file.domain.FileMetadata;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FileMetadataRepository extends JpaRepository<FileMetadata, UUID> {
    List<FileMetadata> findAllByRecordIdOrderByCreatedAtAsc(UUID recordId);
}
