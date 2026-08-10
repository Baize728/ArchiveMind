-- One-time/resettable maintenance for the parsed Markdown cache after moving payloads to MinIO.
-- Run against the application database before re-processing documents.

TRUNCATE TABLE parsed_documents;

SET @schema_name = DATABASE();

SET @sql = (
    SELECT IF(
        COUNT(*) > 0,
        'ALTER TABLE parsed_documents DROP COLUMN markdown_content',
        'SELECT ''markdown_content already absent'' AS message'
    )
    FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'parsed_documents'
      AND column_name = 'markdown_content'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE;

SET @sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE parsed_documents ADD COLUMN markdown_object_key VARCHAR(1024) NULL AFTER markdown_length',
        'SELECT ''markdown_object_key already exists'' AS message'
    )
    FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'parsed_documents'
      AND column_name = 'markdown_object_key'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE;

SET @sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE parsed_documents ADD COLUMN markdown_bytes BIGINT NOT NULL DEFAULT 0 AFTER markdown_object_key',
        'SELECT ''markdown_bytes already exists'' AS message'
    )
    FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'parsed_documents'
      AND column_name = 'markdown_bytes'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE;

SET @sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE parsed_documents ADD COLUMN storage_type VARCHAR(32) NULL AFTER markdown_bytes',
        'SELECT ''storage_type already exists'' AS message'
    )
    FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'parsed_documents'
      AND column_name = 'storage_type'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE;
