CREATE TABLE rest_area_accesses (
    rest_area_id VARCHAR(80) NOT NULL REFERENCES rest_areas(id),
    approach VARCHAR(30) NOT NULL,
    latitude DOUBLE PRECISION NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude DOUBLE PRECISION NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    travel_bearing DOUBLE PRECISION NOT NULL CHECK (travel_bearing >= 0 AND travel_bearing < 360),
    match_radius_meters INTEGER NOT NULL CHECK (match_radius_meters BETWEEN 1 AND 250),
    source VARCHAR(300) NOT NULL,
    verified_on DATE,
    PRIMARY KEY (rest_area_id, approach)
);

-- Preserve the matching behavior of V9 and V31, and keep the old column for app rollback.
INSERT INTO rest_area_accesses
    (rest_area_id, approach, latitude, longitude, travel_bearing, match_radius_meters, source)
SELECT id, 'legacy', latitude, longitude, travel_bearing, 250, 'V9/V31 catalog heading'
FROM rest_areas
WHERE travel_bearing IS NOT NULL;

-- Haengdamdo: locate each ramp junction in the road network, then project it onto the
-- corresponding Kakao Mobility Seohaean mainline route. The facility keeps its original ID.
-- Road network: © OpenStreetMap contributors, https://www.openstreetmap.org/copyright
-- https://www.openstreetmap.org/way/1122285405
-- https://www.openstreetmap.org/way/1122285406
INSERT INTO rest_area_accesses
    (rest_area_id, approach, latitude, longitude, travel_bearing, match_radius_meters, source, verified_on)
VALUES
    ('rest_b4589fac846a9be8319b29d4', 'mokpo', 36.9429115, 126.8116341, 271, 60,
     'OpenStreetMap way 1122285405 / Kakao Mobility Gongdeok-Daecheon', DATE '2026-11-17'),
    ('rest_b4589fac846a9be8319b29d4', 'seoul', 36.9428771, 126.8062729, 91, 60,
     'OpenStreetMap way 1122285406 / Kakao Mobility Daecheon-Gongdeok', DATE '2026-11-17'),
    -- Compare the direct and rest-area waypoint guides to locate each mainline exit.
    ('rest_97559eb1eba2af813049a9d1', 'gangneung', 37.75748952225537, 128.803051531703, 71, 60,
     'Kakao Mobility Gongdeok-Heonhwaro / Gangneung Daegwallyeong rest area waypoint', DATE '2026-11-17'),
    ('rest_af40ad650694c3feb75a658d', 'busan', 37.46094556751398, 127.04218831748021, 143, 60,
     'Kakao Mobility Gongdeok-Heonhwaro / Seoul Mannam rest area waypoint', DATE '2026-11-17');
