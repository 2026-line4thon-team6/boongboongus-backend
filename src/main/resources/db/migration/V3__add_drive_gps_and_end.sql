ALTER TABLE drives ADD COLUMN ended_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE drives ADD COLUMN end_reason VARCHAR(16);
ALTER TABLE drives ADD COLUMN distance_meters DOUBLE PRECISION NOT NULL DEFAULT 0;
ALTER TABLE drives ADD COLUMN last_gps_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE drives ADD COLUMN gps_quality VARCHAR(16) NOT NULL DEFAULT 'MISSING';

CREATE TABLE drive_gps_samples (
    drive_id VARCHAR(80) NOT NULL REFERENCES drives(id) ON DELETE CASCADE,
    sequence INTEGER NOT NULL CHECK (sequence >= 1),
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    PRIMARY KEY (drive_id, sequence),
    CHECK ((latitude IS NULL AND longitude IS NULL) OR
           (latitude IS NOT NULL AND longitude IS NOT NULL
            AND latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180))
);
