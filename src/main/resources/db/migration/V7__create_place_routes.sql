CREATE TABLE place_routes (
    id VARCHAR(80) PRIMARY KEY,
    creator_id VARCHAR(80) NOT NULL,
    couple_id VARCHAR(80),
    route_input TEXT NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX place_routes_expiry ON place_routes (expires_at);
CREATE INDEX drives_route_access ON drives (route_id, couple_id);
