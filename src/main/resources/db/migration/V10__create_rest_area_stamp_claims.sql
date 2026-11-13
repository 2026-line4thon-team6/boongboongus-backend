CREATE TABLE rest_area_stamp_claims (
    id VARCHAR(80) PRIMARY KEY,
    couple_id VARCHAR(80) NOT NULL,
    drive_id VARCHAR(80) NOT NULL REFERENCES drives(id) ON DELETE CASCADE,
    rest_area_id VARCHAR(80) NOT NULL REFERENCES rest_areas(id),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'VERIFIED', 'REJECTED')),
    stamp_id VARCHAR(80) REFERENCES rest_area_stamps(id),
    already_earned BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CHECK ((status = 'VERIFIED' AND stamp_id IS NOT NULL)
        OR (status <> 'VERIFIED' AND stamp_id IS NULL AND already_earned = FALSE))
);

CREATE INDEX rest_area_stamp_claims_pending ON rest_area_stamp_claims(status, drive_id, created_at);