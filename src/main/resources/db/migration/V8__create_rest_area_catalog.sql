CREATE TABLE rest_areas (
    id VARCHAR(80) PRIMARY KEY,
    source_id VARCHAR(160) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    highway_name VARCHAR(120) NOT NULL,
    direction VARCHAR(120),
    address VARCHAR(240),
    latitude DOUBLE PRECISION NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude DOUBLE PRECISION NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    travel_bearing DOUBLE PRECISION CHECK (travel_bearing >= 0 AND travel_bearing < 360),
    introduction VARCHAR(1000)
);

CREATE TABLE rest_area_menus (
    rest_area_id VARCHAR(80) NOT NULL REFERENCES rest_areas(id),
    position SMALLINT NOT NULL CHECK (position BETWEEN 1 AND 4),
    name VARCHAR(120) NOT NULL,
    image_url VARCHAR(2048),
    PRIMARY KEY (rest_area_id, position)
);

CREATE TABLE drive_rest_area_alerts (
    drive_id VARCHAR(80) NOT NULL REFERENCES drives(id) ON DELETE CASCADE,
    rest_area_id VARCHAR(80) NOT NULL REFERENCES rest_areas(id),
    PRIMARY KEY (drive_id, rest_area_id)
);

CREATE TABLE rest_area_stamps (
    id VARCHAR(80) PRIMARY KEY,
    couple_id VARCHAR(80) NOT NULL,
    rest_area_id VARCHAR(80) NOT NULL REFERENCES rest_areas(id),
    first_drive_id VARCHAR(80) NOT NULL,
    earned_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (couple_id, rest_area_id)
);
