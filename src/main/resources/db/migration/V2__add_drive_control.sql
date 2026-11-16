ALTER TABLE drives ADD COLUMN started_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE drives ADD COLUMN state_changed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE drives ADD COLUMN initial_origin_snapshot TEXT;
ALTER TABLE drives ADD COLUMN initial_coordinate TEXT;
ALTER TABLE drives ADD COLUMN active_millis BIGINT NOT NULL DEFAULT 0;
ALTER TABLE drives ADD COLUMN paused_millis BIGINT NOT NULL DEFAULT 0;

CREATE TABLE drive_pause_periods (
    drive_id VARCHAR(80) NOT NULL REFERENCES drives(id) ON DELETE CASCADE,
    pause_version INTEGER NOT NULL,
    paused_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resumed_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (drive_id, pause_version),
    CHECK (resumed_at IS NULL OR resumed_at >= paused_at)
);
