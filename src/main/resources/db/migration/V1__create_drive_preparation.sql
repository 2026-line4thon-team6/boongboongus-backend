CREATE TABLE drives (
    id VARCHAR(80) PRIMARY KEY,
    couple_id VARCHAR(80) NOT NULL,
    title VARCHAR(50) NOT NULL,
    status VARCHAR(16) NOT NULL,
    active_slot SMALLINT,
    driver_user_id VARCHAR(80) NOT NULL,
    passenger_user_id VARCHAR(80) NOT NULL,
    recorder_user_id VARCHAR(80) NOT NULL,
    route_id VARCHAR(80),
    origin_snapshot TEXT,
    destination_snapshot TEXT,
    control_version INTEGER NOT NULL CHECK (control_version >= 1),
    CONSTRAINT one_active_drive_per_couple UNIQUE (couple_id, active_slot),
    CONSTRAINT drive_status_slot CHECK (
        (status IN ('PREPARING', 'ACTIVE', 'PAUSED') AND active_slot IS NOT NULL AND active_slot = 1)
        OR (status IN ('ENDED', 'CANCELLED') AND active_slot IS NULL)
    ),
    CONSTRAINT drive_roles CHECK (
        driver_user_id <> passenger_user_id
        AND recorder_user_id IN (driver_user_id, passenger_user_id)
    ),
    CONSTRAINT drive_route_snapshot CHECK (
        (route_id IS NULL AND origin_snapshot IS NULL AND destination_snapshot IS NULL)
        OR (route_id IS NOT NULL AND origin_snapshot IS NOT NULL AND destination_snapshot IS NOT NULL)
    )
);

CREATE TABLE drive_idempotency (
    couple_id VARCHAR(80) NOT NULL,
    user_id VARCHAR(80) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    response_snapshot TEXT,
    PRIMARY KEY (couple_id, user_id, idempotency_key)
);

CREATE INDEX drive_idempotency_expiry ON drive_idempotency (expires_at);
