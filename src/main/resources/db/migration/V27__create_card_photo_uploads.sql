CREATE TABLE card_photo_upload_owners (
    user_id VARCHAR(80) PRIMARY KEY
);

CREATE TABLE card_photo_uploads (
    id VARCHAR(80) PRIMARY KEY,
    user_id VARCHAR(80) NOT NULL REFERENCES card_photo_upload_owners(user_id),
    couple_id VARCHAR(80) NOT NULL,
    storage_path VARCHAR(128) NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL CHECK (status IN ('UPLOADING', 'READY', 'ATTACHED', 'DELETED')),
    declared_type VARCHAR(32) NOT NULL CHECK (declared_type IN ('image/jpeg', 'image/png', 'image/webp')),
    declared_bytes INTEGER NOT NULL CHECK (declared_bytes BETWEEN 1 AND 10485760),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    sha256 CHAR(64),
    content_type VARCHAR(32),
    content_bytes INTEGER,
    width INTEGER,
    height INTEGER,
    cleanup_pending BOOLEAN NOT NULL DEFAULT FALSE,
    CHECK (status NOT IN ('READY', 'ATTACHED')
        OR (sha256 IS NOT NULL AND content_type IS NOT NULL AND content_bytes > 0 AND width > 0 AND height > 0))
);

CREATE INDEX card_photo_uploads_owner_created ON card_photo_uploads(user_id, created_at);
CREATE INDEX card_photo_uploads_expiry ON card_photo_uploads(status, expires_at);
CREATE INDEX card_photo_uploads_cleanup ON card_photo_uploads(cleanup_pending);
