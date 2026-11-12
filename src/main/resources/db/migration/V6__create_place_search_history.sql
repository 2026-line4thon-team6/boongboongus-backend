CREATE TABLE place_search_user (
    user_id VARCHAR(80) PRIMARY KEY
);
CREATE TABLE place_search_history (
    id VARCHAR(80) PRIMARY KEY,
    user_id VARCHAR(80) NOT NULL REFERENCES place_search_user (user_id),
    query VARCHAR(100) NOT NULL,
    normalized_query VARCHAR(400) NOT NULL,
    searched_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (user_id, normalized_query)
);
CREATE INDEX place_search_history_order ON place_search_history (user_id, searched_at DESC, id);
