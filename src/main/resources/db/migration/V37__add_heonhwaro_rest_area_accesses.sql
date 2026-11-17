-- Gwangju–Wonju Expressway operator catalog: https://www.2ydway.co.kr/route
-- Each direction has a separate facility and keeps its own stamp identity.
-- Facility and ramp geometry: © OpenStreetMap contributors, https://www.openstreetmap.org/copyright
INSERT INTO rest_areas (id, source_id, name, highway_name, direction, latitude, longitude)
VALUES
    ('rest_b5467df86b146e04db59770b', 'jyd:52:경기광주(원주):하행', '경기광주휴게소 (원주 방향)',
     '광주원주선', '하행', 37.3787609, 127.4353499),
    ('rest_df8d8fbc5a25a806068000f5', 'jyd:52:경기광주(광주):상행', '경기광주휴게소 (광주 방향)',
     '광주원주선', '상행', 37.3801948, 127.4247903),
    ('rest_aa5ee4d3844b5d572dbb49cb', 'jyd:52:양평(원주):하행', '양평휴게소 (원주 방향)',
     '광주원주선', '하행', 37.3960955, 127.7266000),
    ('rest_665ebeb2638f85350010f571', 'jyd:52:양평(광주):상행', '양평휴게소 (광주 방향)',
     '광주원주선', '상행', 37.3983879, 127.7233937);

-- These are the mainline points where the one-way entrance ramps diverge.
-- Checked against actual Kakao Mobility Gongdeok–Heonhwaro routes in both directions.
INSERT INTO rest_area_accesses
    (rest_area_id, approach, latitude, longitude, travel_bearing, match_radius_meters, source, verified_on)
VALUES
    ('rest_b5467df86b146e04db59770b', 'wonju', 37.3792668, 127.4328235, 88.1, 60,
     'OpenStreetMap way 943593484 / Kakao Mobility Gongdeok-Heonhwaro', DATE '2026-11-18'),
    ('rest_df8d8fbc5a25a806068000f5', 'gwangju', 37.3792372, 127.4272893, 268.8, 60,
     'OpenStreetMap way 943593483 / Kakao Mobility Heonhwaro-Gongdeok', DATE '2026-11-18'),
    ('rest_aa5ee4d3844b5d572dbb49cb', 'wonju', 37.3975653, 127.7236653, 106.5, 60,
     'OpenStreetMap way 1194213004 / Kakao Mobility Gongdeok-Heonhwaro', DATE '2026-11-18'),
    ('rest_665ebeb2638f85350010f571', 'gwangju', 37.3969985, 127.7264621, 288, 60,
     'OpenStreetMap way 1194212997 / Kakao Mobility Heonhwaro-Gongdeok', DATE '2026-11-18'),
    ('rest_5474e0537b29a39a882398d6', 'incheon', 37.7614446, 128.8097727, 216.5, 60,
     'OpenStreetMap way 521570644 / Kakao Mobility Heonhwaro-Gongdeok', DATE '2026-11-18');
