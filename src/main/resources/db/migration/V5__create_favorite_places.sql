CREATE TABLE favorite_places (
    user_id VARCHAR(80) NOT NULL,
    place_id VARCHAR(80) NOT NULL,
    category VARCHAR(24) NOT NULL,
    saved_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, place_id)
);
CREATE INDEX favorite_places_order ON favorite_places (user_id, saved_at DESC, place_id);
CREATE INDEX favorite_places_category_order ON favorite_places (user_id, category, saved_at DESC, place_id);
