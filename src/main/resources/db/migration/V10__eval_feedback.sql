-- user_feedback 表（Q28）
CREATE TABLE IF NOT EXISTS user_feedback (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    action VARCHAR(32) NOT NULL,
    rating TINYINT,
    comment TEXT,
    created_at DATETIME NOT NULL,
    INDEX idx_trace (trace_id),
    INDEX idx_conv (conversation_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- eval_samples 表（Q29）
CREATE TABLE IF NOT EXISTS eval_samples (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trace_id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(64),
    expected_intent VARCHAR(32),
    expected_slots JSON,
    expected_clarify_action VARCHAR(16),
    expected_answer TEXT,
    label_note VARCHAR(500),
    labeled_by VARCHAR(64) NOT NULL,
    labeled_at DATETIME NOT NULL,
    source VARCHAR(32) DEFAULT 'MANUAL',
    UNIQUE KEY uk_trace (trace_id),
    INDEX idx_source (source)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
