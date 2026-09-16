ALTER TABLE audit_event
    DROP CONSTRAINT chk_audit_event_request_id_shape;

ALTER TABLE audit_event
    ADD CONSTRAINT chk_audit_event_request_id_uuid
        CHECK (
            request_id IS NULL
            OR (
                CHAR_LENGTH(request_id) = 36
                AND SUBSTRING(request_id, 9, 1) = '-'
                AND SUBSTRING(request_id, 14, 1) = '-'
                AND SUBSTRING(request_id, 19, 1) = '-'
                AND SUBSTRING(request_id, 24, 1) = '-'
                AND REGEXP_LIKE(LOWER(REPLACE(request_id, '-', '')), '^[0-9a-f]{32}$')
            )
        );
