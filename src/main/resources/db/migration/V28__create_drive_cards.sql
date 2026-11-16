CREATE TABLE drive_cards (
    id VARCHAR(80) PRIMARY KEY,
    drive_id VARCHAR(80) NOT NULL UNIQUE REFERENCES drives(id),
    title VARCHAR(50) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'UNWRITTEN'
        CHECK (status IN ('UNWRITTEN', 'DRAFT', 'COMPLETED', 'EXPIRED')),
    editable_until TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    playlist_snapshot TEXT,
    version INTEGER NOT NULL DEFAULT 1 CHECK (version >= 1)
);

CREATE INDEX drive_cards_expiry ON drive_cards(status, editable_until);

CREATE TABLE card_reflections (
    card_id VARCHAR(80) NOT NULL REFERENCES drive_cards(id) ON DELETE CASCADE,
    author_id VARCHAR(80) NOT NULL,
    body VARCHAR(70) NOT NULL,
    submitted_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version INTEGER NOT NULL CHECK (version >= 1),
    PRIMARY KEY (card_id, author_id)
);

ALTER TABLE card_photo_uploads ADD COLUMN card_id VARCHAR(80) REFERENCES drive_cards(id);
ALTER TABLE card_photo_uploads ADD COLUMN card_position SMALLINT;
ALTER TABLE card_photo_uploads ADD CONSTRAINT card_photo_attachment CHECK (
    (status = 'ATTACHED' AND card_id IS NOT NULL AND card_position IS NOT NULL AND card_position BETWEEN 0 AND 1)
    OR (status <> 'ATTACHED' AND card_id IS NULL AND card_position IS NULL)
);
CREATE INDEX card_photo_uploads_card ON card_photo_uploads(card_id);

ALTER TABLE notifications ADD COLUMN expanded_type VARCHAR(32);
UPDATE notifications SET expanded_type = type;
ALTER TABLE notifications ALTER COLUMN expanded_type SET NOT NULL;
ALTER TABLE notifications DROP COLUMN type;
ALTER TABLE notifications RENAME COLUMN expanded_type TO type;
ALTER TABLE notifications ADD CONSTRAINT notifications_type_check CHECK (type IN (
    'PLACE_SHARED', 'REFLECTION_SUBMITTED', 'CARD_COMPLETED', 'COUPLE_CONNECTED', 'COUPLE_DISCONNECTED'
));

CREATE TABLE card_completion_push_events (
    card_id VARCHAR(80) PRIMARY KEY REFERENCES drive_cards(id) ON DELETE CASCADE,
    recipient_user_id VARCHAR(80) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    sent_at TIMESTAMP WITH TIME ZONE
);
