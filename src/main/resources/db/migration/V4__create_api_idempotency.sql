CREATE TABLE api_idempotency (
    user_id VARCHAR(80) NOT NULL,
    request_key VARCHAR(128) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    response_snapshot TEXT,
    PRIMARY KEY (user_id, request_key)
);

CREATE INDEX api_idempotency_expiry ON api_idempotency (expires_at);
